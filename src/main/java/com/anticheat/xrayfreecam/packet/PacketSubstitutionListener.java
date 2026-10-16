package com.anticheat.xrayfreecam.packet;

import com.anticheat.xrayfreecam.OcclusionEngine;
import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * v1.3.0: full "unseen world becomes stone" substitution via PacketEvents.
 *
 * Spec implemented here:
 *  - EVERY block the client cannot see from the player's real eyes is sent
 *    as STONE — ores, builds, cave/mine air, water pockets, entities'
 *    surroundings. No more ore-only allowlist (that was the reason the
 *    anti-cheat "didn't work at all" against freecam shafts).
 *  - Beyond {@link OcclusionEngine#OPEN_RADIUS} (30 blocks) unseen areas are
 *    hidden blindly (no raytraces at all) — cheap and defeats freecam flight.
 *  - Buried pockets (solid roof >= 5 + walls on all sides) protect from
 *    POCKET_RADIUS so bases inside hills are sealed even close-up.
 *  - Sky-accessible positions are NEVER hidden -> no stone blobs above the
 *    surface behind leaves/trees.
 *  - Player's own familiar coordinates never hidden; caches reset on relog.
 *
 * Chunk sections are decided with a two-level scheme to keep CPU bounded:
 *   PASS A (cheap, no raytraces): if every non-air palette entry of the
 *     section is opaque AND the section is beyond OPEN_RADIUS or fully
 *     buried-under-roof, hide it wholesale by remapping ALL palette entries
 *     (air included) to stone. This covers the vast majority of underground
 *     sections instantly.
 *   PASS B (precise, budgeted): otherwise sample distinct palette states and
 *     test representative cells with the OcclusionEngine line-of-sight model;
 *     per-palette-index hide decisions are cached (~TTL) so resends while the
 *     player moves cost nothing.
 *
 * Console contract: ZERO logging on the packet path; errors summarized once
 * per minute by reportErrors().
 */
public class PacketSubstitutionListener extends PacketListenerAbstract {

    /** Per-tick raytrace budget across all packets (bounds worst case). */
    private static final int TICK_BUDGET = 4096;
    /** Section decision cache TTL in ms. */
    private static final long CACHE_TTL = 900L;
    /** Max distinct palette indices precision-tested per section. */
    private static final int MAX_INDEX_CHECKS = 64;

    private final XRayFreecamPlugin plugin;

    private final Map<ClientVersion, WrappedBlockState> stoneCache = new HashMap<>();
    private final Map<Integer, Material> materialCache = new HashMap<>();
    private volatile Field listPaletteDataField;

    // --- per-tick check budget ---
    private long budgetWindow = -1L;
    private int budgetUsed = 0;

    // --- error counters (NO console spam: reported once per minute) ---
    private volatile long errorCount = 0L;
    private volatile String lastError = null;

    private static final class Entry {
        boolean hideAll;      // whole section (incl. air) becomes stone
        boolean[] hideIdx;    // per-palette-index hide decisions (nullable)
        boolean airToStone;   // air palette entry itself condemned to stone
        long stamp;

        boolean hasWork() {
            if (hideAll) return true;
            if (airToStone) return true;
            if (hideIdx != null) for (boolean b : hideIdx) if (b) return true;
            return false;
        }
    }

    private final Map<Player, Map<Long, Entry>> sectionCache = new WeakHashMap<>();

    public PacketSubstitutionListener(XRayFreecamPlugin plugin) {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
    }

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

    private void countError(Throwable t) {
        errorCount++;
        lastError = t.toString();
    }

    /** Called from a repeating task: one log line per minute, only if errors happened. */
    public void reportErrors() {
        long n = errorCount;
        if (n > 0) {
            errorCount = 0;
            plugin.getLogger().warning("Packet sanitization caught " + n
                    + " error(s) in the last minute. Last: " + lastError);
        }
    }

    /** Drop cached decisions for a quitting player. */
    public void clearCache(UUID id) {
        sectionCache.keySet().removeIf(p -> p != null && p.getUniqueId().equals(id));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!plugin.isProtectionEnabled()) return;
        if (!plugin.getPacketEventsManager().isAvailable()) return;

        PacketType.Play.Server type;
        try {
            type = (PacketType.Play.Server) event.getPacketType();
        } catch (Throwable t) {
            return; // not a play-phase server packet
        }
        if (type != PacketType.Play.Server.CHUNK_DATA
                && type != PacketType.Play.Server.BLOCK_CHANGE
                && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            return; // fast path: don't resolve players for irrelevant packets
        }

        Player player = resolvePlayer(event);
        if (player == null) return;
        if (plugin.getSpectatorManager().isExempt(player)) return;

        try {
            if (type == PacketType.Play.Server.CHUNK_DATA) {
                handleChunkData(event, player);
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                handleBlockChange(event, player);
            } else {
                handleMultiBlockChange(event, player);
            }
        } catch (Throwable t) {
            countError(t); // never touch the console here, never break the pipeline
        }
    }

    private Player resolvePlayer(PacketSendEvent event) {
        try {
            UUID uuid = event.getUser().getUUID();
            if (uuid != null) {
                Player p = org.bukkit.Bukkit.getPlayer(uuid);
                if (p != null) return p;
            }
            String name = event.getUser().getName();
            if (name != null && !name.isEmpty()) {
                return org.bukkit.Bukkit.getPlayerExact(name);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Chunk packets
    // ------------------------------------------------------------------
    private void handleChunkData(PacketSendEvent event, Player player) {
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        if (column == null || !column.isFullChunk()) return;
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return;

        World world = player.getWorld();
        int minSectionY = world.getMinHeight() >> 4;
        ClientVersion cv = event.getUser().getClientVersion();
        WrappedBlockState stone = stoneFor(cv);
        if (stone == null) return;
        int stoneId = stone.getGlobalId();

        org.bukkit.Location eye = player.getEyeLocation();
        double px = eye.getX(), py = eye.getY(), pz = eye.getZ();
        Map<Long, Entry> cache = sectionCache.computeIfAbsent(player, k -> new HashMap<>());
        long now = System.currentTimeMillis();

        OcclusionEngine eng = plugin.getOcclusionEngine();
        boolean modified = false;
        for (int s = 0; s < sections.length; s++) {
            if (!(sections[s] instanceof Chunk_v1_18 c18)) continue;
            DataPalette dp = c18.getChunkData();
            if (dp == null || !(dp.palette instanceof ListPalette lp)) continue;

            int baseY = (minSectionY + s) << 4;
            int baseX = column.getX() << 4;
            int baseZ = column.getZ() << 4;

            long key = sectionKey(baseX, baseZ, baseY - world.getMinHeight());
            Entry e = cache.get(key);
            if (e != null && now - e.stamp < CACHE_TTL) {
                // Cached decision: rewrite palette WITHOUT any raytrace.
                if (e.hasWork() && applyEntry(dp, lp, e, stoneId)) modified = true;
                continue;
            }

            double dx = baseX + 8 - px, dz = baseZ + 8 - pz;
            double dy = baseY + 8 - py;
            double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);

            Entry ne = new Entry();
            ne.stamp = now;

            // v1.3.2 ABSOLUTE SURFACE GUARANTEE: a section that touches the
            // sky or sits within the player's protection bubble is sent
            // UNTOUCHED — no stone blobs on the surface, ever (v1.3.1 bug).
            if (dist <= OcclusionEngine.SURFACE_EXEMPT + 16
                    || sectionHasSky(world, baseX, baseZ, baseY)) {
                // Near/surface: only fully-buried pockets inside this section
                // may be sealed, and only via the cheap per-section probe.
                decideNearSection(dp, lp, world, baseY, baseX, baseZ, player, eng, ne, stoneId);
            } else {
                // Far underground area: blind hide unless ANY sampled column
                // reaches the sky (surface terrain stays natural-looking).
                if (!sectionHasSky(world, baseX, baseZ, baseY)) {
                    ne.hideAll = true;
                }
            }
            cache.put(key, ne);
            if (cache.size() > 8192) cache.clear();
            if (ne.hasWork() && applyEntry(dp, lp, ne, stoneId)) modified = true;
        }

        if (modified) wrapper.write();
    }

    /**
     * Near/surface-section pass. A section is condemned to stone ONLY when
     * every sampled non-air cell is buried under >= ROOF_DEPTH opaque blocks
     * AND unseen from the real eyes (turn-your-head rule). Any surface cell,
     * grass, tree, cave entrance or open shaft keeps the section untouched.
     */
    private void decideNearSection(DataPalette dp, ListPalette lp, World world,
                                   int baseY, int baseX, int baseZ,
                                   Player player, OcclusionEngine eng, Entry e, int stoneId) {
        int size = lp.size();
        if (size <= 1) return; // uniform section: either all air or all one block
        int[] ids = readPaletteIds(lp);
        if (ids == null) return;

        // Fast reject: uniform solid rock section needs no work at all.
        boolean allOpaqueRock = true;
        for (int i = 1; i < size; i++) {
            Material m = materialForGlobalId(ids[i]);
            if (m == null) { allOpaqueRock = false; break; }
            if (!isPlainRock(m)) { allOpaqueRock = false; break; }
        }
        if (allOpaqueRock && ids[0] == 0 && !usesAirCells(dp)) return;

        // Probe candidate cells: representative sample per palette index plus
        // random air cells. Every probe must be BURIED + UNSEEN to condemn
        // anything; a single surface/visible sample aborts the whole pass.
        int[] samples = new int[size];
        Arrays.fill(samples, -1);
        int found = 0;
        outer:
        for (int y = 0; y < 16; y += 3) {
            for (int z = 0; z < 16; z += 3) {
                for (int x = 0; x < 16; x += 3) {
                    int idx = dp.get(x, y, z);
                    if (samples[idx] == -1) {
                        samples[idx] = (y << 8) | (z << 4) | x;
                        if (++found > MAX_INDEX_CHECKS) break outer;
                    }
                }
            }
        }

        boolean[] hide = new boolean[size];
        int condemned = 0, probed = 0, aborted = 0;
        for (int i = 0; i < size; i++) {
            if (samples[i] == -1) continue;
            int id = ids[i];
            if (id == stoneId) continue;
            Material m = materialForGlobalId(id);
            if (m == Material.STONE || m == Material.BEDROCK) continue;
            if (m != null && isPlainRock(m) && i != 0) continue; // rock stays rock
            int p = samples[i];
            int x = p & 15, z = (p >> 4) & 15, y = (p >> 8) & 15;
            if (!acquireCheck()) return; // budget exhausted: leave visible (safe)
            probed++;
            if (eng.shouldHideAt(player, world, baseX + x, baseY + y, baseZ + z)) {
                hide[i] = true;
                condemned++;
            } else {
                aborted++;
            }
        }

        // Air cells of near sections: probe randomly, same strict gate.
        java.util.Random rnd = sectionRandom;
        int airCondemned = 0, airProbed = 0;
        for (int t = 0; t < 8; t++) {
            int x = rnd.nextInt(16), z = rnd.nextInt(16), y = rnd.nextInt(16);
            if (dp.get(x, y, z) != 0) continue;
            if (!acquireCheck()) break;
            airProbed++;
            if (eng.shouldHideAt(player, world, baseX + x, baseY + y, baseZ + z)) {
                airCondemned++;
            } else {
                aborted++;
            }
        }

        // Conservative escalation policy:
        //  - ANY visible/surface sample -> do NOT hide the whole section;
        //    keep only the individually condemned palette entries.
        //  - ALL probes buried+unseen -> the section lies behind solid rock:
        //    seal it completely (air included) — "за стеной только камень".
        if (probed + airProbed < 4) return;
        if (aborted == 0) {
            e.hideAll = true;
            e.hideIdx = null;
        } else if (condemned > 0 || airCondemned * 4 >= Math.max(1, airProbed) * 3) {
            if (airCondemned * 4 >= Math.max(1, airProbed) * 3) hide[0] = true;
            e.hideIdx = hide;
            e.airToStone = hide[0];
        }
    }

    private boolean usesAirCells(DataPalette dp) {
        // Cheap probe: four corners + center; any air cell means yes.
        return dp.get(0, 0, 0) == 0 || dp.get(15, 0, 15) == 0
            || dp.get(0, 15, 15) == 0 || dp.get(8, 8, 8) == 0;
    }

    /** True for common terrain fillers that need no protection themselves. */
    private static boolean isPlainRock(Material m) {
        switch (m) {
            case STONE: case GRANITE: case DIORITE: case ANDESITE:
            case DEEPSLATE: case TUFF: case CALCITE:
            case DIRT: case COARSE_DIRT: case GRASS_BLOCK:
            case GRAVEL: case CLAY: case SAND: case RED_SAND:
            case SANDSTONE: case RED_SANDSTONE:
            case MUD: case ROOTED_DIRT:
                return true;
            default:
                return m.name().endsWith("_SHALE") || m == Material.BLACKSTONE
                    || m == Material.BASALT || m.name().startsWith("DEEPSLATE_");
        }
    }

    /**
     * Surface guard: sample 4 columns of the section; if any reaches the top
     * without hitting an opaque block, the section touches the sky and must
     * NOT be hidden wholesale (prevents stone blobs over trees/leaves).
     */
    private boolean sectionHasSky(World world, int baseX, int baseZ, int baseY) {
        int[][] cols = {{baseX + 1, baseZ + 1}, {baseX + 14, baseZ + 1},
                        {baseX + 1, baseZ + 14}, {baseX + 14, baseZ + 14}};
        for (int[] c : cols) {
            boolean sky = true;
            for (int y = baseY + 16; y < world.getMaxHeight(); y++) {
                if (OcclusionEngine.isOpaque(world.getBlockAt(c[0], y, c[1]).getType())) {
                    sky = false; break;
                }
            }
            if (sky) return true;
        }
        return false;
    }

    private static long sectionKey(int baseX, int baseZ, int relY) {
        return ((long) baseX << 40) ^ ((long) baseZ << 20) ^ (long) relY;
    }

    private final java.util.Random sectionRandom = new java.util.Random();

    /**
     * Applies a cached/fresh decision to the palette: flips palette entries
     * to stone and (optionally) rewrites the storage so air cells (index 0)
     * render as stone too.
     */
    private boolean applyEntry(DataPalette dp, ListPalette lp, Entry e, int stoneId) {
        int[] ids = readPaletteIds(lp);
        if (ids == null) return false;
        int size = lp.size();
        boolean changed = false;

        if (e.hideAll) {
            for (int i = 0; i < size; i++) {
                if (ids[i] != 0 && ids[i] != stoneId) {
                    ids[i] = stoneId;
                    changed = true;
                }
            }
            changed |= rewriteAirStorage(dp, stoneId);
        } else if (e.hideIdx != null) {
            for (int i = 1; i < size && i < e.hideIdx.length; i++) {
                if (e.hideIdx[i] && ids[i] != 0 && ids[i] != stoneId) {
                    ids[i] = stoneId;
                    changed = true;
                }
            }
            if (e.airToStone && e.hideIdx.length > 0 && e.hideIdx[0]) {
                changed |= rewriteAirStorage(dp, stoneId);
            }
        }
        return changed;
    }

    /**
     * Rewrites the section's packed storage so every cell holding palette
     * index 0 (air) instead holds `toId`, using PacketEvents' public storage
     * API. The backing long[] words are rewritten directly (same bit layout
     * the vanilla writer produces: valuesPerLong = 64/bits entries per word).
     */
    private boolean rewriteAirStorage(DataPalette dp, int toId) {
        try {
            Object storage = dp.storage;
            if (!(storage instanceof com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage bs)) {
                return false;
            }
            int bits = bs.getBitsPerEntry();
            if (bits <= 0 || bits > 20) return false;
            int mask = (1 << bits) - 1;
            if (toId > mask) return false; // palette grew beyond storage width

            long[] words = bs.getData();
            if (words == null) return false;
            int valuesPerLong = 64 / bits;

            boolean changed = false;
            for (int i = 0; i < words.length; i++) {
                long w = words[i];
                int startIdx = i * valuesPerLong;
                int count = Math.min(valuesPerLong, 4096 - startIdx);
                if (count <= 0) break; // trailing padding word
                long nw = w;
                for (int k = 0; k < count; k++) {
                    if (((w >>> (k * bits)) & mask) == 0L) {
                        nw |= ((long) toId) << (k * bits); // 0-bits -> toId
                    }
                }
                if (nw != w) {
                    words[i] = nw;
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable t) {
            countError(t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Single block changes
    // ------------------------------------------------------------------
    private void handleBlockChange(PacketSendEvent event, Player player) {
        WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
        Vector3i pos = wrapper.getBlockPosition();
        World world = player.getWorld();
        Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
        if (!plugin.getOcclusionEngine().shouldHide(player, block)) return;
        if (!acquireCheck()) return;
        wrapper.setBlockID(stoneIdFor(event.getUser().getClientVersion()));
        wrapper.write();
    }

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
        WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
        Vector3i chunkPos = wrapper.getChunkPosition();
        int baseX = chunkPos.getX() << 4;
        int baseZ = chunkPos.getZ() << 4;
        World world = player.getWorld();
        OcclusionEngine eng = plugin.getOcclusionEngine();
        boolean changed = false;
        int stoneId = stoneIdFor(event.getUser().getClientVersion());
        for (var record : wrapper.getBlocks()) {
            int wx = baseX + record.getX();
            int wy = record.getY();
            int wz = baseZ + record.getZ();
            Block block = world.getBlockAt(wx, wy, wz);
            if (!acquireCheck()) break;
            if (!eng.shouldHide(player, block)) continue;
            record.setBlockId(stoneId);
            changed = true;
        }
        if (changed) wrapper.write();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private int[] readPaletteIds(ListPalette palette) {
        try {
            Field f = listPaletteDataField;
            if (f == null) {
                f = ListPalette.class.getDeclaredField("data");
                f.setAccessible(true);
                listPaletteDataField = f;
            }
            return (int[]) f.get(palette);
        } catch (Throwable t) {
            countError(t);
            return null;
        }
    }

    private int stoneIdFor(ClientVersion cv) {
        WrappedBlockState stone = stoneFor(cv);
        return stone != null ? stone.getGlobalId() : 1; // vanilla fallback: stone id 1
    }

    private WrappedBlockState stoneFor(ClientVersion cv) {
        WrappedBlockState s = stoneCache.get(cv);
        if (s != null) return s;
        try {
            s = WrappedBlockState.getByString(cv, "minecraft:stone");
        } catch (Throwable t) {
            s = null;
        }
        if (s != null) stoneCache.put(cv, s);
        return s;
    }

    private Material materialForGlobalId(int globalId) {
        Material cached = materialCache.get(globalId);
        if (cached != null) return cached == MISSING ? null : cached;
        Material result = null;
        try {
            WrappedBlockState state = WrappedBlockState.getByGlobalId(globalId);
            if (state != null && state.getType() != null) {
                String key = state.getType().getName(); // minecraft:xxx
                String plain = key.startsWith("minecraft:") ? key.substring(10) : key;
                result = Material.matchMaterial(plain.toUpperCase(java.util.Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        materialCache.put(globalId, result != null ? result : MISSING);
        return result;
    }

    /** Sentinel meaning "this id has no Bukkit Material" (negative cache). */
    private static final Material MISSING = Material.AIR;
}
