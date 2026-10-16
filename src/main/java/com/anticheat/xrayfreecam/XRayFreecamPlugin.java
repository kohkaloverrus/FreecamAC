package com.anticheat.xrayfreecam;

import com.anticheat.xrayfreecam.listeners.ChunkListener;
import com.anticheat.xrayfreecam.listeners.ChunkOcclusionManager;
import com.anticheat.xrayfreecam.listeners.FreecamListener;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
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
public class XRayFreecamPlugin extends JavaPlugin implements Listener {

    private boolean protectionEnabled = true;
    private double farDistance = 30.0;      // default protection radius
    private int viewDistance = 8;           // chunks
    private int sendRadiusChunks = 4;       // v1.1.0: max chunks SENT to a protected client
    private boolean logFreecamAttempts = true;
    private boolean spectatePermissionEnabled = true;
    private BlockProtector blockProtector;
    private OcclusionEngine occlusionEngine;
    private SpectatorManager spectatorManager;
    private com.anticheat.xrayfreecam.packet.PacketEventsManager packetEventsManager;
    private ChunkOcclusionManager occlusionManager;
    private FreecamListener freecamListener;

    // Per-player map of sanitized (location -> original material) replacements
    // that were applied to outgoing chunk data. Cleared on rejoin because
    // "прогруженные чанки сбрасываются при перезаходе".
    private final Map<UUID, Map<Location, Material>> playerReplacements = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        // v1.2.2: extend the ore candidate list from the server's #minecraft:ores tag
        com.anticheat.xrayfreecam.OreRegistry.initFromTags(this);

        // v1.2.0: PacketEvents must be initialized BEFORE anything else so
        // its Netty handlers are injected before any player connects.
        this.packetEventsManager = new com.anticheat.xrayfreecam.packet.PacketEventsManager(this);
        this.packetEventsManager.init();

        this.blockProtector = new BlockProtector(this);
        this.occlusionEngine = new OcclusionEngine(this);
        this.spectatorManager = new SpectatorManager(this);
        this.occlusionManager = new ChunkOcclusionManager(this);
        this.freecamListener = new FreecamListener(this);

        this.packetEventsManager.registerListeners();

        getServer().getPluginManager().registerEvents(new ChunkListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(occlusionManager, this);
        getServer().getPluginManager().registerEvents(freecamListener, this);

        // Apply send-distance occlusion to everyone already online (reload)
        for (Player p : getServer().getOnlinePlayers()) {
            occlusionManager.apply(p);
        }

        // v1.2.2: silent housekeeping — at most ONE console line per minute
        // and only when something actually happened (no per-player spam).
        getServer().getScheduler().runTaskTimer(this, () -> {
            freecamListener.tick();
            packetEventsManager.reportErrors();
        }, 20L * 60, 20L * 60);

        getLogger().info("XRayFreecamAntiCheat v1.3.0 enabled. Everything the client cannot see from the "
                + "player\u0027s real eyes is sent as STONE (caves, shafts, bases, ores, entities\u0027 surroundings)."
                + " Unseen areas beyond " + OcclusionEngine.OPEN_RADIUS + " blocks hide blindly; buried pockets"
                + " protect from " + OcclusionEngine.POCKET_RADIUS + ". Sky-exposed terrain is never touched."
                + " Send radius: " + sendRadiusChunks + " chunks.");
    }

    /** Radius (blocks) within which near-position data is scanned precisely. */
    public static final int HIDE_RADIUS_BLOCKS = 48;

    @Override
    public void onDisable() {
        if (packetEventsManager != null) {
            packetEventsManager.shutdown();
        }
        playerReplacements.clear();
        pendingRefresh.clear();
        freshlyLoaded.clear();
        // Restore natural send distances so leaving servers won't be affected
        // (defensive; per-player state does not persist anyway).
        getLogger().info("XRayFreecamAntiCheat disabled.");
    }

