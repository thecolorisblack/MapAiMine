package dev.mapaimine.bridge;

import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.job.Job;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@code /mapaimine} — the operator console for the bridge.
 *
 * <pre>
 *   /mapaimine token [regenerate]   show or rotate the API token
 *   /mapaimine status               port, capabilities, jobs, undo history
 *   /mapaimine undo [undoId]        roll back the last (or a named) operation
 *   /mapaimine reload               re-read config.yml and restart the HTTP server
 *   /mapaimine pos1 | pos2          mark a region corner for GET /api/v1/selection
 * </pre>
 */
public final class MapAiMineCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("token", "status", "undo", "reload", "pos1", "pos2", "selection");

    private final MapAiMinePlugin plugin;

    public MapAiMineCommand(MapAiMinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mapaimine.admin")) {
            sender.sendMessage(ChatColor.RED + "You need the mapaimine.admin permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "token" -> token(sender, args);
            case "status" -> status(sender);
            case "undo" -> undo(sender, args);
            case "reload" -> reload(sender);
            case "pos1" -> pos(sender, 1);
            case "pos2" -> pos(sender, 2);
            case "selection" -> selection(sender);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /mapaimine <"
                    + String.join("|", SUBCOMMANDS) + ">");
        }
        return true;
    }

    private void token(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("regenerate")) {
            String token = plugin.regenerateToken();
            sender.sendMessage(ChatColor.GREEN + "New token: " + ChatColor.WHITE + token);
            sender.sendMessage(ChatColor.GRAY + "Update your MCP server config; the old token is dead.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "URL   : " + ChatColor.WHITE + plugin.httpServer().baseUrl());
        sender.sendMessage(ChatColor.GREEN + "Token : " + ChatColor.WHITE + plugin.bridgeConfig().token);
        sender.sendMessage(ChatColor.GRAY + "/mapaimine token regenerate rotates it.");
    }

    private void status(CommandSender sender) {
        BridgeConfig config = plugin.bridgeConfig();
        sender.sendMessage(ChatColor.AQUA + "MapAiMine " + plugin.version()
                + ChatColor.GRAY + " (protocol 1)");
        sender.sendMessage(ChatColor.GRAY + "  listening   : " + ChatColor.WHITE
                + config.host + ":" + config.port);
        sender.sendMessage(ChatColor.GRAY + "  capabilities: " + ChatColor.WHITE
                + String.join(", ", config.capabilityList()));
        sender.sendMessage(ChatColor.GRAY + "  blocks/tick : " + ChatColor.WHITE + config.blocksPerTick);
        sender.sendMessage(ChatColor.GRAY + "  jobs active : " + ChatColor.WHITE
                + plugin.jobRunner().activeCount());
        sender.sendMessage(ChatColor.GRAY + "  undo history: " + ChatColor.WHITE
                + plugin.undoManager().historySize() + " / " + config.maxUndoHistory);
        for (com.google.gson.JsonElement el : plugin.jobRunner().listJson()) {
            com.google.gson.JsonObject job = el.getAsJsonObject();
            String status = job.get("status").getAsString();
            if (status.equals("done") || status.equals("cancelled")) continue;
            sender.sendMessage(ChatColor.GRAY + "  " + job.get("jobId").getAsString() + " "
                    + status + " " + job.getAsJsonObject("progress").get("percent").getAsDouble() + "% "
                    + ChatColor.DARK_GRAY + job.get("label").getAsString());
        }
    }

    private void undo(CommandSender sender, String[] args) {
        String undoId = args.length > 1 ? args[1] : null;
        try {
            sender.sendMessage(ChatColor.GRAY + "Rolling back…");
            int restored = plugin.undoManager().requestRestore(undoId)
                    .get(plugin.bridgeConfig().syncTimeoutMs, TimeUnit.MILLISECONDS);
            sender.sendMessage(ChatColor.GREEN + "Restored " + restored + " blocks.");
        } catch (ApiException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        } catch (Exception ex) {
            sender.sendMessage(ChatColor.RED + "Undo failed: " + ex.getMessage());
        }
    }

    private void reload(CommandSender sender) {
        if (plugin.reloadBridge()) {
            sender.sendMessage(ChatColor.GREEN + "MapAiMine reloaded; bridge listening on "
                    + plugin.httpServer().baseUrl());
        } else {
            sender.sendMessage(ChatColor.RED + "Reload failed — see the server log (port in use?).");
        }
    }

    private void pos(CommandSender sender, int corner) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can mark a position.");
            return;
        }
        if (corner == 1) {
            plugin.selections().setPos1(player.getName(), player.getLocation());
        } else {
            plugin.selections().setPos2(player.getName(), player.getLocation());
        }
        sender.sendMessage(ChatColor.GREEN + "pos" + corner + " set to "
                + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockY() + ", "
                + player.getLocation().getBlockZ());
        selection(sender);
    }

    private void selection(CommandSender sender) {
        String name = sender instanceof Player player ? player.getName() : "";
        sender.sendMessage(ChatColor.GRAY + "selection: " + plugin.selections().toJson(name));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(sub);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("token")) {
            return List.of("regenerate");
        }
        return List.of();
    }
}
