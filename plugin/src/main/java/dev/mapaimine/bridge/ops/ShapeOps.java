package dev.mapaimine.bridge.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.block.BlockRef;
import dev.mapaimine.bridge.json.J;

import java.util.Iterator;
import java.util.Locale;

/**
 * Geometric primitives — PROTOCOL.md §2 op group 4: sphere / cylinder / pyramid / cone / line /
 * walls / torus.
 *
 * <p>All of them are implemented as lazy scans over the shape's bounding box with an inclusion
 * test, so they never allocate a position list. Rejected positions do not consume tick budget;
 * only emitted placements do.
 */
public final class ShapeOps {

    private ShapeOps() {
    }

    // ── sphere / ellipsoid ───────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"sphere","center":[..],"radius":8|[rx,ry,rz],"block":"..","hollow":bool}}
     *
     * <p>Radii are inflated by 0.5 before the containment test so that a radius of 1 produces the
     * expected 3-wide ball rather than a single block.
     */
    public static final class SphereOp extends AbstractPlacementOp {
        private final int cx, cy, cz;
        private final double rx, ry, rz;
        private final boolean hollow;
        private final BlockRef ref;

        public SphereOp(BlockParser parser, JsonObject o) {
            int[] c = J.pos(o, "center");
            cx = c[0];
            cy = c[1];
            cz = c[2];
            double[] radii = radii(o, "radius");
            rx = radii[0];
            ry = radii[1];
            rz = radii[2];
            hollow = J.bool(o, "hollow", false);
            ref = parser.parseRefField(o, "block");
        }

        @Override
        public String type() {
            return "sphere";
        }

        @Override
        public long estimate() {
            double outer = 4.0 / 3.0 * Math.PI * (rx + 0.5) * (ry + 0.5) * (rz + 0.5);
            if (!hollow) return (long) Math.ceil(outer);
            double inner = 4.0 / 3.0 * Math.PI * Math.max(0, rx - 0.5) * Math.max(0, ry - 0.5) * Math.max(0, rz - 0.5);
            return (long) Math.ceil(Math.max(1, outer - inner));
        }

        @Override
        public void validate(BridgeConfig config) {
            new Region(cx - (int) rx, cy - (int) ry, cz - (int) rz,
                    cx + (int) rx, cy + (int) ry, cz + (int) rz)
                    .requireVolume(config.maxRegionVolume, "sphere");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            int irx = (int) Math.ceil(rx);
            int iry = (int) Math.ceil(ry);
            int irz = (int) Math.ceil(rz);
            double ox = rx + 0.5, oy = ry + 0.5, oz = rz + 0.5;
            double ix = Math.max(0.0001, rx - 0.5), iy = Math.max(0.0001, ry - 0.5), iz = Math.max(0.0001, rz - 0.5);
            Scan scan = new Scan(cx - irx, cy - iry, cz - irz, cx + irx, cy + iry, cz + irz);
            return new PlacementIterator() {
                @Override
                protected Placement computeNext() {
                    while (scan.advance()) {
                        int dx = scan.x - cx, dy = scan.y - cy, dz = scan.z - cz;
                        if (norm(dx, dy, dz, ox, oy, oz) > 1.0) continue;
                        if (hollow && norm(dx, dy, dz, ix, iy, iz) <= 1.0) continue;
                        return scratch.set(scan.x, scan.y, scan.z, ref.pick(ctx.random()));
                    }
                    return null;
                }
            };
        }
    }

    // ── cylinder ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"cylinder","base":[..],"radius":5,"height":12,"axis":"y","hollow":bool}}
     *
     * <p>{@code base} is the centre of the first slice; the cylinder extends {@code height} blocks
     * along the positive direction of {@code axis}. Hollow cylinders are open tubes (no caps).
     */
    public static final class CylinderOp extends AbstractPlacementOp {
        private final int bx, by, bz;
        private final double radius;
        private final int height;
        private final char axis;
        private final boolean hollow;
        private final BlockRef ref;

