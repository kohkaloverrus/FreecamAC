package com.anticheat.xrayfreecam.listeners;

import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.HashMap;

/**
 * Hooks chunk loading to sanitize outgoing block data for players and
 * manages per-player state on join/quit (chunks reset on rejoin).
 */
public class ChunkListener implements Listener {

    private final XRayFreecamPlugin plugin;

    public ChunkListener(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.isProtectionEnabled()) return;

        for (Player player : event.getChunk().getWorld().getPlayers()) {
            // Spectators & bypass staff never receive stone substitution
            if (plugin.getSpectatorManager().isExempt(player)) continue;

            double distance = player.getLocation()
                    .distance(event.getChunk().getBlock(8, 64, 8).getLocation());
            if (distance > (plugin.getViewDistance() + 2) * 16.0) continue;

            plugin.getBlockProtector().sanitizeChunk(player, event.getChunk());
        }
    }

    /**
     * When a player switches INTO spectator mode, immediately resend chunks
     * unsanitized so the stone overlay disappears instantly (and vice versa).
     */
    @EventHandler
    public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        boolean wasExempt = plugin.getSpectatorManager().isExempt(event.getPlayer());
        // Simulate the new gamemode result
        boolean willBeExempt = event.getNewGameMode() == org.bukkit.GameMode.SPECTATOR
                || event.getPlayer().hasPermission("anticheat.bypass")
                || plugin.getSpectatorManager().isFlagged(event.getPlayer().getUniqueId());

        if (wasExempt != willBeExempt) {
            Player p = event.getPlayer();
            // Clear cached replacements; Paper resends chunks when visibility changes.
            plugin.getPlayerReplacements().put(p.getUniqueId(), new HashMap<>());
            // Force a chunk refresh around the player so the client gets fresh data
            // with the correct (sanitized or raw) block state.
            final int viewDist = Math.min(plugin.getViewDistance(), 6);
            final org.bukkit.Chunk center = p.getLocation().getChunk();
            // Refresh on next tick, after the game mode change has been applied
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (int dx = -viewDist; dx <= viewDist; dx++) {
                    for (int dz = -viewDist; dz <= viewDist; dz++) {
                        org.bukkit.Chunk c = p.getWorld().getChunkAt(
                                center.getX() + dx, center.getZ() + dz);
                        if (!c.isLoaded()) continue;
                        try {
                            // Paper API: per-player chunk visibility toggle
                            Player.class.getMethod("hideChunk", org.bukkit.Chunk.class)
                                    .invoke(p, c);
                            Player.class.getMethod("showChunk", org.bukkit.Chunk.class)
                                    .invoke(p, c);
                        } catch (ReflectiveOperationException ignored) {
                            // Older/other API without hide/showChunk: the player's
                            // own chunk-view refresh on gamemode change is enough;
                            // replacements cache was already cleared above.
                        }
                    }
                }
            }, 1L);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Loaded chunks are dropped on re-login -> reset cached replacements
        plugin.getPlayerReplacements()
              .put(event.getPlayer().getUniqueId(), new HashMap<>());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerReplacements().remove(event.getPlayer().getUniqueId());
    }
}
