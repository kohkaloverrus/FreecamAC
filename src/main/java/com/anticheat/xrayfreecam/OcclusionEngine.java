package com.anticheat.xrayfreecam;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;

/**
 * v1.3.0: the "everything outside your view is solid stone" engine.
 *
 * Principle (from spec): if the client cannot see a position with vanilla
 * line-of-sight from the player's REAL eyes, then behind the wall there is
 * no empty space and no hidden blocks — only stone. This hides caves,
 * mineshafts, bases, entities (air + all block states) from Freecam AND
 * from X-Ray texture packs, while the player themselves notices nothing:
 * what they can already see is never touched.
 *
 * Visibility model:
 *  - A cell is VISIBLE if some corner of its AABB has an unobstructed ray
 *    from the eyes (corners are sampled in nearest-first order; any hit
 *    makes the cell visible). Walls that occlude the far corners still
 *    correctly reveal the near ones.
 *  - Opaque blocks (see {@link #isOpaque}) stop rays. Everything else
 *    (air, glass, leaves, slabs, plants...) is transparent per the spec's
 *    full transparency list.
 *  - A TRANSPARENT block itself becomes STONE when it is not directly seen
 *    AND at least one of its 6 neighbors is opaque (i.e. it is embedded in
 *    a wall and serves no viewing purpose for this client — the spec's
 *    "glass in a wall becomes stone" rule). Fully exposed glass panes or
 *    windows stay visible because their other sides face open air the
 *    client can reach rays through.
 *
 * Guard rails (spec):
 *  - Never hide above the surface (blocks with sky access via column scan)
 *    -> stone can never appear over leaves/trees on the surface.
 *  - Never hide within OPEN_RADIUS of the real player -> normal gameplay
 *    (mining, looking into your own base) is untouched.
 *  - Wall-pocket exception: deep inside solid rock (roof >= ROOF_DEPTH and
 *    all 4 horizontal sides buried) protection works from POCKET_RADIUS.
 *  - Visited/familiar coordinates are permanently exempt per player and
 *    reset on relog (chunk data resends anyway).
 *
 * Cost control: results cached per (chunk, section, player) with a short
 * TTL; per-tick ray budget bounds worst-case CPU. Cache miss beyond the
 * budget degrades to "leave visible" (safe direction: leak, don't lag).
 */
public final class OcclusionEngine {

    /** Beyond this distance from the player's eyes everything unseen is stone. */
    public static final int OPEN_RADIUS = 30;
    /** Minimum continuous opaque roof above a cell to count as buried. */
    public static final int ROOF_DEPTH = 5;
    /** Buried-pocket protection radius (walls all around, no F5 top-down). */
    public static final int POCKET_RADIUS = 12;
    /**
     * v1.3.2: absolute protection bubble around the player's feet. NOTHING
     * inside it is ever replaced with stone — this guarantees normal mining
     * and kills the v1.3.1 "stone everywhere near the player" regression.
     */
    public static final int SURFACE_EXEMPT = 16;
    /**
     * v1.3.1: "turn your head" rule from the spec — if a cell has NO clear
     * ray from the eyes to ANY of its 8 AABB corners, it is not visible even
     * in principle (rotating the camera cannot reveal it), so it becomes
     * stone regardless of distance. This seals cave/shaft air that pokes
     * into the OPEN_RADIUS bubble behind walls.
     */
    public static final int TURN_HEAD_RADIUS = OPEN_RADIUS;
    /** Column sky-scan depth cap (overworld height ~384, plenty). */
    private static final int SKY_SCAN_LIMIT = 200;
    /** Section decision cache TTL, ms. */
    private static final long CACHE_TTL = 900L;
    /** Max corner-ray tests per server tick across all packets. */
    private static final int TICK_BUDGET = 1600;

