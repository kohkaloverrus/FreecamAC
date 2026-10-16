package com.anticheat.xrayfreecam.packet;

import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.SpigotConversionUtil;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * v1.2.0 core: real block substitution via PacketEvents.
 *
 * Instead of (only) shrinking the send distance, this listener rewrites the
 * OUTGOING chunk/block packets themselves, so the cheat client physically
 * never receives the true blocks of protected areas:
 *
 *  - PLAY_CHUNK_DATA (new / updated chunks): every section is scanned and any
 *    block that {@link com.anticheat.xrayfreecam.BlockProtector} deems hidden
 *    (outside line of sight / behind walls / freecam area) is replaced with
 *    stone in the packet copy. The real world is NEVER modified.
 *  - PLAY_BLOCK_CHANGE (single-block updates, e.g. mining, redstone, pistons):
 *    a spoofed update inside a protected area is cancelled outright, otherwise
 *    an attacker could paint ores back in through random block updates.
 *
 * Runs at HIGHEST priority so other plugins' edits are sanitized last.
 */
public class PacketSubstitutionListener extends PacketListenerAbstract {

    private final XRayFreecamPlugin plugin;

    /** Cached stone state per client version (id lookup is not free). */
    private final java.util.Map<ClientVersion, WrappedBlockState> stoneCache =
            new java.util.EnumMap<>(ClientVersion.class);

    public PacketSubstitutionListener(XRayFreecamPlugin plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!plugin.isProtectionEnabled()) return;

        PacketType.Play.Server type = event.getPacketType() instanceof PacketType.Play.Server p ? p : null;
        if (type == null) return;

        Player player = org.bukkit.Bukkit.getPlayer(event.getUser().getProfile().getUUID());
        if (player == null) return;
        if (plugin.getSpectatorManager().isExempt(player)) return;

        if (type == PacketType.Play.Server.PLAY_CHUNK_DATA || type == PacketType.Play.Server.PLAY_CHUNK_DATA_BULK) {
            handleChunkData(event, player);
        } else if (type == PacketType.Play.Server.PLAY_BLOCK_CHANGE) {
            handleBlockChange(event, player);
        }
    }

    // ------------------------------------------------------------------
    // Chunk packets
    // ------------------------------------------------------------------
    private void handleChunkData(PacketSendEvent event, Player player) {
        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            Column column = wrapper.getColumn();
            if (column == null || !column.isFullChunk()) return; // non-full updates carry no sections to scan cheaply
            BaseChunk[] chunks = column.getChunks();
            if (chunks == null) return;

            World world = player.getWorld();
            int minSectionY = world.getMinHeight() >> 4;
            int totalSections = chunks.length;
            ClientVersion cv = event.getUser().getClientVersion();
            WrappedBlockState stone = stoneFor(cv);
            if (stone == null) return;

            boolean modified = false;
            for (int s = 0; s < totalSections; s++) {
                BaseChunk chunk = chunks[s];
                if (chunk == null || chunk.isEmpty()) continue;

                int baseY = (minSectionY + s) << 4;
                // Skip whole sections far above/below nothing? -> simple distance cull:
                // if the entire section is beyond protection interest we still must
                // check visibility, but a quick radial test on section center helps.
                for (int x = 0; x < 16; x++) {
                    int wx = (column.getX() << 4) + x;
                    for (int z = 0; z < 16; z++) {
                        int wz = (column.getZ() << 4) + z;
                        for (int y = 0; y < 16; y++) {
                            int wy = baseY + y;
                            int id = chunk.getBlockId(x, y, z);
                            if (id == 0) continue; // air
                            WrappedBlockState state = WrappedBlockState.getByGlobalId(cv, id);
                            if (state == null || state.getType().isAir()) continue;

                            Block block = world.getBlockAt(wx, wy, wz);
                            if (plugin.getBlockProtector().shouldReplaceWithStone(player, block)) {
                                chunk.set(x, y, z, stone.readGlobalId(cv));
                                modified = true;
                                // Remember what we hid so late block-change
                                // packets cannot leak the truth back in.
                                plugin.getPendingReplacements(player)
                                      .put(new Location(world, wx, wy, wz), block.getType());
                            }
                        }
                    }
                }
            }

            if (modified) {
                // Re-encode the packet with our edited column.
                wrapper.setColumn(column);
                event.setChanged(true);
            }
        } catch (Throwable t) {
            // Never break packet pipeline because of sanitization errors.
            if (plugin.isLogFreecamAttempts()) {
                plugin.getLogger().warning("chunk sanitize failed for " + player.getName() + ": " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Single block changes
    // ------------------------------------------------------------------
    private void handleBlockChange(PacketSendEvent event, Player player) {
        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            var pos = wrapper.getBlockPosition();
            Location loc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
            // If we previously hid this position, drop the update entirely —
            // the client must keep seeing stone.
            var replacements = plugin.getPlayerReplacements().get(player.getUniqueId());
            if (replacements != null && replacements.containsKey(loc)) {
                event.setCancelled(true);
                return;
            }
            // Otherwise apply the same rules as for chunk data.
            Block block = player.getWorld().getBlockAt(loc);
            if (plugin.getBlockProtector().shouldReplaceWithStone(player, block)) {
                ClientVersion cv = event.getUser().getClientVersion();
                WrappedBlockState stone = stoneFor(cv);
                if (stone != null) {
                    wrapper.setBlockState(stone);
                    event.setChanged(true);
                    if (replacements != null) replacements.put(loc, block.getType());
                }
            }
        } catch (Throwable t) {
            if (plugin.isLogFreecamAttempts()) {
                plugin.getLogger().warning("block-change sanitize failed for " + player.getName() + ": " + t);
            }
        }
    }

    private WrappedBlockState stoneFor(ClientVersion cv) {
        return stoneCache.computeIfAbsent(cv, v -> {
            try {
                return SpigotConversionUtil.fromBukkitBlockData(
                        org.bukkit.Material.STONE.createNewBlockData());
            } catch (Throwable t) {
                return null;
            }
        });
    }
}
