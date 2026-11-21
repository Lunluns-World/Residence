package com.bekvon.bukkit.residence.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.bekvon.bukkit.residence.ConfigManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.utils.Utils;

public final class ContainerTransferListener implements Listener {
    private static final int PUT = 1;
    private static final int TAKE = 2;

    private final Residence plugin;

    public ContainerTransferListener(Residence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;

        Player player = (Player) event.getWhoClicked();
        if (ignorePlayer(player))
            return;

        Inventory inventory = event.getInventory();
        int slot = event.getRawSlot();
        boolean top = slot >= 0 && slot < inventory.getSize();
        String action = event.getAction().name();
        int transfer;
        ItemStack collected = null;

        switch (action) {
        case "COLLECT_TO_CURSOR":
            collected = event.getCursor();
            if (!hasItem(collected) || collected.getAmount() >= collected.getMaxStackSize())
                return;
            transfer = TAKE;
            slot = -1;
            break;
        case "MOVE_TO_OTHER_INVENTORY":
            if (slot < 0)
                return;
            transfer = top ? TAKE : PUT;
            if (!top)
                slot = -1;
            break;
        case "HOTBAR_SWAP":
            if (!top)
                return;
            int hotbarButton = event.getHotbarButton();
            ItemStack incoming;
            if ("SWAP_OFFHAND".equals(event.getClick().name())) {
                incoming = player.getInventory().getItemInOffHand();
            } else if (hotbarButton >= 0 && hotbarButton < 9) {
                incoming = player.getInventory().getItem(hotbarButton);
            } else {
                incoming = null;
            }
            transfer = itemsSwapped(incoming, event.getCurrentItem());
            if (hotbarButton < 0 && !"SWAP_OFFHAND".equals(event.getClick().name()))
                transfer |= PUT;
            break;
        case "HOTBAR_MOVE_AND_READD":
        case "PICKUP_ALL":
        case "PICKUP_SOME":
        case "PICKUP_HALF":
        case "PICKUP_ONE":
        case "PICKUP_FROM_BUNDLE":
        case "PICKUP_ALL_INTO_BUNDLE":
        case "PICKUP_SOME_INTO_BUNDLE":
        case "DROP_ALL_SLOT":
        case "DROP_ONE_SLOT":
        case "CLONE_STACK":
            transfer = top ? TAKE : 0;
            break;
        case "PLACE_ALL":
        case "PLACE_SOME":
        case "PLACE_ONE":
        case "PLACE_FROM_BUNDLE":
        case "PLACE_ALL_INTO_BUNDLE":
        case "PLACE_SOME_INTO_BUNDLE":
            transfer = top ? PUT : 0;
            break;
        case "SWAP_WITH_CURSOR":
            transfer = top ? itemsSwapped(event.getCursor(), event.getCurrentItem()) : 0;
            break;
        case "NOTHING":
        case "DROP_ALL_CURSOR":
        case "DROP_ONE_CURSOR":
            return;
        default:
            transfer = PUT | TAKE;
            if (!top)
                slot = -1;
        }

        if (transfer == 0)
            return;
        Flags denied = deniedTransfer(player, inventory, slot, transfer, collected);
        if (denied != null) {
            event.setCancelled(true);
            lm.Flag_Deny.sendMessage(player, denied);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;

        Player player = (Player) event.getWhoClicked();
        if (ignorePlayer(player))
            return;

        Inventory inventory = event.getInventory();
        for (int slot : event.getRawSlots()) {
            if (slot < 0 || slot >= inventory.getSize())
                continue;
            Flags denied = deniedTransfer(player, inventory, slot, PUT, null);
            if (denied != null) {
                event.setCancelled(true);
                lm.Flag_Deny.sendMessage(player, denied);
                return;
            }
            if (!(inventory instanceof DoubleChestInventory))
                return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player) || !Flags.container.isGlobalyEnabled())
            return;

        Player player = (Player) event.getPlayer();
        if (ignorePlayer(player))
            return;

        Flags denied = deniedTransfer(player, event.getInventory(), -1, 0, null);
        if (denied != null) {
            event.setCancelled(true);
            lm.Flag_Deny.sendMessage(player, denied);
        }
    }

    private boolean ignorePlayer(Player player) {
        return (!Flags.container_put.isGlobalyEnabled() && !Flags.container_take.isGlobalyEnabled())
                || ResAdmin.isResAdmin(player) || player.hasMetadata("NPC");
    }

    private Flags deniedTransfer(Player player, Inventory inventory, int slot, int transfer, ItemStack collected) {
        if (inventory instanceof DoubleChestInventory) {
            DoubleChestInventory chest = (DoubleChestInventory) inventory;
            Inventory left = chest.getLeftSide();
            if (slot >= left.getSize())
                return deniedTransfer(player, chest.getRightSide(), slot - left.getSize(), transfer, collected);

            Flags denied = deniedTransfer(player, left, slot, transfer, collected);
            return denied != null || slot >= 0 ? denied : deniedTransfer(player, chest.getRightSide(), -1, transfer, collected);
        }

        if (collected != null && !inventory.containsAtLeast(collected, 1))
            return null;

        InventoryHolder holder = Utils.getHolderNoSnapshot(inventory);
        Location location;
        boolean blockContainer = holder instanceof BlockState;
        if (blockContainer) {
            BlockState block = (BlockState) holder;
            Flags useFlag = FlagPermissions.getMaterialUseFlagList().get(block.getType());
            if (useFlag != Flags.container && (useFlag != null
                    || plugin.getConfigManager().getCustomRightClick().contains(block.getType())
                    || plugin.getConfigManager().getCustomBothClick().contains(block.getType())
                    || !plugin.getConfigManager().getCustomContainers().contains(block.getType())))
                return null;
            location = block.getLocation();
        } else if (holder instanceof Vehicle) {
            location = ((Vehicle) holder).getLocation();
        } else {
            return null;
        }

        if (plugin.isDisabledWorldListener(location))
            return null;

        ClaimedResidence claim = plugin.getResidenceManager().getByLoc(location);
        if (claim == null)
            return null;

        FlagPermissions permissions = claim.getPermissions();
        boolean allowUse = !blockContainer || permissions.playerHas(player, Flags.use, true);
        boolean allowOpen = !Flags.container.isGlobalyEnabled() || permissions.playerHas(player, Flags.container, allowUse)
                || ResPerm.bypass_container.hasPermission(player, 10000L);
        if (blockContainer && Flags.container.isGlobalyEnabled() && ConfigManager.RaidEnabled && claim.getRaid().isUnderRaid()) {
            if (claim.getRaid().isAttacker(player))
                allowOpen = true;
            else if (!ConfigManager.RaidDefenderContainerUsage && claim.getRaid().isDefender(player))
                allowOpen = false;
        }

        return deniedFlag(permissions, player, allowOpen, transfer);
    }

    static Flags deniedFlag(FlagPermissions permissions, Player player, boolean allowOpen, int transfer) {
        if (!allowOpen)
            return Flags.container;
        if ((transfer & PUT) != 0 && Flags.container_put.isGlobalyEnabled() && !permissions.playerHas(player, Flags.container_put, allowOpen))
            return Flags.container_put;
        if ((transfer & TAKE) != 0 && Flags.container_take.isGlobalyEnabled() && !permissions.playerHas(player, Flags.container_take, allowOpen))
            return Flags.container_take;
        return null;
    }

    private static int itemsSwapped(ItemStack incoming, ItemStack outgoing) {
        return (hasItem(incoming) ? PUT : 0) | (hasItem(outgoing) ? TAKE : 0);
    }

    private static boolean hasItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }
}
