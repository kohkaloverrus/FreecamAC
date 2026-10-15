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
 * XRay & Freecam AntiCheat for Paper 1.21.1
 *
 * Protects bases and ores from X-Ray texture packs and Freecam cheats by
 * replacing blocks outside the player's real line of sight with stone in
 * the data sent to the client. The real world is never modified.
 */
public class XRayFreecamPlugin extends JavaPlugin {

    private boolean protectionEnabled = true;
    private double farDistance = 30.0;      // default protection radius
    private int viewDistance = 8;           // chunks
    private BlockProtector blockProtector;

    // Per-player map of sanitized (location -> original material) replacements
    // that were applied to outgoing chunk data. Cleared on rejoin because
    // "прогруженные чанки сбрасываются при перезаходе".
    private final Map<UUID, Map<Location, Material>> playerReplacements = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        this.blockProtector = new BlockProtector(this);

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
            case "status":
            default:
                sender.sendMessage("§7Защита: " + (protectionEnabled ? "§aвкл" : "§cвыкл"));
                sender.sendMessage("§7Радиус: §f" + farDistance + " блоков");
                sender.sendMessage("§7View distance: §f" + viewDistance + " чанков");
                break;
        }
        return true;
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
