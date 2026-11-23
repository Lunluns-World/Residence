package com.bekvon.bukkit.residence.auction;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceAreaDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceFlagChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceOwnerChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceRenameEvent;
import com.bekvon.bukkit.residence.event.ResidenceRentEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceSubzoneCreationEvent;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

/**
 * 領地拍賣凍結保護監聽器：
 * 1. 攔截跨邊界漏斗／漏斗礦車抽物、活塞推拉方塊進出拍賣中領地。
 * 2. 攔截所有外部對拍賣中領地的刪除、改名、縮放、增刪區域、建子領地、轉讓、改旗標、出租操作。
 * 3. 監聽玩家登入，觸發原領主回歸中止拍賣與待確認通知提醒。
 */
public class AuctionProtectionListener implements Listener {

    private final Residence plugin;

    public AuctionProtectionListener(Residence plugin) {
        this.plugin = plugin;
    }

    private boolean isFrozen(ClaimedResidence res) {
        AuctionManager mgr = plugin.getAuctionManager();
        return mgr != null && mgr.isEnabled() && mgr.isResidenceInAuction(res) && !mgr.isInternalMutationInProgress(res);
    }

    private boolean isLocationFrozen(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        if (mgr == null || !mgr.isEnabled()) {
            return false;
        }
        ClaimedResidence res = plugin.getResidenceManager().getByLoc(loc);
        return res != null && mgr.isResidenceInAuction(res) && !mgr.isInternalMutationInProgress(res);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Inventory src = event.getSource();
        Inventory dst = event.getDestination();
        if ((src != null && isLocationFrozen(src.getLocation()))
                || (dst != null && isLocationFrozen(dst.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        BlockFace dir = event.getDirection();
        if (isLocationFrozen(event.getBlock().getLocation())
                || isLocationFrozen(event.getBlock().getRelative(dir).getLocation())) {
            event.setCancelled(true);
            return;
        }
        for (Block b : event.getBlocks()) {
            if (isLocationFrozen(b.getLocation()) || isLocationFrozen(b.getRelative(dir).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        BlockFace dir = event.getDirection();
        if (isLocationFrozen(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        for (Block b : event.getBlocks()) {
            if (isLocationFrozen(b.getLocation()) || isLocationFrozen(b.getRelative(dir).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResDelete(ResidenceDeleteEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResRename(ResidenceRenameEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResSizeChange(ResidenceSizeChangeEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResOwnerChange(ResidenceOwnerChangeEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResFlagChange(ResidenceFlagChangeEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResRent(ResidenceRentEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResAreaAdd(ResidenceAreaAddEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResAreaDelete(ResidenceAreaDeleteEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResSubzoneCreate(ResidenceSubzoneCreationEvent event) {
        if (isFrozen(event.getResidence())) {
            event.setCancelled(true);
            notifyFrozen(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        AuctionManager mgr = plugin.getAuctionManager();
        if (mgr != null && mgr.isEnabled()) {
            mgr.handlePlayerJoin(event.getPlayer());
        }
    }

    private void notifyFrozen(Player player) {
        if (player != null && player.isOnline()) {
            lm.Auction_ResidenceFrozen.sendMessage(player);
        }
    }
}
