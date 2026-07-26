package dev.mapaimine.bridge;

import com.google.gson.JsonObject;
import dev.mapaimine.bridge.json.J;
import org.bukkit.Location;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region selections made in-game with {@code /mapaimine pos1} / {@code pos2}.
 *
 * <p>This is the "point at it and tell the AI" workflow: a player marks two corners, the MCP
 * server reads them through {@code GET /api/v1/selection?player=<name>} and can then build,
 * capture or survey exactly that box without anybody typing coordinates.
 *
 * <p>Selections live in memory only and are keyed by player name; they are intentionally not
 * persisted across restarts.
 */
public final class SelectionManager {

    /** One corner pair. Either corner may be unset. */
    public static final class Selection {
        public String world;
        public int[] pos1;
        public int[] pos2;
    }

    private final Map<String, Selection> selections = new ConcurrentHashMap<>();

    public void setPos1(String player, Location loc) {
        Selection sel = selections.computeIfAbsent(player.toLowerCase(java.util.Locale.ROOT),
                k -> new Selection());
        sel.world = loc.getWorld() == null ? null : loc.getWorld().getName();
        sel.pos1 = new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
    }

    public void setPos2(String player, Location loc) {
        Selection sel = selections.computeIfAbsent(player.toLowerCase(java.util.Locale.ROOT),
                k -> new Selection());
        sel.world = loc.getWorld() == null ? null : loc.getWorld().getName();
        sel.pos2 = new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
    }

    public Selection get(String player) {
        return selections.get(player.toLowerCase(java.util.Locale.ROOT));
    }

    /** The {@code GET /selection} representation. */
    public JsonObject toJson(String player) {
        Selection sel = get(player);
        JsonObject o = new JsonObject();
        o.addProperty("player", player);
        if (sel == null) {
            o.addProperty("world", (String) null);
            o.add("pos1", null);
            o.add("pos2", null);
            o.addProperty("complete", false);
            return o;
        }
        o.addProperty("world", sel.world);
        o.add("pos1", sel.pos1 == null ? null : J.intArray(sel.pos1[0], sel.pos1[1], sel.pos1[2]));
        o.add("pos2", sel.pos2 == null ? null : J.intArray(sel.pos2[0], sel.pos2[1], sel.pos2[2]));
        boolean complete = sel.pos1 != null && sel.pos2 != null;
        o.addProperty("complete", complete);
        if (complete) {
            int sx = Math.abs(sel.pos1[0] - sel.pos2[0]) + 1;
            int sy = Math.abs(sel.pos1[1] - sel.pos2[1]) + 1;
            int sz = Math.abs(sel.pos1[2] - sel.pos2[2]) + 1;
            o.add("size", J.intArray(sx, sy, sz));
            o.addProperty("volume", (long) sx * sy * sz);
        }
        return o;
    }

    public void clear() {
        selections.clear();
    }
}
