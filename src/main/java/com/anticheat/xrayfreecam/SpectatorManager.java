package com.anticheat.xrayfreecam;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks players that are currently SPECTATING (or otherwise flying through
 * blocks) and must NOT receive anti-XRay stone substitution.
 *
 * Spectators can fly through walls, so their real line-of-sight is not the
 * normal survival one — hiding blocks behind walls for them makes no sense
 * and would break the vanilla spectator experience. Therefore:
 *   - Players in GameMode.SPECTATOR are always exempt from protection.
 *   - Additionally, any player flagged as a spectator via API/config-list
 *     (e.g. staff with a spectator item, or an external spectate plugin that
 *     uses Adventure/creative-fly to roam) is exempt while the flag is on.
 */
public class SpectatorManager {

    private final XRayFreecamPlugin plugin;

    // Manually flagged spectators (UUID -> expiry timestamp ms, Long.MAX_VALUE = permanent)
    private final Map<UUID, Long> flaggedSpectators = new ConcurrentHashMap<>();

    public SpectatorManager(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Central exemption check used by BlockProtector before replacing anything.
     */
    public boolean isExempt(Player player) {
        if (player == null) return true;
        // Vanilla spectator game mode
        if (player.getGameMode() == GameMode.SPECTATOR) return true;
        // Permission-based bypass (staff) or explicit spectator permission
        if (player.hasPermission("anticheat.bypass")) return true;
        if (plugin.isSpectatePermissionEnabled() && player.hasPermission("anticheat.spectate"))
            return true;
        // Manually flagged spectator (still valid?)
        Long expiry = flaggedSpectators.get(player.getUniqueId());
        if (expiry != null) {
            if (expiry > System.currentTimeMillis()) return true;
            flaggedSpectators.remove(player.getUniqueId()); // expired
        }
        return false;
    }

    /** Flag a player as spectator for {@code seconds} (<=0 => until unflagged). */
    public void addSpectator(UUID id, long seconds) {
        long expiry = seconds <= 0 ? Long.MAX_VALUE
                : System.currentTimeMillis() + seconds * 1000L;
        flaggedSpectators.put(id, expiry);
    }

    public void removeSpectator(UUID id) {
        flaggedSpectators.remove(id);
    }

    public boolean isFlagged(UUID id) {
        Long expiry = flaggedSpectators.get(id);
        if (expiry == null) return false;
        if (expiry > System.currentTimeMillis()) return true;
        flaggedSpectators.remove(id);
        return false;
    }

    public void clearAll() {
        flaggedSpectators.clear();
    }

    public Map<UUID, Long> getFlagged() {
        return flaggedSpectators;
    }
}