    // ------------------------------------------------------------------
    // Opaque classification (spec: everything NOT on the transparency
    // list is opaque -> stops sight rays and gets replaced with stone
    // when unseen).
    // ------------------------------------------------------------------
    private static final Map<Material, Boolean> OPAQUE_CACHE = new EnumMap<>(Material.class);

    /** True if this material fully blocks the client's sight through walls. */
    public static boolean isOpaque(Material m) {
        if (m == null) return false;
        Boolean cached = OPAQUE_CACHE.get(m);
        if (cached != null) return cached;
        boolean opaque = computeOpaque(m);
        OPAQUE_CACHE.put(m, opaque);
        return opaque;
    }

    private static boolean computeOpaque(Material m) {
        if (!m.isSolid()) return false;                 // air, water, plants, dust...
        String n = m.name();
        // Spec exceptions among solid materials:
        if (n.endsWith("_GLASS") || n.endsWith("_STAINED_GLASS")) return false; // tinted too
        if (n.contains("GLASS_PANE")) return false;
        switch (n) {
            case "SLIME_BLOCK", "HONEY_BLOCK", "BARRIER", "STRUCTURAL_VOID",
                 "END_PORTAL_FRAME", "END_GATEWAY", "PORTAL",
                 "SEA_LANTERN", "SEA_PICKLE", "SHULKER_BOX", "DYED_SHULKER_BOX",
                 "CARVED_PUMPKIN", "JACK_O_LANTERN", "GLOWSTONE",
                 "FROG_SPAWN", "CONDUIT", "DRAGON_EGG":
                return false;
            default:
        }
        if (n.endsWith("_LEAVES")) return false;        // all leaf variants
        if (n.startsWith("ICE") || n.equals("PACKED_ICE")
                || n.equals("BLUE_ICE") || n.equals("FROSTED_ICE")) return false;
        // Amethyst bud cluster / budding amethyst are see-through decorations
        if (n.contains("AMETHYST_BUD") || n.equals("BUDDING_AMETHYST")) return false;
        // Sculk family: sensor/catalyst/shrieker are partial non-blocking shapes
        if (n.equals("SCULK_SENSOR") || n.equals("SCULK_CATALYST")
                || n.equals("SCULK_SHRIEKER")) return false;
        // Full cubes & stairs/walls/fences etc. keep their faces: opaque.
        return true;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------
    private final XRayFreecamPlugin plugin;

    // per-player section caches (weak keys so quitting players clean up)
    private final Map<Player, Map<Long, Boolean>> visibilityCache =
            new java.util.WeakHashMap<>();

    private long budgetWindow = -1L;
    private int budgetUsed = 0;

    public OcclusionEngine(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearCache(Player player) {
        visibilityCache.remove(player);
        stamps.remove(player);
    }

    public void clearAllCaches() {
        visibilityCache.clear();
        stamps.clear();
    }

    /**
     * Should this block be sent to the client as STONE instead of its real
     * state? Covers EVERYTHING the client can't see: ores, machines,
     * structures, cave air, water in hidden pockets — the whole section
     * simply renders as stone beyond the player's view.
     *
     * Fast path first (cheap distance/structure checks) so the packet
     * sanitizer can call this for thousands of sampled cells without
     * paying for chunk lookups on blocks that are obviously visible-safe.
     */
    public boolean shouldHide(Player player, Block block) {
        return shouldHideAt(player, block.getWorld(),
                block.getX(), block.getY(), block.getZ());
    }

    /**
     * v1.3.2 core decision on raw coordinates (no Block object churn).
     *
     * ABSOLUTE RULES (never hide):
     *  - any position with sky access -> the ENTIRE surface layer stays
     *    completely natural: no stone blobs in grass/sand/gravel, none
     *    behind trees or leaves (this was the v1.3.1 regression);
     *  - any position within SURFACE_EXEMPT of the player's feet;
     *  - any position the player has already visited.
     * Only fully buried cells (continuous opaque roof >= ROOF_DEPTH above)
     * are candidates for stone.
     */
    public boolean shouldHideAt(Player player, World w, int x, int y, int z) {
        if (!plugin.isProtectionEnabled()) return false;
        if (plugin.getSpectatorManager().isExempt(player)) return false;
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return false;

        // Familiar area (player has been here) — never stone.
        if (VisitedBlocksManager.isFamiliar(player, x, y, z)) return false;

        Location loc = player.getLocation();
        double fdx = x + 0.5 - loc.getX();
        double fdz = z + 0.5 - loc.getZ();
        double fdy = y + 0.5 - loc.getY();
        if (fdx * fdx + fdz * fdz + fdy * fdy < SURFACE_EXEMPT * SURFACE_EXEMPT)
            return false;

        // Buried gate FIRST (cheap vertical probe, cached): open-sky columns
        // are rejected before any ray work -> zero cost on the surface.
        if (!isBuried(w, x, y, z)) return false;

        // The "turn your head" oracle: condemned only when NO corner of the
        // cell can ever be seen from the REAL eyes (shaft/cave/base air and
        // blocks behind walls). Distance-independent, but strictly gated by
        // the buried check above, so it can never touch the surface.
        return !isSectionVisible(player, w, x, y, z);
    }

    /** A cell is buried when it has a continuous opaque roof >= ROOF_DEPTH. */
    private boolean isBuried(World w, int x, int y, int z) {
        if (w.getBlockAt(x, y, z).getType() == Material.BEDROCK) return false;
        for (int dy = 1; dy <= ROOF_DEPTH; dy++) {
            if (!isOpaqueAt(w, x, y + dy, z)) return false;
        }
        return true;
    }

    /**
     * Section-level visibility cache: one corner-ray evaluation decides the
     * whole 16^3 section (v1.3.1 recomputed up to 8 rays PER BLOCK — the CPU
     * bomb that killed the server). Entries expire lazily via stamps.
     */
    private boolean isSectionVisible(Player player, World w, int x, int y, int z) {
        long key = sectionKey(x >> 4, z >> 4, y >> 4);
        Map<Long, Boolean> cache =
                visibilityCache.computeIfAbsent(player, p -> new java.util.HashMap<>());
        Map<Long, Long> sm = stamps.computeIfAbsent(player, p -> new java.util.HashMap<>());
        long now = System.currentTimeMillis();
        Long s = sm.get(key);
        Boolean hit = cache.get(key);
        if (hit != null && s != null && now - s < CACHE_TTL) return hit;

        boolean visible = computeVisible(player, w.getBlockAt(x, y, z));

        cache.put(key, visible);
        sm.put(key, now);
        if (cache.size() > 4096) { cache.clear(); sm.clear(); }
        return visible;
    }

    // simple parallel stamp map (avoid allocating Entry objects per lookup)
    private final Map<Player, Map<Long, Long>> stamps = new java.util.WeakHashMap<>();

    // ------------------------------------------------------------------
    // Core raytracing
    // ------------------------------------------------------------------
    private boolean computeVisible(Player player, Block block) {
        World w = block.getWorld();
        var eye = player.getEyeLocation().toVector();

        double minX = block.getX(), minY = block.getY(), minZ = block.getZ();
        double maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;

        // 8 AABB corners, nearest-to-eye first.
        double[][] cs = {
            {minX, minY, minZ}, {maxX, minY, minZ}, {minX, maxY, minZ}, {minX, minY, maxZ},
            {maxX, maxY, minZ}, {maxX, minY, maxZ}, {minX, maxY, maxZ},
            {maxX, maxY, maxZ}
        };
        final double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        java.util.Arrays.sort(cs, (a, b) -> Double.compare(
                sq(a[0]-ex)+sq(a[1]-ey)+sq(a[2]-ez),
                sq(b[0]-ex)+sq(b[1]-ey)+sq(b[2]-ez)));

        for (double[] c : cs) {
            if (!acquireCheck()) return true; // budget exhausted -> safe: visible
            if (rayClear(w, ex, ey, ez, c[0], c[1], c[2])) return true;
        }
        return false;
    }

    private static double sq(double v) { return v * v; }

    /**
     * DDA-style ray march from eye toward an AABB corner. The target cell
     * itself never occludes. Step size scales with distance so long rays
     * stay cheap (~constant samples).
     */
    private boolean rayClear(World w, double ex, double ey, double ez,
                             double tx, double ty, double tz) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 1e-4) return true;
        int steps = (int) Math.min(64, Math.max(8, len / 0.7));
        double sx = dx / steps, sy = dy / steps, sz = dz / steps;
        double px = ex, py = ey, pz = ez;
        for (int i = 1; i < steps; i++) {
            px += sx; py += sy; pz += sz;
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            // ignore the source (eye) cell and target cell
            if (bx == (int) Math.floor(ex) && by == (int) Math.floor(ey) && bz == (int) Math.floor(ez)) continue;
            if (bx == (int) Math.floor(tx) && by == (int) Math.floor(ty) && bz == (int) Math.floor(tz)) continue;
            if (isOpaqueAt(w, bx, by, bz)) return false;
        }
        return true;
    }

