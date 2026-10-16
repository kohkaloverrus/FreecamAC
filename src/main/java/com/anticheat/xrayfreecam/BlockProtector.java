package com.anticheat.xrayfreecam;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Core protection logic: decides whether a block should be replaced with stone
 * for the client (anti-XRay / anti-Freecam).
 */
public class BlockProtector {

    private final XRayFreecamPlugin plugin;

    public BlockProtector(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Main decision method: should this block be hidden (turned into stone) for this player?
     *
     * Rules from spec:
     * 1. Only blocks farther than 30 blocks from the player are replaced
     *    (exception: solid walls around -> 5 blocks horizontal, 10 blocks down)
     * 2. Never replace blocks the player has already visited/been near
     * 3. Never replace behind leaves/trees on the surface
     * 4. Transparent blocks stay visible only if the player can actually see through them
     * 5. Everything outside the player's view (freecam detection) -> stone
     */
    public boolean shouldReplaceWithStone(Player player, Block block) {
        if (!plugin.isProtectionEnabled()) return false;
        // Spectators (game mode SPECTATOR / flagged / bypass) never see stone:
        // they legitimately fly through blocks, protection is only for
        // survival/adventure/creative players whose client can be spoofed.
        if (plugin.getSpectatorManager().isExempt(player)) return false;

        Material type = block.getType();
        if (type.isAir()) return false;

        // Rule 2: player familiar area — never touch
        if (VisitedBlocksManager.isPlayerFamiliarArea(player, block)) return false;

        // Rule 3: never place stone behind surface leaves/wood
        if (VisitedBlocksManager.isSurfaceVegetation(block)) return false;

        double distance = player.getLocation().distance(block.getLocation());

        // Rule 1: distance thresholds
        boolean surroundedByWalls = isSurroundedBySolidWalls(block);
        double threshold;
        if (surroundedByWalls) {
            // Behind solid walls: protect starting at 5 blocks horizontally / 10 blocks down
            threshold = 5.0;
            double dy = player.getLocation().getY() - block.getLocation().getY();
            if (dy > 0 && distance < 10.0) {
                // within 10 blocks below the player and inside walls -> still protect
                threshold = Math.min(threshold, distance);
            }
        } else {
            threshold = plugin.getFarDistance(); // default 30
        }

        if (distance < threshold) return false;

        // Rule 4/5: if the player can directly see this block, don't replace it
        if (canPlayerSeeBlock(player, block)) return false;

        // Otherwise — hide it (stone)
        return true;
    }

    /**
     * Simple line-of-sight check from player eyes to block center.
     */
    private boolean canPlayerSeeBlock(Player player, Block block) {
        var eye = player.getEyeLocation().toVector();
        var target = block.getLocation().add(0.5, 0.5, 0.5).toVector();
        var dir = target.clone().subtract(eye);
        double len = dir.length();
        if (len < 1) return true;
        dir.normalize();

        World world = player.getWorld();
        double stepSize = 0.4;
        int steps = (int) (len / stepSize);
        for (int i = 1; i < steps; i++) {
            double d = i * stepSize;
            int bx = (int) Math.floor(eye.getX() + dir.getX() * d);
            int by = (int) Math.floor(eye.getY() + dir.getY() * d);
            int bz = (int) Math.floor(eye.getZ() + dir.getZ() * d);
            if (bx == block.getX() && by == block.getY() && bz == block.getZ()) {
                return true; // reached the target itself
            }
            Block b = world.getBlockAt(bx, by, bz);
            if (!b.getType().isTransparent()) {
                return false; // opaque block in between
            }
        }
        return true;
    }

    /**
     * Check whether the block is enclosed by solid walls such that the player
     * cannot look at it from above (F5 case in spec). If so we can protect
     * even at short distances (5 blocks / 10 blocks down).
     */
    private boolean isSurroundedBySolidWalls(Block block) {
        World world = block.getWorld();
        // Check column above the block: if there's a continuous run of >= 5 solid
        // blocks above, it is "underground behind walls".
        int solidAbove = 0;
        for (int dy = 1; dy <= 10; dy++) {
            Block above = world.getBlockAt(block.getX(), block.getY() + dy, block.getZ());
            if (above.getType().isAir()) break;
            if (!above.getType().isTransparent() && !above.getType().isAir()) {
                solidAbove++;
            } else {
                break;
            }
        }
        if (solidAbove >= 5) return true;

        // Also treat as surrounded if all 4 horizontal neighbors are solid
        int solidNeighbors = 0;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                Block n = world.getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                if (!n.getType().isAir() && !n.getType().isTransparent()) solidNeighbors++;
            }
        }
        return solidNeighbors >= 3;
    }

    /**
     * Process an entire chunk for a specific player, replacing protected blocks.
     * Used by the ChunkLoad listener to sanitize outgoing chunk data.
     */
    public void sanitizeChunk(Player player, Chunk chunk) {
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                    if (shouldReplaceWithStone(player, block)) {
                        // Do NOT modify the real world — this is handled packet-side.
                        // This method exists for full-world scans used by debug tools.
                        plugin.getPendingReplacements(player).put(block.getLocation(), block.getType());
                    }
                }
            }
        }
    }
}
