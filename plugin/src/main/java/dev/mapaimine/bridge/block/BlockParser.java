package dev.mapaimine.bridge.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.api.ErrorCode;
import dev.mapaimine.bridge.util.Levenshtein;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses protocol {@code Block} / {@code BlockRef} strings into Bukkit {@link BlockData}.
 *
 * <p>Threading: this class is thread-safe and deliberately does <em>not</em> touch the world, so
 * HTTP worker threads can validate a whole request before anything is scheduled onto the main
 * thread. {@link Bukkit#createBlockData(String)} is a pure parser on the server's block registry
 * and is safe off-thread.
 *
 * <p>Caching: {@link BlockData} instances returned by the server are immutable value objects, so a
 * single parsed instance is shared by every placement of that state. Without the cache a
 * million-block fill would allocate a million identical objects.
 */
public final class BlockParser {

    private final Map<String, BlockData> cache = new ConcurrentHashMap<>();
    private final Map<String, List<Material>> tagCache = new ConcurrentHashMap<>();
    private volatile List<String> blockNamesCache;

    /** Adds the implicit {@code minecraft:} namespace and lowercases the identifier part. */
    public static String normalizeInput(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) throw new ApiException(ErrorCode.BAD_BLOCK, "empty block id");
        int bracket = s.indexOf('[');
        String id = bracket >= 0 ? s.substring(0, bracket) : s;
        String states = bracket >= 0 ? s.substring(bracket) : "";
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("#")) {
            String body = id.substring(1);
            if (!body.contains(":")) body = "minecraft:" + body;
            return "#" + body;
        }
        if (!id.contains(":")) id = "minecraft:" + id;
        return id + states.replace(" ", "");
    }

    /**
     * Parses a block state string. Accepts a missing {@code minecraft:} prefix.
     *
     * @throws ApiException with {@code BAD_BLOCK} and a "did you mean" hint when unknown
     */
    public BlockData parse(String raw) {
        String key = normalizeInput(raw);
        BlockData cached = cache.get(key);
        if (cached != null) return cached;
        BlockData data;
        try {
            data = Bukkit.createBlockData(key);
        } catch (IllegalArgumentException ex) {
            List<String> suggestions = suggest(key);
            String hint = suggestions.isEmpty() ? null : "did you mean " + suggestions.get(0) + "?";
            throw new ApiException(ErrorCode.BAD_BLOCK, "Unknown block id: " + key, hint);
        }
        cache.put(key, data);
        return data;
    }

    /** Non-throwing variant used by {@code POST /validate}. Returns {@code null} when invalid. */
    public BlockData tryParse(String raw) {
        try {
            return parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Parses a {@code BlockRef}: either a plain block string or
     * {@code {"choices":[{"block":..,"weight":n}]}}.
     */
    public BlockRef parseRef(JsonElement el, String field) {
        if (el == null || el.isJsonNull()) {
            throw ApiException.badRequest("missing required field '" + field + "'");
        }
        if (el.isJsonPrimitive()) {
            return BlockRef.fixed(parse(el.getAsString()));
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("choices") && o.get("choices").isJsonArray()) {
                JsonArray choices = o.getAsJsonArray("choices");
                if (choices.isEmpty()) {
                    throw ApiException.badRequest("'" + field + ".choices' must not be empty");
                }
                List<BlockData> blocks = new ArrayList<>(choices.size());
                List<Integer> weights = new ArrayList<>(choices.size());
                for (JsonElement ce : choices) {
                    if (ce.isJsonPrimitive()) {
                        blocks.add(parse(ce.getAsString()));
                        weights.add(1);
                        continue;
                    }
                    JsonObject co = ce.getAsJsonObject();
                    if (!co.has("block")) {
                        throw ApiException.badRequest("'" + field + ".choices[]' entries need a 'block'");
                    }
                    blocks.add(parse(co.get("block").getAsString()));
                    int w = co.has("weight") ? co.get("weight").getAsInt() : 1;
                    weights.add(Math.max(1, w));
                }
                return BlockRef.weighted(blocks, weights);
            }
            if (o.has("block")) {
                return BlockRef.fixed(parse(o.get("block").getAsString()));
            }
        }
        throw ApiException.badRequest("'" + field + "' must be a block string or {\"choices\":[...]}");
    }

    /** Convenience for the very common {@code {"block": ...}} field. */
    public BlockRef parseRefField(JsonObject o, String field) {
        return parseRef(o.get(field), field);
    }

    /**
     * Resolves a {@code #namespace:path} block tag to its materials.
     *
     * <p>Unknown tags do not fail the request: they degrade to a substring heuristic on the
     * material name (e.g. {@code #minecraft:custom_logs} matches everything containing
     * {@code log}), which keeps AI-authored filters usable across server versions.
     */
    public List<Material> resolveTag(String tagName) {
        String key = normalizeInput(tagName);
        List<Material> cached = tagCache.get(key);
        if (cached != null) return cached;

        String body = key.substring(1);
        int colon = body.indexOf(':');
        String namespace = body.substring(0, colon);
        String path = body.substring(colon + 1);

        List<Material> out = new ArrayList<>();
        Tag<Material> tag = null;
        try {
            NamespacedKey nsk = new NamespacedKey(namespace, path);
            tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, nsk, Material.class);
        } catch (RuntimeException ignored) {
            // malformed key or registry unavailable -> heuristic below
        }
        if (tag != null) {
            out.addAll(tag.getValues());
        } else {
            String needle = path.endsWith("s") ? path.substring(0, path.length() - 1) : path;
            for (Material m : Material.values()) {
                if (m.isBlock() && m.getKey().getKey().contains(needle)) out.add(m);
            }
        }
        List<Material> result = Collections.unmodifiableList(out);
        tagCache.put(key, result);
        return result;
    }

    /** All block material ids, sorted, in {@code minecraft:x} form. Cached after first call. */
    public List<String> blockNames() {
        List<String> names = blockNamesCache;
        if (names != null) return names;
        List<String> list = new ArrayList<>(1200);
        for (Material m : Material.values()) {
            if (m.isBlock() && !m.isLegacy()) list.add(m.getKey().toString());
        }
        Collections.sort(list);
        names = Collections.unmodifiableList(list);
        blockNamesCache = names;
        return names;
    }

    /** Levenshtein-based "did you mean" list for an unknown block id. */
    public List<String> suggest(String raw) {
        String key = normalizeInput(raw);
        int bracket = key.indexOf('[');
        String id = bracket >= 0 ? key.substring(0, bracket) : key;
        return Levenshtein.closest(id, blockNames(), 3, Math.max(2, id.length() / 4 + 2));
    }

    public void clearCaches() {
        cache.clear();
        tagCache.clear();
        blockNamesCache = null;
    }
}
