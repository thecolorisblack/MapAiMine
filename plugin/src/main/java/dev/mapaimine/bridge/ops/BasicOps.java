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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.PrimitiveIterator;

/** Ops 1, 2, 2b, 3, 5 and 8-{@code clear} of PROTOCOL.md §2: set / blocks / fill / replace / clear. */
public final class BasicOps {

    private BasicOps() {
    }

    // ── set ──────────────────────────────────────────────────────────────────────────────────

    /** {@code {"type":"set","pos":[x,y,z],"block":"..."}} */
    public static final class SetOp extends AbstractPlacementOp {
        private final int x, y, z;
        private final BlockRef ref;

        public SetOp(BlockParser parser, JsonObject o) {
            int[] p = J.pos(o, "pos");
            this.x = p[0];
            this.y = p[1];
            this.z = p[2];
            this.ref = parser.parseRefField(o, "block");
        }

        @Override
        public String type() {
            return "set";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            return new PlacementIterator() {
                private boolean done;

                @Override
                protected Placement computeNext() {
                    if (done) return null;
                    done = true;
                    return scratch.set(x, y, z, ref.pick(ctx.random()));
                }
            };
        }
    }

    // ── blocks ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"blocks", ...}} in both shapes:
     * <ul>
     *   <li>explicit — {@code {"blocks":[{"pos":[..],"block":".."}, ...]}}, order preserved
     *       (doors and beds rely on the lower half being placed first);</li>
     *   <li>compact — {@code {"origin":[..],"palette":[..],"size":[sx,sy,sz],"data":"RLE"}} in
     *       {@code y → z → x} order.</li>
     * </ul>
     */
    public static final class BlocksOp extends AbstractPlacementOp {

        private final int[] xs;
        private final int[] ys;
        private final int[] zs;
        private final BlockData[] datas;

        // compact form
        private final int ox, oy, oz, sx, sy, sz;
        private final BlockData[] palette;
        private final String data;
        private final long compactCount;

        public BlocksOp(BlockParser parser, JsonObject o) {
            if (J.has(o, "blocks")) {
                JsonArray arr = J.reqArr(o, "blocks");
                int n = arr.size();
                xs = new int[n];
                ys = new int[n];
                zs = new int[n];
                datas = new BlockData[n];
                for (int i = 0; i < n; i++) {
                    JsonObject e = J.asObject(arr.get(i), "blocks[" + i + "]");
                    int[] p = J.pos(e, "pos");
                    xs[i] = p[0];
                    ys[i] = p[1];
                    zs[i] = p[2];
                    datas[i] = parser.parseRef(e.get("block"), "blocks[" + i + "].block").any();
                }
                ox = oy = oz = sx = sy = sz = 0;
                palette = null;
                data = null;
                compactCount = 0;
            } else if (J.has(o, "data")) {
                int[] origin = J.pos(o, "origin");
                ox = origin[0];
                oy = origin[1];
                oz = origin[2];
                JsonArray size = J.reqArr(o, "size");
                if (size.size() != 3) throw ApiException.badRequest("'size' must be [sx, sy, sz]");
                sx = size.get(0).getAsInt();
                sy = size.get(1).getAsInt();
                sz = size.get(2).getAsInt();
                if (sx <= 0 || sy <= 0 || sz <= 0) {
                    throw ApiException.badRequest("'size' components must be positive");
                }
                JsonArray pal = J.reqArr(o, "palette");
                palette = new BlockData[pal.size()];
                for (int i = 0; i < pal.size(); i++) {
                    palette[i] = parser.parse(pal.get(i).getAsString());
                }
                if (palette.length == 0) throw ApiException.badRequest("'palette' must not be empty");
                data = J.reqStr(o, "data");
                compactCount = (long) sx * sy * sz;
                xs = null;
                ys = null;
                zs = null;
                datas = null;
            } else {
                throw ApiException.badRequest(
                        "op 'blocks' needs either 'blocks' (explicit list) or 'origin'+'palette'+'size'+'data'");
            }
        }

        @Override
        public String type() {
            return "blocks";
        }

        @Override
        public long estimate() {
            return datas != null ? datas.length : compactCount;
        }

        @Override
        public void validate(BridgeConfig config) {
            if (estimate() > config.maxBlocksPerOp) {
                throw ApiException.limit("op 'blocks' has " + estimate() + " entries, over limits.maxBlocksPerOp",
                        "split the blueprint into several ops");
            }
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            if (datas != null) {
                return new PlacementIterator() {
                    private int i;

                    @Override
                    protected Placement computeNext() {
                        if (i >= datas.length) return null;
                        int k = i++;
                        return scratch.set(xs[k], ys[k], zs[k], datas[k]);
                    }
                };
            }
            return new PlacementIterator() {
                private final PrimitiveIterator.OfInt it = RleData.decode(data);
                private long index;

                @Override
                protected Placement computeNext() {
                    if (!it.hasNext() || index >= compactCount) return null;
                    int paletteIdx = it.nextInt();
                    long i = index++;
                    // y -> z -> x ordering, per PROTOCOL.md §2 op 2b.
                    int y = (int) (i / ((long) sx * sz));
                    long rem = i % ((long) sx * sz);
                    int z = (int) (rem / sx);
                    int x = (int) (rem % sx);
                    if (paletteIdx < 0 || paletteIdx >= palette.length) {
                        throw ApiException.badRequest("palette index " + paletteIdx + " out of range in 'data'");
                    }
                    return scratch.set(ox + x, oy + y, oz + z, palette[paletteIdx]);
                }
            };
        }
    }

