package com.anticheat.xrayfreecam.packet;

import com.anticheat.xrayfreecam.OreRegistry;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * v1.2.2: ore/cave-focused block substitution via PacketEvents.
 *
 * What reaches the client now (fixes "антифрикам не работает вовсе"):
 *   - Every candidate block (ores, gems, spawners, storage...) that the
 *     player cannot currently see is remapped to STONE in the outgoing
 *     packet palette — X-Ray texture packs show nothing but stone.
 *   - Naturally generated cave/mineshaft AIR below the player's feet is
 *     remapped to STONE too (via a dedicated air->stone palette entry), so
 *     freecam flying under ground reveals no tunnels or shafts. Air above
 *     eye level stays open — looking into a cave from its entrance works.
 *   - Plain terrain (stone/dirt/deepslate/gravel) is sent UNCHANGED.
 *   - Sections farther than HIDE_RADIUS from the real player position are
 *     hidden wholesale (no scanning at all) — this is what defeats freecam
 *     flight away from the body without spending any CPU on raytraces.
 *
 * Console & performance contract (v1.2.2 fixes):
 *   - ZERO logging on the per-packet path. The old build printed a warning
 *     for every sanitized chunk/block update of every player and flooded
 *     the console. Errors are counted and summarized once per minute by
 *     {@link #reportErrors()} only when they actually happened.
 *   - Per-section decision cache (~0.7 s TTL): chunks are resent constantly
 *     while the player moves; recomputing raytraces each time froze the
 *     main thread (v1.2.1 lag). A cache hit rewrites palettes with zero
 *     world access.
 *   - Strict per-tick raytrace budget bounds worst-case cost.
 */
public class PacketSubstitutionListener extends PacketListenerAbstract {

    /** Sections farther than this (blocks) from the player are hidden blindly. */
    private static final double HIDE_RADIUS = 48.0;
    /** Max sampled positions per section for the candidate scan. */
    private static final int MAX_CHECKS = 48;
    /** Random cave-air probes per underground section. */
    private static final int CAVE_PROBES = 10;
    /** Max visibility (raytrace) tests per server tick across all packets. */
    private static final int TICK_BUDGET = 512;
    /** Section decision cache TTL in ms. */
    private static final long CACHE_TTL = 700L;

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

    // --- per-player section decision cache (weak keys: auto-cleanup on quit) ---
    private static final class Entry {
        boolean hideAll;      // whole section (incl. air) becomes stone
        boolean[] hideIdx;    // per-palette-index hide decisions (nullable)
        int airToStone;       // >0: rewrite storage index 0 (air) to this id
        long stamp;

        boolean hasWork(int stoneId) {
            if (hideAll || airToStone > 0) return true;
            if (hideIdx != null) {
                for (boolean b : hideIdx) if (b) return true;
            }
            return false;
        }
    }

    /**
     * Player references inside packet handlers must never be strong map keys
     * (leak on quit). WeakHashMap<Player,...> is fine because Bukkit Player
     * implements equals/hashCode consistently with identity while online.
     */
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

        org.bukkit.Location ploc = player.getLocation();
        double px = ploc.getX(), py = ploc.getY(), pz = ploc.getZ();
        Map<Long, Entry> cache = sectionCache.computeIfAbsent(player, k -> new HashMap<>());
        long now = System.currentTimeMillis();

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
                if (e.hasWork(stoneId) && applyEntry(dp, lp, e, stoneId)) modified = true;
                continue;
            }

            double dx = baseX + 8 - px, dz = baseZ + 8 - pz;
            double dy = baseY + 8 - py;
            double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);

            Entry ne = new Entry();
            ne.stamp = now;
            if (dist > HIDE_RADIUS) {
                // Freecam / far area: hide everything unconditionally.
                ne.hideAll = true;
            } else {
                sanitizeSection(dp, lp, world, baseY, baseX, baseZ, player, stoneId, ne);
            }
            cache.put(key, ne);
            if (ne.hasWork(stoneId) && applyEntry(dp, lp, ne, stoneId)) modified = true;
        }

        if (modified) wrapper.write();
    }

    private static long sectionKey(int baseX, int baseZ, int relY) {
        return ((long) baseX << 40) ^ ((long) baseZ << 20) ^ (long) relY;
    }

    /**
     * Precise pass over one near section. Decisions are stored in the entry
     * (not applied directly) so future resends reuse them for free.
     */
    private void sanitizeSection(DataPalette dp, ListPalette lp, World world,
                                 int baseY, int baseX, int baseZ,
                                 Player player, int stoneId, Entry e) {
        int size = lp.size();
        if (size <= 1) return;
        int[] ids = readPaletteIds(lp);
        if (ids == null) return;

        org.bukkit.Location ploc = player.getLocation();
        double feetY = ploc.getY();

        // Sample first occurrence position per palette index (bounded pass).
        int[] samples = new int[size];
        java.util.Arrays.fill(samples, -1);
        boolean usesAir = false;
        int found = 0;
        outer:
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = dp.get(x, y, z);
                    if (idx == 0) { usesAir = true; continue; }
                    if (samples[idx] == -1) {
                        samples[idx] = (y << 8) | (z << 4) | x;
                        if (++found >= MAX_CHECKS) break outer;
                    }
                }
            }
        }

        boolean[] hide = new boolean[size];
        for (int i = 1; i < size; i++) {
            if (samples[i] == -1) continue;
            int id = ids[i];
            if (id == 0 || id == stoneId) continue;
            Material m = materialForGlobalId(id);
            if (m == null || !OreRegistry.isCandidate(m)) continue; // plain rock: leave visible
            int p = samples[i];
            int x = p & 15, z = (p >> 4) & 15, y = (p >> 8) & 15;
            if (!acquireCheck()) break; // per-tick budget exhausted
            Block block = world.getBlockAt(baseX + x, baseY + y, baseZ + z);
            if (plugin.getBlockProtector().canSee(player, block)) continue;
            hide[i] = true;
        }
        e.hideIdx = hide;

        // Cave-air pass: natural air BELOW the player's feet gets turned to
        // stone in the packet, hiding mineshaft/cave geometry from freecam.
        // One confirmed hidden cave sample condemns the whole section's air.
        if (usesAir && baseY + 16 <= feetY + 8) {
            java.util.Random rnd = sectionRandom;
            for (int t = 0; t < CAVE_PROBES; t++) {
                if (!acquireCheck()) break;
                int x = rnd.nextInt(16), z = rnd.nextInt(16), y = rnd.nextInt(16);
                if (dp.get(x, y, z) != 0) continue; // not air
                int wy = baseY + y;
                if (wy >= feetY) continue;          // only below the player
                Block b = world.getBlockAt(baseX + x, wy, baseZ + z);
                if (b.getType() != Material.AIR) continue;
                if (!OreRegistry.isUndergroundCaveAir(b)) continue;
                if (plugin.getBlockProtector().canSee(player, b)) continue;
                // Confirmed unseen natural cave here: hide ALL air of this
                // section by rewriting storage index 0 -> stone.
                e.airToStone = stoneId;
                break;
            }
        }
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
            for (int i = 1; i < size; i++) {
                if (ids[i] != 0 && ids[i] != stoneId) {
                    ids[i] = stoneId;
                    changed = true;
                }
            }
            if (e.airToStone <= 0) e.airToStone = stoneId; // hideAll covers air too
        } else if (e.hideIdx != null) {
            for (int i = 1; i < size && i < e.hideIdx.length; i++) {
                if (e.hideIdx[i] && ids[i] != 0 && ids[i] != stoneId) {
                    ids[i] = stoneId;
                    changed = true;
                }
            }
        }

        if (e.airToStone > 0) {
            changed |= rewriteAirStorage(dp, e.airToStone);
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
        if (!isHidingTarget(block, player)) return;
        if (!acquireCheck()) return;
        if (plugin.getBlockProtector().canSee(player, block)) return;
        wrapper.setBlockID(stoneIdFor(event.getUser().getClientVersion()));
        wrapper.write();
    }

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
        WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
        Vector3i chunkPos = wrapper.getChunkPosition();
        int baseX = chunkPos.getX() << 4;
        int baseZ = chunkPos.getZ() << 4;
        World world = player.getWorld();
        boolean changed = false;
        int stoneId = stoneIdFor(event.getUser().getClientVersion());
        for (var record : wrapper.getBlocks()) {
            int wx = baseX + record.getX();
            int wy = record.getY();
            int wz = baseZ + record.getZ();
            Block block = world.getBlockAt(wx, wy, wz);
            if (!isHidingTarget(block, player)) continue;
            if (!acquireCheck()) break;
            if (plugin.getBlockProtector().canSee(player, block)) continue;
            record.setBlockId(stoneId);
            changed = true;
        }
        if (changed) wrapper.write();
    }

    /** Target = ore-like candidate, OR underground cave air below the player. */
    private boolean isHidingTarget(Block block, Player player) {
        Material type = block.getType();
        if (type == Material.AIR) {
            return block.getY() < player.getLocation().getY()
                    && OreRegistry.isUndergroundCaveAir(block);
        }
        return OreRegistry.isCandidate(type);
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
