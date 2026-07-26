package dev.mapaimine.bridge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Typed snapshot of {@code config.yml}.
 *
 * <p>A snapshot is taken once per {@code /mapaimine reload} and then read from many threads, so
 * every field is final and every collection is immutable — no synchronisation needed on the hot
 * path.
 */
public final class BridgeConfig {

    // ── transport ────────────────────────────────────────────────────────────────────────────
    public final String host;
    public final int port;
    public final String token;
    public final int httpThreads;
    public final int maxRequestBytes;

    // ── limits (mirrored verbatim into GET /health) ──────────────────────────────────────────
    public final int maxOpsPerRequest;
    public final long maxBlocksPerOp;
    public final long maxRegionVolume;
    public final int blocksPerTick;
    public final int maxUndoHistory;
    public final int surveyMaxPoints;

    // ── limits not advertised in /health but enforced ────────────────────────────────────────
    public final long maxUndoBlocks;
    public final int maxUndoMemoryMb;
    public final int maxProbePoints;
    public final long maxSyncBlocks;
    public final int surveyMaxChunks;
    public final int syncTimeoutMs;
    public final int readTimeoutMs;
    public final int finishedJobsKept;
    public final int maxCaptureVolume;

    // ── capabilities ─────────────────────────────────────────────────────────────────────────
    private final Set<String> capabilities;

    // ── safety ───────────────────────────────────────────────────────────────────────────────
    public final Set<String> allowedWorlds;
    public final List<ProtectedRegion> protectedRegions;
    public final boolean defaultPhysics;
    public final boolean defaultLightUpdate;
    public final String logLevel;

    public BridgeConfig(FileConfiguration c) {
        this.host = c.getString("host", "127.0.0.1");
        this.port = c.getInt("port", 25599);
        this.token = c.getString("token", "");
        this.httpThreads = clamp(c.getInt("httpThreads", 4), 1, 32);
        this.maxRequestBytes = clamp(c.getInt("maxRequestBytes", 32 * 1024 * 1024), 64 * 1024, 256 * 1024 * 1024);

        this.maxOpsPerRequest = clamp(c.getInt("limits.maxOpsPerRequest", 4096), 1, 1_000_000);
        this.maxBlocksPerOp = Math.max(1L, c.getLong("limits.maxBlocksPerOp", 4_000_000L));
        this.maxRegionVolume = Math.max(1L, c.getLong("limits.maxRegionVolume", 8_000_000L));
        this.blocksPerTick = clamp(c.getInt("limits.blocksPerTick", 20_000), 1, 5_000_000);
        this.maxUndoHistory = clamp(c.getInt("limits.maxUndoHistory", 20), 0, 1000);
        this.surveyMaxPoints = clamp(c.getInt("limits.surveyMaxPoints", 16_384), 16, 1_048_576);

        this.maxUndoBlocks = Math.max(0L, c.getLong("limits.maxUndoBlocks", 2_000_000L));
        this.maxUndoMemoryMb = clamp(c.getInt("limits.maxUndoMemoryMb", 256), 1, 8192);
        this.maxProbePoints = clamp(c.getInt("limits.maxProbePoints", 1024), 1, 65536);
        this.maxSyncBlocks = Math.max(1L, c.getLong("limits.maxSyncBlocks", 200_000L));
        this.surveyMaxChunks = clamp(c.getInt("limits.surveyMaxChunks", 4096), 16, 262_144);
        this.syncTimeoutMs = clamp(c.getInt("limits.syncTimeoutMs", 60_000), 1000, 600_000);
        this.readTimeoutMs = clamp(c.getInt("limits.readTimeoutMs", 15_000), 500, 120_000);
        this.finishedJobsKept = clamp(c.getInt("limits.finishedJobsKept", 64), 1, 4096);
        this.maxCaptureVolume = clamp(c.getInt("limits.maxCaptureVolume", 2_000_000), 1, 64_000_000);

        Set<String> caps = new LinkedHashSet<>();
        ConfigurationSection capSection = c.getConfigurationSection("capabilities");
        for (String name : DEFAULT_CAPABILITIES) {
            boolean enabled = capSection == null || capSection.getBoolean(name, defaultCapability(name));
            if (enabled) caps.add(name);
        }
        this.capabilities = Set.copyOf(caps);

        Set<String> worlds = new LinkedHashSet<>(c.getStringList("allowedWorlds"));
        this.allowedWorlds = Set.copyOf(worlds);

        List<ProtectedRegion> regions = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("protectedRegions")) {
            ProtectedRegion r = ProtectedRegion.fromMap(raw);
            if (r != null) regions.add(r);
        }
        this.protectedRegions = List.copyOf(regions);

        this.defaultPhysics = c.getBoolean("physics", false);
        this.defaultLightUpdate = c.getBoolean("lightUpdate", true);
        this.logLevel = c.getString("logLevel", "INFO").toUpperCase(Locale.ROOT);
    }

    /** Capability names advertised in {@code GET /health} (PROTOCOL.md §2). */
    public static final List<String> DEFAULT_CAPABILITIES = List.of(
            "survey", "undo", "jobs", "worldCreate", "schematic", "entities", "containers",
            "commands", "trees");

    private static boolean defaultCapability(String name) {
        // "commands" is an escape hatch that can run arbitrary console commands: opt-in only.
        return !name.equals("commands");
    }

    public boolean has(String capability) {
        return capabilities.contains(capability);
    }

    public List<String> capabilityList() {
        return new ArrayList<>(capabilities);
    }

    /** {@code allowedWorlds} empty means "every loaded world is fair game". */
    public boolean worldAllowed(String worldName) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(worldName);
    }

    /** True when any protected region covers the given block. */
    public boolean isProtected(String world, int x, int y, int z) {
        for (ProtectedRegion r : protectedRegions) {
            if (r.contains(world, x, y, z)) return true;
        }
        return false;
    }

    /** True when a protected region intersects the given (inclusive) box. */
    public boolean intersectsProtected(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (ProtectedRegion r : protectedRegions) {
            if (r.intersects(world, x1, y1, z1, x2, y2, z2)) return true;
        }
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** An axis-aligned, inclusive, world-scoped no-build box. */
    public static final class ProtectedRegion {
        public final String world;
        public final int x1, y1, z1, x2, y2, z2;

        ProtectedRegion(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
            this.world = world;
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
        }

        static ProtectedRegion fromMap(Map<?, ?> raw) {
            Object world = raw.get("world");
            Object from = raw.get("from");
            Object to = raw.get("to");
            if (!(from instanceof List<?> f) || !(to instanceof List<?> t) || f.size() < 3 || t.size() < 3) {
                return null;
            }
            return new ProtectedRegion(world == null ? "*" : String.valueOf(world),
                    num(f.get(0)), num(f.get(1)), num(f.get(2)),
                    num(t.get(0)), num(t.get(1)), num(t.get(2)));
        }

        private static int num(Object o) {
            return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o));
        }

        boolean matchesWorld(String name) {
            return world.equals("*") || world.equals(name);
        }

        boolean contains(String w, int x, int y, int z) {
            return matchesWorld(w) && x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }

        boolean intersects(String w, int ax1, int ay1, int az1, int ax2, int ay2, int az2) {
            if (!matchesWorld(w)) return false;
            return ax1 <= x2 && ax2 >= x1 && ay1 <= y2 && ay2 >= y1 && az1 <= z2 && az2 >= z1;
        }
    }
}
