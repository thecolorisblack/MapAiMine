package dev.mapaimine.bridge.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A set of block matchers used by {@code filter}, {@code find}, {@code onlyOn} and friends.
 *
 * <p>Matching rules:
 * <ul>
 *   <li>{@code "#minecraft:logs"} — matches any material in the block tag.</li>
 *   <li>{@code "minecraft:oak_stairs"} — matches by material only, any block state.</li>
 *   <li>{@code "minecraft:oak_stairs[facing=north]"} — matches only states that declare the
 *       listed properties (partial match, extra properties are ignored).</li>
 * </ul>
 */
public final class BlockFilter {

    private final EnumSet<Material> materials;
    private final List<BlockData> statefulMatchers;

    private BlockFilter(EnumSet<Material> materials, List<BlockData> statefulMatchers) {
        this.materials = materials;
        this.statefulMatchers = statefulMatchers;
    }

    /** Returns {@code null} when the field is absent — callers treat null as "match everything". */
    public static BlockFilter parse(BlockParser parser, JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) return null;
        JsonElement el = o.get(field);
        List<String> entries = new ArrayList<>();
        if (el.isJsonPrimitive()) {
            entries.add(el.getAsString());
        } else if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.isEmpty()) return null;
            for (JsonElement e : a) entries.add(e.getAsString());
        } else {
            throw dev.mapaimine.bridge.api.ApiException.badRequest(
                    "'" + field + "' must be a string or an array of block/tag strings");
        }
        return of(parser, entries);
    }

    public static BlockFilter of(BlockParser parser, List<String> entries) {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        List<BlockData> stateful = new ArrayList<>();
        for (String raw : entries) {
            String norm = BlockParser.normalizeInput(raw);
            if (norm.startsWith("#")) {
                materials.addAll(parser.resolveTag(norm));
            } else if (norm.indexOf('[') >= 0) {
                stateful.add(parser.parse(norm));
            } else {
                BlockData d = parser.parse(norm);
                materials.add(d.getMaterial());
            }
        }
        return new BlockFilter(materials, stateful);
    }

    public boolean matches(BlockData data) {
        if (data == null) return false;
        if (materials.contains(data.getMaterial())) return true;
        for (BlockData m : statefulMatchers) {
            if (data.matches(m)) return true;
        }
        return false;
    }

    public boolean matches(Material material) {
        if (materials.contains(material)) return true;
        for (BlockData m : statefulMatchers) {
            if (m.getMaterial() == material) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return materials.isEmpty() && statefulMatchers.isEmpty();
    }
}
