package dev.mapaimine.bridge.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.block.BlockFilter;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.block.BlockRef;
import dev.mapaimine.bridge.json.J;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.Set;

/**
 * Surface and terrain ops — PROTOCOL.md §2 groups 6, 7 and 8:
 * paint / scatter / smooth / flatten / raise / terrace.
 *
 * <p>These all work per <em>column</em>: the {@code y} components of {@code from}/{@code to} are
 * ignored except where a vertical range is explicitly meaningful. Because they read the world to
 * find the surface, their iterators are created on the main thread and evaluate lazily, one column
 * at a time, so the tick budget still applies.
 */
public final class TerrainOps {

    private TerrainOps() {
    }

    /** Above this many columns a terrain op is refused: the height arrays would be too large. */
    private static final long MAX_COLUMNS = 4_000_000L;

    // ── shared surface probing ───────────────────────────────────────────────────────────────

    /**
     * Y of the topmost "ground" block of a column: the highest solid, non-fluid, non-foliage
     * block. This is the semantic the survey endpoint and every terrain op agree on.
     */
    public static int surfaceY(World world, int x, int z) {
        int min = world.getMinHeight();
        int y = Math.min(world.getMaxHeight() - 1, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
        int guard = 0;
        while (y >= min && guard++ < 512) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (isGround(m)) return y;
            y--;
        }
        return min;
    }

    /** True for blocks that make up terrain (excludes air, fluids, plants and leaves). */
    public static boolean isGround(Material m) {
        if (m.isAir()) return false;
        if (m == Material.WATER || m == Material.LAVA) return false;
        if (Tag.LEAVES.isTagged(m)) return false;
        return m.isSolid();
    }

    static boolean isWaterAt(World world, int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m == Material.WATER || m == Material.ICE || m == Material.FROSTED_ICE;
    }

    // ── paint ────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"paint","from":[..],"to":[..],"block":BlockRef,"depth":1,"onlyOn":[..],"seed":n}}
     *
     * <p>Repaints the top {@code depth} blocks of every column in the footprint. The {@code y}
     * component of {@code from}/{@code to} is ignored, as the protocol requires.
     */
    public static final class PaintOp extends AbstractPlacementOp {
        private final Region region;
        private final BlockRef ref;
        private final int depth;
        private final BlockFilter onlyOn;
        private final long seed;

        public PaintOp(BlockParser parser, JsonObject o) {
            region = Region.parse(o);
            ref = parser.parseRefField(o, "block");
            depth = Math.max(1, J.i(o, "depth", 1));
            onlyOn = BlockFilter.parse(parser, o, "onlyOn");
            seed = J.lng(o, "seed", 0);
        }

        @Override
        public String type() {
            return "paint";
        }

        @Override
        public long estimate() {
            return region.area() * depth;
        }

        @Override
        public void validate(BridgeConfig config) {
            requireColumns(region, "paint");
            if (estimate() > config.maxBlocksPerOp) {
                throw ApiException.limit("op 'paint' would touch " + estimate() + " blocks, over limits.maxBlocksPerOp",
                        "reduce 'depth' or the area");
            }
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            World world = ctx.world();
            Random rng = seed == 0 ? ctx.random() : new Random(seed);
            return new PlacementIterator() {
                private int x = region.x1;
                private int z = region.z1;
                private int layer = -1;
                private int top;

                @Override
                protected Placement computeNext() {
                    while (true) {
                        if (x > region.x2) return null;
                        if (layer < 0) {
                            top = surfaceY(world, x, z);
                            if (onlyOn != null && !onlyOn.matches(world.getBlockAt(x, top, z).getBlockData())) {
                                nextColumn();
                                continue;
                            }
                            layer = 0;
                        }
                        if (layer >= depth) {
                            nextColumn();
                            continue;
                        }
                        int y = top - layer;
                        layer++;
                        return scratch.set(x, y, z, ref.pick(rng)).withFilter(null).withDestroy(false);
                    }
                }

                private void nextColumn() {
                    layer = -1;
                    if (++z > region.z2) {
                        z = region.z1;
                        x++;
                    }
                }
            };
        }
    }

