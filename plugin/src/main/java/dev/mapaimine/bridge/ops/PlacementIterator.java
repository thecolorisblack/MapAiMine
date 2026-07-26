package dev.mapaimine.bridge.ops;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Pull-style base for placement sequences: subclasses implement {@link #computeNext()} and return
 * {@code null} to signal the end.
 *
 * <p>The protected {@link #scratch} placement is reused for every element (see {@link Placement}),
 * so {@code computeNext()} should fill and return {@code scratch} rather than allocating.
 */
public abstract class PlacementIterator implements Iterator<Placement> {

    protected final Placement scratch = new Placement();

    private Placement pending;
    private boolean exhausted;

    /** @return the next placement, or {@code null} when the sequence is finished */
    protected abstract Placement computeNext();

    @Override
    public final boolean hasNext() {
        if (exhausted) return false;
        if (pending == null) {
            pending = computeNext();
            if (pending == null) {
                exhausted = true;
                return false;
            }
        }
        return true;
    }

    @Override
    public final Placement next() {
        if (!hasNext()) throw new NoSuchElementException();
        Placement p = pending;
        pending = null;
        return p;
    }

    /** An empty sequence. */
    public static Iterator<Placement> empty() {
        return new PlacementIterator() {
            @Override
            protected Placement computeNext() {
                return null;
            }
        };
    }
}
