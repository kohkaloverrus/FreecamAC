package com.anticheat.xrayfreecam.packet;

import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * v1.2.0 core: real block substitution via PacketEvents.
 *
 * Rewrites OUTGOING chunk/block packets so a cheat client physically never
 * receives the true blocks of protected areas:
 *
 *  - CHUNK_DATA: every palette-backed 16x16x16 section is scanned and any
 *    block state {@link com.anticheat.xrayfreecam.BlockProtector} deems
 *    hidden (outside line of sight / behind walls / freecam area) is remapped
 *    to STONE inside the outgoing packet's palette. The real world is NEVER
 *    modified.
 *  - BLOCK_CHANGE / MULTI_BLOCK_CHANGE: updates for positions we already hid
 *    are replaced with stone outright, otherwise an attacker could paint ores
 *    back in through block updates.
 *
 * Performance notes:
 *  - Decisions are made PER PALETTE ENTRY (a section typically holds fewer
 *    than 16 distinct states), not per block position — a whole section costs
 *    a handful of visibility checks instead of 4096 raytraces. Hidden entries
 *    are detected by sampling representative positions with a hard cap
 *    (MAX_CHECKS) so pathological palettes can never stall the network loop.
 *  - Singleton palettes (uniform sections: pure air, deepslate blobs) are
 *    skipped — mutating them would require cloning the section, and uniform
 *    sections contain nothing worth hiding.
 *  - Bukkit API access happens on the main thread (PacketEvents' default
 *    Spigot settings dispatch send events there), so world.getBlockAt() is
 *    safe here.
 */
public class PacketSubstitutionListener extends PacketListenerAbstract {

    /** Hard cap on visibility tests per 16x16x16 section. */
    private static final int MAX_CHECKS = 64;

    private final XRayFreecamPlugin plugin;

    /** Cached stone state per client version (id lookup is not free). */
    private final Map<ClientVersion, WrappedBlockState> stoneCache = new HashMap<>();

    /** Cached reflective handle to ListPalette's backing id array. */
    private volatile Field listPaletteDataField;