    // ── scatter ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"scatter", ...}} — sprinkles blocks, mini-structures and trees over a
     * footprint (PROTOCOL.md §2 op 7).
     *
     * <p>This op is not a pure placement stream (it can call {@code generateTree}), so it drives
     * its own budgeted cursor. Determinism comes from a per-column hash of {@code seed}, which
     * means the same request always produces the same layout even if it is resumed across ticks.
     */
    public static final class ScatterOp implements Op {

        private final Region region;
        private final double density;
        private final long seed;
        private final int minSpacing;
        private final List<Entry> entries = new ArrayList<>();
        private final int totalWeight;
        private final BlockFilter onlyOn;
        private final boolean needsAir;
        private final boolean avoidWater;

        private int cx;
        private int cz;
        private boolean started;
        private boolean done;
        private final Set<Long> occupied = new HashSet<>();

        public ScatterOp(BlockParser parser, JsonObject o) {
            region = Region.parse(o);
            density = Math.max(0, Math.min(1, J.dbl(o, "density", 0.05)));
            seed = J.lng(o, "seed", 12345);
            minSpacing = Math.max(0, Math.min(32, J.i(o, "minSpacing", 0)));
            onlyOn = BlockFilter.parse(parser, o, "onlyOn");
            needsAir = J.bool(o, "needsAir", true);
            avoidWater = J.bool(o, "avoidWater", true);

            JsonArray arr = J.reqArr(o, "entries");
            int weight = 0;
            for (JsonElement el : arr) {
                JsonObject e = J.asObject(el, "entries[]");
                Entry entry = new Entry();
                entry.weight = Math.max(1, J.i(e, "weight", 1));
                if (J.has(e, "block")) {
                    entry.block = parser.parseRef(e.get("block"), "entries[].block");
                } else if (J.has(e, "structure")) {
                    entry.structure = Structure.parse(parser, J.child(e, "structure"));
                } else if (J.has(e, "tree")) {
                    entry.tree = parseTree(J.str(e, "tree", "OAK"));
                } else {
                    throw ApiException.badRequest("scatter entries need one of 'block', 'structure' or 'tree'");
                }
                weight += entry.weight;
                entries.add(entry);
            }
            if (entries.isEmpty()) throw ApiException.badRequest("'entries' must not be empty");
            totalWeight = weight;
        }

        @Override
        public String type() {
            return "scatter";
        }

        @Override
        public long estimate() {
            double perPick = 1;
            for (Entry e : entries) {
                if (e.structure != null) {
                    perPick = Math.max(perPick, e.structure.volume());
                } else if (e.tree != null) {
                    perPick = Math.max(perPick, 40);
                }
            }
            return Math.max(1, (long) (region.area() * density * perPick));
        }

        @Override
        public void validate(BridgeConfig config) {
            requireColumns(region, "scatter");
            for (Entry e : entries) {
                if (e.tree != null && !config.has("trees")) {
                    throw ApiException.disabled("trees");
                }
            }
        }

