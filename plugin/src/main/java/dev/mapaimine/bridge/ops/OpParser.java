package dev.mapaimine.bridge.ops;

import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.json.J;

import java.util.List;
import java.util.Locale;

/**
 * Turns one entry of the {@code ops} array into an {@link Op}.
 *
 * <p>Unknown {@code type} values are rejected with {@code BAD_REQUEST} and the full list of
 * supported types, as PROTOCOL.md §4 requires.
 */
public final class OpParser {

    /** Every op type of Bridge Protocol v1, in the order they appear in PROTOCOL.md §2. */
    public static final List<String> SUPPORTED = List.of(
            "set", "blocks", "fill", "sphere", "cylinder", "pyramid", "cone", "line", "walls",
            "torus", "replace", "paint", "scatter", "smooth", "flatten", "raise", "terrace",
            "clear", "sign", "container", "head", "spawner", "entity", "command", "checkpoint");

    private OpParser() {
    }

    public static Op parse(BlockParser parser, BridgeConfig config, JsonObject o, int index) {
        String type = J.str(o, "type", null);
        if (type == null) {
            throw ApiException.badRequest("ops[" + index + "] is missing 'type'",
                    "supported types: " + String.join(", ", SUPPORTED));
        }
        String t = type.trim().toLowerCase(Locale.ROOT);
        try {
            Op op = create(parser, o, t);
            op.validate(config);
            return op;
        } catch (ApiException ex) {
            // Prefix the op index so the MCP server can point at the offending entry.
            throw new ApiException(ex.code(), "ops[" + index + "] (" + t + "): " + ex.getMessage(), ex.hint());
        }
    }

    private static Op create(BlockParser parser, JsonObject o, String type) {
        return switch (type) {
            case "set" -> new BasicOps.SetOp(parser, o);
            case "blocks" -> new BasicOps.BlocksOp(parser, o);
            case "fill" -> new BasicOps.FillOp(parser, o);
            case "replace" -> new BasicOps.ReplaceOp(parser, o);
            case "clear" -> new BasicOps.ClearOp(o);
            case "sphere" -> new ShapeOps.SphereOp(parser, o);
            case "cylinder" -> new ShapeOps.CylinderOp(parser, o);
            case "pyramid" -> new ShapeOps.PyramidOp(parser, o);
            case "cone" -> new ShapeOps.ConeOp(parser, o);
            case "line" -> new ShapeOps.LineOp(parser, o);
            case "walls" -> new ShapeOps.WallsOp(parser, o);
            case "torus" -> new ShapeOps.TorusOp(parser, o);
            case "paint" -> new TerrainOps.PaintOp(parser, o);
            case "scatter" -> new TerrainOps.ScatterOp(parser, o);
            case "smooth" -> new TerrainOps.SmoothOp(o);
            case "flatten" -> new TerrainOps.FlattenOp(parser, o);
            case "raise" -> new TerrainOps.RaiseOp(o);
            case "terrace" -> new TerrainOps.TerraceOp(o);
            case "sign" -> new EntityOps.SignOp(parser, o);
            case "container" -> new EntityOps.ContainerOp(parser, o);
            case "head" -> new EntityOps.HeadOp(parser, o);
            case "spawner" -> new EntityOps.SpawnerOp(parser, o);
            case "entity" -> new EntityOps.EntityOp(o);
            case "command" -> new EntityOps.CommandOp(o);
            case "checkpoint" -> new EntityOps.CheckpointOp(o);
            default -> throw ApiException.badRequest("unknown op type '" + type + "'",
                    "supported types: " + String.join(", ", SUPPORTED));
        };
    }
}