    /** Track every chunk load so refresh logic can skip brand-new chunks. */
    @EventHandler
    public void onChunkLoadTrack(ChunkLoadEvent event) {
        noteChunkLoaded(event.getChunk().getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    private void loadConfig() {
        protectionEnabled = getConfig().getBoolean("protection.enabled", true);
        farDistance = getConfig().getDouble("protection.far-distance", 30.0);
        viewDistance = getConfig().getInt("protection.view-distance-chunks", 8);
        sendRadiusChunks = Math.max(2, Math.min(12, getConfig().getInt("protection.send-radius-chunks", 12)));
        logFreecamAttempts = getConfig().getBoolean("logging.log-freecam-attempts", true);
        spectatePermissionEnabled = getConfig().getBoolean("spectator.permission-exempt", true);
    }

    public ChunkOcclusionManager getOcclusionManager() {
        return occlusionManager;
    }

    public int getSendRadiusChunks() {
        return sendRadiusChunks;
    }

    public boolean isLogFreecamAttempts() {
        return logFreecamAttempts;
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
                // Re-apply occlusion to everyone after config change
                for (Player p : getServer().getOnlinePlayers()) {
                    occlusionManager.apply(p);
                }
                sender.sendMessage("§eКонфиг перезагружен");
                break;
            case "occlude":
            case "apply": {
                // /anticheat occlude [player] — force re-sync send radius + chunks
                Player target = resolveTarget(sender, args, 1);
                if (target == null && args.length >= 1 && !(sender instanceof Player)) break;
                if (target != null) {
                    occlusionManager.resync(target);
                    sender.sendMessage("§aОкклюзия применена к " + target.getName()
                            + " (send radius: " + getSendRadiusChunks() + " чанков)");
                } else {
                    for (Player p : getServer().getOnlinePlayers()) occlusionManager.resync(p);
                    sender.sendMessage("§aОкклюзия применена ко всем игрокам");
                }
                break;
            }
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
                sender.sendMessage("§7Радиус камня: §f" + farDistance + " блоков");
                sender.sendMessage("§7Send radius (окклюзия): §f" + sendRadiusChunks + " чанков — вне его чанки НЕ отсылаются клиенту");
                sender.sendMessage("§7View distance сервера: §f" + viewDistance + " чанков");
                sender.sendMessage("§7Спектаторы exempt: §fGameMode.SPECTATOR + флаг anticheat.spectate");
                sender.sendMessage("§7Подмена блоков (PacketEvents): §f"
                        + (packetEventsManager != null && packetEventsManager.isAvailable()
                           ? "АКТИВНА (chunk data + block changes)" : "НЕДОСТУПНА (только окклюзия)"));
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

    public com.anticheat.xrayfreecam.packet.PacketEventsManager getPacketEventsManager() {
        return packetEventsManager;
    }

    /** v1.2.2: quit hook — frees the packet sanitizer's per-player caches. */
    public void onPlayerQuit(UUID id) {
        if (packetEventsManager != null) packetEventsManager.onPlayerQuit(id);
        if (occlusionEngine != null) { Player pl = getServer().getPlayer(id); if (pl != null) occlusionEngine.clearCache(pl); }
        if (freecamListener != null) freecamListener.clear(id);
    }

    public BlockProtector getBlockProtector() {
        return blockProtector;
    }

    /** v1.3.0: full line-of-sight occlusion oracle used by the packet sanitizer. */
    public OcclusionEngine getOcclusionEngine() {
        return occlusionEngine;
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

    // ------------------------------------------------------------------
    // v1.1.1 chunk-refresh throttle (prevents ChunkLoadEvent -> refreshChunk
    // -> ChunkLoadEvent infinite loops that froze terrain loading forever)
    // ------------------------------------------------------------------
    private final java.util.Set<String> pendingRefresh = new java.util.HashSet<>();
    private final java.util.Map<String, Long> freshlyLoaded = new java.util.HashMap<>();

    private static String key(org.bukkit.World world, int cx, int cz) {
        return world.getName() + ";" + cx + ";" + cz;
    }

    /** Called from ChunkListener.onChunkLoad for EVERY load event. */
    public void noteChunkLoaded(org.bukkit.World world, int cx, int cz) {
        freshlyLoaded.put(key(world, cx, cz), System.currentTimeMillis());
        // keep the map bounded
        if (freshlyLoaded.size() > 20000) {
            long cutoff = System.currentTimeMillis() - 5000;
            freshlyLoaded.values().removeIf(t -> t < cutoff);
        }
    }

    /** True if this chunk had a fresh load event within the last second. */
    public boolean wasFreshlyLoaded(org.bukkit.World world, int cx, int cz) {
        Long t = freshlyLoaded.get(key(world, cx, cz));
        return t != null && System.currentTimeMillis() - t < 1000L;
    }

    /** First caller within 5 seconds gets true (may schedule a refresh). */
    public boolean markRefreshPending(org.bukkit.World world, int cx, int cz) {
        return pendingRefresh.add(key(world, cx, cz));
    }

    public void clearRefreshPending(org.bukkit.World world, int cx, int cz) {
        pendingRefresh.remove(key(world, cx, cz));
    }
}