    // ── fill / replace ───────────────────────────────────────────────────────────────────────

    /** Fill modes of PROTOCOL.md §2 op 3. */
    public enum FillMode {
        /** Every block in the region. */
        REPLACE,
        /** Only blocks that are currently replaceable (air, grass, water…). */
        KEEP,
        /** Only the six outer faces of the box; the interior is untouched. */
        HOLLOW,
        /** Only the twelve edges of the box (a wireframe). */
        OUTLINE,
        /** Like {@link #REPLACE}, but the previous block is broken naturally and drops items. */
        DESTROY;

        public static FillMode parse(String raw) {
            if (raw == null) return REPLACE;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("unknown fill mode '" + raw + "'",
                        "supported: replace, keep, hollow, outline, destroy");
            }
        }
    }

    /** {@code {"type":"fill","from":[..],"to":[..],"block":"..","mode":"..","filter":[..]}} */
    public static final class FillOp extends AbstractPlacementOp {
        private final Region region;
        private final BlockRef ref;
        private final FillMode mode;
        private final BlockFilter filter;

        public FillOp(BlockParser parser, JsonObject o) {
            this.region = Region.parse(o);
            this.ref = parser.parseRefField(o, "block");
            this.mode = FillMode.parse(J.str(o, "mode", "replace"));
            this.filter = BlockFilter.parse(parser, o, "filter");
        }

        /** Used by {@code replace}, which is a fill whose filter is the {@code find} list. */
        FillOp(Region region, BlockRef ref, BlockFilter filter) {
            this.region = region;
            this.ref = ref;
            this.mode = FillMode.REPLACE;
            this.filter = filter;
        }

        @Override
        public String type() {
            return "fill";
        }

        @Override
        public long estimate() {
            return switch (mode) {
                case HOLLOW -> shellCount(region);
                case OUTLINE -> edgeCount(region);
                default -> region.volume();
            };
        }

        @Override
        public void validate(BridgeConfig config) {
            region.requireVolume(config.maxRegionVolume, "fill");
            if (estimate() > config.maxBlocksPerOp) {
                throw ApiException.limit("op 'fill' would touch " + estimate() + " blocks, over limits.maxBlocksPerOp",
                        "split the region");
            }
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            BlockFilter effective = filter;
            if (mode == FillMode.KEEP && effective == null) {
                effective = replaceableFilter(ctx);
            }
            final BlockFilter f = effective;
            final boolean destroy = mode == FillMode.DESTROY;
            RegionCursor cursor = new RegionCursor(region, mode);
            return new PlacementIterator() {
                @Override
                protected Placement computeNext() {
                    if (!cursor.advance()) return null;
                    return scratch.set(cursor.x, cursor.y, cursor.z, ref.pick(ctx.random()))
                            .withFilter(f)
                            .withDestroy(destroy);
                }
            };
        }
    }

    /** {@code {"type":"replace","from":[..],"to":[..],"find":[..],"block":".."}} */
    public static final class ReplaceOp extends AbstractPlacementOp {
        private final FillOp delegate;
        private final Region region;

        public ReplaceOp(BlockParser parser, JsonObject o) {
            this.region = Region.parse(o);
            BlockFilter find = BlockFilter.parse(parser, o, "find");
            if (find == null) {
                throw ApiException.badRequest("op 'replace' requires a non-empty 'find' list");
            }
            this.delegate = new FillOp(region, parser.parseRefField(o, "block"), find);
        }

        @Override
        public String type() {
            return "replace";
        }

        @Override
        public long estimate() {
            return region.volume();
        }

        @Override
        public void validate(BridgeConfig config) {
            delegate.validate(config);
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            return delegate.placements(ctx);
        }
    }

    // ── clear ────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"clear","from":[..],"to":[..],"keepGround":true}}
     *
     * <p>{@code keepGround:false} turns the whole region into air. {@code keepGround:true} only
     * clears what sits <em>above</em> the terrain surface of each column (buildings, trees,
     * vegetation), leaving the ground itself intact — the usual "prepare a plot" operation.
     */
    public static final class ClearOp extends AbstractPlacementOp {
        private final Region region;
        private final boolean keepGround;

        public ClearOp(JsonObject o) {
            this.region = Region.parse(o);
            this.keepGround = J.bool(o, "keepGround", false);
        }

        @Override
        public String type() {
            return "clear";
        }

        @Override
        public long estimate() {
            return region.volume();
        }

        @Override
        public void validate(BridgeConfig config) {
            region.requireVolume(config.maxRegionVolume, "clear");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            BlockData air = ctx.parser().parse("minecraft:air");
            if (!keepGround) {
                RegionCursor cursor = new RegionCursor(region, FillMode.REPLACE);
                return new PlacementIterator() {
                    @Override
                    protected Placement computeNext() {
                        if (!cursor.advance()) return null;
                        return scratch.set(cursor.x, cursor.y, cursor.z, air).withFilter(null).withDestroy(false);
                    }
                };
            }
            World world = ctx.world();
            return new PlacementIterator() {
                private int x = region.x1;
                private int z = region.z1;
                private int y = Integer.MIN_VALUE;
                private int top;

                @Override
                protected Placement computeNext() {
                    while (true) {
                        if (y == Integer.MIN_VALUE) {
                            if (x > region.x2) return null;
                            int surface = TerrainOps.surfaceY(world, x, z);
                            y = Math.max(region.y1, surface + 1);
                            top = region.y2;
                            if (y > top) {
                                y = Integer.MIN_VALUE;
                                if (++z > region.z2) {
                                    z = region.z1;
                                    x++;
                                }
                                continue;
                            }
                        }
                        if (y > top) {
                            y = Integer.MIN_VALUE;
                            if (++z > region.z2) {
                                z = region.z1;
                                x++;
                            }
                            continue;
                        }
                        int cy = y++;
                        return scratch.set(x, cy, z, air).withFilter(null).withDestroy(false);
                    }
                }
            };
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Materials treated as "free to build into" for {@code mode:"keep"}. */
    static BlockFilter replaceableFilter(ExecutionContext ctx) {
        List<String> names = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isBlock() || m.isLegacy()) continue;
            if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
                    || m == Material.WATER || m == Material.LAVA
                    || m == Material.SHORT_GRASS || m == Material.TALL_GRASS
                    || m == Material.FERN || m == Material.LARGE_FERN
                    || m == Material.SNOW || m == Material.VINE || m == Material.DEAD_BUSH) {
                names.add(m.getKey().toString());
            }
        }
        return BlockFilter.of(ctx.parser(), names);
    }

    static long shellCount(Region r) {
        long total = r.volume();
        long inner = (long) Math.max(0, r.sizeX() - 2) * Math.max(0, r.sizeY() - 2) * Math.max(0, r.sizeZ() - 2);
        return total - inner;
    }

    static long edgeCount(Region r) {
        int sx = r.sizeX();
        int sy = r.sizeY();
        int sz = r.sizeZ();
        if (sx == 1 && sy == 1 && sz == 1) return 1;
        // 4 edges along each axis minus the 8 shared corners counted three times.
        long along = 4L * sx + 4L * sy + 4L * sz;
        return Math.max(1, along - 16);
    }

    /**
     * Walks the positions of a region for a given fill mode without materialising them.
     *
     * <p>{@code HOLLOW} and {@code OUTLINE} skip the interior <em>structurally</em> rather than by
     * testing every position, so a hollow 500³ box costs the shell (~1.5M) and not the volume
     * (125M) in tick budget.
     */
    static final class RegionCursor {
        private final Region r;
        private final FillMode mode;
        int x, y, z;
        private boolean started;
        private boolean done;

        RegionCursor(Region r, FillMode mode) {
            this.r = r;
            this.mode = mode;
            this.x = r.x1;
            this.y = r.y1;
            this.z = r.z1;
        }

        /** Advances to the next valid position; returns false when the region is exhausted. */
        boolean advance() {
            if (done) return false;
            if (!started) {
                started = true;
                if (accepts(x, y, z)) return true;
            }
            while (true) {
                if (++x > r.x2) {
                    x = r.x1;
                    if (++z > r.z2) {
                        z = r.z1;
                        if (++y > r.y2) {
                            done = true;
                            return false;
                        }
                    }
                }
                if (accepts(x, y, z)) return true;
            }
        }

        private boolean accepts(int px, int py, int pz) {
            return switch (mode) {
                case HOLLOW -> px == r.x1 || px == r.x2 || py == r.y1 || py == r.y2 || pz == r.z1 || pz == r.z2;
                case OUTLINE -> {
                    int onFace = 0;
                    if (px == r.x1 || px == r.x2) onFace++;
                    if (py == r.y1 || py == r.y2) onFace++;
                    if (pz == r.z1 || pz == r.z2) onFace++;
                    yield onFace >= 2;
                }
                default -> true;
            };
        }
    }

    /** Convenience used by several ops: is this block air-like? */
    static boolean isAir(Block block) {
        Material m = block.getType();
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }
}
