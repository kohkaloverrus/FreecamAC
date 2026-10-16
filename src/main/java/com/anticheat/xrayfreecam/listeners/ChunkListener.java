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

    // NOTE (v1.1.1): chunk sanitization is handled exclusively by
    // ChunkOcclusionManager.onChunkLoad. Running it here as well caused
    // duplicate full-chunk scans and refresh storms that froze chunk delivery
    // ("вечная загрузка территории"). Do not re-add a ChunkLoadEvent handler.

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
            final Player p = event.getPlayer();
            // v1.1.0: clear cached replacements, re-apply send-distance occlusion
            // and force-refresh surrounding chunks so the client immediately gets
            // either the full raw world (spectator) or the protected copy.
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getOcclusionManager().resync(p), 1L);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Loaded chunks are dropped on re-login -> reset cached replacements
        plugin.getPlayerReplacements()
              .put(event.getPlayer().getUniqueId(), new HashMap<>());
        // Occlusion is applied by ChunkOcclusionManager.onJoin as well;
        // nothing extra needed here.
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerReplacements().remove(event.getPlayer().getUniqueId());
    }
}
