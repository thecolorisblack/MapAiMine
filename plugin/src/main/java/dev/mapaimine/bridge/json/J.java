package dev.mapaimine.bridge.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mapaimine.bridge.api.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small, exception-safe accessors over Gson trees.
 *
 * <p>Every getter validates and throws {@link ApiException} with {@code BAD_REQUEST} instead of
 * letting a {@code ClassCastException} or {@code NullPointerException} escape — the HTTP layer
 * must never turn a malformed request into a 500.
 */
public final class J {

    private J() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonObject asObject(JsonElement el, String what) {
        if (el == null || !el.isJsonObject()) {
            throw ApiException.badRequest(what + " must be a JSON object");
        }
        return el.getAsJsonObject();
    }

    public static boolean has(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    public static String str(JsonObject o, String key, String def) {
        if (!has(o, key)) return def;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) throw ApiException.badRequest("'" + key + "' must be a string");
        return el.getAsString();
    }

    public static String reqStr(JsonObject o, String key) {
        String v = str(o, key, null);
        if (v == null) throw ApiException.badRequest("missing required field '" + key + "'");
        return v;
    }

    public static int i(JsonObject o, String key, int def) {
        if (!has(o, key)) return def;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !((JsonPrimitive) el).isNumber()) {
            throw ApiException.badRequest("'" + key + "' must be a number");
        }
        return el.getAsInt();
    }

    public static int reqInt(JsonObject o, String key) {
        if (!has(o, key)) throw ApiException.badRequest("missing required field '" + key + "'");
        return i(o, key, 0);
    }

    public static long lng(JsonObject o, String key, long def) {
        if (!has(o, key)) return def;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !((JsonPrimitive) el).isNumber()) {
            throw ApiException.badRequest("'" + key + "' must be a number");
        }
        return el.getAsLong();
    }

    public static double dbl(JsonObject o, String key, double def) {
        if (!has(o, key)) return def;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !((JsonPrimitive) el).isNumber()) {
            throw ApiException.badRequest("'" + key + "' must be a number");
        }
        return el.getAsDouble();
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (!has(o, key)) return def;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) throw ApiException.badRequest("'" + key + "' must be a boolean");
        return el.getAsBoolean();
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (!has(o, key)) return null;
        JsonElement el = o.get(key);
        if (!el.isJsonArray()) throw ApiException.badRequest("'" + key + "' must be an array");
        return el.getAsJsonArray();
    }

    public static JsonArray reqArr(JsonObject o, String key) {
        JsonArray a = arr(o, key);
        if (a == null) throw ApiException.badRequest("missing required array '" + key + "'");
        return a;
    }

    public static JsonObject child(JsonObject o, String key) {
        if (!has(o, key)) return null;
        JsonElement el = o.get(key);
        if (!el.isJsonObject()) throw ApiException.badRequest("'" + key + "' must be an object");
        return el.getAsJsonObject();
    }

    /** Parses a {@code Pos} — {@code [x, y, z]} of integers. */
    public static int[] pos(JsonObject o, String key) {
        JsonArray a = reqArr(o, key);
        if (a.size() != 3) throw ApiException.badRequest("'" + key + "' must be [x, y, z]");
        return new int[]{floor(a.get(0)), floor(a.get(1)), floor(a.get(2))};
    }

    public static int[] posOrNull(JsonObject o, String key) {
        return has(o, key) ? pos(o, key) : null;
    }

    /** Parses a floating point position — used for entity spawns which may sit mid-block. */
    public static double[] posD(JsonObject o, String key) {
        JsonArray a = reqArr(o, key);
        if (a.size() != 3) throw ApiException.badRequest("'" + key + "' must be [x, y, z]");
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    /** Parses a {@code Pos2} — {@code [x, z]}. */
    public static int[] pos2(JsonObject o, String key) {
        JsonArray a = reqArr(o, key);
        if (a.size() != 2) throw ApiException.badRequest("'" + key + "' must be [x, z]");
        return new int[]{floor(a.get(0)), floor(a.get(1))};
    }

    private static int floor(JsonElement el) {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw ApiException.badRequest("coordinate must be a number");
        }
        return (int) Math.floor(el.getAsDouble());
    }

    public static List<String> strList(JsonObject o, String key) {
        JsonArray a = arr(o, key);
        if (a == null) return null;
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement el : a) out.add(el.getAsString());
        return out;
    }

    public static JsonArray toArray(Iterable<String> values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    public static JsonArray intArray(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) a.add(v);
        return a;
    }

    public static JsonObject histogram(Map<String, Integer> counts) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : counts.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    /** Rounds to one decimal place; used for percentages and averages in responses. */
    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
