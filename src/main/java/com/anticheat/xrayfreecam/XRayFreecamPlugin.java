package com.anticheat.xrayfreecam;

import com.anticheat.xrayfreecam.listeners.ChunkListener;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * XRay & Freecam AntiCheat for Paper 1.21.11 (Java 25)
 *
 * Protects bases and ores from X-Ray texture packs and Freecam cheats by
 * replacing blocks outside the player's real line of sight with stone in
 * the data sent to the client. The real world is never modified.
 */
public class XRayFreecamPlugin extends JavaPlugin {

    private boolean protectionEnabled = true;
    private double farDistance = 30.0;      // default protection radius
    private int viewDistance = 8;           // chunks
    private boolean spectatePermissionEnabled = true;
    private BlockProtector blockProtector;
    private SpectatorManager spectatorManager;

    // Per-player map of sanitized (location -> original material) replacements
    // that were applied to outgoing chunk data. Cleared on rejoin because
    // "прогруженные чанки сбрасываются при перезаходе".
    private final Map<UUID, Map<Location, Material>> playerReplacements = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        this.blockProtector = new BlockProtector(this);
        this.spectatorManager = new SpectatorManager(this);

        getServer().getPluginManager().registerEvents(new ChunkListener(this), this);

        getLogger().info("XRayFreecamAntiCheat enabled. Protection radius: " + farDistance + " blocks.");
    }

    @Override
    public void onDisable() {
        playerReplacements.clear();
        getLogger().info("XRayFreecamAntiCheat disabled.");
    }

    private void loadConfig() {
        protectionEnabled = getConfig().getBoolean("protection.enabled", true);
        farDistance = getConfig().getDouble("protection.far-distance", 30.0);
        viewDistance = getConfig().getInt("protection.view-distance-chunks", 8);
        spectatePermissionEnabled = getConfig().getBoolean("spectator.permission-exempt", true);
    }

    public boolean isSpectatePermissionEnabled() {
        return spectatePermissionEnabled;
    }

    public void reload() {
        reloadConfig();
        loadConfig();
    }

    // ------------------------------------------------------------------
    // Command handling
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage("§cНет прав anticheat.admin");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§eИспользование: /anticheat <enable|disable|status|reload>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "enable":
                protectionEnabled = true;
                sender.sendMessage("§aЗащита включена");
                break;
            case "disable":
                protectionEnabled = false;
                sender.sendMessage("§cЗащита выключена");
                break;
            case "reload":
                reload();
                sender.sendMessage("§eКонфиг перезагружен");
                break;
            case "spectator":
            case "spec":
                // /anticheat spectator <add|remove|list> [player] [seconds]
                if (args.length < 2) {
                    sender.sendMessage("§eИспользование: /anticheat spectator <add|remove|list> [игрок] [секунды]");
                    return true;
                }
                String sub = args[1].toLowerCase();
                switch (sub) {
                    case "add": {
                        Player target = resolveTarget(sender, args, 2);
                        if (target == null) return true;
                        long secs = args.length >= 4 ? parseSeconds(args[3]) : 0;
                        spectatorManager.addSpectator(target.getUniqueId(), secs);
                        sender.sendMessage("§a" + target.getName()
                                + " — спектатор, камень античита для него скрыт"
                                + (secs > 0 ? " (" + secs + " сек)" : " (пока не снят)"));
                        break;
                    }
                    case "remove": {
                        Player target = resolveTarget(sender, args, 2);
                        if (target == null) return true;
                        spectatorManager.removeSpectator(target.getUniqueId());
                        sender.sendMessage("§cФлаг спектатора снят: " + target.getName());
                        break;
                    }
                    case "list": {
                        var flagged = spectatorManager.getFlagged();
                        if (flagged.isEmpty()) {
                            sender.sendMessage("§7Нет вручную помеченных спектаторов.");
                        } else {
                            for (var e : flagged.entrySet()) {
                                Player p = getServer().getPlayer(e.getKey());
                                String name = p != null ? p.getName() : e.getKey().toString();
                                sender.sendMessage("§b" + name + (e.getValue() == Long.MAX_VALUE
                                        ? " §7(постоянно)" : " §7(до истечения таймера)"));
                            }
                        }
                        break;
                    }
                    default:
                        sender.sendMessage("§eИспользование: /anticheat spectator <add|remove|list> [игрок] [секунды]");
                }
                break;
            case "status":
            default:
                sender.sendMessage("§7Защита: " + (protectionEnabled ? "§aвкл" : "§cвыкл"));
                sender.sendMessage("§7Радиус: §f" + farDistance + " блоков");
                sender.sendMessage("§7View distance: §f" + viewDistance + " чанков");
                sender.sendMessage("§7Спектаторы exempt: §fGameMode.SPECTATOR + флаг anticheat.spectate");
                break;
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int idx) {
        if (args.length <= idx) {
            if (sender instanceof Player p) return p;
            sender.sendMessage("§cУкажите игрока");
            return null;
        }
        Player target = getServer().getPlayer(args[idx]);
        if (target == null) sender.sendMessage("§cИгрок не найден: " + args[idx]);
        return target;
    }

    private long parseSeconds(String s) {
        try {
            return Math.max(0, Long.parseLong(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Getters / state
    // ------------------------------------------------------------------
    public boolean isProtectionEnabled() {
        return protectionEnabled;
    }

    public double getFarDistance() {
        return farDistance;
    }

    public int getViewDistance() {
        return viewDistance;
    }

    public BlockProtector getBlockProtector() {
        return blockProtector;
    }

    public SpectatorManager getSpectatorManager() {
        return spectatorManager;
    }

    public Map<UUID, Map<Location, Material>> getPlayerReplacements() {
        return playerReplacements;
    }

    /**
     * Returns the mutable pending-replacement map for a given player.
     * Used by BlockProtector.sanitizeChunk.
     */
    public Map<Location, Material> getPendingReplacements(Player player) {
        return playerReplacements.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
    }
}
