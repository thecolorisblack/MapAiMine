package dev.mapaimine.bridge.http;

import com.google.gson.JsonElement;

/** A handler result: the {@code data} payload plus the HTTP status to send it with. */
public final class ApiResponse {

    public final int status;
    public final JsonElement data;

    private ApiResponse(int status, JsonElement data) {
        this.status = status;
        this.data = data;
    }

    public static ApiResponse ok(JsonElement data) {
        return new ApiResponse(200, data);
    }

    /** 202 — the job was accepted and will run on the tick budget (PROTOCOL.md §2). */
    public static ApiResponse accepted(JsonElement data) {
        return new ApiResponse(202, data);
    }
}
