package dev.mapaimine.bridge.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.json.J;
import dev.mapaimine.bridge.world.VoidChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;

/**
 * {@code /worlds} endpoints: listing, creation, settings, spawn and world border.
 *
 * <p><b>Threading.</b> Every method here touches the world and must be invoked on the main thread.
 * World creation in particular can take tens of seconds while chunks generate; the HTTP layer
 * gives it its own extended timeout (PROTOCOL.md §2 allows up to 60 s).
 */
public final class WorldService {

    private final Plugin plugin;
    private final BridgeConfig config;

    public WorldService(Plugin plugin, BridgeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ── read ─────────────────────────────────────────────────────────────────────────────────

    public JsonArray listWorlds() {
        JsonArray arr = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            if (!config.worldAllowed(world.getName())) continue;
            arr.add(worldJson(world));
        }
        return arr;
    }

    public JsonObject worldJson(World world) {
        JsonObject o = new JsonObject();
        o.addProperty("name", world.getName());
        o.addProperty("environment", world.getEnvironment().name());
        o.addProperty("seed", world.getSeed());
        o.addProperty("worldType", world.getWorldType() == null ? "CUSTOM" : world.getWorldType().name());
        Location spawn = world.getSpawnLocation();
        o.add("spawn", J.intArray(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()));
        o.addProperty("time", world.getTime());
        o.addProperty("weather", world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear");
        o.addProperty("difficulty", world.getDifficulty().name());
        o.addProperty("players", world.getPlayers().size());
        o.addProperty("loadedChunks", world.getLoadedChunks().length);
        o.addProperty("minY", world.getMinHeight());
        o.addProperty("maxY", world.getMaxHeight());
        o.addProperty("pvp", world.getPVP());
        return o;
    }

    // ── create ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code POST /worlds}. Supports {@code normal|flat|large_biomes|amplified|void}.
     *
     * <p>The legacy {@code flatPreset} string is translated into the modern generator-settings
     * JSON the server expects, so callers can keep using the familiar
     * {@code "minecraft:bedrock,2*minecraft:dirt;minecraft:plains"} form.
     */
    public World createWorld(JsonObject body) {
        if (!config.has("worldCreate")) throw ApiException.disabled("worldCreate");
        String name = J.reqStr(body, "name").trim();
        if (!name.matches("[A-Za-z0-9_\\-]{1,48}")) {
            throw ApiException.badRequest("world 'name' must match [A-Za-z0-9_-]{1,48}");
        }
        if (Bukkit.getWorld(name) != null) {
            throw ApiException.badRequest("world '" + name + "' already exists and is loaded");
        }
        if (!config.worldAllowed(name)) {
            throw new ApiException(dev.mapaimine.bridge.api.ErrorCode.REGION_PROTECTED,
                    "world '" + name + "' is not in allowedWorlds");
        }

        WorldCreator creator = new WorldCreator(name);
        String environment = J.str(body, "environment", "normal").toUpperCase(Locale.ROOT);
        try {
            creator.environment(World.Environment.valueOf(
                    environment.equals("THE_END") ? "THE_END" : environment));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("unknown environment '" + environment + "'",
                    "supported: normal, nether, the_end");
        }

        String worldType = J.str(body, "worldType", "normal").toLowerCase(Locale.ROOT);
        switch (worldType) {
            case "normal" -> creator.type(WorldType.NORMAL);
            case "flat" -> creator.type(WorldType.FLAT);
            case "large_biomes" -> creator.type(WorldType.LARGE_BIOMES);
            case "amplified" -> creator.type(WorldType.AMPLIFIED);
            case "void" -> {
                creator.type(WorldType.FLAT);
                creator.generator(new VoidChunkGenerator());
                creator.generateStructures(false);
            }
            default -> throw ApiException.badRequest("unknown worldType '" + worldType + "'",
                    "supported: normal, flat, large_biomes, amplified, void");
        }

        if (J.has(body, "seed")) creator.seed(J.lng(body, "seed", 0));
        creator.generateStructures(J.bool(body, "generateStructures", !worldType.equals("void")));

        String flatPreset = J.str(body, "flatPreset", null);
        String biome = J.str(body, "biome", null);
        if (worldType.equals("flat") && (flatPreset != null || biome != null)) {
            creator.generatorSettings(flatSettings(flatPreset, biome));
        }

        World world;
        try {
            world = creator.createWorld();
        } catch (RuntimeException ex) {
            throw new ApiException(dev.mapaimine.bridge.api.ErrorCode.INTERNAL,
                    "world creation failed: " + ex.getMessage());
        }
        if (world == null) {
            throw new ApiException(dev.mapaimine.bridge.api.ErrorCode.INTERNAL,
                    "world creation returned null (see server log)");
        }
        return world;
    }

    /**
     * Converts {@code "minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains"}
     * into the JSON generator settings used since 1.16. Unparseable input is passed through
     * untouched so a caller can always supply raw JSON instead.
     */
    static String flatSettings(String preset, String biome) {
        if (preset != null && preset.trim().startsWith("{")) return preset;
        StringBuilder layers = new StringBuilder("[");
        String resolvedBiome = biome != null ? withNamespace(biome) : "minecraft:plains";
        if (preset != null && !preset.isBlank()) {
            String[] halves = preset.split(";");
            if (halves.length > 1 && !halves[1].isBlank() && biome == null) {
                resolvedBiome = withNamespace(halves[1].trim());
            }
            for (String layer : halves[0].split(",")) {
                String spec = layer.trim();
                if (spec.isEmpty()) continue;
                int height = 1;
                String block = spec;
                int star = spec.indexOf('*');
                if (star > 0) {
                    try {
                        height = Integer.parseInt(spec.substring(0, star).trim());
                    } catch (NumberFormatException ignored) {
                        height = 1;
                    }
                    block = spec.substring(star + 1).trim();
                }
                if (layers.length() > 1) layers.append(',');
                layers.append("{\"block\":\"").append(withNamespace(block))
                        .append("\",\"height\":").append(Math.max(1, height)).append('}');
            }
        }
        layers.append(']');
        if (layers.length() == 2) {
            layers = new StringBuilder("[{\"block\":\"minecraft:bedrock\",\"height\":1},"
                    + "{\"block\":\"minecraft:dirt\",\"height\":2},"
                    + "{\"block\":\"minecraft:grass_block\",\"height\":1}]");
        }
        return "{\"layers\":" + layers + ",\"biome\":\"" + resolvedBiome + "\"}";
    }

    private static String withNamespace(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────────

    /** {@code POST /worlds/{name}/settings}; every field is optional. */
    public JsonObject applySettings(World world, JsonObject body) {
        JsonObject applied = new JsonObject();

        if (J.has(body, "time")) {
            world.setTime(J.lng(body, "time", 0));
            applied.addProperty("time", world.getTime());
        }
        if (J.has(body, "timeLock")) {
            boolean lock = J.bool(body, "timeLock", false);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, !lock);
            applied.addProperty("timeLock", lock);
        }
        if (J.has(body, "weather")) {
            String weather = J.str(body, "weather", "clear").toLowerCase(Locale.ROOT);
            switch (weather) {
                case "clear" -> {
                    world.setStorm(false);
                    world.setThundering(false);
                }
                case "rain" -> {
                    world.setStorm(true);
                    world.setThundering(false);
                }
                case "thunder" -> {
                    world.setStorm(true);
                    world.setThundering(true);
                }
                default -> throw ApiException.badRequest("unknown weather '" + weather + "'",
                        "supported: clear, rain, thunder");
            }
            applied.addProperty("weather", weather);
        }
        if (J.has(body, "weatherLock")) {
            boolean lock = J.bool(body, "weatherLock", false);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, !lock);
            applied.addProperty("weatherLock", lock);
        }
        if (J.has(body, "difficulty")) {
            String raw = J.str(body, "difficulty", "normal").toUpperCase(Locale.ROOT);
            try {
                world.setDifficulty(Difficulty.valueOf(raw));
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("unknown difficulty '" + raw + "'",
                        "supported: peaceful, easy, normal, hard");
            }
            applied.addProperty("difficulty", world.getDifficulty().name());
        }
        if (J.has(body, "pvp")) {
            world.setPVP(J.bool(body, "pvp", true));
            applied.addProperty("pvp", world.getPVP());
        }
        if (J.has(body, "spawnRadius")) {
            world.setGameRule(GameRule.SPAWN_RADIUS, J.i(body, "spawnRadius", 8));
            applied.addProperty("spawnRadius", J.i(body, "spawnRadius", 8));
        }
        JsonObject gameRules = J.child(body, "gameRules");
        if (gameRules != null) {
            JsonObject appliedRules = new JsonObject();
            for (Map.Entry<String, JsonElement> e : gameRules.entrySet()) {
                String result = applyGameRule(world, e.getKey(), e.getValue());
                appliedRules.addProperty(e.getKey(), result);
            }
            applied.add("gameRules", appliedRules);
        }
        if (J.has(body, "keepLoaded")) {
            boolean keep = J.bool(body, "keepLoaded", false);
            applyKeepLoaded(world, keep);
            applied.addProperty("keepLoaded", keep);
        }
        return applied;
    }

