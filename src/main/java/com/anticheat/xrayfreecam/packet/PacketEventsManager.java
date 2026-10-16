package com.anticheat.xrayfreecam.packet;

import com.anticheat.xrayfreecam.XRayFreecamPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.Plugin;

/**
 * v1.2.0: embeds the PacketEvents API into this plugin (no separate
 * packetevents plugin required). The API is built and initialized in
 * onEnable and cleanly shut down in onDisable.
 */
public final class PacketEventsManager {

    private final XRayFreecamPlugin plugin;
    private boolean available = false;
    private PacketSubstitutionListener substitutionListener;

    public PacketEventsManager(XRayFreecamPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Must be called BEFORE Bukkit listeners are registered (early onEnable),
     * because PacketEvents needs to inject its Netty handlers before players
     * can connect.
     */
    public void init() {
        try {
            // If a standalone packetevents plugin is installed it has already
            // set up the API — reuse it instead of double-injecting.
            Plugin standalone = plugin.getServer().getPluginManager().getPlugin("packetevents");
            if (standalone != null && standalone.isEnabled()) {
                try {
                    if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
                        available = true;
                        plugin.getLogger().info("Using external packetevents plugin " +
                                standalone.getDescription().getVersion() + ".");
                        return;
                    }
                } catch (Throwable ignored) {
                    // API not set -> build our own below.
                }
            }

            var api = SpigotPacketEventsBuilder.build(plugin);
            api.getSettings().checkForUpdates(false);
            api.init();
            available = api.isInitialized();
            if (available) {
                ServerVersion v = api.getServerManager().getVersion();
                plugin.getLogger().info("Embedded PacketEvents initialized. Server protocol: " + v);
            } else {
                plugin.getLogger().severe("PacketEvents failed to initialize - falling back to " +
                        "send-distance occlusion only (block substitution disabled).");
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("PacketEvents init error: " + t +
                    " - block substitution disabled, send-distance occlusion still active.");
            available = false;
        }
    }

    /** Register the block-substitution packet listener (after init()). */
    public void registerListeners() {
        if (!available) return;
        try {
            substitutionListener = new PacketSubstitutionListener(plugin);
            PacketEvents.getAPI().getEventManager().registerListener(substitutionListener);
            plugin.getLogger().info("Packet-level block substitution ENABLED " +
                    "(chunk data + block changes outside line of sight never reach clients).");
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to register packet listener: " + t);
            available = false;
        }
    }

    public void shutdown() {
        try {
            if (substitutionListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(substitutionListener);
                substitutionListener = null;
            }
            // Only shut down the API we built ourselves; an external plugin
            // manages its own lifecycle.
            if (available && plugin.getServer().getPluginManager().getPlugin("packetevents") == null) {
                PacketEvents.getAPI().terminate();
            }
        } catch (Throwable ignored) {
        }
        available = false;
    }

    /** Once-per-minute error summary from the packet listener (silent otherwise). */
    public void reportErrors() {
        if (substitutionListener != null) substitutionListener.reportErrors();
    }

    /** Drop per-player sanitizer caches when someone quits. */
    public void onPlayerQuit(java.util.UUID id) {
        if (substitutionListener != null) substitutionListener.clearCache(id);
    }

    public boolean isAvailable() {
        return available;
    }
}