    public PacketSubstitutionListener(XRayFreecamPlugin plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
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

        if (type == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkData(event, player);
        } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event, player);
        } else {
            handleMultiBlockChange(event, player);
        }
    }

    private Player resolvePlayer(PacketSendEvent event) {
        try {
            java.util.UUID uuid = event.getUser().getUUID();
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
        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            var column = wrapper.getColumn();
            if (column == null || !column.isFullChunk()) return;
            BaseChunk[] sections = column.getChunks();
            if (sections == null || sections.length == 0) return;

            World world = player.getWorld();
            int minSectionY = world.getMinHeight() >> 4;
            ClientVersion cv = event.getUser().getClientVersion();
            WrappedBlockState stone = stoneFor(cv);
            if (stone == null) return;

            boolean modified = false;
            for (int s = 0; s < sections.length; s++) {
                BaseChunk chunk = sections[s];
                // Modern servers (1.18+) always send Chunk_v1_18 sections;
                // anything else stays untouched (v1.1.1 send-distance still
                // protects those legacy clients coarsely).
                if (!(chunk instanceof Chunk_v1_18 c18)) continue;
                DataPalette dataPalette = c18.getChunkData();
                if (dataPalette == null) continue;

                modified |= sanitizeSection(dataPalette, world,
                        (minSectionY + s) << 4,
                        column.getX() << 4, column.getZ() << 4,
                        player, stone);
            }

            if (modified) {
                // Re-encode the mutated payload into the outgoing buffer.
                wrapper.write();
            }
        } catch (Throwable t) {
            // Never break the packet pipeline because of sanitization errors.
            logFailure("chunk", player, t);
        }
    }

    /**
     * Palette-level rewrite of one 16x16x16 section: every distinct block
     * state whose sampled position is hidden gets its palette entry swapped
     * to stone, so ALL blocks sharing that state become stone in the sent
     * packet at once. The real world stays untouched.
     */
    private boolean sanitizeSection(DataPalette dataPalette, World world,
                                    int baseY, int baseX, int baseZ,
                                    Player player, WrappedBlockState stone) {
        if (!(dataPalette.palette instanceof ListPalette listPalette)) {
            return false; // singleton palettes: skip (see class doc)
        }
        int size = listPalette.size();
        if (size <= 1) return false;

        int[] samples = new int[size]; // local index of first occurrence, -1 = unseen
        java.util.Arrays.fill(samples, -1);

        // One pass over the section: remember the first occurrence position of
        // each palette index. Sampling caps keep the cost bounded.
        int checked = 0;
        outer:
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = dataPalette.get(x, y, z);
                    if (idx == 0) continue; // air
                    if (samples[idx] == -1) {
                        samples[idx] = (y << 8) | (z << 4) | x;
                        if (++checked >= MAX_CHECKS) break outer;
                    }
                }
            }
        }

        int stoneId = stone.getGlobalId();
        int[] ids = readPaletteIds(listPalette);
        if (ids == null) return false;

        boolean changed = false;
        Map<Location, Material> pending = plugin.getPendingReplacements(player);
        for (int idx = 1; idx < size; idx++) {
            if (samples[idx] == -1) continue; // entry unused in section
            int currentId = ids[idx];
            if (currentId == 0 || currentId == stoneId) continue;
            int p = samples[idx];
            int x = p & 15, z = (p >> 4) & 15, y = (p >> 8) & 15;
            Block block = world.getBlockAt(baseX + x, baseY + y, baseZ + z);
            if (!plugin.getBlockProtector().shouldReplaceWithStone(player, block)) continue;
            ids[idx] = stoneId; // hide every block using this palette entry
            changed = true;
            // Remember the position so late block-change packets cannot leak
            // the truth back into a hidden spot.
            pending.put(new Location(world, baseX + x, baseY + y, baseZ + z), block.getType());
        }
        return changed;
    }

    /** Reads ListPalette's private backing array via cached reflection. */
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
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Single block changes
    // ------------------------------------------------------------------
    private void handleBlockChange(PacketSendEvent event, Player player) {
        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i pos = wrapper.getBlockPosition();
            World world = player.getWorld();
            Location loc = new Location(world, pos.getX(), pos.getY(), pos.getZ());
            int stoneId = stoneIdFor(event.getUser().getClientVersion());

            if (isHiddenAlready(player, loc)) {
                // Position was hidden in chunk data: keep it stone.
                wrapper.setBlockID(stoneId);
                wrapper.write();
                return;
            }
            Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            if (plugin.getBlockProtector().shouldReplaceWithStone(player, block)) {
                wrapper.setBlockID(stoneId);
                wrapper.write();
                plugin.getPendingReplacements(player).put(loc, block.getType());
            }
        } catch (Throwable t) {
            logFailure("block-change", player, t);
        }
    }

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            Vector3i chunkPos = wrapper.getChunkPosition();
            int baseX = chunkPos.getX() << 4;
            int baseZ = chunkPos.getZ() << 4;
            World world = player.getWorld();
            int stoneId = stoneIdFor(event.getUser().getClientVersion());
            boolean changed = false;
            for (var record : wrapper.getBlocks()) {
                int wx = baseX + record.getX();
                int wy = record.getY();
                int wz = baseZ + record.getZ();
                Location loc = new Location(world, wx, wy, wz);
                if (isHiddenAlready(player, loc)) {
                    record.setBlockId(stoneId);
                    changed = true;
                    continue;
                }
                Block block = world.getBlockAt(wx, wy, wz);
                if (plugin.getBlockProtector().shouldReplaceWithStone(player, block)) {
                    record.setBlockId(stoneId);
                    changed = true;
                    plugin.getPendingReplacements(player).put(loc, block.getType());
                }
            }
            if (changed) wrapper.write();
        } catch (Throwable t) {
            logFailure("multi-block-change", player, t);
        }
    }

    private boolean isHiddenAlready(Player player, Location loc) {
        Map<Location, Material> map = plugin.getPlayerReplacements().get(player.getUniqueId());
        return map != null && map.containsKey(loc);
    }

    private void logFailure(String what, Player player, Throwable t) {
        if (plugin.isLogFreecamAttempts()) {
            plugin.getLogger().warning(what + " sanitize failed for "
                    + player.getName() + ": " + t);
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
}
