package dev.mapaimine.bridge.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.json.J;

import java.util.Map;

/** A parsed request: path parameters, query string and (lazily validated) JSON body. */
public final class Req {

    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> query;
    private final String rawBody;
    private final boolean authenticated;

    private JsonObject parsedBody;

    public Req(String method, String path, Map<String, String> pathParams, Map<String, String> query,
               String rawBody, boolean authenticated) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.query = query;
        this.rawBody = rawBody;
        this.authenticated = authenticated;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) throw ApiException.badRequest("missing path parameter '" + name + "'");
        return value;
    }

    public String query(String name, String def) {
        String value = query.get(name);
        return value == null || value.isEmpty() ? def : value;
    }

    public int queryInt(String name) {
        String value = query.get(name);
        if (value == null || value.isEmpty()) {
            throw ApiException.badRequest("missing required query parameter '" + name + "'");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("query parameter '" + name + "' must be an integer");
        }
    }

    public Integer queryIntOrNull(String name) {
        String value = query.get(name);
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("query parameter '" + name + "' must be an integer");
        }
    }

    public boolean queryBool(String name, boolean def) {
        String value = query.get(name);
        if (value == null || value.isEmpty()) return def;
        return value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
    }

    /** The request body as a JSON object; an empty body yields an empty object. */
    public JsonObject body() {
        if (parsedBody != null) return parsedBody;
        if (rawBody == null || rawBody.isBlank()) {
            parsedBody = new JsonObject();
            return parsedBody;
        }
        try {
            JsonElement el = JsonParser.parseString(rawBody);
            parsedBody = J.asObject(el, "request body");
        } catch (JsonSyntaxException ex) {
            throw ApiException.badRequest("malformed JSON body: " + ex.getMessage());
        }
        return parsedBody;
    }
}
