package dev.mapaimine.bridge.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.json.J;
import dev.mapaimine.bridge.ops.TerrainOps;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code GET /survey} — the AI's eyes.
 *
 * <p>Returns a down-sampled top-down view of a rectangle: the terrain height, the surface block,
 * the biome, water coverage and optionally light, plus aggregate statistics that let a model
 * decide where and how to build without reading every block.
 *
 * <h2>Sampling</h2>
 * {@code step} is auto-selected as the smallest value that keeps {@code cols * rows} at or below
 * {@code limits.surveyMaxPoints}. Rows advance along Z, columns along X, and every value is taken
 * from {@code origin + index * step} — stated verbatim in the {@code legend} field so the client
 * never has to guess.
 *
 * <h2>Cost control</h2>
 * Sampling forces chunk generation, which is by far the most expensive thing this plugin can do.
 * The number of distinct chunks the request would touch is estimated up front and refused with
 * {@code LIMIT_EXCEEDED} above {@code limits.surveyMaxChunks}.
 *
 * <h2>Threading</h2>
 * {@link #survey} touches the world and must run on the main thread.
 */
public final class SurveyService {

    private final BridgeConfig config;

    public SurveyService(BridgeConfig config) {
        this.config = config;
    }

    public JsonObject survey(World world, int ax1, int az1, int ax2, int az2, Integer requestedStep,
                             Set<String> include) {
        int x1 = Math.min(ax1, ax2);
        int z1 = Math.min(az1, az2);
        int x2 = Math.max(ax1, ax2);
        int z2 = Math.max(az1, az2);
        int sizeX = x2 - x1 + 1;
        int sizeZ = z2 - z1 + 1;

        int step = requestedStep != null && requestedStep > 0 ? requestedStep : autoStep(sizeX, sizeZ);
        int cols = ceilDiv(sizeX, step);
        int rows = ceilDiv(sizeZ, step);
        long points = (long) cols * rows;
        if (points > config.surveyMaxPoints) {
            throw ApiException.limit("survey would sample " + points + " points, over limits.surveyMaxPoints ("
                            + config.surveyMaxPoints + ")",
                    "omit 'step' to let the plugin pick one, or shrink the region");
        }
        long chunks = Math.min((long) ceilDiv(sizeX, 16) * ceilDiv(sizeZ, 16), points);
        if (chunks > config.surveyMaxChunks) {
            throw ApiException.limit("survey would touch about " + chunks + " chunks, over limits.surveyMaxChunks ("
                            + config.surveyMaxChunks + ")",
                    "survey a smaller region; generating that many chunks would stall the server");
        }

        boolean wantHeight = include.contains("height");
        boolean wantSurface = include.contains("surface");
        boolean wantBiome = include.contains("biome");
        boolean wantWater = include.contains("water");
        boolean wantLight = include.contains("light");

        JsonArray heightmap = new JsonArray();
        JsonArray surface = new JsonArray();
        JsonArray biome = new JsonArray();
        JsonArray water = new JsonArray();
        JsonArray light = new JsonArray();

        int[] heights = new int[cols * rows];
        Map<String, Integer> surfaceHistogram = new LinkedHashMap<>();
        Map<String, Integer> biomeHistogram = new LinkedHashMap<>();
        int waterCount = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long sumY = 0;

        for (int row = 0; row < rows; row++) {
            int z = z1 + row * step;
            JsonArray hRow = new JsonArray();
            JsonArray sRow = new JsonArray();
            JsonArray bRow = new JsonArray();
            JsonArray wRow = new JsonArray();
            JsonArray lRow = new JsonArray();
            for (int col = 0; col < cols; col++) {
                int x = x1 + col * step;
                int y = TerrainOps.surfaceY(world, x, z);
                heights[row * cols + col] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                sumY += y;
                if (wantHeight) hRow.add(y);

                Block ground = world.getBlockAt(x, y, z);
                String surfaceName = shortName(ground.getType());
                surfaceHistogram.merge(surfaceName, 1, Integer::sum);
                if (wantSurface) sRow.add(surfaceName);

                Biome b = world.getBiome(x, y, z);
                String biomeName = shortKey(b);
                biomeHistogram.merge(biomeName, 1, Integer::sum);
                if (wantBiome) bRow.add(biomeName);

                boolean isWater = isWaterColumn(world, x, y, z);
                if (isWater) waterCount++;
                if (wantWater) wRow.add(isWater ? 1 : 0);

                if (wantLight) {
                    int ly = Math.min(world.getMaxHeight() - 1, y + 1);
                    lRow.add(world.getBlockAt(x, ly, z).getLightLevel());
                }
            }
            if (wantHeight) heightmap.add(hRow);
            if (wantSurface) surface.add(sRow);
            if (wantBiome) biome.add(bRow);
            if (wantWater) water.add(wRow);
            if (wantLight) light.add(lRow);
        }

        JsonObject data = new JsonObject();
        data.addProperty("world", world.getName());
        data.add("origin", J.intArray(x1, z1));
        data.add("size", J.intArray(sizeX, sizeZ));
        data.addProperty("step", step);
        data.addProperty("cols", cols);
        data.addProperty("rows", rows);
        if (wantHeight) data.add("heightmap", heightmap);
        if (wantSurface) data.add("surface", surface);
        if (wantBiome) data.add("biome", biome);
        if (wantWater) data.add("water", water);
        if (wantLight) data.add("light", light);

        JsonObject legend = new JsonObject();
        legend.addProperty("note", "rows идут по Z, колонки по X, значения — от origin с шагом step");
        legend.addProperty("noteEn", "rows advance along Z, columns along X; value[r][c] is at "
                + "origin + [c*step, r*step]");
        data.add("legend", legend);

        data.add("stats", stats(heights, cols, rows, minY, maxY, sumY, waterCount,
                surfaceHistogram, biomeHistogram));
        return data;
    }

    private JsonObject stats(int[] heights, int cols, int rows, int minY, int maxY, long sumY,
                             int waterCount, Map<String, Integer> surfaceHistogram,
                             Map<String, Integer> biomeHistogram) {
        int n = heights.length;
        JsonObject stats = new JsonObject();
        if (n == 0) return stats;
        double avg = (double) sumY / n;

        // Steepest step between orthogonally adjacent samples.
        int slopeMax = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int h = heights[r * cols + c];
                if (c + 1 < cols) slopeMax = Math.max(slopeMax, Math.abs(h - heights[r * cols + c + 1]));
                if (r + 1 < rows) slopeMax = Math.max(slopeMax, Math.abs(h - heights[(r + 1) * cols + c]));
            }
        }

        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        int median = sorted[n / 2];

        // Flatness: mean absolute deviation from the median, mapped so that a perfectly level
        // plot scores 1.0 and anything with an average 8-block deviation scores 0.
        double mad = 0;
        for (int h : heights) mad += Math.abs(h - median);
        mad /= n;
        double flatness = Math.max(0.0, Math.min(1.0, 1.0 - mad / 8.0));

        stats.addProperty("minY", minY);
        stats.addProperty("maxY", maxY);
        stats.addProperty("avgY", J.round1(avg));
        stats.addProperty("slopeMax", slopeMax);
        stats.addProperty("waterFraction", J.round2((double) waterCount / n));
        stats.add("surfaceHistogram", J.histogram(sortDescending(surfaceHistogram)));
        stats.add("biomeHistogram", J.histogram(sortDescending(biomeHistogram)));
        stats.addProperty("flatnessScore", J.round2(flatness));
        // Build one above the median terrain height: high enough to clear most of the plot,
        // low enough not to float.
        stats.addProperty("suggestedBuildY", median);
        return stats;
    }

    private static Map<String, Integer> sortDescending(Map<String, Integer> in) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(in.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Picks the smallest step that keeps the sample count within {@code surveyMaxPoints}. */
    private int autoStep(int sizeX, int sizeZ) {
        int step = 1;
        while ((long) ceilDiv(sizeX, step) * ceilDiv(sizeZ, step) > config.surveyMaxPoints) {
            step++;
        }
        return step;
    }

    private static boolean isWaterColumn(World world, int x, int groundY, int z) {
        int top = Math.min(world.getMaxHeight() - 1, groundY + 1);
        Material m = world.getBlockAt(x, top, z).getType();
        if (m == Material.WATER || m == Material.ICE || m == Material.FROSTED_ICE) return true;
        return world.getBlockAt(x, groundY, z).getType() == Material.WATER;
    }

    static String shortName(Material material) {
        return material.getKey().getKey();
    }

    static String shortKey(Biome biome) {
        if (biome == null) return "unknown";
        try {
            return biome.getKey().getKey();
        } catch (RuntimeException ex) {
            return biome.toString().toLowerCase(Locale.ROOT);
        }
    }

    static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Parses the {@code include} CSV, defaulting to {@code height,surface,biome}. */
    public static Set<String> parseInclude(String raw) {
        if (raw == null || raw.isBlank()) return Set.of("height", "surface", "biome");
        Map<String, Boolean> seen = new TreeMap<>();
        for (String part : raw.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if (!Set.of("height", "surface", "biome", "water", "light").contains(p)) {
                throw ApiException.badRequest("unknown 'include' value '" + p + "'",
                        "supported: height, surface, biome, water, light");
            }
            seen.put(p, true);
        }
        return seen.isEmpty() ? Set.of("height", "surface", "biome") : seen.keySet();
    }
}
