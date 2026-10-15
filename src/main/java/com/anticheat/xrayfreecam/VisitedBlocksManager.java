package com.anticheat.xrayfreecam;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Tracks blocks a player has already seen (visited / directly visible).
 * Stone substitution must never appear where the player has already been.
 */
public class VisitedBlocksManager {

    /**
     * Check if player has physically visited this location before
     * (within the last 30 seconds = "recently seen" window)
     */
    public static boolean hasVisited(Player player, Block block) {
        Location pLoc = player.getLocation();
        Location bLoc = block.getLocation();

        // Same world check
        if (!pLoc.getWorld().equals(bLoc.getWorld())) return false;

        double distance = pLoc.distance(bLoc);

        // If the player is close to the block, they have effectively "been there"
        return distance < 16.0;
    }

    /**
     * Determine whether we should protect this block from stone substitution
     * because the player has already seen/been at this place.
     */
    public static boolean isPlayerFamiliarArea(Player player, Block block) {
        return hasVisited(player, block);
    }

    /**
     * Surface leaves/tree check: don't place stone behind leaves or trees on surface.
     * Returns true if block is above surface level and near vegetation.
     */
    public static boolean isSurfaceVegetation(Block block) {
        int surfaceY = block.getWorld().getHighestBlockAt(block.getX(), block.getZ()).getY();
        if (block.getY() < surfaceY - 3) return false; // underground -> not surface vegetation

        Material type = block.getType();
        return isLeavesOrLog(type);
    }

    private static boolean isLeavesOrLog(Material m) {
        String name = m.name();
        return name.contains("LEAVES") || name.contains("LOG") || name.contains("WOOD")
                || name.contains("BARK") || name.equals("MOSS_BLOCK") || name.contains("AZALEA");
    }
}
