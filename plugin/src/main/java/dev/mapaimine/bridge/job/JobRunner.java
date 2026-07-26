package dev.mapaimine.bridge.job;

import com.google.gson.JsonArray;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.api.ErrorCode;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.ops.ExecutionContext;
import dev.mapaimine.bridge.ops.Op;
import dev.mapaimine.bridge.undo.UndoManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tick-budgeted executor that keeps big builds from freezing the server.
 *
 * <h2>Tick budgeting</h2>
 * {@link #tick()} runs once per server tick on the main thread. It hands the active job a budget of
 * {@code limits.blocksPerTick} positions and lets ops consume it; when the budget runs out the job
 * is suspended mid-op and resumes on the next tick from exactly where it stopped (see
 * {@code AbstractPlacementOp}). A 4-million-block build at the default 20 000 blocks/tick therefore
 * spends ~200 ticks (10 s) at a steady, predictable cost per tick instead of one multi-second
 * stall.
 *
 * <h2>Ordering</h2>
 * Jobs run strictly one at a time, in submission order, and ops within a job run strictly
 * sequentially — PROTOCOL.md §2 "Порядок исполнения" depends on that (a door's lower half must be
 * placed before its upper half).
 *
 * <h2>Undo priority</h2>
 * A pending {@code POST /undo} rollback pre-empts job work for the tick, so an operator can always
 * stop a runaway build and get the world back.
 */
public final class JobRunner implements Runnable {

    private final Plugin plugin;
    private final BridgeConfig config;
    private final BlockParser parser;
    private final UndoManager undoManager;

    private final Queue<Job> pending = new ConcurrentLinkedQueue<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Deque<String> finishedOrder = new ArrayDeque<>();
    private final AtomicInteger idCounter = new AtomicInteger();

    private volatile Job active;

    public JobRunner(Plugin plugin, BridgeConfig config, BlockParser parser, UndoManager undoManager) {
        this.plugin = plugin;
        this.config = config;
        this.parser = parser;
        this.undoManager = undoManager;
    }

    public String nextJobId() {
        return "j_" + Integer.toHexString(idCounter.incrementAndGet() | 0x1000).substring(1);
    }

    public void submit(Job job) {
        jobs.put(job.id(), job);
        pending.add(job);
    }

    public Job get(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND, "Unknown jobId: " + id,
                    "finished jobs are kept for the last " + config.finishedJobsKept + " runs");
        }
        return job;
    }

    public JsonArray listJson() {
        JsonArray arr = new JsonArray();
        List<Job> snapshot = new ArrayList<>(jobs.values());
        snapshot.sort((a, b) -> Long.compare(b.startedAt(), a.startedAt()));
        for (Job job : snapshot) arr.add(job.toJson());
        return arr;
    }

    public int activeCount() {
        return (active != null ? 1 : 0) + pending.size();
    }

    /** Marks a job cancelled; already-placed blocks stay, and its undoId remains valid. */
    public void cancel(String id) {
        Job job = get(id);
        if (job.isTerminal()) return;
        job.requestCancel();
        if (job.status() == Job.Status.QUEUED) {
            pending.remove(job);
            finish(job, Job.Status.CANCELLED, null);
        }
    }

    @Override
    public void run() {
        try {
            tick();
        } catch (Throwable t) {
            // A throw here would kill the repeating task and silently stop every future job.
            plugin.getLogger().warning("JobRunner tick failed: " + t);
        }
    }

    private void tick() {
        int budget = config.blocksPerTick;

        // Rollbacks pre-empt builds.
        int usedByUndo = undoManager.tick(budget);
        if (usedByUndo > 0) return;

        Job job = active;
        if (job == null) {
            job = pending.poll();
            if (job == null) return;
            active = job;
            job.startedAt(System.currentTimeMillis());
            job.status(Job.Status.RUNNING);
        }

        if (job.cancelRequested()) {
            finish(job, Job.Status.CANCELLED, null);
            return;
        }

        ExecutionContext ctx = job.context();
        if (ctx == null) {
            World world = Bukkit.getWorld(job.worldName());
            if (world == null) {
                finish(job, Job.Status.FAILED, "world '" + job.worldName() + "' is no longer loaded");
                return;
            }
            ctx = new ExecutionContext(plugin, config, parser, world, job.undo(), job.physics(),
                    job.lightUpdate(), job.warnings(), job.seed());
            job.context(ctx);
        }

        ctx.refillBudget(budget);
        List<Op> ops = job.ops();
        try {
            while (ctx.hasBudget() && job.opIndex() < ops.size()) {
                int index = job.opIndex();
                Op op = ops.get(index);
                boolean finished = op.execute(ctx);
                job.blocksChanged(ctx.blocksChanged());
                if (finished) {
                    ctx.flushCounters(index);
                    if (op.checkpointName() != null) job.checkpoint(op.checkpointName());
                    job.opIndex(index + 1);
                }
                if (job.cancelRequested()) {
                    finish(job, Job.Status.CANCELLED, null);
                    return;
                }
            }
        } catch (ApiException ex) {
            finish(job, Job.Status.FAILED, ex.code() + ": " + ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("job " + job.id() + " failed: " + ex);
            finish(job, Job.Status.FAILED, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return;
        }

        job.blocksChanged(ctx.blocksChanged());
        if (job.opIndex() >= ops.size()) {
            finish(job, Job.Status.DONE, null);
        }
    }

    private void finish(Job job, Job.Status status, String error) {
        ExecutionContext ctx = job.context();
        if (ctx != null) {
            try {
                ctx.finish();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("job " + job.id() + " post-processing failed: " + ex);
            }
            job.blocksChanged(ctx.blocksChanged());
        }
        if (job.undo() != null) {
            if (job.undo().hadBlockEntityOverflow()) {
                job.addWarning("undo: more than " + dev.mapaimine.bridge.undo.UndoSnapshot.MAX_BLOCK_ENTITIES
                        + " block entities were touched; their NBT will not be restored");
            }
            undoManager.commit(job.undo());
        }
        job.status(status);
        job.error(error);
        job.finishedAt(System.currentTimeMillis());
        job.completion().countDown();
        if (active == job) active = null;
        retire(job.id());
    }

    /** Keeps the finished-job map bounded so a long-running server does not leak jobs. */
    private synchronized void retire(String id) {
        finishedOrder.addLast(id);
        while (finishedOrder.size() > config.finishedJobsKept) {
            String old = finishedOrder.removeFirst();
            Job job = jobs.get(old);
            if (job != null && job.isTerminal()) jobs.remove(old);
        }
    }

    public void shutdown() {
        Job job = active;
        if (job != null && !job.isTerminal()) {
            job.requestCancel();
            finish(job, Job.Status.CANCELLED, "server shutting down");
        }
        for (Job queued : pending) {
            queued.status(Job.Status.CANCELLED);
            queued.completion().countDown();
        }
        pending.clear();
    }
}
