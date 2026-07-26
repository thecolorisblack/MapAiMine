package dev.mapaimine.bridge.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.api.ErrorCode;
import dev.mapaimine.bridge.json.J;
import dev.mapaimine.bridge.ops.Region;
import dev.mapaimine.bridge.ops.RleData;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code POST /capture} and the {@code /captures} readers.
 *
 * <p>Produces the {@code RawStructure} document of SCHEMAS.md §3: a de-duplicated block-state
 * palette plus a run-length-encoded index stream in {@code y → z → x} order — the exact same
 * encoding the compact {@code blocks} op consumes, so a capture can be replayed verbatim.
 *
 * <p>Captures are written to {@code plugins/MapAiMine/captures/<name>.json}.
 *
 * <p><b>Threading.</b> {@link #capture} reads the world and must run on the main thread; the file
 * write is done inline because captures are bounded by {@code limits.maxCaptureVolume}.
 */
public final class CaptureService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final BridgeConfig config;
    private final Path directory;

    public CaptureService(BridgeConfig config, Path pluginFolder) {
        this.config = config;
        this.directory = pluginFolder.resolve("captures");
    }

    public JsonObject capture(World world, Region region, String rawName, boolean trimAir) {
        if (!config.has("schematic")) throw ApiException.disabled("schematic");
        String name = sanitize(rawName);
        if (region.volume() > config.maxCaptureVolume) {
            throw ApiException.limit("capture volume " + region.volume() + " exceeds limits.maxCaptureVolume ("
                    + config.maxCaptureVolume + ")", "capture a smaller region");
        }

        Region bounds = trimAir ? trim(world, region) : region;
        if (bounds == null) {
            throw ApiException.badRequest("the region contains only air; nothing to capture");
        }

        int sx = bounds.sizeX();
        int sy = bounds.sizeY();
        int sz = bounds.sizeZ();

        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        RleData.Encoder encoder = new RleData.Encoder();
        JsonArray blockEntities = new JsonArray();

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int wx = bounds.x1 + x;
                    int wy = bounds.y1 + y;
                    int wz = bounds.z1 + z;
                    if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) {
                        encoder.add(indexOf(palette, paletteIndex, "minecraft:air"));
                        continue;
                    }
                    Block block = world.getBlockAt(wx, wy, wz);
                    BlockData data = block.getBlockData();
                    encoder.add(indexOf(palette, paletteIndex, data.getAsString()));
                    JsonObject entity = describeBlockEntity(block, x, y, z);
                    if (entity != null) blockEntities.add(entity);
                }
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("id", name);
        out.addProperty("kind", "raw");
        out.add("size", J.intArray(sx, sy, sz));
        out.add("palette", J.toArray(palette));
        out.addProperty("data", encoder.finish());
        out.add("blockEntities", blockEntities);
        out.add("entities", new JsonArray());
        out.addProperty("capturedAt", System.currentTimeMillis());
        out.addProperty("sourceWorld", world.getName());
        out.add("origin", J.intArray(bounds.x1, bounds.y1, bounds.z1));

        save(name, out);
        return out;
    }

    /** Shrinks the region so that fully-air outer slabs are dropped. */
    private Region trim(World world, Region region) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int y = region.y1; y <= region.y2; y++) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            for (int z = region.z1; z <= region.z2; z++) {
                for (int x = region.x1; x <= region.x2; x++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) continue;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new Region(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Captures the cheap, useful parts of a block entity: sign text, inventory, spawner type. */
    private JsonObject describeBlockEntity(Block block, int x, int y, int z) {
        BlockState state;
        try {
            state = block.getState();
        } catch (RuntimeException ex) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.add("pos", J.intArray(x, y, z));
        if (state instanceof Sign sign) {
            o.addProperty("type", "sign");
            o.add("front", J.toArray(List.of(sign.getSide(Side.FRONT).getLines())));
            o.add("back", J.toArray(List.of(sign.getSide(Side.BACK).getLines())));
            o.addProperty("glowing", sign.getSide(Side.FRONT).isGlowingText());
            return o;
        }
        if (state instanceof CreatureSpawner spawner) {
            o.addProperty("type", "spawner");
            o.addProperty("entity", spawner.getSpawnedType() == null ? null
                    : spawner.getSpawnedType().getKey().toString());
            o.addProperty("delay", spawner.getDelay());
            return o;
        }
        if (state instanceof Container container) {
            o.addProperty("type", "container");
            JsonArray items = new JsonArray();
            ItemStack[] contents = container.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || stack.getType().isAir()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("slot", slot);
                item.addProperty("id", stack.getType().getKey().toString());
                item.addProperty("count", stack.getAmount());
                items.add(item);
            }
            if (items.isEmpty()) return null;
            o.add("items", items);
            return o;
        }
        return null;
    }

    private static int indexOf(List<String> palette, Map<String, Integer> index, String state) {
        Integer existing = index.get(state);
        if (existing != null) return existing;
        int i = palette.size();
        palette.add(state);
        index.put(state, i);
        return i;
    }

    // ── storage ──────────────────────────────────────────────────────────────────────────────

    private void save(String name, JsonObject document) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".json"), GSON.toJson(document),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL, "could not write capture: " + ex.getMessage());
        }
    }

    public JsonArray list() {
        JsonArray arr = new JsonArray();
        if (!Files.isDirectory(directory)) return arr;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        JsonObject o = new JsonObject();
                        String file = p.getFileName().toString();
                        o.addProperty("name", file.substring(0, file.length() - 5));
                        try {
                            o.addProperty("bytes", Files.size(p));
                            o.addProperty("modifiedAt", Files.getLastModifiedTime(p).toMillis());
                        } catch (IOException ignored) {
                            // stat failure is not worth failing the listing over
                        }
                        arr.add(o);
                    });
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL, "could not list captures: " + ex.getMessage());
        }
        return arr;
    }

    public JsonObject read(String rawName) {
        String name = sanitize(rawName);
        Path file = directory.resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "no capture named '" + name + "'",
                    "call GET /api/v1/captures for the list");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return J.asObject(parsed, "capture file");
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL, "could not read capture: " + ex.getMessage());
        }
    }

    /** Prevents path traversal — capture names are file names, not paths. */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) throw ApiException.badRequest("'name' is required");
        String name = raw.trim();
        if (!name.matches("[A-Za-z0-9_\\-.]{1,64}") || name.contains("..")) {
            throw ApiException.badRequest("capture 'name' must match [A-Za-z0-9_-.]{1,64}");
        }
        return name;
    }
}
