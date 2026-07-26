package dev.mapaimine.bridge.util;

import java.util.Arrays;

/** Growable primitive {@code int} list — see {@link LongList} for why this exists. */
public final class IntList {

    private int[] data;
    private int size;

    public IntList() {
        this(1024);
    }

    public IntList(int capacity) {
        this.data = new int[Math.max(8, capacity)];
    }

    public void add(int v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 8);
        }
        data[size++] = v;
    }

    public int get(int index) {
        return data[index];
    }

    public int size() {
        return size;
    }

    public long bytes() {
        return 4L * data.length + 32L;
    }
}
