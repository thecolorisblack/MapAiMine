package dev.mapaimine.bridge.ops;

import com.google.gson.JsonObject;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.json.J;

/**
 * An inclusive axis-aligned box. Corner order in the request is irrelevant (PROTOCOL.md §2):
 * {@code from}/{@code to} are normalised here so every op can assume {@code x1 <= x2} etc.
 */
public final class Region {

    public final int x1, y1, z1, x2, y2, z2;

    public Region(int ax, int ay, int az, int bx, int by, int bz) {
        this.x1 = Math.min(ax, bx);
        this.y1 = Math.min(ay, by);
        this.z1 = Math.min(az, bz);
        this.x2 = Math.max(ax, bx);
        this.y2 = Math.max(ay, by);
        this.z2 = Math.max(az, bz);
    }

    public static Region parse(JsonObject o) {
        return parse(o, "from", "to");
    }

    public static Region parse(JsonObject o, String fromKey, String toKey) {
        int[] a = J.pos(o, fromKey);
        int[] b = J.pos(o, toKey);
        return new Region(a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    public int sizeX() {
        return x2 - x1 + 1;
    }

    public int sizeY() {
        return y2 - y1 + 1;
    }

    public int sizeZ() {
        return z2 - z1 + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public long area() {
        return (long) sizeX() * sizeZ();
    }

    /** Enforces {@code maxRegionVolume} — PROTOCOL.md §2 "Правила безопасности плагина". */
    public Region requireVolume(long max, String opType) {
        if (volume() > max) {
            throw ApiException.limit(
                    "op '" + opType + "' covers " + volume() + " blocks, over the maxRegionVolume of " + max,
                    "split the region into smaller ops or raise limits.maxRegionVolume");
        }
        return this;
    }

    @Override
    public String toString() {
        return "[" + x1 + "," + y1 + "," + z1 + "]..[" + x2 + "," + y2 + "," + z2 + "]";
    }
}