        public CylinderOp(BlockParser parser, JsonObject o) {
            int[] b = J.pos(o, "base");
            bx = b[0];
            by = b[1];
            bz = b[2];
            radius = J.dbl(o, "radius", 1);
            height = Math.max(1, J.i(o, "height", 1));
            axis = axis(J.str(o, "axis", "y"));
            hollow = J.bool(o, "hollow", false);
            ref = parser.parseRefField(o, "block");
            if (radius <= 0) throw ApiException.badRequest("'radius' must be > 0");
        }

        @Override
        public String type() {
            return "cylinder";
        }

        @Override
        public long estimate() {
            double r = radius + 0.5;
            double area = hollow ? 2 * Math.PI * r : Math.PI * r * r;
            return (long) Math.ceil(Math.max(1, area * height));
        }

        @Override
        public void validate(BridgeConfig config) {
            int ir = (int) Math.ceil(radius);
            Region r = switch (axis) {
                case 'x' -> new Region(bx, by - ir, bz - ir, bx + height - 1, by + ir, bz + ir);
                case 'z' -> new Region(bx - ir, by - ir, bz, bx + ir, by + ir, bz + height - 1);
                default -> new Region(bx - ir, by, bz - ir, bx + ir, by + height - 1, bz + ir);
            };
            r.requireVolume(config.maxRegionVolume, "cylinder");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            int ir = (int) Math.ceil(radius);
            double outer = radius + 0.5;
            double inner = Math.max(0.0001, radius - 0.5);
            Scan scan = switch (axis) {
                case 'x' -> new Scan(bx, by - ir, bz - ir, bx + height - 1, by + ir, bz + ir);
                case 'z' -> new Scan(bx - ir, by - ir, bz, bx + ir, by + ir, bz + height - 1);
                default -> new Scan(bx - ir, by, bz - ir, bx + ir, by + height - 1, bz + ir);
            };
            return new PlacementIterator() {
                @Override
                protected Placement computeNext() {
                    while (scan.advance()) {
                        double a;
                        double b;
                        switch (axis) {
                            case 'x' -> {
                                a = scan.y - by;
                                b = scan.z - bz;
                            }
                            case 'z' -> {
                                a = scan.x - bx;
                                b = scan.y - by;
                            }
                            default -> {
                                a = scan.x - bx;
                                b = scan.z - bz;
                            }
                        }
                        double d2 = a * a + b * b;
                        if (d2 > outer * outer) continue;
                        if (hollow && d2 <= inner * inner) continue;
                        return scratch.set(scan.x, scan.y, scan.z, ref.pick(ctx.random()));
                    }
                    return null;
                }
            };
        }
    }

    // ── pyramid ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"pyramid","base":[..],"size":9,"hollow":bool,"inverted":bool}}
     *
     * <p>{@code size} is both the height and the half-width of the base: the bottom layer is
     * {@code 2*size-1} blocks across and the apex is a single block. {@code inverted:true} flips
     * it so the point is at the bottom. Hollow pyramids keep only the perimeter of each layer,
     * i.e. the sloped faces, with an open interior.
     */
    public static final class PyramidOp extends AbstractPlacementOp {
        private final int bx, by, bz;
        private final int size;
        private final boolean hollow;
        private final boolean inverted;
        private final BlockRef ref;

        public PyramidOp(BlockParser parser, JsonObject o) {
            int[] b = J.pos(o, "base");
            bx = b[0];
            by = b[1];
            bz = b[2];
            size = Math.max(1, J.i(o, "size", 1));
            hollow = J.bool(o, "hollow", false);
            inverted = J.bool(o, "inverted", false);
            ref = parser.parseRefField(o, "block");
        }

        @Override
        public String type() {
            return "pyramid";
        }

        @Override
        public long estimate() {
            long total = 0;
            for (int i = 0; i < size; i++) {
                int w = inverted ? i : size - 1 - i;
                int side = 2 * w + 1;
                total += hollow ? Math.max(1, 4L * side - 4) : (long) side * side;
            }
            return total;
        }

