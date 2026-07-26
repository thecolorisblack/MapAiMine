package dev.mapaimine.bridge.ops;

import java.util.Iterator;

/**
 * Base class for every op whose work is "emit a sequence of block placements".
 *
 * <p>Resumability is achieved by keeping the {@link Iterator} between ticks: the runner calls
 * {@link #execute} once per tick, we drain as much of the iterator as the budget allows and return
 * {@code false} while it still has elements. The iterator is created lazily on the first call so
 * that ops which need to read the world (paint, smooth, …) do so on the main thread.
 */
public abstract class AbstractPlacementOp implements Op {

    private Iterator<Placement> iterator;
    private boolean finished;

    /** Creates the (usually lazy) placement sequence. Called once, on the main thread. */
    protected abstract Iterator<Placement> placements(ExecutionContext ctx);

    @Override
    public boolean execute(ExecutionContext ctx) {
        if (finished) return true;
        if (iterator == null) iterator = placements(ctx);
        while (ctx.hasBudget() && iterator.hasNext()) {
            Placement p = iterator.next();
            if (p != null) ctx.place(p);
        }
        if (!iterator.hasNext()) {
            finished = true;
            return true;
        }
        return false;
    }
}
