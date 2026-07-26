package dev.mapaimine.bridge.ops;

import dev.mapaimine.bridge.block.BlockFilter;
import org.bukkit.block.data.BlockData;

/**
 * One pending block write.
 *
 * <p><b>Instances are recycled.</b> The iterators returned by {@link Op}s hand back the same
 * mutable {@code Placement} on every {@code next()} call, because a 4-million-block fill would
 * otherwise allocate 4 million short-lived objects and thrash the GC on the main thread.
 * {@link ExecutionContext#place} consumes the placement immediately and never stores it, so the
 * recycling is invisible to callers. Never keep a reference to a Placement.
 */
public final class Placement {

    public int x;
    public int y;
    public int z;
    public BlockData data;

    /** When non-null, the existing block must match this filter or the write is skipped. */
    public BlockFilter filter;

    /** When true the previous block is broken naturally (drops items) before being replaced. */
    public boolean destroy;

    public Placement set(int x, int y, int z, BlockData data) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.data = data;
        return this;
    }

    public Placement withFilter(BlockFilter filter) {
        this.filter = filter;
        return this;
    }

    public Placement withDestroy(boolean destroy) {
        this.destroy = destroy;
        return this;
    }
}