        @Override
        public boolean execute(ExecutionContext ctx) {
            if (done) return true;
            World world = ctx.world();
            if (!started) {
                started = true;
                cx = region.x1;
                cz = region.z1;
            }
            Placement scratch = new Placement();
            while (ctx.hasBudget()) {
                if (cx > region.x2) {
                    done = true;
                    return true;
                }
                int x = cx;
                int z = cz;
                if (++cz > region.z2) {
                    cz = region.z1;
                    cx++;
                }

                // Deterministic per-column RNG: independent of iteration order or resume points.
                Random rng = new Random(mix(seed, x, z));
                ctx.spend(1);
                if (rng.nextDouble() >= density) continue;
                if (minSpacing > 0 && tooClose(x, z)) continue;

                int surface = surfaceY(world, x, z);
                Block ground = world.getBlockAt(x, surface, z);
                if (onlyOn != null && !onlyOn.matches(ground.getBlockData())) continue;
                if (avoidWater && isWaterAt(world, x, surface + 1, z)) continue;
                if (needsAir && !world.getBlockAt(x, surface + 1, z).getType().isAir()) continue;

                Entry entry = pick(rng);
                int baseY = surface + 1;
                if (entry.block != null) {
                    ctx.place(scratch.set(x, baseY, z, entry.block.pick(rng)).withFilter(null).withDestroy(false));
                } else if (entry.structure != null) {
                    entry.structure.stamp(ctx, scratch, x, baseY, z);
                } else if (entry.tree != null && ctx.config().has("trees")) {
                    Location loc = new Location(world, x, baseY, z);
                    // generateTree writes many blocks at once; charge a flat cost to the budget.
                    ctx.spend(40);
                    try {
                        if (world.generateTree(loc, entry.tree)) {
                            ctx.markChanged(x, baseY, z);
                        }
                    } catch (RuntimeException ex) {
                        ctx.warn("scatter: tree " + entry.tree + " failed at " + x + "," + baseY + "," + z);
                    }
                }
                if (minSpacing > 0) occupied.add(key(x, z));
            }
            return false;
        }

        private boolean tooClose(int x, int z) {
            for (int dx = -minSpacing; dx <= minSpacing; dx++) {
                for (int dz = -minSpacing; dz <= minSpacing; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (occupied.contains(key(x + dx, z + dz))) return true;
                }
            }
            return false;
        }

        private Entry pick(Random rng) {
            int roll = rng.nextInt(totalWeight);
            int acc = 0;
            for (Entry e : entries) {
                acc += e.weight;
                if (roll < acc) return e;
            }
            return entries.get(entries.size() - 1);
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        private static long mix(long seed, int x, int z) {
            long h = seed * 0x9E3779B97F4A7C15L;
            h ^= (long) x * 0xC2B2AE3D27D4EB4FL;
            h ^= (long) z * 0x165667B19E3779F9L;
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 32;
            return h;
        }

        private static final class Entry {
            int weight;
            BlockRef block;
            Structure structure;
            TreeType tree;
        }
    }

    /** A tiny inline structure ({@code size}/{@code palette}/{@code data}) used by scatter. */
    public static final class Structure {
        final int sx, sy, sz;
        final BlockData[] palette;
        final String data;

        Structure(int sx, int sy, int sz, BlockData[] palette, String data) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.palette = palette;
            this.data = data;
        }

        static Structure parse(BlockParser parser, JsonObject o) {
            if (o == null) throw ApiException.badRequest("'structure' must be an object");
            JsonArray size = J.reqArr(o, "size");
            if (size.size() != 3) throw ApiException.badRequest("'structure.size' must be [sx, sy, sz]");
            JsonArray pal = J.reqArr(o, "palette");
            BlockData[] palette = new BlockData[pal.size()];
            for (int i = 0; i < pal.size(); i++) palette[i] = parser.parse(pal.get(i).getAsString());
            return new Structure(size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt(),
                    palette, J.reqStr(o, "data"));
        }

        long volume() {
            return (long) sx * sy * sz;
        }

        /** Places the structure with its lower-north-west corner at the given position. */
        void stamp(ExecutionContext ctx, Placement scratch, int ox, int oy, int oz) {
            PrimitiveIterator.OfInt it = RleData.decode(data);
            long i = 0;
            long total = volume();
            while (it.hasNext() && i < total) {
                int idx = it.nextInt();
                int y = (int) (i / ((long) sx * sz));
                long rem = i % ((long) sx * sz);
                int z = (int) (rem / sx);
                int x = (int) (rem % sx);
                i++;
                if (idx < 0 || idx >= palette.length) continue;
                BlockData d = palette[idx];
                if (d.getMaterial().isAir()) continue;
                ctx.place(scratch.set(ox + x, oy + y, oz + z, d).withFilter(null).withDestroy(false));
            }
        }
    }

