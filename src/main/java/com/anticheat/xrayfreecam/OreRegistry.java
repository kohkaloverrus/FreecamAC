package com.anticheat.xrayfreecam;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * v1.2.2: decides WHICH blocks are worth hiding (the anti-XRay core question).
 *
 * Hidden set = everything a texture-pack / freecam cheat wants to find through
 * walls: ores, gems, overworld metals, Nether "ores", End precious blocks,
 * plus naturally generated caves (AIR underground) — turning unseen cave air
 * into stone hides the mineshaft/cave GEOMETRY itself, which is exactly what
 * an X-Ray pack highlights. Player-made tunnels stay open because their air
 * was already sent to the client before it became "unseen" (chunk cache).
 *
 * Regular stone/dirt/deepslate/gravel are NOT candidates: they are what the
 * substitution turns everything into anyway, and scanning them would only
 * burn CPU without protecting anything.
 */
public final class OreRegistry {

    /** Fixed vanilla candidate set (fallback + always included). */
    private static final Set<Material> BASE = EnumSet.noneOf(Material.class);

    static {
        String[] names = {
            // Overworld ores & gems
            "COAL_ORE", "DEEPSLATE_COAL_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE",
            "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
            "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
            "ANCIENT_DEBRIS", "RAW_IRON_BLOCK", "RAW_GOLD_BLOCK",
            // Nether ores
            "NETHER_QUARTZ_ORE", "GLOWSTONE",
            // End / other treasures
            "OBSIDIAN", "CRYING_OBSIDIAN", "END_PORTAL_FRAME",
            // Bases: spawners & storage are prime xray targets too
            "SPAWNER", "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "BARREL"
        };
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) BASE.add(m);
        }
    }

    /** BASE merged with every block tagged minecraft:ores (server registry). */
    private static volatile Set<Material> extended = BASE;

    private OreRegistry() {}

    /**
     * Called once on plugin enable: adds all Tag.BLOCKS tagged #ores so any
     * datapack/custom ore is covered automatically. Failures are non-fatal.
     */
    public static void initFromTags(org.bukkit.plugin.Plugin plugin) {
        try {
            Set<Material> all = EnumSet.copyOf(BASE);
            // Paper 1.21.x registry API: add every *_ORES tag value so any
            // vanilla/deepslate/nether/custom ore is covered automatically.
            org.bukkit.Registry<org.bukkit.block.BlockType> reg = org.bukkit.Registry.BLOCK;
            for (io.papermc.paper.registry.tag.Tag<org.bukkit.block.BlockType> tag : reg.getTags()) {
                String key = tag.tagKey().key().value();
                if (!key.endsWith("_ores")) continue;
                for (org.bukkit.block.BlockType bt : reg.getTagValues(tag.tagKey())) {
                    Material m = bt == null ? null : bt.asMaterial();
                    if (m != null && m.isSolid()) all.add(m);
                }
            }
            extended = Collections.unmodifiableSet(all);
        } catch (Throwable t) {
            plugin.getLogger().warning("Ore tag scan failed, using built-in list: " + t);
        }
    }

    /** True if this material is something cheats look for through walls. */
    public static boolean isCandidate(Material m) {
        if (m == null || m == Material.AIR) return false;
        return extended.contains(m);
    }

    /**
     * Cave-air detection: AIR that is part of natural generation, i.e. has a
     * continuous solid roof above it (>= 3 blocks). Surface air / player-made
     * open tunnels near sky are NOT hidden. Cheap: max 3 block reads, bails
     * out at the first transparent block upward.
     */
    public static boolean isUndergroundCaveAir(Block block) {
        World w = block.getWorld();
        int y = block.getY();
        if (y >= w.getMaxHeight() - 4) return false;
        for (int dy = 1; dy <= 3; dy++) {
            Material above = w.getBlockAt(block.getX(), y + dy, block.getZ()).getType();
            if (above == Material.AIR || above.isTransparent()) return false;
        }
        return true;
    }
}
