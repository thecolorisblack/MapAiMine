package dev.mapaimine.bridge.api;

/**
 * Error codes of Bridge Protocol v1 (PROTOCOL.md §1) together with the HTTP status
 * the bridge answers with. The MCP server switches on the {@code code} string, so the
 * spelling here is part of the wire contract and must not change.
 */
public enum ErrorCode {
    UNAUTHORIZED(401),
    BAD_REQUEST(400),
    BAD_BLOCK(400),
    NO_WORLD(404),
    LIMIT_EXCEEDED(413),
    REGION_PROTECTED(403),
    JOB_NOT_FOUND(404),
    UNDO_EMPTY(409),
    DISABLED(403),
    INTERNAL(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