    static TreeType parseTree(String raw) {
        try {
            return TreeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            StringBuilder sb = new StringBuilder();
            for (TreeType t : TreeType.values()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(t.name());
            }
            throw ApiException.badRequest("unknown tree type '" + raw + "'", "supported: " + sb);
        }
    }

    // ── height-based ops ─────────────────────────────────────────────────────────────────────

    /**
     * Common machinery for smooth / raise / terrace: read the current surface height of every
     * column, compute a target height, then add or remove blocks to match it.
     *
     * <p>The height grids are {@code int[]} of the footprint area (4 bytes per column), which is
     * why the footprint is capped at {@link #MAX_COLUMNS}.
     */
    abstract static class HeightOp extends AbstractPlacementOp {
        final Region region;

        HeightOp(JsonObject o) {
            this.region = Region.parse(o);
        }

        @Override
        public void validate(BridgeConfig config) {
            requireColumns(region, type());
        }

        /** Reads the current surface heights of the footprint, row-major over {@code x} then {@code z}. */
        int[] readHeights(World world) {
            int w = region.sizeX();
            int h = region.sizeZ();
            int[] heights = new int[w * h];
            for (int ix = 0; ix < w; ix++) {
                for (int iz = 0; iz < h; iz++) {
                    heights[ix * h + iz] = surfaceY(world, region.x1 + ix, region.z1 + iz);
                }
            }
            return heights;
        }

