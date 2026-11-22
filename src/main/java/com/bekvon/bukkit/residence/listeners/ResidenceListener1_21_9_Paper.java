package com.bekvon.bukkit.residence.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;

public class ResidenceListener1_21_9_Paper implements Listener {

    private Residence plugin;

    public ResidenceListener1_21_9_Paper(Residence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCopperGolemInteract(ItemTransportingEntityValidateTargetEvent event) {

        if (!event.isAllowed())
            return;

        if (plugin.isDisabledWorldListener(event.getEntity())) {
            return;
        }
        if (event.getEntityType() != EntityType.COPPER_GOLEM)
            return;

        Location blockLoc = event.getBlock().getLocation();
        ClaimedResidence targetRes = ClaimedResidence.getByLoc(blockLoc);
        FlagPermissions perms = (targetRes != null)
                ? targetRes.getPermissions()
                : plugin.getWorldFlags().getPerms(blockLoc.getWorld());

        if (Flags.golemopenchest.isGlobalyEnabled()
                && !perms.has(Flags.golemopenchest, perms.has(Flags.container, true))) {
            event.setAllowed(false);
            return;
        }

        Location originLoc = event.getEntity().getOrigin();
        ClaimedResidence originRes = originLoc != null ? ClaimedResidence.getByLoc(originLoc) : null;
        ClaimedResidence currentRes = ClaimedResidence.getByLoc(event.getEntity().getLocation());

        if (targetRes != null) {
            boolean sameAsOrigin = originRes != null && (originRes == targetRes || originRes.isOwner(targetRes.getOwner()));
            boolean sameAsCurrent = currentRes != null && (currentRes == targetRes || currentRes.isOwner(targetRes.getOwner()));
            if ((!sameAsOrigin || !sameAsCurrent) && Flags.container.isGlobalyEnabled()
                    && !targetRes.getPermissions().has(Flags.container, true)) {
                event.setAllowed(false);
                return;
            }
        }
        if (originRes != null && (targetRes == null || (originRes != targetRes && !originRes.isOwner(targetRes.getOwner())))) {
            if (Flags.container.isGlobalyEnabled() && !originRes.getPermissions().has(Flags.container, true)) {
                event.setAllowed(false);
                return;
            }
        }
    }

    // Prevent external copper golems from forming statues inside Residence
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCopperGolemStatueForm(EntityChangeBlockEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.build, block)) {
            return;
        }
        if (event.getEntityType() != EntityType.COPPER_GOLEM)
            return;

        if (event.getTo() != Material.OXIDIZED_COPPER_GOLEM_STATUE)
            return;

        ClaimedResidence statueRes = ClaimedResidence.getByLoc(block.getLocation());
        if (statueRes == null)
            return;

        Location entSpawnLoc = event.getEntity().getOrigin();
        if (entSpawnLoc != null) {

            ClaimedResidence entSpawnRes = ClaimedResidence.getByLoc(entSpawnLoc);

            if (entSpawnRes != null && (entSpawnRes == statueRes || entSpawnRes.isOwner(statueRes.getOwner())))
                return;

        }

        if (statueRes.getPermissions().has(Flags.build, true))
            return;

        event.setCancelled(true);

    }
}