        @Override
        public void validate(BridgeConfig config) {
            new Region(bx - size, by, bz - size, bx + size, by + size, bz + size)
                    .requireVolume(config.maxRegionVolume, "pyramid");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            return new PlacementIterator() {
                private int layer;
                private int dx = Integer.MIN_VALUE;
                private int dz;
                private int w;

                @Override
                protected Placement computeNext() {
                    while (true) {
                        if (layer >= size) return null;
                        if (dx == Integer.MIN_VALUE) {
                            w = inverted ? layer : size - 1 - layer;
                            dx = -w;
                            dz = -w;
                        }
                        if (dx > w) {
                            dx = Integer.MIN_VALUE;
                            layer++;
                            continue;
                        }
                        int px = dx, pz = dz;
                        if (++dz > w) {
                            dz = -w;
                            dx++;
                        }
                        if (hollow && Math.abs(px) != w && Math.abs(pz) != w) continue;
                        return scratch.set(bx + px, by + layer, bz + pz, ref.pick(ctx.random()));
                    }
                }
            };
        }
    }

    // ── cone ─────────────────────────────────────────────────────────────────────────────────

    /** {@code {"type":"cone","base":[..],"radius":6,"height":10,"hollow":bool}} — axis is +Y. */
    public static final class ConeOp extends AbstractPlacementOp {
        private final int bx, by, bz;
        private final double radius;
        private final int height;
        private final boolean hollow;
        private final boolean inverted;
        private final BlockRef ref;

        public ConeOp(BlockParser parser, JsonObject o) {
            int[] b = J.pos(o, "base");
            bx = b[0];
            by = b[1];
            bz = b[2];
            radius = J.dbl(o, "radius", 1);
            height = Math.max(1, J.i(o, "height", 1));
            hollow = J.bool(o, "hollow", false);
            inverted = J.bool(o, "inverted", false);
            ref = parser.parseRefField(o, "block");
            if (radius <= 0) throw ApiException.badRequest("'radius' must be > 0");
        }

        @Override
        public String type() {
            return "cone";
        }

        @Override
        public long estimate() {
            double total = 0;
            for (int i = 0; i < height; i++) {
                double r = layerRadius(i) + 0.5;
                total += hollow ? 2 * Math.PI * r : Math.PI * r * r;
            }
            return (long) Math.ceil(Math.max(1, total));
        }

        private double layerRadius(int i) {
            double t = (double) i / height;
            return inverted ? radius * t : radius * (1.0 - t);
        }

        @Override
        public void validate(BridgeConfig config) {
            int ir = (int) Math.ceil(radius);
            new Region(bx - ir, by, bz - ir, bx + ir, by + height - 1, bz + ir)
                    .requireVolume(config.maxRegionVolume, "cone");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            int ir = (int) Math.ceil(radius);
            return new PlacementIterator() {
                private int layer;
                private int x = Integer.MIN_VALUE;
                private int z;
                private double outer;
                private double inner;

                @Override
                protected Placement computeNext() {
                    while (true) {
                        if (layer >= height) return null;
                        if (x == Integer.MIN_VALUE) {
                            double r = layerRadius(layer);
                            outer = r + 0.5;
                            inner = Math.max(0.0001, r - 0.5);
                            x = -ir;
                            z = -ir;
                        }
                        if (x > ir) {
                            x = Integer.MIN_VALUE;
                            layer++;
                            continue;
                        }
                        int px = x, pz = z;
                        if (++z > ir) {
                            z = -ir;
                            x++;
                        }
                        double d2 = (double) px * px + (double) pz * pz;
                        if (d2 > outer * outer) continue;
                        if (hollow && d2 <= inner * inner) continue;
                        return scratch.set(bx + px, by + layer, bz + pz, ref.pick(ctx.random()));
                    }
                }
            };
        }
    }

    // ── line ─────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"line","from":[..],"to":[..],"thickness":2}}
     *
     * <p>The line is sampled at one step per block of its longest axis; each sample stamps a ball
     * of the requested thickness. Overlapping samples can re-visit a position — harmless (the
     * second write is a no-op) and much cheaper than deduplicating millions of coordinates.
     */
    public static final class LineOp extends AbstractPlacementOp {
        private final int x1, y1, z1, x2, y2, z2;
        private final int thickness;
        private final BlockRef ref;

