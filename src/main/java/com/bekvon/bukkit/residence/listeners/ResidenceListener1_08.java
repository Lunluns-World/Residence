package com.bekvon.bukkit.residence.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;

import net.Zrips.CMILib.Version.Version;

public class ResidenceListener1_08 implements Listener {

    private final Residence plugin;

    public ResidenceListener1_08(Residence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractAtArmoStand(PlayerInteractAtEntityEvent event) {

        Entity entity = event.getRightClicked();

        if (FlagPermissions.shouldIgnoreCheck(Flags.container, entity)) {
            return;
        }
        if (!(entity instanceof ArmorStand)) {
            return;
        }
        Player player = event.getPlayer();
        if (FlagPermissions.shouldDenyAndNotify(player, entity, Flags.container, Flags.use)) {
            event.setCancelled(true);
            return;
        }

        ArmorStand stand = (ArmorStand) entity;
        ItemStack held = com.bekvon.bukkit.residence.utils.Utils.getItemInUseHand(event);
        boolean hasHeld = hasItem(held);
        int transfer = detectArmorStandTransfer(stand, held, hasHeld);
        if (transfer != 0) {
            Flags denied = ContainerTransferListener.deniedDirectTransfer(player, entity.getLocation(), transfer, false);
            if (denied != null) {
                lm.Flag_Deny.sendMessage(player, denied);
                event.setCancelled(true);
            }
        }
    }

    private static int detectArmorStandTransfer(ArmorStand stand, ItemStack held, boolean hasHeld) {
        EntityEquipment eq = stand.getEquipment();
        if (hasHeld && eq != null) {
            EquipmentSlot armorSlot = resolveArmorSlot(held.getType());
            if (armorSlot != null) {
                ItemStack existing = getArmorSlotItem(eq, armorSlot);
                return hasItem(existing)
                        ? (ContainerTransferListener.PUT | ContainerTransferListener.TAKE)
                        : ContainerTransferListener.PUT;
            }
        }
        boolean standHasAnyItem = hasArmorStandItems(stand);
        if (hasHeld) {
            return standHasAnyItem
                    ? (ContainerTransferListener.PUT | ContainerTransferListener.TAKE)
                    : ContainerTransferListener.PUT;
        }
        return standHasAnyItem ? ContainerTransferListener.TAKE : 0;
    }

    private static EquipmentSlot resolveArmorSlot(Material type) {
        if (type == null)
            return null;
        String name = type.name();
        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET") || name.equals("CARVED_PUMPKIN")
                || name.endsWith("_SKULL") || name.endsWith("_HEAD"))
            return EquipmentSlot.HEAD;
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA"))
            return EquipmentSlot.CHEST;
        if (name.endsWith("_LEGGINGS"))
            return EquipmentSlot.LEGS;
        if (name.endsWith("_BOOTS"))
            return EquipmentSlot.FEET;
        return null;
    }

    private static ItemStack getArmorSlotItem(EntityEquipment eq, EquipmentSlot slot) {
        switch (slot) {
        case HEAD:
            return eq.getHelmet();
        case CHEST:
            return eq.getChestplate();
        case LEGS:
            return eq.getLeggings();
        case FEET:
            return eq.getBoots();
        default:
            return null;
        }
    }

    private static boolean hasItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    private static boolean hasArmorStandItems(ArmorStand stand) {
        EntityEquipment eq = stand.getEquipment();
        if (eq == null)
            return false;
        for (ItemStack armor : eq.getArmorContents()) {
            if (hasItem(armor))
                return true;
        }
        if (net.Zrips.CMILib.Version.Version.isCurrentEqualOrHigher(net.Zrips.CMILib.Version.Version.v1_9_0)) {
            if (hasItem(eq.getItemInMainHand()))
                return true;
            return hasItem(eq.getItemInOffHand());
        }
        @SuppressWarnings("deprecation")
        ItemStack hand = eq.getItemInHand();
        return hasItem(hand);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplodeEvent(BlockExplodeEvent event) {

        Block sourceBlock = event.getBlock();
        // disabling event on world
        if (plugin.isDisabledWorldListener(sourceBlock)) {
            return;
        }
        // ExplosionResult.TRIGGER_BLOCK does not destroy blocks
        // it is triggered by (Enchantment: Wind Burst)
        if (Version.isCurrentEqualOrHigher(Version.v1_21_0)
                && event.getExplosionResult() == org.bukkit.ExplosionResult.TRIGGER_BLOCK) {
            ResidenceListener1_21.onWindExplode(event);
            return;
        }
        boolean shouldCheckExplode = Flags.explode.isGlobalyEnabled();
        boolean shouldCheckDestroy = Flags.destroy.isGlobalyEnabled();
        boolean shouldCheckBuild = Flags.build.isGlobalyEnabled();
        if (!shouldCheckExplode && !shouldCheckDestroy && !shouldCheckBuild) {
            return;
        }
        if (shouldCheckExplode || shouldCheckDestroy || shouldCheckBuild) {
            FlagPermissions sourceBlockPerms = FlagPermissions.getPerms(sourceBlock.getLocation());
            boolean sourceFallbackBuild = !shouldCheckBuild || sourceBlockPerms.has(Flags.build, true);
            boolean sourceAllowDestroy = shouldCheckDestroy
                    ? sourceBlockPerms.has(Flags.destroy, sourceFallbackBuild)
                    : sourceFallbackBuild;
            boolean sourceAllowExplode = shouldCheckExplode
                    ? sourceBlockPerms.has(Flags.explode, sourceAllowDestroy)
                    : sourceAllowDestroy;
            // Explosion is prohibited at the source location; cancel the event directly
            if (!sourceAllowExplode) {
                event.setCancelled(true);
                return;
            }
        }
        // Source allows explosion, so check each affected block for destruction
        event.blockList().removeIf(block -> {
            FlagPermissions blockPerms = FlagPermissions.getPerms(block.getLocation());
            boolean fallbackBuild = !shouldCheckBuild || blockPerms.has(Flags.build, true);
            boolean allowDestroy = shouldCheckDestroy
                    ? blockPerms.has(Flags.destroy, fallbackBuild)
                    : fallbackBuild;
            boolean allowExplode = shouldCheckExplode
                    ? blockPerms.has(Flags.explode, allowDestroy)
                    : allowDestroy;
            return !allowExplode;
        });
    }
}
