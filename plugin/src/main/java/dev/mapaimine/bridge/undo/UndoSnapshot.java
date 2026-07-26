package dev.mapaimine.bridge.undo;

import dev.mapaimine.bridge.util.IntList;
import dev.mapaimine.bridge.util.LongList;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A compact "what was here before" record for one job.
 *
 * <h2>Encoding</h2>
 * A naive {@code HashMap<Location, BlockData>} costs well over 100 bytes per block, which makes a
 * 10M-block build impossible to undo. Instead a snapshot keeps three parallel structures:
 * <ul>
 *   <li>{@code positions} — one {@code long} per write, packed as
 *       {@code x:26 | y:12 | z:26} (the vanilla BlockPos layout, signed);</li>
 *   <li>{@code paletteIndices} — one {@code int} per write, index into…</li>
 *   <li>{@code palette} — the de-duplicated list of previous {@link BlockData} states.</li>
 * </ul>
 * That is 12 bytes per block regardless of how varied the terrain is, because real builds touch
 * only a few dozen distinct block states.
 *
 * <h2>Block entities</h2>
 * Sign text, container contents and spawner settings live in NBT that {@link BlockData} does not
 * carry. Those are captured as full {@link BlockState} snapshots in a side map, which is expensive
 * per entry but rare — the map is hard-capped by {@link #MAX_BLOCK_ENTITIES} and simply stops
 * recording (with a warning) beyond it.
 *
 * <h2>Degradation</h2>
 * When a job exceeds {@code limits.maxUndoBlocks} or the memory budget, the snapshot marks itself
 * {@link #isDisabled() disabled}, drops its buffers and the job continues <em>without</em> undo,
 * reporting a warning. Building never fails just because it is too big to undo.
 *
 * <h2>Threading</h2>
 * Recording happens on the main thread only (from the JobRunner). {@link #restore(int)} likewise.
 */
public final class UndoSnapshot {

    public static final int MAX_BLOCK_ENTITIES = 8192;

    private final String undoId;
    private final String worldName;
    private final String label;
    private final long createdAt = System.currentTimeMillis();
    private final long maxBlocks;
    private final long maxBytes;

    private LongList positions = new LongList(4096);
    private IntList paletteIndices = new IntList(4096);
    private final List<BlockData> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private Map<Long, BlockState> blockEntities = new HashMap<>();

    private boolean disabled;
    private String disabledReason;
    private boolean blockEntityOverflow;

    public UndoSnapshot(String undoId, String worldName, String label, long maxBlocks, long maxBytes) {
        this.undoId = undoId;
        this.worldName = worldName;
        this.label = label;
        this.maxBlocks = maxBlocks;
        this.maxBytes = maxBytes;
    }

    public String undoId() {
        return undoId;
    }

    public String worldName() {
        return worldName;
    }

    public String label() {
        return label;
    }

    public long createdAt() {
        return createdAt;
    }

    public int size() {
        return disabled ? 0 : positions.size();
    }

    public boolean isDisabled() {
        return disabled;
    }

    public String disabledReason() {
        return disabledReason;
    }

    public boolean hadBlockEntityOverflow() {
        return blockEntityOverflow;
    }

    public long approximateBytes() {
        if (disabled) return 0;
        return positions.bytes() + paletteIndices.bytes() + 256L * blockEntities.size();
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) (packed << 26 >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 38 >> 38);
    }

    /**
     * Records the current state of {@code block} before it is overwritten.
     *
     * @return false when undo has been disabled for this job (caller should stop trying)
     */
    public boolean record(Block block) {
        if (disabled) return false;
        if (positions.size() >= maxBlocks) {
            disable("job exceeded limits.maxUndoBlocks (" + maxBlocks + "); undo disabled for this job");
            return false;
        }
        // Checking the memory budget on every write would be wasteful; every 64k writes is plenty.
        if ((positions.size() & 0xFFFF) == 0 && approximateBytes() > maxBytes) {
            disable("job exceeded limits.maxUndoMemoryMb; undo disabled for this job");
            return false;
        }

        BlockData data = block.getBlockData();
        String key = data.getAsString();
        Integer idx = paletteIndex.get(key);
        if (idx == null) {
            idx = palette.size();
            palette.add(data);
            paletteIndex.put(key, idx);
        }
        positions.add(pack(block.getX(), block.getY(), block.getZ()));
        paletteIndices.add(idx);

        if (block.getState() instanceof TileState tile) {
            if (blockEntities.size() < MAX_BLOCK_ENTITIES) {
                blockEntities.putIfAbsent(pack(block.getX(), block.getY(), block.getZ()), tile);
            } else {
                blockEntityOverflow = true;
            }
        }
        return true;
    }

    private void disable(String reason) {
        disabled = true;
        disabledReason = reason;
        positions = new LongList(8);
        paletteIndices = new IntList(8);
        palette.clear();
        paletteIndex.clear();
        blockEntities = new HashMap<>();
    }

    /**
     * Restores up to {@code limit} recorded blocks, newest first.
     *
     * <p>Reverse order matters: a position written several times by the same job was recorded once
     * per write, and replaying backwards ends on the oldest — i.e. genuinely original — state.
     *
     * @return the number of blocks restored, or -1 when the world is gone
     */
    public int restore(int limit) {
        if (disabled) return 0;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return -1;
        int restored = 0;
        int i = positions.size() - 1 - restoredCount;
        for (; i >= 0 && restored < limit; i--) {
            long packed = positions.get(i);
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            Block block = world.getBlockAt(x, y, z);
            block.setBlockData(palette.get(paletteIndices.get(i)), false);
            BlockState tile = blockEntities.get(packed);
            if (tile != null) {
                try {
                    tile.update(true, false);
                } catch (RuntimeException ignored) {
                    // The block type changed under us; the plain block data restore above still stands.
                }
            }
            restored++;
        }
        restoredCount += restored;
        return restored;
    }

    private int restoredCount;

    /** True once every recorded block has been rolled back. */
    public boolean isFullyRestored() {
        return disabled || restoredCount >= positions.size();
    }

    public int restoredCount() {
        return restoredCount;
    }
}