        public LineOp(BlockParser parser, JsonObject o) {
            int[] a = J.pos(o, "from");
            int[] b = J.pos(o, "to");
            x1 = a[0];
            y1 = a[1];
            z1 = a[2];
            x2 = b[0];
            y2 = b[1];
            z2 = b[2];
            thickness = Math.max(1, J.i(o, "thickness", 1));
            ref = parser.parseRefField(o, "block");
        }

        @Override
        public String type() {
            return "line";
        }

        private int steps() {
            return Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1))) + 1;
        }

        @Override
        public long estimate() {
            int r = (thickness - 1) / 2;
            long per = (2L * r + 1) * (2L * r + 1) * (2L * r + 1);
            return (long) steps() * per;
        }

        @Override
        public void validate(BridgeConfig config) {
            if (estimate() > config.maxBlocksPerOp) {
                throw ApiException.limit("op 'line' would touch ~" + estimate() + " blocks, over limits.maxBlocksPerOp",
                        "reduce 'thickness' or split the line");
            }
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            int steps = steps();
            int r = (thickness - 1) / 2;
            double half = thickness / 2.0;
            return new PlacementIterator() {
                private int step;
                private int ox = Integer.MIN_VALUE, oy, oz;
                private int px, py, pz;

                @Override
                protected Placement computeNext() {
                    while (true) {
                        if (step >= steps) return null;
                        if (ox == Integer.MIN_VALUE) {
                            double t = steps == 1 ? 0 : (double) step / (steps - 1);
                            px = (int) Math.round(x1 + (x2 - x1) * t);
                            py = (int) Math.round(y1 + (y2 - y1) * t);
                            pz = (int) Math.round(z1 + (z2 - z1) * t);
                            ox = -r;
                            oy = -r;
                            oz = -r;
                        }
                        if (ox > r) {
                            ox = Integer.MIN_VALUE;
                            step++;
                            continue;
                        }
                        int ax = ox, ay = oy, az = oz;
                        if (++oz > r) {
                            oz = -r;
                            if (++oy > r) {
                                oy = -r;
                                ox++;
                            }
                        }
                        if (thickness > 1 && ax * ax + ay * ay + az * az > half * half) continue;
                        return scratch.set(px + ax, py + ay, pz + az, ref.pick(ctx.random()));
                    }
                }
            };
        }
    }

    // ── walls ────────────────────────────────────────────────────────────────────────────────

    /** {@code {"type":"walls","from":[..],"to":[..]}} — the four vertical sides, no floor/ceiling. */
    public static final class WallsOp extends AbstractPlacementOp {
        private final Region region;
        private final BlockRef ref;

        public WallsOp(BlockParser parser, JsonObject o) {
            region = Region.parse(o);
            ref = parser.parseRefField(o, "block");
        }

        @Override
        public String type() {
            return "walls";
        }

        @Override
        public long estimate() {
            int sx = region.sizeX();
            int sz = region.sizeZ();
            long perimeter = sx == 1 || sz == 1 ? (long) sx * sz : 2L * sx + 2L * sz - 4;
            return perimeter * region.sizeY();
        }

        @Override
        public void validate(BridgeConfig config) {
            region.requireVolume(config.maxRegionVolume, "walls");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            Scan scan = new Scan(region.x1, region.y1, region.z1, region.x2, region.y2, region.z2);
            return new PlacementIterator() {
                @Override
                protected Placement computeNext() {
                    while (scan.advance()) {
                        boolean edge = scan.x == region.x1 || scan.x == region.x2
                                || scan.z == region.z1 || scan.z == region.z2;
                        if (!edge) continue;
                        return scratch.set(scan.x, scan.y, scan.z, ref.pick(ctx.random()));
                    }
                    return null;
                }
            };
        }
    }

    // ── torus ────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"torus","center":[..],"radius":12,"tube":3}} — a donut lying flat in the XZ
     * plane: {@code radius} is the distance from the centre to the middle of the tube.
     */
    public static final class TorusOp extends AbstractPlacementOp {
        private final int cx, cy, cz;
        private final double major;
        private final double tube;
        private final boolean hollow;
        private final BlockRef ref;

        public TorusOp(BlockParser parser, JsonObject o) {
            int[] c = J.pos(o, "center");
            cx = c[0];
            cy = c[1];
            cz = c[2];
            major = J.dbl(o, "radius", 8);
            tube = J.dbl(o, "tube", 2);
            hollow = J.bool(o, "hollow", false);
            ref = parser.parseRefField(o, "block");
            if (major <= 0 || tube <= 0) throw ApiException.badRequest("'radius' and 'tube' must be > 0");
        }

        @Override
        public String type() {
            return "torus";
        }

        @Override
        public long estimate() {
            double outer = 2 * Math.PI * Math.PI * major * (tube + 0.5) * (tube + 0.5);
            if (!hollow) return (long) Math.ceil(outer);
            double inner = 2 * Math.PI * Math.PI * major * Math.max(0, tube - 0.5) * Math.max(0, tube - 0.5);
            return (long) Math.ceil(Math.max(1, outer - inner));
        }

        @Override
        public void validate(BridgeConfig config) {
            int rr = (int) Math.ceil(major + tube);
            int ry = (int) Math.ceil(tube);
            new Region(cx - rr, cy - ry, cz - rr, cx + rr, cy + ry, cz + rr)
                    .requireVolume(config.maxRegionVolume, "torus");
        }

        @Override
        protected Iterator<Placement> placements(ExecutionContext ctx) {
            int rr = (int) Math.ceil(major + tube) + 1;
            int ry = (int) Math.ceil(tube) + 1;
            double outer = tube + 0.5;
            double inner = Math.max(0.0001, tube - 0.5);
            Scan scan = new Scan(cx - rr, cy - ry, cz - rr, cx + rr, cy + ry, cz + rr);
            return new PlacementIterator() {
                @Override
                protected Placement computeNext() {
                    while (scan.advance()) {
                        double dx = scan.x - cx;
                        double dy = scan.y - cy;
                        double dz = scan.z - cz;
                        double planar = Math.sqrt(dx * dx + dz * dz) - major;
                        double d2 = planar * planar + dy * dy;
                        if (d2 > outer * outer) continue;
                        if (hollow && d2 <= inner * inner) continue;
                        return scratch.set(scan.x, scan.y, scan.z, ref.pick(ctx.random()));
                    }
                    return null;
                }
            };
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────

    private static double norm(int dx, int dy, int dz, double rx, double ry, double rz) {
        double a = dx / rx;
        double b = dy / ry;
        double c = dz / rz;
        return a * a + b * b + c * c;
    }

    /** Accepts either a scalar radius or {@code [rx, ry, rz]} (ellipsoid). */
    static double[] radii(JsonObject o, String key) {
        if (!J.has(o, key)) throw ApiException.badRequest("missing required field '" + key + "'");
        JsonElement el = o.get(key);
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.size() != 3) throw ApiException.badRequest("'" + key + "' must be a number or [rx, ry, rz]");
            double[] r = {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
            for (double v : r) {
                if (v <= 0) throw ApiException.badRequest("'" + key + "' components must be > 0");
            }
            return r;
        }
        double r = el.getAsDouble();
        if (r <= 0) throw ApiException.badRequest("'" + key + "' must be > 0");
        return new double[]{r, r, r};
    }

    static char axis(String raw) {
        String a = raw == null ? "y" : raw.trim().toLowerCase(Locale.ROOT);
        if (a.equals("x") || a.equals("y") || a.equals("z")) return a.charAt(0);
        throw ApiException.badRequest("'axis' must be one of x, y, z");
    }

    /** Bounding-box walker in {@code y → z → x} order. */
    static final class Scan {
        private final int x1, y1, z1, x2, y2, z2;
        int x, y, z;
        private boolean started;
        private boolean done;

        Scan(int x1, int y1, int z1, int x2, int y2, int z2) {
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
            this.x = this.x1;
            this.y = this.y1;
            this.z = this.z1;
        }

        boolean advance() {
            if (done) return false;
            if (!started) {
                started = true;
                return true;
            }
            if (++x > x2) {
                x = x1;
                if (++z > z2) {
                    z = z1;
                    if (++y > y2) {
                        done = true;
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
