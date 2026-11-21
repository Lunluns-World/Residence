package com.bekvon.bukkit.residence.listeners;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.protection.FlagPermissions;

import net.Zrips.CMILib.Items.CMIMaterial;

public class ResidenceListener1_10 implements Listener {

    private Residence plugin;

    public ResidenceListener1_10(Residence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityHotFloorDamage(EntityDamageEvent event) {

        Entity ent = event.getEntity();

        if (FlagPermissions.shouldIgnoreCheck(Flags.hotfloor, event.getEntity())) {
            return;
        }

        Block damager = event instanceof EntityDamageByBlockEvent ? ((EntityDamageByBlockEvent) event).getDamager() : null;
        boolean magmaBlock = damager != null && CMIMaterial.get(damager.getType()) == CMIMaterial.MAGMA_BLOCK;
        if ((magmaBlock || "HOT_FLOOR".equals(event.getCause().name())) && !FlagPermissions.has(ent.getLocation(), Flags.hotfloor, true))
            event.setCancelled(true);
    }
}
