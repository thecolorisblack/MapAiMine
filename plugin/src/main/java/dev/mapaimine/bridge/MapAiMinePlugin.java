package dev.mapaimine.bridge;

import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.http.BridgeHttpServer;
import dev.mapaimine.bridge.job.JobRunner;
import dev.mapaimine.bridge.service.CaptureService;
import dev.mapaimine.bridge.service.PlayerService;
import dev.mapaimine.bridge.service.SurveyService;
import dev.mapaimine.bridge.service.WorldService;
import dev.mapaimine.bridge.undo.UndoManager;
import dev.mapaimine.bridge.world.VoidChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;

/**
 * MapAiMine Bridge — exposes Bridge Protocol v1 over HTTP so an MCP server (and through it, a
 * language model) can read and build in a Minecraft world.
 *
 * <p>Startup order matters: config → token → services → HTTP server → per-tick job runner. The
 * HTTP server is started last so no request can arrive before the runner exists.
 */
public final class MapAiMinePlugin extends JavaPlugin {

    private BridgeConfig config;
    private BlockParser blockParser;
    private UndoManager undoManager;
    private JobRunner jobRunner;
    private BridgeHttpServer httpServer;
    private SelectionManager selections;
    private BukkitTask tickTask;
    private String version = "0.1.0";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        version = getDescription().getVersion();
        selections = new SelectionManager();
        blockParser = new BlockParser();

        if (!bootstrap()) {
            getLogger().severe("MapAiMine failed to start; disabling the plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        MapAiMineCommand command = new MapAiMineCommand(this);
        org.bukkit.command.PluginCommand registered = getCommand("mapaimine");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }
        announce();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (httpServer != null) httpServer.stop();
        if (jobRunner != null) jobRunner.shutdown();
        getLogger().info("MapAiMine bridge stopped.");
    }

    /** Builds every component from the current config. Returns false when the port is unusable. */
    private boolean bootstrap() {
        config = new BridgeConfig(getConfig());
        ensureToken();
        config = new BridgeConfig(getConfig());

        undoManager = new UndoManager(config);
        jobRunner = new JobRunner(this, config, blockParser, undoManager);
        SurveyService surveyService = new SurveyService(config);
        WorldService worldService = new WorldService(this, config);
        CaptureService captureService = new CaptureService(config, getDataFolder().toPath());
        PlayerService playerService = new PlayerService(config);

        httpServer = new BridgeHttpServer(this, config, blockParser, jobRunner, undoManager,
                surveyService, worldService, captureService, playerService, selections);
        try {
            httpServer.start();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not bind " + config.host + ":" + config.port
                    + " — is another instance running?", ex);
            httpServer = null;
            return false;
        }

        // One tick = one budget refill. See JobRunner for how the budget bounds main-thread time.
        tickTask = Bukkit.getScheduler().runTaskTimer(this, jobRunner, 1L, 1L);
        return true;
    }

    /** Generates a 256-bit URL-safe token on first run and writes it back to config.yml. */
    private void ensureToken() {
        String token = getConfig().getString("token", "");
        if (token != null && !token.isBlank()) return;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        getConfig().set("token", generated);
        saveConfig();
        getLogger().info("Generated a new bridge token and saved it to config.yml.");
    }

    /** Prints the connection details an operator has to paste into the MCP server config. */
    private void announce() {
        String line = "=".repeat(66);
        getLogger().info(line);
        getLogger().info("  MapAiMine bridge is up.");
        getLogger().info("  URL   : " + httpServer.baseUrl());
        getLogger().info("  Token : " + config.token);
        getLogger().info("  Copy both into your MCP server config, e.g.:");
        getLogger().info("    MAPAIMINE_URL=" + httpServer.baseUrl());
        getLogger().info("    MAPAIMINE_TOKEN=" + config.token);
        getLogger().info("  Capabilities: " + String.join(", ", config.capabilityList()));
        getLogger().info(line);
    }

    /** Rebuilds everything from disk; used by {@code /mapaimine reload}. */
    public boolean reloadBridge() {
        if (tickTask != null) tickTask.cancel();
        if (httpServer != null) httpServer.stop();
        if (jobRunner != null) jobRunner.shutdown();
        blockParser.clearCaches();
        reloadConfig();
        return bootstrap();
    }

    /**
     * Hands the void generator back to Bukkit when a world created with {@code worldType:"void"}
     * is reloaded from {@code bukkit.yml} after a restart.
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (id != null && id.equalsIgnoreCase("void")) return new VoidChunkGenerator();
        return null;
    }

    public BridgeConfig bridgeConfig() {
        return config;
    }

    public JobRunner jobRunner() {
        return jobRunner;
    }

    public UndoManager undoManager() {
        return undoManager;
    }

    public SelectionManager selections() {
        return selections;
    }

    public BridgeHttpServer httpServer() {
        return httpServer;
    }

    public String version() {
        return version;
    }

    /** Replaces the stored token with a fresh one and restarts the bridge. */
    public String regenerateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        getConfig().set("token", generated);
        saveConfig();
        reloadBridge();
        return generated;
    }
}
