package com.bekvon.bukkit.residence.crossserver;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.bekvon.bukkit.residence.Residence;

public class CrossServerListener implements Listener {

    private final Residence plugin;

    public CrossServerListener(Residence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            plugin.getCrossServerManager().handlePlayerJoinArrival(event.getPlayer());
        }
    }
}