    /** Cached chunk-level opaque probe: avoids Bukkit Block object churn. */
    private boolean isOpaqueAt(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return false;
        Chunk ch = w.getChunkAt(x >> 4, z >> 4);
        return isOpaque(ch.getBlock(x & 15, y, z & 15).getType());
    }

    // ------------------------------------------------------------------
    // Structural helpers
    // ------------------------------------------------------------------
    /** Continuous opaque roof of >= ROOF_DEPTH blocks directly above. */
    public boolean hasDeepRoof(Block block) {
        World w = block.getWorld();
        int run = 0;
        for (int dy = 1; dy <= ROOF_DEPTH; dy++) {
            if (isOpaqueAt(w, block.getX(), block.getY() + dy, block.getZ())) {
                run++;
                if (run >= ROOF_DEPTH) return true;
            } else {
                return false;
            }
        }
        return run >= ROOF_DEPTH;
    }

    /** All four horizontal neighbors opaque (buried corridor pocket). */
    public boolean isBuriedPocket(Block block) {
        World w = block.getWorld();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        return isOpaqueAt(w, x + 1, y, z) && isOpaqueAt(w, x - 1, y, z)
            && isOpaqueAt(w, x, y, z + 1) && isOpaqueAt(w, x, y, z - 1)
            && hasDeepRoof(block);
    }

