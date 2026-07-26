package dev.mapaimine.bridge.util;

import java.util.Arrays;

/**
 * Growable primitive {@code long} list.
 *
 * <p>Undo snapshots can hold millions of entries; a {@code java.util.ArrayList<Long>} would cost
 * ~16 bytes of object header per element plus a pointer. This costs exactly 8 bytes per element.
 */
public final class LongList {

    private long[] data;
    private int size;

    public LongList() {
        this(1024);
    }

    public LongList(int capacity) {
        this.data = new long[Math.max(8, capacity)];
    }

    public void add(long v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 8);
        }
        data[size++] = v;
    }

    public long get(int index) {
        return data[index];
    }

    public int size() {
        return size;
    }

    /** Approximate retained heap size in bytes, used to enforce the undo memory budget. */
    public long bytes() {
        return 8L * data.length + 32L;
    }
}
