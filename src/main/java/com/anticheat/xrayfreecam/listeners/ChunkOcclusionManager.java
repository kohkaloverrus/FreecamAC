package com.anticheat.xrayfreecam.listeners;

import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.1.0 core: CLIENT-SENT OCCLUSION ("не отсылать клиенту то, что вне его видимости").
 *
 * Instead of merely deciding which blocks would become stone, this manager
 * actually prevents the protected area from being transmitted to the client
 * at all. It uses only public Paper API available in 1.21.11:
 *
 *   Player#setSendViewDistance(int)  — how far chunks are SENT to a client
 *   Player#getClientViewDistance()   — what the client itself requested
 *   World#refreshChunk(x,z)          — re-send a chunk to everyone seeing it
 *
 * How it works:
 *  1. The player's SEND distance is clamped to `protection.send-radius-chunks`
 *     (default 4 chunks ≈ 64 blocks). Everything beyond that radius is simply
 *     never sent to the client — freecam flying out of the body reveals
 *     nothing but unloaded sky/void, because those chunks do not exist on
 *     the cheat client at all.
 *  2. Inside the sent radius, BlockProtector still decides per-block whether
 *     it should appear as stone (deep unseen areas behind solid walls), so
 *     x-ray texture packs see only stone where the real player cannot see.
 *  3. Spectators / bypass staff keep their normal full send distance and no
 *     substitution (SpectatorManager exemption).
 */
public class ChunkOcclusionManager implements Listener {

    private final XRayFreecamPlugin plugin;

    // Remembered natural send distances so we can restore them for exempt players
    private final Map<UUID, Integer> originalSendDistance = new HashMap<>();

    public ChunkOcclusionManager(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        originalSendDistance.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Clamp the outgoing chunk radius for one player. Called on join and via
     * /anticheat occlude|apply. Safe to call repeatedly.
     */
    public void apply(Player player) {
        UUID id = player.getUniqueId();
        if (!originalSendDistance.containsKey(id)) {
            try {
                originalSendDistance.put(id, player.getSendViewDistance());
            } catch (Throwable ignored) {
                originalSendDistance.put(id, plugin.getViewDistance());
            }
        }

        int target;
        if (!plugin.isProtectionEnabled() || plugin.getSpectatorManager().isExempt(player)) {
            // Restore full view for spectators/staff — they legitimately see everything
            Integer orig = originalSendDistance.get(id);
            target = orig != null ? orig : plugin.getViewDistance();
        } else {
            target = plugin.getSendRadiusChunks();
            int clientVd = safeClientViewDistance(player);
            // Never advertise less than the client already has loaded; shrinking
            // below the client request causes visual glitches. We only CAP it.
            if (clientVd > 0 && clientVd < target) target = clientVd;
        }

        try {
            if (player.getSendViewDistance() != target) {
                player.setSendViewDistance(target);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("setSendViewDistance failed for " + player.getName() + ": " + t);
        }
    }

    private int safeClientViewDistance(Player player) {
        try {
            return player.getClientViewDistance();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * When a fresh chunk loads near a protected player, run the classic
     * block-level sanitizer (stone behind walls) and refresh the chunk for
     * that player so the client receives the sanitized version instead of
     * the raw one. This covers the "за стеной только камень" rule inside the
     * reduced send radius.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.isProtectionEnabled()) return;
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();

        boolean anyProtected = false;
        for (Player player : world.getPlayers()) {
            if (plugin.getSpectatorManager().isExempt(player)) continue;
            double dx = (chunk.getX() * 16 + 8) - player.getLocation().getX();
            double dz = (chunk.getZ() * 16 + 8) - player.getLocation().getZ();
            if (Math.hypot(dx, dz) <= (plugin.getSendRadiusChunks() + 1) * 16.0) {
                plugin.getBlockProtector().sanitizeChunk(player, chunk);
                anyProtected = true;
            }
        }
        if (anyProtected) {
            // Re-send the chunk to everyone currently seeing it so clients
            // that already received the raw data get the corrected copy.
            try {
                world.refreshChunk(chunk.getX(), chunk.getZ());
            } catch (Throwable ignored) {
                // refresh may fail if the chunk was unloaded again this tick
            }
        }
    }

    /**
     * Full resync: clear cached replacements and force every chunk around the
     * player to be re-evaluated and re-sent (used after gamemode change or
     * /anticheat reload).
     */
    public void resync(Player player) {
        plugin.getPlayerReplacements().put(player.getUniqueId(), new HashMap<>());
        apply(player);
        World world = player.getWorld();
        Location loc = player.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        int r = plugin.getSendRadiusChunks();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                try {
                    world.refreshChunk(cx + dx, cz + dz);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public Map<UUID, Integer> getOriginalSendDistance() {
        return originalSendDistance;
    }
}
