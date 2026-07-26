package dev.mapaimine.bridge.job;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.json.J;
import dev.mapaimine.bridge.ops.ExecutionContext;
import dev.mapaimine.bridge.ops.Op;
import dev.mapaimine.bridge.undo.UndoSnapshot;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * One {@code POST /ops} request in flight.
 *
 * <p>Mutable fields are written only from the main thread (by {@code JobRunner}) but read from
 * HTTP worker threads serving {@code GET /jobs/{id}}, hence the {@code volatile} markers and the
 * thread-safe warning list. That is cheaper and simpler than locking a job on every status poll.
 */
public final class Job {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final String id;
    private final String label;
    private final String worldName;
    private final List<Op> ops;
    private final long estimatedBlocks;
    private final boolean physics;
    private final boolean lightUpdate;
    private final UndoSnapshot undo;
    private final long seed;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final CountDownLatch completion = new CountDownLatch(1);
    private final long createdAt = System.currentTimeMillis();

    private volatile Status status = Status.QUEUED;
    private volatile int opIndex;
    private volatile long blocksChanged;
    private volatile String checkpoint;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile String error;
    private volatile boolean cancelRequested;

    /** Created lazily on the main thread when the job first runs. */
    private ExecutionContext context;

    public Job(String id, String label, String worldName, List<Op> ops, long estimatedBlocks,
               boolean physics, boolean lightUpdate, UndoSnapshot undo, long seed,
               List<String> initialWarnings) {
        this.id = id;
        this.label = label;
        this.worldName = worldName;
        this.ops = List.copyOf(ops);
        this.estimatedBlocks = estimatedBlocks;
        this.physics = physics;
        this.lightUpdate = lightUpdate;
        this.undo = undo;
        this.seed = seed;
        if (initialWarnings != null) warnings.addAll(initialWarnings);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String worldName() {
        return worldName;
    }

    public List<Op> ops() {
        return ops;
    }

    public long estimatedBlocks() {
        return estimatedBlocks;
    }

    public boolean physics() {
        return physics;
    }

    public boolean lightUpdate() {
        return lightUpdate;
    }

    public UndoSnapshot undo() {
        return undo;
    }

    public long seed() {
        return seed;
    }

    public Status status() {
        return status;
    }

    public void status(Status status) {
        this.status = status;
    }

    public int opIndex() {
        return opIndex;
    }

    public void opIndex(int opIndex) {
        this.opIndex = opIndex;
    }

    public long blocksChanged() {
        return blocksChanged;
    }

    public void blocksChanged(long blocksChanged) {
        this.blocksChanged = blocksChanged;
    }

    public String checkpoint() {
        return checkpoint;
    }

    public void checkpoint(String checkpoint) {
        this.checkpoint = checkpoint;
    }

    public long startedAt() {
        return startedAt;
    }

    public void startedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public void finishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String error() {
        return error;
    }

    public void error(String error) {
        this.error = error;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public void requestCancel() {
        this.cancelRequested = true;
    }

    public ExecutionContext context() {
        return context;
    }

    public void context(ExecutionContext context) {
        this.context = context;
    }

    public List<String> warnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        if (warnings.size() < 256) warnings.add(warning);
    }

    public CountDownLatch completion() {
        return completion;
    }

    public boolean isTerminal() {
        Status s = status;
        return s == Status.DONE || s == Status.FAILED || s == Status.CANCELLED;
    }

    public long elapsedMs() {
        long start = startedAt == 0 ? createdAt : startedAt;
        long end = finishedAt == 0 ? System.currentTimeMillis() : finishedAt;
        return Math.max(0, end - start);
    }

    /** ETA from the observed throughput so far; {@code null} once the job is terminal. */
    public Long etaMs() {
        if (isTerminal() || blocksChanged <= 0) return null;
        long elapsed = elapsedMs();
        long remaining = Math.max(0, estimatedBlocks - blocksChanged);
        if (remaining == 0) return 0L;
        double perBlock = (double) elapsed / blocksChanged;
        return (long) (remaining * perBlock);
    }

    /** The {@code GET /jobs/{id}} representation from PROTOCOL.md §2. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("jobId", id);
        o.addProperty("label", label);
        o.addProperty("world", worldName);
        o.addProperty("status", status.wire());

        JsonObject progress = new JsonObject();
        progress.addProperty("opsDone", opIndex);
        progress.addProperty("opsTotal", ops.size());
        progress.addProperty("blocksChanged", blocksChanged);
        progress.addProperty("estimatedBlocks", estimatedBlocks);
        double percent = estimatedBlocks <= 0
                ? (isTerminal() ? 100.0 : 0.0)
                : Math.min(100.0, 100.0 * blocksChanged / estimatedBlocks);
        if (status == Status.DONE) percent = 100.0;
        progress.addProperty("percent", J.round1(percent));
        progress.addProperty("checkpoint", checkpoint);
        o.add("progress", progress);

        o.addProperty("undoId", undo == null || undo.isDisabled() ? null : undo.undoId());
        o.addProperty("startedAt", startedAt == 0 ? createdAt : startedAt);
        o.addProperty("elapsedMs", elapsedMs());
        Long eta = etaMs();
        if (eta == null) {
            o.add("etaMs", null);
        } else {
            o.addProperty("etaMs", eta);
        }
        JsonArray warns = new JsonArray();
        for (String w : Collections.unmodifiableList(warnings)) warns.add(w);
        o.add("warnings", warns);
        o.addProperty("error", error);
        return o;
    }
}
