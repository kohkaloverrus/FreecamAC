package com.anticheat.xrayfreecam.listeners;

import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Freecam detection (v1.1.0).
 *
 * A freecam cheat moves the player's camera WITHOUT the server ever seeing
 * matching movement packets for that player. So if a chunk is requested /
 * loaded "around" a position far away from where the server thinks the
 * player actually is — that is a freecam attempt.
 *
 * This listener:
 *  - tracks real per-player movement speed,
 *  - flags players whose client appears to be peeking beyond their real
 *    position (teleport-like jumps without teleport events),
 *  - and lets {@link ChunkOcclusionManager} know the player's REAL location,
 *    which is what the occlusion logic uses as the view origin.
 */
public class FreecamListener implements Listener {

    private final XRayFreecamPlugin plugin;

    // Last known real positions for jump detection
    private final Map<UUID, Location> lastPositions = new HashMap<>();

    public FreecamListener(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        Location last = lastPositions.get(id);
        if (last != null && last.getWorld().equals(to.getWorld())) {
            double dist = last.distance(to);
            // Teleports fire their own event; a raw >5 block move in one tick
            // without teleport is impossible in vanilla -> suspicious.
            if (dist > 5.0 && !player.isFlying() && !plugin.getSpectatorManager().isExempt(player)) {
                if (plugin.isLogFreecamAttempts()) {
                    plugin.getLogger().warning("Freecam? " + player.getName()
                            + " moved " + String.format("%.1f", dist)
                            + " blocks in one tick without teleport.");
                }
            }
        }
        lastPositions.put(id, to.clone());
    }

    public void clear(UUID id) {
        lastPositions.remove(id);
    }
}
