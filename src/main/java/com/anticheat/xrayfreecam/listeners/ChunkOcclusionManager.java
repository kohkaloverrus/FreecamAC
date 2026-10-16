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

    public ChunkOcclusionManager(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // nothing per-player to clean up anymore (v1.1.1)
    }

    /**
     * Clamp the outgoing chunk radius for one player. Called on join and via
     * /anticheat occlude|apply. Safe to call repeatedly.
     *
     * v1.1.1 fix: we NEVER lower a player's send distance below what their own
     * client requested. Shrinking the server-side send radius below the client
     * view distance makes the client wait forever for chunks it will never
     * receive -> infinite "Downloading terrain" and console errors. If the
     * configured protection radius is smaller than the client's request, we
     * skip the clamp entirely (block-level stone sanitization still applies).
     */
    public void apply(Player player) {
        UUID id = player.getUniqueId();

        int target;
        boolean exempt = !plugin.isProtectionEnabled() || plugin.getSpectatorManager().isExempt(player);

        if (exempt) {
            // Restore the distance the CLIENT itself asked for (vanilla behaviour).
            // Using a remembered value here could leave the player stuck with a
            // reduced radius after gamemode changes.
            target = safeClientViewDistance(player);
            if (target <= 0) target = plugin.getServer().getViewDistance();
        } else {
            int clientVd = safeClientViewDistance(player);
            if (clientVd < 0) clientVd = plugin.getServer().getViewDistance();
            target = Math.min(plugin.getSendRadiusChunks(), clientVd);
            // Protection radius must not be SMALLER than the client request,
            // otherwise the client would hang loading invisible chunks.
            // In that case we do not clamp at all.
            if (target >= clientVd) {
                return;
            }
        }

        try {
            if (player.getSendViewDistance() != target) {
                player.setSendViewDistance(target);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("setSendViewDistance failed for " + player.getName() + ": " + t);
        }
    }

    /**
     * Fully restore a player's natural (client-requested) send distance.
     */
    public void restore(Player player) {
        int clientVd = safeClientViewDistance(player);
        if (clientVd <= 0) clientVd = plugin.getServer().getViewDistance();
        try {
            if (player.getSendViewDistance() != clientVd) {
                player.setSendViewDistance(clientVd);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("send-distance restore failed for " + player.getName() + ": " + t);
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
        // v1.2.0 hotfix: with PacketEvents block substitution active, chunk
        // packets are sanitized in-flight (see PacketSubstitutionListener).
        // The old per-block scan here ran 65k+ shouldReplaceWithStone()
        // raytraces per loaded chunk PER PLAYER on the main thread and was
        // the primary cause of the server-wide lag / join timeouts. Skip it.
        if (plugin.getPacketEventsManager().isAvailable()) return;

        final Chunk chunk = event.getChunk();
        final World world = chunk.getWorld();

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
        if (!anyProtected) return;

        // v1.1.1: refresh only chunks we actually modified AND that are NOT
        // fresh load events (a freshly loaded chunk has not been sent to any
        // client yet, refreshing it would unload/reload it -> infinite loop of
        // ChunkLoadEvents and "Downloading terrain" forever on the client).
        final int cx = chunk.getX();
        final int cz = chunk.getZ();
        if (plugin.markRefreshPending(world, cx, cz)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (world.isChunkLoaded(cx, cz) && !plugin.wasFreshlyLoaded(world, cx, cz)) {
                        world.refreshChunk(cx, cz);
                    }
                } catch (Throwable ignored) {
                } finally {
                    plugin.clearRefreshPending(world, cx, cz);
                }
            }, 2L);
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
}
