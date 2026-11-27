package com.bekvon.bukkit.residence.crossserver;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.bekvon.bukkit.residence.Residence;

public class CrossServerListener implements Listener {

    private final Residence plugin;

    public CrossServerListener(Residence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getPlayerLimitManager() != null) {
            plugin.getPlayerLimitManager().refreshPlayerAsync(event.getPlayer().getUniqueId());
        }
        if (plugin.getCleanupReclaimManager() != null) {
            plugin.getCleanupReclaimManager().restoreForPlayerAsync(event.getPlayer());
        }
        if (plugin.getCrossServerManager() != null) {
            plugin.getCrossServerManager().recordPlayerActivityAsync(event.getPlayer());
            if (plugin.getCrossServerManager().isEnabled()) {
                plugin.getCrossServerManager().handlePlayerJoinArrival(event.getPlayer());
            }
        }
        RedisSyncBus bus = plugin.getRedisSyncBus();
        if (bus != null && bus.isEnabled()) {
            bus.publish(RedisSyncBus.TOPIC_CLEANUP_RECLAIM, event.getPlayer().getUniqueId().toString());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getCrossServerManager() != null) {
            plugin.getCrossServerManager().recordPlayerActivityAsync(event.getPlayer());
        }
    }
}
