package com.bekvon.bukkit.residence.listeners;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.bekvon.bukkit.residence.ConfigManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.commands.padd;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.utils.Utils;

import net.Zrips.CMILib.Items.CMIMC;
import net.Zrips.CMILib.Items.CMIMaterial;
import net.Zrips.CMILib.Version.Version;

public final class ContainerTransferListener implements Listener {
    public static final int PUT = 1;
    public static final int TAKE = 2;

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
        if (!(event.getPlayer() instanceof Player) || (!Flags.container.isGlobalyEnabled() && !Flags.brew.isGlobalyEnabled()))
            return;

        Player player = (Player) event.getPlayer();
        if (ResAdmin.isResAdmin(player) || player.hasMetadata("NPC"))
            return;

        Flags denied = deniedTransfer(player, event.getInventory(), -1, 0, null);
        if (denied != null) {
            event.setCancelled(true);
            lm.Flag_Deny.sendMessage(player, denied);
        }
    }

    private static boolean ignorePlayer(Player player) {
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
        Flags openFlag = Flags.container;
        if (blockContainer) {
            BlockState block = (BlockState) holder;
            Flags useFlag = FlagPermissions.getMaterialUseFlagList().get(block.getType());
            if (useFlag == Flags.brew) {
                openFlag = Flags.brew;
            } else if (useFlag != Flags.container && (useFlag != null
                    || plugin.getConfigManager().getCustomRightClick().contains(block.getType())
                    || plugin.getConfigManager().getCustomBothClick().contains(block.getType())
                    || !plugin.getConfigManager().getCustomContainers().contains(block.getType()))) {
                return null;
            }
            location = block.getLocation();
        } else if (holder instanceof Vehicle) {
            location = ((Vehicle) holder).getLocation();
        } else {
            return null;
        }

        return evaluateTransfer(plugin, player, location, transfer, blockContainer, openFlag);
    }

    public static Flags deniedDirectTransfer(Player player, Location location, int transfer, boolean blockContainer) {
        if (player == null || location == null || transfer == 0 || ignorePlayer(player))
            return null;
        Residence plugin = Residence.getInstance();
        if (plugin == null)
            return null;
        return evaluateTransfer(plugin, player, location, transfer, blockContainer, Flags.container);
    }

    private static boolean isPoweredShelf(Block block) {
        if (block == null || !CMIMaterial.get(block.getType()).containsCriteria(CMIMC.SHELF))
            return false;
        BlockData data = block.getBlockData();
        return data instanceof Powerable && ((Powerable) data).isPowered();
    }

    public static List<Block> getConnectedShelfChain(Block clickedShelf) {
        if (clickedShelf == null || !CMIMaterial.get(clickedShelf.getType()).containsCriteria(CMIMC.SHELF))
            return Collections.emptyList();
        BlockData data = clickedShelf.getBlockData();
        if (!(data instanceof Powerable) || !(data instanceof Directional))
            return Collections.singletonList(clickedShelf);

        if (!((Powerable) data).isPowered())
            return Collections.singletonList(clickedShelf);

        BlockFace facing = ((Directional) data).getFacing();
        BlockFace axisA;
        BlockFace axisB;
        if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
            axisA = BlockFace.WEST;
            axisB = BlockFace.EAST;
        } else {
            axisA = BlockFace.NORTH;
            axisB = BlockFace.SOUTH;
        }

        List<Block> chain = new ArrayList<>(5);
        chain.add(clickedShelf);
        collectChainedShelves(clickedShelf, axisA, facing, chain);
        collectChainedShelves(clickedShelf, axisB, facing, chain);
        return chain;
    }

    private static void collectChainedShelves(Block origin, BlockFace step, BlockFace facing, List<Block> chain) {
        Block cursor = origin;
        for (int i = 0; i < 2; i++) {
            cursor = cursor.getRelative(step);
            if (!CMIMaterial.get(cursor.getType()).containsCriteria(CMIMC.SHELF))
                break;
            BlockData nextData = cursor.getBlockData();
            if (!(nextData instanceof Powerable) || !(nextData instanceof Directional))
                break;
            if (!((Powerable) nextData).isPowered() || ((Directional) nextData).getFacing() != facing) {
                break;
            }
            chain.add(cursor);
        }
    }

    public static Flags deniedNonGuiBlockTransfer(PlayerInteractEvent event, Player player, Block block) {
        if (event == null || player == null || block == null || ResAdmin.isResAdmin(player) || player.hasMetadata("NPC"))
            return null;

        if (CMIMaterial.get(block.getType()).containsCriteria(CMIMC.SHELF)) {
            return deniedShelfTransfer(event, player, block);
        }

        if (ignorePlayer(player))
            return null;
        int transfer = detectNonGuiBlockTransfer(event, block);
        if (transfer == 0)
            return null;
        return deniedDirectTransfer(player, block.getLocation(), transfer, true);
    }

    private static Flags deniedShelfTransfer(PlayerInteractEvent event, Player player, Block clickedBlock) {
        if (player.isSneaking()) {
            boolean mainHeld = hasItem(player.getInventory().getItemInMainHand());
            boolean offHeld = hasItem(player.getInventory().getItemInOffHand());
            if (mainHeld || offHeld)
                return null;
        }

        Residence plugin = Residence.getInstance();
        if (plugin == null)
            return null;

        List<Block> chain = getConnectedShelfChain(clickedBlock);
        boolean powered = isPoweredShelf(clickedBlock);

        boolean playerHasIncoming = false;
        if (powered) {
            for (int i = 0; i < 9; i++) {
                if (hasItem(player.getInventory().getItem(i))) {
                    playerHasIncoming = true;
                    break;
                }
            }
        } else {
            playerHasIncoming = hasItem(event.getItem());
        }

        for (Block shelfBlock : chain) {
            boolean shelfHasItems = false;
            BlockState state = shelfBlock.getState();
            if (state instanceof InventoryHolder) {
                for (ItemStack item : ((InventoryHolder) state).getInventory().getContents()) {
                    if (hasItem(item)) {
                        shelfHasItems = true;
                        break;
                    }
                }
            } else {
                shelfHasItems = true;
            }

            int transfer = 0;
            if (playerHasIncoming)
                transfer |= PUT;
            if (shelfHasItems)
                transfer |= TAKE;

            Flags denied = evaluateTransfer(plugin, player, shelfBlock.getLocation(), transfer, true, Flags.container);
            if (denied != null)
                return denied;
        }
        return null;
    }

    private static int detectNonGuiBlockTransfer(PlayerInteractEvent event, Block block) {
        Player player = event.getPlayer();
        if (player != null && player.isSneaking()) {
            boolean mainHeld = hasItem(player.getInventory().getItemInMainHand());
            boolean offHeld = Version.isCurrentEqualOrHigher(Version.v1_9_0)
                    && hasItem(player.getInventory().getItemInOffHand());
            if (mainHeld || offHeld)
                return 0;
        }
        CMIMaterial mat = CMIMaterial.get(block.getType());
        ItemStack held = event.getItem();
        boolean hasHeld = hasItem(held);

        if (mat == CMIMaterial.JUKEBOX) {
            BlockState state = block.getState();
            if (state instanceof Jukebox) {
                Jukebox jukebox = (Jukebox) state;
                ItemStack record = jukebox.getRecord();
                if (hasItem(record) || jukebox.isPlaying())
                    return TAKE;
                if (hasHeld && held.getType().isRecord())
                    return PUT;
            }
            return 0;
        }

        if (mat == CMIMaterial.COMPOSTER) {
            if (Version.isCurrentEqualOrHigher(Version.v1_14_0) && block.getBlockData() instanceof Levelled) {
                Levelled levelled = (Levelled) block.getBlockData();
                if (levelled.getLevel() >= levelled.getMaximumLevel())
                    return TAKE;
                if (hasHeld)
                    return PUT;
            }
            return 0;
        }

        if (mat == CMIMaterial.LECTERN) {
            if (hasHeld && (held.getType() == Material.WRITABLE_BOOK || held.getType() == Material.WRITTEN_BOOK)) {
                BlockState state = block.getState();
                if (state instanceof Lectern && ((Lectern) state).getInventory().isEmpty()) {
                    return PUT;
                }
            }
            return 0;
        }

        if (mat == CMIMaterial.DECORATED_POT) {
            return hasHeld ? PUT : 0;
        }

        if (mat == CMIMaterial.CHISELED_BOOKSHELF) {
            boolean holdingBook = isBookshelfBook(held);
            if (Version.isCurrentEqualOrHigher(Version.v1_20_0) && block.getState() instanceof org.bukkit.block.ChiseledBookshelf) {
                org.bukkit.block.ChiseledBookshelf shelf = (org.bukkit.block.ChiseledBookshelf) block.getState();
                Inventory inv = shelf.getInventory();
                int filled = 0;
                int size = inv.getSize();
                for (int i = 0; i < size; i++) {
                    if (hasItem(inv.getItem(i)))
                        filled++;
                }
                if (filled == 0)
                    return holdingBook ? PUT : 0;
                if (!holdingBook)
                    return TAKE;
                try {
                    Vector clickedPos = event.getClickedPosition();
                    if (clickedPos != null) {
                        int slot = shelf.getSlot(clickedPos);
                        if (slot >= 0 && slot < size)
                            return hasItem(inv.getItem(slot)) ? TAKE : PUT;
                    }
                } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
                }
                if (filled >= size)
                    return TAKE;
                return PUT | TAKE;
            }
            return holdingBook ? (PUT | TAKE) : TAKE;
        }

        if (mat.containsCriteria(CMIMC.SHELF)) {
            if (block.getState() instanceof InventoryHolder) {
                Inventory inv = ((InventoryHolder) block.getState()).getInventory();
                boolean hasAny = false;
                for (ItemStack item : inv.getContents()) {
                    if (hasItem(item)) {
                        hasAny = true;
                        break;
                    }
                }
                if (!hasAny)
                    return hasHeld ? PUT : 0;
                return hasHeld ? (PUT | TAKE) : TAKE;
            }
            return hasHeld ? (PUT | TAKE) : TAKE;
        }

        return 0;
    }

    private static boolean isBookshelfBook(ItemStack item) {
        if (!hasItem(item))
            return false;
        switch (CMIMaterial.get(item.getType())) {
        case BOOK:
        case WRITABLE_BOOK:
        case WRITTEN_BOOK:
        case ENCHANTED_BOOK:
        case KNOWLEDGE_BOOK:
            return true;
        default:
            return false;
        }
    }

    private static Flags evaluateTransfer(Residence plugin, Player player, Location location, int transfer, boolean blockContainer, Flags openFlag) {
        if (plugin.isDisabledWorldListener(location))
            return null;

        ClaimedResidence claim = plugin.getResidenceManager().getByLoc(location);
        if (claim == null)
            return null;

        if (blockContainer && openFlag.isGlobalyEnabled() && ConfigManager.RaidEnabled && claim.getRaid().isUnderRaid()) {
            if (claim.getRaid().isAttacker(player))
                return null;
            if (!ConfigManager.RaidDefenderContainerUsage && claim.getRaid().isDefender(player))
                return openFlag;
        }

        if (ResPerm.bypass_container.hasPermission(player, 10000L))
            return null;

        FlagPermissions permissions = claim.getPermissions();
        boolean allowUse = permissions.playerHas(player, Flags.use, true);
        boolean allowOpen = !openFlag.isGlobalyEnabled() || permissions.playerHas(player, openFlag, allowUse);

        boolean privileged = isPrivilegedContainerUser(claim, player);
        Flags denied = deniedFlag(permissions, player, allowOpen, transfer, privileged);
        if (denied == Flags.container && openFlag != Flags.container)
            return openFlag;
        return denied;
    }

    private static boolean isPrivilegedContainerUser(ClaimedResidence claim, Player player) {
        if (claim == null || player == null)
            return false;
        if (claim.isOwner(player))
            return true;
        if (claim.isRented() && claim.getRentedLand() != null && claim.getRentedLand().isRenter(player))
            return true;
        if (claim.getPermissions().playerHas(player, Flags.admin, FlagCombo.OnlyTrue))
            return true;
        return isTrustedIgnoringDirectional(claim, player);
    }

    private static boolean isTrustedIgnoringDirectional(ClaimedResidence claim, Player player) {
        HashMap<String, FlagState> flags = FlagPermissions.getValidFlagGroups().get(padd.groupedFlag);
        if (flags == null || flags.isEmpty())
            return false;
        boolean checkedAny = false;
        for (Entry<String, FlagState> entry : flags.entrySet()) {
            Flags f = Flags.getFlag(entry.getKey());
            if (f == null || f == Flags.container_put || f == Flags.container_take)
                continue;
            checkedAny = true;
            if (f.isInGroup(padd.groupedFlag) && !claim.getPermissions().playerHas(player, f, FlagCombo.OnlyTrue)) {
                return false;
            }
        }
        return checkedAny;
    }

    static Flags deniedFlag(FlagPermissions permissions, Player player, boolean allowOpen, int transfer) {
        return deniedFlag(permissions, player, allowOpen, transfer, false);
    }

    static Flags deniedFlag(FlagPermissions permissions, Player player, boolean allowOpen, int transfer, boolean privileged) {
        if (!allowOpen)
            return Flags.container;
        if ((transfer & PUT) != 0 && Flags.container_put.isGlobalyEnabled()
                && !hasDirectionalAccess(permissions, player, Flags.container_put, allowOpen, privileged))
            return Flags.container_put;
        if ((transfer & TAKE) != 0 && Flags.container_take.isGlobalyEnabled()
                && !hasDirectionalAccess(permissions, player, Flags.container_take, allowOpen, privileged))
            return Flags.container_take;
        return null;
    }

    private static boolean hasDirectionalAccess(FlagPermissions permissions, Player player, Flags dirFlag, boolean allowOpen, boolean privileged) {
        if (privileged && !permissions.inheritanceIsPlayerSet(player.getUniqueId(), dirFlag.toString())) {
            return allowOpen;
        }
        return permissions.playerHas(player, dirFlag, allowOpen);
    }

    private static int itemsSwapped(ItemStack incoming, ItemStack outgoing) {
        return (hasItem(incoming) ? PUT : 0) | (hasItem(outgoing) ? TAKE : 0);
    }

    private static boolean hasItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }
}
