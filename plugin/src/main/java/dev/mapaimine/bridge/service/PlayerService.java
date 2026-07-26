package dev.mapaimine.bridge.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.api.ErrorCode;
import dev.mapaimine.bridge.json.J;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Player and chat endpoints — PROTOCOL.md §2 "Игроки и чат".
 *
 * <p><b>Threading.</b> All methods touch live entities and must run on the main thread.
 */
public final class PlayerService {

    private final BridgeConfig config;

    public PlayerService(BridgeConfig config) {
        this.config = config;
    }

    public JsonArray list() {
        JsonArray arr = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", player.getName());
            o.addProperty("uuid", player.getUniqueId().toString());
            Location loc = player.getLocation();
            o.addProperty("world", loc.getWorld() == null ? null : loc.getWorld().getName());
            o.add("pos", J.intArray(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            o.addProperty("yaw", loc.getYaw());
            o.addProperty("pitch", loc.getPitch());
            o.addProperty("gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
            o.addProperty("op", player.isOp());
            arr.add(o);
        }
        return arr;
    }

    public Player require(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) player = Bukkit.getPlayer(name);
        if (player == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "player '" + name + "' is not online",
                    "call GET /api/v1/players for the online list");
        }
        return player;
    }

    public JsonObject teleport(String name, JsonObject body) {
        Player player = require(name);
        String worldName = J.str(body, "world", player.getWorld().getName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) throw ApiException.noWorld(worldName);
        if (!config.worldAllowed(worldName)) {
            throw new ApiException(ErrorCode.REGION_PROTECTED, "world '" + worldName + "' is not allowed");
        }
        Location target = new Location(world,
                J.dbl(body, "x", player.getLocation().getX()),
                J.dbl(body, "y", player.getLocation().getY()),
                J.dbl(body, "z", player.getLocation().getZ()),
                (float) J.dbl(body, "yaw", player.getLocation().getYaw()),
                (float) J.dbl(body, "pitch", player.getLocation().getPitch()));
        player.teleport(target);
        JsonObject out = new JsonObject();
        out.addProperty("player", player.getName());
        out.addProperty("world", world.getName());
        out.add("pos", J.intArray(target.getBlockX(), target.getBlockY(), target.getBlockZ()));
        return out;
    }

    public JsonObject gamemode(String name, JsonObject body) {
        Player player = require(name);
        String mode = J.reqStr(body, "mode").trim().toUpperCase(Locale.ROOT);
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("unknown gamemode '" + mode + "'",
                    "supported: survival, creative, adventure, spectator");
        }
        JsonObject out = new JsonObject();
        out.addProperty("player", player.getName());
        out.addProperty("gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
        return out;
    }

    /** {@code POST /broadcast} — legacy {@code §} colour codes in the message are honoured. */
    public JsonObject broadcast(JsonObject body) {
        String message = J.reqStr(body, "message");
        boolean toOps = J.bool(body, "toOps", false);
        int recipients = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (toOps && !player.isOp()) continue;
            player.sendMessage(message);
            recipients++;
        }
        if (!toOps) Bukkit.getConsoleSender().sendMessage(message);
        JsonObject out = new JsonObject();
        out.addProperty("recipients", recipients);
        return out;
    }
}