    @SuppressWarnings("unchecked")
    private String applyGameRule(World world, String name, JsonElement value) {
        GameRule<?> rule = GameRule.getByName(name);
        if (rule == null) return "unknown gamerule, ignored";
        try {
            if (rule.getType() == Boolean.class) {
                world.setGameRule((GameRule<Boolean>) rule, value.getAsBoolean());
                return String.valueOf(value.getAsBoolean());
            }
            world.setGameRule((GameRule<Integer>) rule, value.getAsInt());
            return String.valueOf(value.getAsInt());
        } catch (RuntimeException ex) {
            return "invalid value, ignored";
        }
    }

    /**
     * Keeps the spawn area resident. Chunk tickets are added around spawn in addition to
     * {@code setKeepSpawnInMemory} because several forks ignore the latter.
     */
    @SuppressWarnings("deprecation")
    private void applyKeepLoaded(World world, boolean keep) {
        try {
            world.setKeepSpawnInMemory(keep);
        } catch (RuntimeException ignored) {
            // not supported on this fork; the chunk tickets below still do the job
        }
        Location spawn = world.getSpawnLocation();
        int cx = spawn.getBlockX() >> 4;
        int cz = spawn.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (keep) {
                    world.addPluginChunkTicket(cx + dx, cz + dz, plugin);
                } else {
                    world.removePluginChunkTicket(cx + dx, cz + dz, plugin);
                }
            }
        }
    }

    // ── spawn ────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code POST /worlds/{name}/spawn}. With {@code safe:true} the point is lifted to the first
     * position with two blocks of headroom and a platform is laid underneath if it would otherwise
     * be floating over the void.
     */
    public JsonObject setSpawn(World world, JsonObject body) {
        int x = J.i(body, "x", world.getSpawnLocation().getBlockX());
        int y = J.i(body, "y", world.getSpawnLocation().getBlockY());
        int z = J.i(body, "z", world.getSpawnLocation().getBlockZ());
        float yaw = (float) J.dbl(body, "yaw", 0);
        float pitch = (float) J.dbl(body, "pitch", 0);
        boolean safe = J.bool(body, "safe", false);

        if (safe) {
            int[] resolved = findSafeSpot(world, x, y, z);
            x = resolved[0];
            y = resolved[1];
            z = resolved[2];
        }
        world.setSpawnLocation(new Location(world, x + 0.5, y, z + 0.5, yaw, pitch));

        JsonObject out = new JsonObject();
        out.addProperty("world", world.getName());
        out.add("spawn", J.intArray(x, y, z));
        out.addProperty("yaw", yaw);
        out.addProperty("pitch", pitch);
        out.addProperty("safe", safe);
        return out;
    }

    private int[] findSafeSpot(World world, int x, int y, int z) {
        int min = world.getMinHeight();
        int max = world.getMaxHeight() - 2;
        int start = Math.max(min + 1, Math.min(max, y));
        for (int cy = start; cy < max; cy++) {
            Block feet = world.getBlockAt(x, cy, z);
            Block head = world.getBlockAt(x, cy + 1, z);
            Block ground = world.getBlockAt(x, cy - 1, z);
            if (feet.getType().isAir() && head.getType().isAir() && ground.getType().isSolid()) {
                return new int[]{x, cy, z};
            }
        }
        // Nothing solid anywhere in the column (typically a void world): lay a small platform.
        int platformY = Math.max(min + 1, Math.min(max - 1, y));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(x + dx, platformY - 1, z + dz).setType(Material.STONE, false);
                world.getBlockAt(x + dx, platformY, z + dz).setType(Material.AIR, false);
                world.getBlockAt(x + dx, platformY + 1, z + dz).setType(Material.AIR, false);
            }
        }
        return new int[]{x, platformY, z};
    }

    // ── border ───────────────────────────────────────────────────────────────────────────────

    /** {@code POST /worlds/{name}/border}. */
    public JsonObject setBorder(World world, JsonObject body) {
        if (J.has(body, "center")) {
            int[] c = J.pos2(body, "center");
            world.getWorldBorder().setCenter(c[0], c[1]);
        }
        if (J.has(body, "size")) {
            double size = J.dbl(body, "size", 60_000_000);
            if (size <= 0) throw ApiException.badRequest("'size' must be > 0");
            world.getWorldBorder().setSize(size);
        }
        if (J.has(body, "warningDistance")) {
            world.getWorldBorder().setWarningDistance(J.i(body, "warningDistance", 5));
        }
        JsonObject out = new JsonObject();
        Location center = world.getWorldBorder().getCenter();
        out.add("center", J.intArray(center.getBlockX(), center.getBlockZ()));
        out.addProperty("size", world.getWorldBorder().getSize());
        out.addProperty("warningDistance", world.getWorldBorder().getWarningDistance());
        return out;
    }
}