        abstract int[] targetHeights(World world, int[] current);

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            World world = ctx.world();
            int[] current = readHeights(world);
            int[] target = targetHeights(world, current);
            return new ColumnAdjustIterator(ctx, region, current, target);
        }
    }

    /**
     * Turns a "column is now N blocks taller/shorter" instruction into placements: growing a
     * column extends it with its own sub-surface material and re-caps it with the original top
     * block; shrinking it replaces the removed blocks with air and re-caps the new top.
     */
    static final class ColumnAdjustIterator extends PlacementIterator {
        private final ExecutionContext ctx;
        private final Region region;
        private final int[] current;
        private final int[] target;
        private final int depth;
        private int index;
        private int step = -1;
        private BlockData topData;
        private BlockData fillData;
        private BlockData air;
        private int from, to, y;
        private boolean removing;

        ColumnAdjustIterator(ExecutionContext ctx, Region region, int[] current, int[] target) {
            this.ctx = ctx;
            this.region = region;
            this.current = current;
            this.target = target;
            this.depth = region.sizeZ();
            this.air = ctx.parser().parse("minecraft:air");
        }

        @Override
        protected Placement computeNext() {
            World world = ctx.world();
            while (true) {
                if (index >= current.length) return null;
                if (step < 0) {
                    int ix = index / depth;
                    int iz = index % depth;
                    int x = region.x1 + ix;
                    int z = region.z1 + iz;
                    int oldH = current[index];
                    int newH = target[index];
                    if (oldH == newH) {
                        index++;
                        continue;
                    }
                    topData = world.getBlockAt(x, oldH, z).getBlockData();
                    Block below = world.getBlockAt(x, Math.max(world.getMinHeight(), oldH - 1), z);
                    fillData = isGround(below.getType()) ? below.getBlockData() : topData;
                    removing = newH < oldH;
                    from = removing ? newH + 1 : oldH + 1;
                    to = removing ? oldH : newH;
                    y = from;
                    step = 0;
                    columnX = x;
                    columnZ = z;
                    columnTop = newH;
                }
                if (y > to) {
                    // Re-cap the column with its original surface block.
                    index++;
                    step = -1;
                    if (removing) {
                        return scratch.set(columnX, columnTop, columnZ, topData).withFilter(null).withDestroy(false);
                    }
                    continue;
                }
                int cy = y++;
                BlockData data;
                if (removing) {
                    data = air;
                } else {
                    data = cy == to ? topData : fillData;
                }
                return scratch.set(columnX, cy, columnZ, data).withFilter(null).withDestroy(false);
            }
        }

        private int columnX;
        private int columnZ;
        private int columnTop;
    }

    /** {@code {"type":"smooth","from":[..],"to":[..],"iterations":2,"strength":1.0}} */
    public static final class SmoothOp extends HeightOp {
        private final int iterations;
        private final double strength;

        public SmoothOp(JsonObject o) {
            super(o);
            iterations = Math.max(1, Math.min(16, J.i(o, "iterations", 1)));
            strength = Math.max(0, Math.min(1, J.dbl(o, "strength", 1.0)));
        }

        @Override
        public String type() {
            return "smooth";
        }

        @Override
        public long estimate() {
            // Smoothing typically moves a couple of blocks per column.
            return region.area() * 3;
        }

        @Override
        int[] targetHeights(World world, int[] current) {
            int w = region.sizeX();
            int h = region.sizeZ();
            double[] buf = new double[current.length];
            for (int i = 0; i < current.length; i++) buf[i] = current[i];
            double[] next = new double[current.length];
            for (int it = 0; it < iterations; it++) {
                for (int ix = 0; ix < w; ix++) {
                    for (int iz = 0; iz < h; iz++) {
                        double sum = 0;
                        int n = 0;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int jx = ix + dx, jz = iz + dz;
                                if (jx < 0 || jx >= w || jz < 0 || jz >= h) continue;
                                sum += buf[jx * h + jz];
                                n++;
                            }
                        }
                        next[ix * h + iz] = sum / n;
                    }
                }
                System.arraycopy(next, 0, buf, 0, buf.length);
            }
            int[] target = new int[current.length];
            for (int i = 0; i < current.length; i++) {
                double blended = current[i] + (buf[i] - current[i]) * strength;
                target[i] = (int) Math.round(blended);
            }
            return target;
        }
    }

    /**
     * {@code {"type":"raise","from":[..],"to":[..],"amount":4,"falloff":"smooth"}}
     *
     * <p>Negative {@code amount} lowers the terrain. {@code falloff} controls how the delta decays
     * towards the edge of the footprint: {@code none} (flat), {@code linear} or {@code smooth}
     * (smoothstep, the default) which produces a natural-looking hill.
     */
    public static final class RaiseOp extends HeightOp {
        private final int amount;
        private final String falloff;

        public RaiseOp(JsonObject o) {
            super(o);
            amount = J.i(o, "amount", 1);
            falloff = J.str(o, "falloff", "smooth").toLowerCase(Locale.ROOT);
        }

        @Override
        public String type() {
            return "raise";
        }

        @Override
        public long estimate() {
            return region.area() * Math.max(1, Math.abs(amount));
        }

        @Override
        int[] targetHeights(World world, int[] current) {
            int w = region.sizeX();
            int h = region.sizeZ();
            double cxc = (w - 1) / 2.0;
            double czc = (h - 1) / 2.0;
            int[] target = new int[current.length];
            for (int ix = 0; ix < w; ix++) {
                for (int iz = 0; iz < h; iz++) {
                    double nx = cxc == 0 ? 0 : (ix - cxc) / cxc;
                    double nz = czc == 0 ? 0 : (iz - czc) / czc;
                    double d = Math.min(1.0, Math.sqrt(nx * nx + nz * nz));
                    double factor = switch (falloff) {
                        case "none" -> 1.0;
                        case "linear" -> 1.0 - d;
                        default -> {
                            double t = 1.0 - d;
                            yield t * t * (3 - 2 * t); // smoothstep
                        }
                    };
                    int i = ix * h + iz;
                    target[i] = current[i] + (int) Math.round(amount * factor);
                }
            }
            return target;
        }
    }

    /** {@code {"type":"terrace","from":[..],"to":[..],"step":3}} — quantises heights into steps. */
    public static final class TerraceOp extends HeightOp {
        private final int step;

        public TerraceOp(JsonObject o) {
            super(o);
            step = Math.max(1, J.i(o, "step", 3));
        }

        @Override
        public String type() {
            return "terrace";
        }

        @Override
        public long estimate() {
            return region.area() * step;
        }

        @Override
        int[] targetHeights(World world, int[] current) {
            int[] target = new int[current.length];
            for (int i = 0; i < current.length; i++) {
                target[i] = Math.floorDiv(current[i], step) * step;
            }
            return target;
        }
    }

    /**
     * {@code {"type":"flatten","from":[..],"to":[..],"y":72,"surface":"..","fill":"..","clearAbove":8}}
     *
     * <p>Per column: everything from {@code y+1} to {@code y+clearAbove} becomes air, {@code y}
     * becomes {@code surface}, and everything from {@code y-1} down to {@code from.y} that is not
     * already solid becomes {@code fill}. That gives a clean, buildable plateau in one op.
     */
    public static final class FlattenOp extends AbstractPlacementOp {
        private final Region region;
        private final int targetY;
        private final BlockRef surface;
        private final BlockRef fill;
        private final int clearAbove;

        public FlattenOp(BlockParser parser, JsonObject o) {
            region = Region.parse(o);
            targetY = J.reqInt(o, "y");
            surface = J.has(o, "surface") ? parser.parseRef(o.get("surface"), "surface")
                    : BlockRef.fixed(parser.parse("minecraft:grass_block"));
            fill = J.has(o, "fill") ? parser.parseRef(o.get("fill"), "fill")
                    : BlockRef.fixed(parser.parse("minecraft:dirt"));
            clearAbove = Math.max(0, J.i(o, "clearAbove", 4));
        }

        @Override
        public String type() {
            return "flatten";
        }

        @Override
        public long estimate() {
            int below = Math.max(0, targetY - region.y1);
            return region.area() * (1L + clearAbove + below);
        }

        @Override
        public void validate(BridgeConfig config) {
            requireColumns(region, "flatten");
            if (estimate() > config.maxBlocksPerOp) {
                throw ApiException.limit("op 'flatten' would touch " + estimate() + " blocks, over limits.maxBlocksPerOp",
                        "reduce 'clearAbove' or the area");
            }
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            BlockData air = ctx.parser().parse("minecraft:air");
            BlockFilter airOnly = BlockFilter.of(ctx.parser(),
                    List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water"));
            int bottom = region.y1;
            return new PlacementIterator() {
                private int x = region.x1;
                private int z = region.z1;
                private int phase;   // 0 = clear above, 1 = surface, 2 = fill below
                private int y = targetY + 1;

                @Override
                protected Placement computeNext() {
                    while (true) {
                        if (x > region.x2) return null;
                        switch (phase) {
                            case 0 -> {
                                if (clearAbove == 0 || y > targetY + clearAbove) {
                                    phase = 1;
                                    continue;
                                }
                                int cy = y++;
                                return scratch.set(x, cy, z, air).withFilter(null).withDestroy(false);
                            }
                            case 1 -> {
                                phase = 2;
                                y = targetY - 1;
                                return scratch.set(x, targetY, z, surface.pick(ctx.random()))
                                        .withFilter(null).withDestroy(false);
                            }
                            default -> {
                                if (y < bottom) {
                                    phase = 0;
                                    y = targetY + 1;
                                    if (++z > region.z2) {
                                        z = region.z1;
                                        x++;
                                    }
                                    continue;
                                }
                                int cy = y--;
                                return scratch.set(x, cy, z, fill.pick(ctx.random()))
                                        .withFilter(airOnly).withDestroy(false);
                            }
                        }
                    }
                }
            };
        }
    }

    private static void requireColumns(Region region, String opType) {
        if (region.area() > MAX_COLUMNS) {
            throw ApiException.limit("op '" + opType + "' covers " + region.area()
                            + " columns, over the terrain-op cap of " + MAX_COLUMNS,
                    "split the footprint into tiles");
        }
    }
}
