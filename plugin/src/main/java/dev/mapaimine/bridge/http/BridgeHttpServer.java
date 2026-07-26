package dev.mapaimine.bridge.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.MapAiMinePlugin;
import dev.mapaimine.bridge.SelectionManager;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.api.ErrorCode;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.job.Job;
import dev.mapaimine.bridge.job.JobRunner;
import dev.mapaimine.bridge.json.J;
import dev.mapaimine.bridge.ops.Op;
import dev.mapaimine.bridge.ops.OpParser;
import dev.mapaimine.bridge.ops.Region;
import dev.mapaimine.bridge.service.CaptureService;
import dev.mapaimine.bridge.service.PlayerService;
import dev.mapaimine.bridge.service.SurveyService;
import dev.mapaimine.bridge.service.WorldService;
import dev.mapaimine.bridge.undo.UndoManager;
import dev.mapaimine.bridge.undo.UndoSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HTTP face of the bridge: JDK {@link HttpServer}, a tiny router, bearer-token auth and the
 * uniform {@code {ok, data}} / {@code {ok, error}} envelope of PROTOCOL.md §1.
 *
 * <h2>Threading rules — the single most important thing in this class</h2>
 * Handlers run on a small worker pool, <b>never</b> on the server's main thread. Anything that
 * touches the Bukkit world must therefore be marshalled:
 * <ul>
 *   <li><b>reads</b> ({@code /survey}, {@code /probe}, {@code /worlds}, …) go through
 *       {@link #sync}, which uses {@code Bukkit.getScheduler().callSyncMethod(...)} and waits with
 *       a timeout, so a stalled server produces a clean 500 instead of a hung socket;</li>
 *   <li><b>writes</b> ({@code /ops}) never run inline at all — they are turned into a
 *       {@link Job} and executed by the tick-budgeted {@code JobRunner}.</li>
 * </ul>
 * Parsing, validation and block-id lookups deliberately avoid the world so they can happen on the
 * worker thread and reject bad requests before the main thread is disturbed.
 */
public final class BridgeHttpServer {

    private static final String BASE = "/api/v1";
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private final MapAiMinePlugin plugin;
    private final BridgeConfig config;
    private final BlockParser parser;
    private final JobRunner jobRunner;
    private final UndoManager undoManager;
    private final SurveyService surveyService;
    private final WorldService worldService;
    private final CaptureService captureService;
    private final PlayerService playerService;
    private final SelectionManager selections;

    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;
    private ExecutorService executor;

    public BridgeHttpServer(MapAiMinePlugin plugin, BridgeConfig config, BlockParser parser,
                            JobRunner jobRunner, UndoManager undoManager, SurveyService surveyService,
                            WorldService worldService, CaptureService captureService,
                            PlayerService playerService, SelectionManager selections) {
        this.plugin = plugin;
        this.config = config;
        this.parser = parser;
        this.jobRunner = jobRunner;
        this.undoManager = undoManager;
        this.surveyService = surveyService;
        this.worldService = worldService;
        this.captureService = captureService;
        this.playerService = playerService;
        this.selections = selections;
        registerRoutes();
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.host, config.port), 32);
        AtomicInteger counter = new AtomicInteger();
        executor = new ThreadPoolExecutor(1, config.httpThreads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256), r -> {
            Thread t = new Thread(r, "MapAiMine-http-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    public String baseUrl() {
        return "http://" + config.host + ":" + config.port + BASE;
    }

    // ── routing ──────────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Handler {
        ApiResponse handle(Req req) throws Exception;
    }

    private record Route(String method, String[] segments, Handler handler) {
    }

    private void route(String method, String pattern, Handler handler) {
        routes.add(new Route(method, split(BASE + pattern), handler));
    }

    private static String[] split(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out.toArray(new String[0]);
    }

    private void registerRoutes() {
        route("GET", "/health", this::health);

        route("GET", "/worlds", r -> ApiResponse.ok(wrap("worlds", sync(worldService::listWorlds))));
        route("POST", "/worlds", this::createWorld);
        route("POST", "/worlds/{name}/settings", this::worldSettings);
        route("POST", "/worlds/{name}/spawn", this::worldSpawn);
        route("POST", "/worlds/{name}/border", this::worldBorder);

        route("GET", "/survey", this::survey);
        route("POST", "/probe", this::probe);
        route("GET", "/materials", this::materials);
        route("POST", "/validate", this::validate);

        route("POST", "/ops", this::ops);

        route("GET", "/jobs", r -> ApiResponse.ok(wrap("jobs", jobRunner.listJson())));
        route("GET", "/jobs/{id}", r -> ApiResponse.ok(jobRunner.get(r.pathParam("id")).toJson()));
        route("DELETE", "/jobs/{id}", this::cancelJob);

        route("GET", "/undo", r -> ApiResponse.ok(wrap("history", undoManager.historyJson())));
        route("POST", "/undo", this::undo);

        route("POST", "/capture", this::capture);
        route("GET", "/captures", r -> ApiResponse.ok(wrap("captures", captureService.list())));
        route("GET", "/captures/{name}", r -> ApiResponse.ok(captureService.read(r.pathParam("name"))));

        route("GET", "/players", r -> ApiResponse.ok(wrap("players", sync(playerService::list))));
        route("POST", "/players/{name}/teleport",
                r -> ApiResponse.ok(sync(() -> playerService.teleport(r.pathParam("name"), r.body()))));
        route("POST", "/players/{name}/gamemode",
                r -> ApiResponse.ok(sync(() -> playerService.gamemode(r.pathParam("name"), r.body()))));
        route("POST", "/broadcast", r -> ApiResponse.ok(sync(() -> playerService.broadcast(r.body()))));

        // Region selection made in-game with /mapaimine pos1 | pos2. Lets the model act on
        // "the area I marked" without the player reading coordinates out loud.
        route("GET", "/selection", r -> ApiResponse.ok(selections.toJson(r.query("player", ""))));
    }

    // ── dispatch ─────────────────────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getRawPath();
        try {
            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            String[] segments = split(path);
            Map<String, String> pathParams = new HashMap<>();
            Route matched = null;
            boolean pathExists = false;
            for (Route route : routes) {
                if (matches(route, segments, pathParams)) {
                    pathExists = true;
                    if (route.method().equals(method)) {
                        matched = route;
                        break;
                    }
                }
                pathParams.clear();
            }

            if (matched == null) {
                if (pathExists) {
                    respondError(exchange, 405, ErrorCode.BAD_REQUEST,
                            "method " + method + " is not allowed on " + path, null);
                } else {
                    respondError(exchange, 404, ErrorCode.BAD_REQUEST, "no such endpoint: " + path,
                            "see docs/PROTOCOL.md for the endpoint list");
                }
                return;
            }

            boolean authenticated = isAuthenticated(exchange);
            boolean healthEndpoint = segments.length == 3 && segments[2].equals("health");
            if (!authenticated && !healthEndpoint) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("WWW-Authenticate", "Bearer realm=\"MapAiMine\"");
                respondError(exchange, 401, ErrorCode.UNAUTHORIZED, "missing or invalid token",
                        "send 'Authorization: Bearer <token>' or 'X-MapAiMine-Token: <token>'; "
                                + "the token is in plugins/MapAiMine/config.yml");
                return;
            }

            String body = readBody(exchange);
            Req req = new Req(method, path, pathParams, parseQuery(exchange.getRequestURI().getRawQuery()),
                    body, authenticated);
            ApiResponse response = matched.handler().handle(req);
            respondOk(exchange, response);

        } catch (ApiException ex) {
            respondError(exchange, ex.code().status(), ex.code(), ex.getMessage(), ex.hint());
        } catch (BodyTooLargeException ex) {
            respondError(exchange, 413, ErrorCode.LIMIT_EXCEEDED, ex.getMessage(),
                    "raise 'maxRequestBytes' in config.yml or split the request");
        } catch (Exception ex) {
            plugin.getLogger().warning("unhandled error on " + method + " " + path + ": " + ex);
            respondError(exchange, 500, ErrorCode.INTERNAL,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
        } finally {
            exchange.close();
        }
    }

    private static boolean matches(Route route, String[] segments, Map<String, String> params) {
        if (route.segments().length != segments.length) return false;
        for (int i = 0; i < segments.length; i++) {
            String expected = route.segments()[i];
            if (expected.startsWith("{") && expected.endsWith("}")) {
                params.put(expected.substring(1, expected.length() - 1), decode(segments[i]));
            } else if (!expected.equals(segments[i])) {
                return false;
            }
        }
        return true;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private boolean isAuthenticated(HttpExchange exchange) {
        String expected = config.token;
        if (expected == null || expected.isEmpty()) return false;
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (constantTimeEquals(expected, header.substring(7).trim())) return true;
        }
        String alt = exchange.getRequestHeaders().getFirst("X-MapAiMine-Token");
        return alt != null && constantTimeEquals(expected, alt.trim());
    }

    /** Comparison that does not leak the token length or prefix through timing. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length && i < y.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > config.maxRequestBytes) {
                    throw new BodyTooLargeException("request body exceeds maxRequestBytes ("
                            + config.maxRequestBytes + ")");
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void respondOk(HttpExchange exchange, ApiResponse response) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.add("data", response.data == null ? new JsonObject() : response.data);
        write(exchange, response.status, envelope);
    }

    private void respondError(HttpExchange exchange, int status, ErrorCode code, String message, String hint) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code.name());
        error.addProperty("message", message == null ? code.name() : message);
        if (hint != null) error.addProperty("hint", hint);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", false);
        envelope.add("error", error);
        write(exchange, status, envelope);
    }

    private void write(HttpExchange exchange, int status, JsonObject envelope) {
        byte[] payload = GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (IOException ex) {
            // Client hung up mid-response: nothing useful to do, and it must not become a 500 loop.
            plugin.getLogger().fine("could not write response: " + ex.getMessage());
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {
        BodyTooLargeException(String message) {
            super(message);
        }
    }

    // ── main-thread marshalling ──────────────────────────────────────────────────────────────

    private <T> T sync(Callable<T> callable) {
        return sync(callable, config.readTimeoutMs);
    }

    /**
     * Runs {@code callable} on the server main thread and waits for the result.
     *
     * <p>A timeout produces {@code INTERNAL} rather than blocking the worker forever — if the
     * server is lagging that hard, the caller should back off instead of piling up requests.
     */
    private <T> T sync(Callable<T> callable, int timeoutMs) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiException(ErrorCode.INTERNAL, ex.getMessage());
            }
        }
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, callable);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new ApiException(ErrorCode.INTERNAL,
                    "the server did not process the request within " + timeoutMs + " ms",
                    "the main thread is busy; retry later or raise limits.readTimeoutMs");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL, "interrupted while waiting for the main thread");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ApiException api) throw api;
            throw new ApiException(ErrorCode.INTERNAL,
                    cause == null ? ex.toString() : cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static JsonObject wrap(String key, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    // ── handlers ─────────────────────────────────────────────────────────────────────────────

    /** {@code GET /health} — reduced payload without a token, full payload with one. */
    private ApiResponse health(Req req) {
        JsonObject data = new JsonObject();
        data.addProperty("protocol", 1);
        data.addProperty("plugin", "MapAiMine " + plugin.version());
        if (!req.authenticated()) return ApiResponse.ok(data);

        data.addProperty("server", Bukkit.getName());
        data.addProperty("minecraftVersion", minecraftVersion());
        JsonArray worlds = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            if (config.worldAllowed(world.getName())) worlds.add(world.getName());
        }
        data.add("worlds", worlds);
        data.add("capabilities", J.toArray(config.capabilityList()));

        JsonObject limits = new JsonObject();
        limits.addProperty("maxOpsPerRequest", config.maxOpsPerRequest);
        limits.addProperty("maxBlocksPerOp", config.maxBlocksPerOp);
        limits.addProperty("maxRegionVolume", config.maxRegionVolume);
        limits.addProperty("blocksPerTick", config.blocksPerTick);
        limits.addProperty("maxUndoHistory", config.maxUndoHistory);
        limits.addProperty("surveyMaxPoints", config.surveyMaxPoints);
        limits.addProperty("maxUndoBlocks", config.maxUndoBlocks);
        limits.addProperty("maxProbePoints", config.maxProbePoints);
        limits.addProperty("maxSyncBlocks", config.maxSyncBlocks);
        limits.addProperty("maxCaptureVolume", config.maxCaptureVolume);
        limits.addProperty("surveyMaxChunks", config.surveyMaxChunks);
        limits.addProperty("maxRequestBytes", config.maxRequestBytes);
        data.add("limits", limits);

        JsonObject jobs = new JsonObject();
        jobs.addProperty("active", jobRunner.activeCount());
        jobs.addProperty("undoHistory", undoManager.historySize());
        data.add("status", jobs);
        data.add("opTypes", J.toArray(OpParser.SUPPORTED));
        return ApiResponse.ok(data);
    }

    private ApiResponse createWorld(Req req) {
        JsonObject body = req.body();
        // World generation can take a long time; PROTOCOL.md §2 allows up to 60 seconds.
        World world = sync(() -> worldService.createWorld(body), 60_000);
        return ApiResponse.ok(sync(() -> worldService.worldJson(world)));
    }

    private ApiResponse worldSettings(Req req) {
        String name = req.pathParam("name");
        JsonObject body = req.body();
        return ApiResponse.ok(sync(() -> {
            World world = requireWorld(name);
            JsonObject applied = worldService.applySettings(world, body);
            JsonObject out = worldService.worldJson(world);
            out.add("applied", applied);
            return out;
        }));
    }

    private ApiResponse worldSpawn(Req req) {
        String name = req.pathParam("name");
        JsonObject body = req.body();
        return ApiResponse.ok(sync(() -> worldService.setSpawn(requireWorld(name), body)));
    }

    private ApiResponse worldBorder(Req req) {
        String name = req.pathParam("name");
        JsonObject body = req.body();
        return ApiResponse.ok(sync(() -> worldService.setBorder(requireWorld(name), body)));
    }

    private ApiResponse survey(Req req) {
        if (!config.has("survey")) throw ApiException.disabled("survey");
        String worldName = req.query("world", null);
        if (worldName == null) throw ApiException.badRequest("missing required query parameter 'world'");
        int x1 = req.queryInt("x1");
        int z1 = req.queryInt("z1");
        int x2 = req.queryInt("x2");
        int z2 = req.queryInt("z2");
        Integer step = req.queryIntOrNull("step");
        Set<String> include = SurveyService.parseInclude(req.query("include", null));
        // A survey can generate chunks, so give it the longer world-generation timeout.
        return ApiResponse.ok(sync(() -> surveyService.survey(requireWorld(worldName), x1, z1, x2, z2,
                step, include), Math.max(config.readTimeoutMs, 30_000)));
    }

    private ApiResponse probe(Req req) {
        JsonObject body = req.body();
        String worldName = J.reqStr(body, "world");
        JsonArray points = J.reqArr(body, "points");
        if (points.size() > config.maxProbePoints) {
            throw ApiException.limit("probe accepts at most " + config.maxProbePoints + " points",
                    "split the request");
        }
        List<int[]> parsed = new ArrayList<>(points.size());
        for (JsonElement el : points) {
            if (!el.isJsonArray() || el.getAsJsonArray().size() != 3) {
                throw ApiException.badRequest("each entry of 'points' must be [x, y, z]");
            }
            JsonArray a = el.getAsJsonArray();
            parsed.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        return ApiResponse.ok(sync(() -> {
            World world = requireWorld(worldName);
            JsonArray blocks = new JsonArray();
            for (int[] p : parsed) {
                JsonObject entry = new JsonObject();
                entry.add("pos", J.intArray(p[0], p[1], p[2]));
                if (p[1] < world.getMinHeight() || p[1] >= world.getMaxHeight()) {
                    entry.addProperty("block", (String) null);
                    entry.addProperty("outOfWorld", true);
                    blocks.add(entry);
                    continue;
                }
                Block block = world.getBlockAt(p[0], p[1], p[2]);
                entry.addProperty("block", block.getBlockData().getAsString());
                entry.addProperty("light", block.getLightLevel());
                entry.addProperty("biome", block.getBiome().getKey().getKey());
                blocks.add(entry);
            }
            return wrap("blocks", blocks);
        }));
    }

    private ApiResponse materials(Req req) {
        String filter = req.query("filter", "").toLowerCase(Locale.ROOT);
        boolean solidOnly = req.queryBool("solidOnly", false);
        JsonArray blocks = new JsonArray();
        int count = 0;
        for (String name : parser.blockNames()) {
            if (!filter.isEmpty() && !name.contains(filter)) continue;
            if (solidOnly) {
                Material material = Material.matchMaterial(name);
                if (material == null || !material.isSolid()) continue;
            }
            blocks.add(name);
            count++;
        }
        JsonObject data = new JsonObject();
        data.addProperty("count", count);
        data.add("blocks", blocks);
        return ApiResponse.ok(data);
    }

    private ApiResponse validate(Req req) {
        JsonArray input = J.reqArr(req.body(), "blocks");
        JsonArray results = new JsonArray();
        for (JsonElement el : input) {
            String raw = el.getAsString();
            JsonObject result = new JsonObject();
            result.addProperty("input", raw);
            BlockData data = parser.tryParse(raw);
            if (data != null) {
                result.addProperty("valid", true);
                result.addProperty("normalized", data.getAsString());
            } else {
                result.addProperty("valid", false);
                result.add("suggestions", J.toArray(parser.suggest(raw)));
            }
            results.add(result);
        }
        return ApiResponse.ok(wrap("results", results));
    }

    /**
     * {@code POST /ops} — the main worker endpoint.
     *
     * <p>Parsing and validation happen here, off the main thread. Nothing is written until the
     * {@code JobRunner} picks the job up, so a malformed request can never leave the world in a
     * half-built state.
     */
    private ApiResponse ops(Req req) throws InterruptedException {
        JsonObject body = req.body();
        String worldName = J.reqStr(body, "world");
        if (!config.worldAllowed(worldName)) {
            throw new ApiException(ErrorCode.REGION_PROTECTED,
                    "world '" + worldName + "' is not in allowedWorlds");
        }
        if (Bukkit.getWorld(worldName) == null) throw ApiException.noWorld(worldName);

        JsonArray opsArray = J.reqArr(body, "ops");
        if (opsArray.size() > config.maxOpsPerRequest) {
            throw ApiException.limit("request has " + opsArray.size() + " ops, over limits.maxOpsPerRequest ("
                    + config.maxOpsPerRequest + ")", "split the request");
        }

        String label = J.str(body, "label", "");
        boolean wantUndo = J.bool(body, "undo", true);
        boolean dryRun = J.bool(body, "dryRun", false);
        boolean async = J.bool(body, "async", true);
        boolean physics = J.bool(body, "physics", config.defaultPhysics);
        boolean lightUpdate = J.bool(body, "lightUpdate", config.defaultLightUpdate);
        long seed = J.lng(body, "seed", System.nanoTime());

        List<String> warnings = new ArrayList<>();
        List<Op> ops = new ArrayList<>(opsArray.size());
        long estimated = 0;
        for (int i = 0; i < opsArray.size(); i++) {
            Op op = OpParser.parse(parser, config, J.asObject(opsArray.get(i), "ops[" + i + "]"), i);
            ops.add(op);
            estimated += op.estimate();
        }

        if (wantUndo && !undoManager.enabled()) {
            warnings.add("undo is disabled in the plugin config; this job cannot be rolled back");
            wantUndo = false;
        }
        if (wantUndo && estimated > config.maxUndoBlocks) {
            warnings.add("estimated " + estimated + " blocks exceeds limits.maxUndoBlocks ("
                    + config.maxUndoBlocks + "); undo will be dropped for this job");
        }

        if (dryRun) {
            JsonObject data = new JsonObject();
            data.addProperty("jobId", (String) null);
            data.addProperty("status", "dry-run");
            data.addProperty("estimatedBlocks", estimated);
            data.addProperty("opsTotal", ops.size());
            data.addProperty("blocksChanged", 0);
            data.add("warnings", J.toArray(warnings));
            return ApiResponse.ok(data);
        }

        if (!config.has("jobs")) async = false;
        if (!async && estimated > config.maxSyncBlocks) {
            throw ApiException.limit("synchronous execution is limited to " + config.maxSyncBlocks
                            + " blocks (this request estimates " + estimated + ")",
                    "send \"async\": true and poll GET /api/v1/jobs/{id}");
        }

        UndoSnapshot snapshot = wantUndo ? undoManager.createSnapshot(worldName, label) : null;
        Job job = new Job(jobRunner.nextJobId(), label, worldName, ops, estimated, physics,
                lightUpdate, snapshot, seed, warnings);
        jobRunner.submit(job);

        if (async) {
            JsonObject data = new JsonObject();
            data.addProperty("jobId", job.id());
            data.addProperty("status", job.status().wire());
            data.addProperty("estimatedBlocks", estimated);
            data.add("warnings", J.toArray(job.warnings()));
            return ApiResponse.accepted(data);
        }

        boolean completed = job.completion().await(config.syncTimeoutMs, TimeUnit.MILLISECONDS);
        JsonObject data = new JsonObject();
        data.addProperty("jobId", job.id());
        data.addProperty("status", completed ? job.status().wire() : "running");
        data.addProperty("blocksChanged", job.blocksChanged());
        data.addProperty("estimatedBlocks", estimated);
        data.addProperty("undoId", snapshot == null || snapshot.isDisabled() ? null : snapshot.undoId());
        data.addProperty("elapsedMs", job.elapsedMs());
        data.add("warnings", J.toArray(job.warnings()));
        if (job.error() != null) data.addProperty("error", job.error());
        if (!completed) {
            data.addProperty("note", "the job is still running; poll GET /api/v1/jobs/" + job.id());
        }
        return ApiResponse.ok(data);
    }

    private ApiResponse cancelJob(Req req) {
        String id = req.pathParam("id");
        Job job = jobRunner.get(id);
        jobRunner.cancel(id);
        JsonObject data = new JsonObject();
        data.addProperty("jobId", id);
        data.addProperty("status", job.status().wire());
        data.addProperty("cancelRequested", true);
        data.addProperty("undoId", job.undo() == null ? null : job.undo().undoId());
        return ApiResponse.ok(data);
    }

    private ApiResponse undo(Req req) throws Exception {
        String undoId = J.str(req.body(), "undoId", null);
        int restored;
        try {
            restored = undoManager.requestRestore(undoId)
                    .get(config.syncTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ApiException api) throw api;
            throw new ApiException(ErrorCode.INTERNAL,
                    cause == null ? ex.toString() : cause.getMessage());
        } catch (TimeoutException ex) {
            throw new ApiException(ErrorCode.INTERNAL,
                    "the rollback did not finish within " + config.syncTimeoutMs + " ms",
                    "it is still running on the tick budget; call GET /api/v1/undo to check the history");
        }
        JsonObject data = new JsonObject();
        data.addProperty("blocksRestored", restored);
        data.addProperty("undoId", undoId);
        return ApiResponse.ok(data);
    }

    private ApiResponse capture(Req req) {
        JsonObject body = req.body();
        String worldName = J.reqStr(body, "world");
        Region region = Region.parse(body);
        String name = J.reqStr(body, "name");
        boolean trimAir = J.bool(body, "trimAir", true);
        return ApiResponse.ok(sync(() -> captureService.capture(requireWorld(worldName), region, name, trimAir),
                Math.max(config.readTimeoutMs, 30_000)));
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────

    /**
     * Derives the plain Minecraft version from {@code getBukkitVersion()}
     * ({@code "1.21.4-R0.1-SNAPSHOT"} → {@code "1.21.4"}). Deliberately avoids the Paper-only
     * {@code Bukkit.getMinecraftVersion()} so the plugin also runs on Spigot and Purpur.
     */
    private static String minecraftVersion() {
        String raw = Bukkit.getBukkitVersion();
        int dash = raw.indexOf('-');
        return dash > 0 ? raw.substring(0, dash) : raw;
    }

    private World requireWorld(String name) {
        if (!config.worldAllowed(name)) {
            throw new ApiException(ErrorCode.REGION_PROTECTED, "world '" + name + "' is not in allowedWorlds");
        }
        World world = Bukkit.getWorld(name);
        if (world == null) throw ApiException.noWorld(name);
        return world;
    }
}
