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
            if (player.hasPermission("anticheat.bypass")) continue;

            double distance = player.getLocation()
                    .distance(event.getChunk().getBlock(8, 64, 8).getLocation());
            if (distance > (plugin.getViewDistance() + 2) * 16.0) continue;

            plugin.getBlockProtector().sanitizeChunk(player, event.getChunk());
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
