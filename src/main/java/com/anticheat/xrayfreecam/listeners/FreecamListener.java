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
 * Freecam detection (v1.2.2: SILENT).
 *
 * A freecam cheat moves the player's camera WITHOUT the server ever seeing
 * matching movement packets for that player. The actual counter-measure is
 * packet-level: {@link com.anticheat.xrayfreecam.packet.PacketSubstitutionListener}
 * hides everything beyond a radius of the player's REAL (server-side)
 * position, so a free-flying camera sees only stone/void.
 *
 * This listener only tracks suspicious jumps for the admin API. It NEVER
 * writes to the console per-movement: the old build logged every >5-block
 * jump of every player (elytra, riptide, knockback, lag spikes all trigger
 * it) and flooded the log. Detections are counted instead; one summary line
 * per minute max via {@link #report()}, and only when log-freecam-attempts
 * is enabled in the config.
 */
public class FreecamListener implements Listener {

    private final XRayFreecamPlugin plugin;

    // Last known real positions for jump detection
    private final Map<UUID, Location> lastPositions = new HashMap<>();

    // Silent detections: UUID -> count since last report
    private final Map<UUID, Integer> detections = new HashMap<>();
    private long lastReportTick = -1L;
    private static final long REPORT_INTERVAL_TICKS = 1200L; // ~1 minute

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
            // Teleports fire their own event; a raw >8 block move in one tick
            // without teleport is impossible in vanilla -> suspicious.
            // (Threshold raised from 5 to 8 and flying/exempt players skipped
            // harder to cut false positives.)
            if (dist > 8.0 && !player.isFlying() && !player.isGliding()
                    && !plugin.getSpectatorManager().isExempt(player)) {
                detections.merge(id, 1, Integer::sum);
            }
        }
        lastPositions.put(id, to.clone());
    }

    /**
     * Called once per second from the plugin. At most ONE console line per
     * minute, and only when something was actually detected AND logging is
     * enabled. Never logs per-player per-move anymore.
     */
    public void tick() {
        long now = plugin.getServer().getCurrentTick();
        if (detections.isEmpty()) return;
        if (now - lastReportTick < REPORT_INTERVAL_TICKS) return;
        lastReportTick = now;
        if (!plugin.isLogFreecamAttempts()) {
            detections.clear();
            return;
        }
        int total = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Integer> e : detections.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            String name = p != null ? p.getName() : "?";
            sb.append(name).append(" x").append(e.getValue()).append(", ");
            total += e.getValue();
        }
        detections.clear();
        if (total > 0) {
            plugin.getLogger().info("Freecam-like jumps in the last minute (" + total
                    + "): " + sb.substring(0, Math.max(0, sb.length() - 2)));
        }
    }

    public void clear(UUID id) {
        lastPositions.remove(id);
        detections.remove(id);
    }
}