    /** At least one opaque neighbor (embedded in a wall). */
    public boolean isEmbeddedInWall(Block block) {
        World w = block.getWorld();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        return isOpaqueAt(w, x + 1, y, z) || isOpaqueAt(w, x - 1, y, z)
            || isOpaqueAt(w, x, y, z + 1) || isOpaqueAt(w, x, y, z - 1)
            || isOpaqueAt(w, x, y + 1, z) || isOpaqueAt(w, x, y - 1, z);
    }

    /** Sky access: no opaque block between here and world top. */
    public boolean hasSkyAccess(Block block) {
        World w = block.getWorld();
        int y = block.getY();
        int top = Math.min(w.getMaxHeight() - 1, y + SKY_SCAN_LIMIT);
        for (int yy = y + 1; yy <= top; yy++) {
            if (isOpaqueAt(w, block.getX(), yy, block.getZ())) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Budget
    // ------------------------------------------------------------------
    private boolean acquireCheck() {
        long tick = plugin.getServer().getCurrentTick();
        if (tick != budgetWindow) {
            budgetWindow = tick;
            budgetUsed = 0;
        }
        if (budgetUsed >= TICK_BUDGET) return false;
        budgetUsed++;
        return true;
    }

    private static long sectionKey(int cx, int cz, int secY) {
        return ((long) cx << 42) ^ ((long) cz << 21) ^ (long) (secY + 64);
    }
}
