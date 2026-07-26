package dev.mapaimine.bridge.ops;

import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.undo.UndoSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Everything an {@link Op} needs while it runs, plus the tick budget that keeps the server alive.
 *
 * <h2>Threading rule</h2>
 * Every method on this class touches the Bukkit world and therefore <b>must only be called from
 * the main server thread</b> (i.e. from {@code JobRunner}). HTTP worker threads may construct ops
 * and call {@link Op#estimate}/{@link Op#validate}, which never touch the world.
 *
 * <h2>Tick budgeting</h2>
 * {@code JobRunner} refills {@link #budget} with {@code limits.blocksPerTick} once per tick and
 * then drives ops until the budget hits zero. Every <i>considered</i> position costs one unit —
 * including positions skipped by a filter or outside the world — because scanning is itself work.
 * That guarantees a bounded amount of main-thread time per tick regardless of op shape.
 */
public final class ExecutionContext {

    private final Plugin plugin;
    private final BridgeConfig config;
    private final BlockParser parser;
    private final World world;
    private final UndoSnapshot undo;
    private final boolean physics;
    private final boolean lightUpdate;
    private final List<String> warnings;
    private final Random random;

    private int budget;
    private long blocksChanged;
    private long blocksConsidered;
    private int skippedOutOfWorld;
    private int skippedProtected;
    private int skippedFiltered;
    private boolean undoWarned;

    /** Chunks touched by this job; used for the end-of-job relight/refresh pass. */
    private final Set<Long> touchedChunks = new LinkedHashSet<>();

    public ExecutionContext(Plugin plugin, BridgeConfig config, BlockParser parser, World world,
                            UndoSnapshot undo, boolean physics, boolean lightUpdate,
                            List<String> warnings, long seed) {
        this.plugin = plugin;
        this.config = config;
        this.parser = parser;
        this.world = world;
        this.undo = undo;
        this.physics = physics;
        this.lightUpdate = lightUpdate;
        this.warnings = warnings;
        this.random = new Random(seed);
    }

    public Plugin plugin() {
        return plugin;
    }

    public BridgeConfig config() {
        return config;
    }

    public BlockParser parser() {
        return parser;
    }

    public World world() {
        return world;
    }

    public Random random() {
        return random;
    }

    public boolean physics() {
        return physics;
    }

    public boolean lightUpdate() {
        return lightUpdate;
    }

    public boolean hasBudget() {
        return budget > 0;
    }

    public void refillBudget(int amount) {
        this.budget = amount;
    }

    public int remainingBudget() {
        return budget;
    }

    /** Spends budget for work that is not a block write (entity spawn, command, …). */
    public void spend(int units) {
        budget -= units;
    }

    public long blocksChanged() {
        return blocksChanged;
    }

    public long blocksConsidered() {
        return blocksConsidered;
    }

    public void warn(String message) {
        if (warnings.size() < 256) warnings.add(message);
    }

    public List<String> warnings() {
        return warnings;
    }

    public int skippedOutOfWorld() {
        return skippedOutOfWorld;
    }

    public int skippedProtected() {
        return skippedProtected;
    }

    public int skippedFiltered() {
        return skippedFiltered;
    }

    public Set<Long> touchedChunks() {
        return touchedChunks;
    }

    /**
     * Applies one placement, honouring world height, protected regions, the placement filter,
     * undo recording and the physics flag.
     *
     * <p>Out-of-world Y is <em>counted, not thrown</em> (PROTOCOL.md §2): the block is skipped and
     * a single aggregated warning is emitted at the end of the job.
     */
    public void place(Placement p) {
        budget--;
        blocksConsidered++;

        if (p.y < world.getMinHeight() || p.y >= world.getMaxHeight()) {
            skippedOutOfWorld++;
            return;
        }
        if (!config.protectedRegions.isEmpty() && config.isProtected(world.getName(), p.x, p.y, p.z)) {
            skippedProtected++;
            return;
        }

        Block block = world.getBlockAt(p.x, p.y, p.z);
        BlockData current = block.getBlockData();
        if (p.filter != null && !p.filter.matches(current)) {
            skippedFiltered++;
            return;
        }
        if (current.equals(p.data)) {
            // Identical state: nothing to do, and recording it would bloat the undo snapshot.
            return;
        }

        if (undo != null && !undo.isDisabled()) {
            if (!undo.record(block) && !undoWarned) {
                undoWarned = true;
                warn("undo: " + undo.disabledReason());
            }
        }

        if (p.destroy) {
            block.breakNaturally();
        }
        block.setBlockData(p.data, physics);
        blocksChanged++;
        touchedChunks.add(chunkKey(p.x >> 4, p.z >> 4));
    }

    /** Records the current state of a position that is about to be modified out-of-band. */
    public void recordUndo(Block block) {
        if (undo != null && !undo.isDisabled()) {
            if (!undo.record(block) && !undoWarned) {
                undoWarned = true;
                warn("undo: " + undo.disabledReason());
            }
        }
    }

    public void markChanged(int x, int y, int z) {
        blocksChanged++;
        touchedChunks.add(chunkKey(x >> 4, z >> 4));
    }

    public boolean inWorld(int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    public int clampY(int y) {
        return Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
    }

    /** Emits the aggregated skip counters once the job is over. */
    public void flushCounters(int opIndex) {
        if (skippedOutOfWorld > 0) {
            warn("op[" + opIndex + "]: " + skippedOutOfWorld + " blocks were outside world height, skipped");
            skippedOutOfWorld = 0;
        }
        if (skippedProtected > 0) {
            warn("op[" + opIndex + "]: " + skippedProtected + " blocks were inside a protected region, skipped");
            skippedProtected = 0;
        }
        skippedFiltered = 0;
    }

    /**
     * End-of-job pass. Resends every touched chunk to nearby players so that lighting and block
     * changes applied with {@code physics=false} are guaranteed to be visible client-side.
     */
    public void finish() {
        if (!lightUpdate) return;
        for (long key : touchedChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            try {
                world.refreshChunk(cx, cz);
            } catch (RuntimeException ignored) {
                // refreshChunk is best-effort; never fail a finished job over it.
            }
        }
        touchedChunks.clear();
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
