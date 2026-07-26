package dev.mapaimine.bridge.api;

/**
 * The only exception type that is allowed to reach the HTTP layer as a client-visible
 * error. Everything else is caught and reported as {@link ErrorCode#INTERNAL}, so a stack
 * trace never leaks into a protocol response.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String hint;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public ErrorCode code() {
        return code;
    }

    public String hint() {
        return hint;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.BAD_REQUEST, message);
    }

    public static ApiException badRequest(String message, String hint) {
        return new ApiException(ErrorCode.BAD_REQUEST, message, hint);
    }

    public static ApiException noWorld(String name) {
        return new ApiException(ErrorCode.NO_WORLD, "Unknown or unloaded world: " + name,
                "call GET /api/v1/worlds to list loaded worlds");
    }

    public static ApiException limit(String message, String hint) {
        return new ApiException(ErrorCode.LIMIT_EXCEEDED, message, hint);
    }

    public static ApiException disabled(String capability) {
        return new ApiException(ErrorCode.DISABLED, "Capability disabled in config: " + capability,
                "enable capabilities." + capability + " in plugins/MapAiMine/config.yml");
    }
}
