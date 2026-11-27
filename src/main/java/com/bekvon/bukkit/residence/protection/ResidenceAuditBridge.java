package com.bekvon.bukkit.residence.protection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.audit.AuditAction;
import com.bekvon.bukkit.residence.audit.AuditRecord;
import com.bekvon.bukkit.residence.audit.ResidenceSnapshot;
import com.bekvon.bukkit.residence.audit.ResidenceSnapshot.Box;
import com.bekvon.bukkit.residence.audit.ResidenceSnapshot.PermData;
import com.bekvon.bukkit.residence.audit.ResidenceSnapshot.TpPoint;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.itemlist.ItemList.ListType;
import com.bekvon.bukkit.residence.itemlist.ResidenceItemList;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager.ReclaimEntry;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Items.CMIMaterial;

/**
 * 位於 protection 套件內的領地快照擷取與原子還原橋接器。
 * 直接存取 ClaimedResidence / ResidenceManager / ResidencePermissions 內部結構，
 * 確保所有還原與重做操作不觸發經濟刷錢漏洞、不污染分塊空間索引，並完整同步跨服與保管狀態。
 */
public final class ResidenceAuditBridge {

    public enum ApplyStatus {
        APPLIED,
        SKIPPED_NOOP,
        CONFLICT_WORLD_MISSING,
        CONFLICT_NAME_EXISTS,
        CONFLICT_AREA_COLLISION,
        CONFLICT_PARENT_MISSING,
        CONFLICT_TARGET_MISSING,
        CONFLICT_AUCTION_FROZEN,
        CONFLICT_INVALID_SNAPSHOT
    }

    public static final class ApplyOutcome {
        public final ApplyStatus status;
        public final String detail;

        public ApplyOutcome(ApplyStatus status, String detail) {
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isApplied() {
            return status == ApplyStatus.APPLIED;
        }
    }

    private ResidenceAuditBridge() {
    }

    public static Box captureNodeEnvelope(ClaimedResidence node) {
        if (node == null) {
            return new Box(0, 0, 0, 0, 0, 0);
        }
        Map<String, CuboidArea> map = node.getAreaMap();
        if (map == null || map.isEmpty()) {
            if (node.tpLoc != null) {
                int x = node.tpLoc.getBlockX();
                int y = node.tpLoc.getBlockY();
                int z = node.tpLoc.getBlockZ();
                return new Box(x, y, z, x, y, z);
            }
            return new Box(0, 0, 0, 0, 0, 0);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (CuboidArea area : map.values()) {
            if (area == null || area.getLowVector() == null || area.getHighVector() == null) {
                continue;
            }
            minX = Math.min(minX, area.getLowVector().getBlockX());
            minY = Math.min(minY, area.getLowVector().getBlockY());
            minZ = Math.min(minZ, area.getLowVector().getBlockZ());
            maxX = Math.max(maxX, area.getHighVector().getBlockX());
            maxY = Math.max(maxY, area.getHighVector().getBlockY());
            maxZ = Math.max(maxZ, area.getHighVector().getBlockZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return new Box(0, 0, 0, 0, 0, 0);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static ResidenceSnapshot captureTopSnapshot(Residence plugin, ClaimedResidence anyNode, boolean moneyRefundedOnDelete) {
        if (anyNode == null) {
            return null;
        }
        ClaimedResidence top = anyNode.getTopParent();
        return captureRecursive(plugin, top, moneyRefundedOnDelete);
    }

    private static ResidenceSnapshot captureRecursive(Residence plugin, ClaimedResidence res, boolean moneyRefundedOnDelete) {
        ResidenceSnapshot snap = new ResidenceSnapshot();
        snap.uuid = res.getUniqueId() != null ? res.getUniqueId().toString() : UUID.randomUUID().toString();
        snap.resName = res.getResidenceName();
        snap.fullName = res.getName();
        snap.worldName = res.getPermissions() != null ? res.getPermissions().getWorldName() : res.getWorldName();
        snap.ownerUuid = res.getOwnerUUID() != null ? res.getOwnerUUID().toString() : null;
        snap.ownerName = res.getOwner();
        snap.createTime = res.getCreateTime();
        snap.mainRes = res.isMainResidence();
        snap.blockSellPrice = moneyRefundedOnDelete ? 0D : res.BlockSellPrice;
        snap.storedMoney = (moneyRefundedOnDelete || res.bank == null) ? 0D : res.bank.getStoredMoneyD();
        snap.moneyRefundedOnDelete = moneyRefundedOnDelete;

        snap.enterMessage = res.enterMessage;
        snap.leaveMessage = res.leaveMessage;
        snap.shopDesc = res.ShopDesc;
        snap.chatPrefix = res.ChatPrefix == null ? "" : res.ChatPrefix;
        snap.channelColor = res.ChannelColor != null ? res.ChannelColor.getName() : "WHITE";

        if (res.tpLoc != null) {
            float pitch = res.PitchYaw != null ? (float) res.PitchYaw.getX() : 0f;
            float yaw = res.PitchYaw != null ? (float) res.PitchYaw.getY() : 0f;
            snap.tpLoc = new TpPoint(res.tpLoc.getX(), res.tpLoc.getY(), res.tpLoc.getZ(), pitch, yaw);
        }

        if (res.cmdBlackList != null) {
            snap.cmdBlackList.addAll(res.cmdBlackList);
        }
        if (res.cmdWhiteList != null) {
            snap.cmdWhiteList.addAll(res.cmdWhiteList);
        }
        if (res.blacklist != null) {
            for (Material m : res.blacklist.toArray()) {
                if (m != null)
                    snap.itemBlackList.add(m.name());
            }
        }
        if (res.ignorelist != null) {
            for (Material m : res.ignorelist.toArray()) {
                if (m != null)
                    snap.itemIgnoreList.add(m.name());
            }
        }

        for (Map.Entry<String, CuboidArea> entry : res.getAreaMap().entrySet()) {
            CuboidArea area = entry.getValue();
            if (area == null || area.getLowVector() == null || area.getHighVector() == null) {
                continue;
            }
            snap.areas.put(entry.getKey(), new Box(
                    area.getLowVector().getBlockX(),
                    area.getLowVector().getBlockY(),
                    area.getLowVector().getBlockZ(),
                    area.getHighVector().getBlockX(),
                    area.getHighVector().getBlockY(),
                    area.getHighVector().getBlockZ()));
        }

        snap.permissions = capturePermissions(res.getPermissions());

        if (res.isTopArea() && plugin != null && plugin.getCleanupReclaimManager() != null) {
            ReclaimEntry reclaim = plugin.getCleanupReclaimManager().getArchivedEntry(res);
            if (reclaim != null) {
                snap.archivedOriginalOwnerUuid = reclaim.getOriginalOwnerUuid() != null ? reclaim.getOriginalOwnerUuid().toString() : null;
                snap.archivedOriginalOwnerName = reclaim.getOriginalOwnerName();
                snap.archivedAt = reclaim.getArchivedAt();
            }
        }

        if (res.subzones != null) {
            for (Map.Entry<String, ClaimedResidence> sub : res.subzones.entrySet()) {
                if (sub.getKey() != null && sub.getValue() != null) {
                    snap.subzones.put(sub.getKey().toLowerCase(Locale.ROOT),
                            captureRecursive(plugin, sub.getValue(), moneyRefundedOnDelete));
                }
            }
        }
        return snap;
    }

    private static PermData capturePermissions(ResidencePermissions perms) {
        PermData pd = new PermData();
        if (perms == null) {
            return pd;
        }
        pd.inheritParentFlags = perms.isInheritParentFlags();
        if (perms.cuboidFlags != null) {
            pd.cuboidFlags.putAll(perms.cuboidFlags);
        }
        Map<UUID, Map<String, Boolean>> pFlags = perms.getPlayerFlags();
        if (pFlags != null) {
            for (Map.Entry<UUID, Map<String, Boolean>> e : pFlags.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    String key = e.getKey().toString();
                    pd.playerFlags.put(key, new LinkedHashMap<>(e.getValue()));
                    String knownName = ResidencePlayer.getName(e.getKey());
                    if (knownName != null && !knownName.isEmpty()) {
                        pd.playerNames.put(key, knownName);
                    }
                }
            }
        }
        Map<String, Map<String, Boolean>> pByName = perms.getPlayerFlagsByName();
        if (pByName != null) {
            for (Map.Entry<String, Map<String, Boolean>> e : pByName.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    pd.playerFlagsByName.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
            }
        }
        Map<String, Map<String, Boolean>> gFlags = perms.getGroupFlags();
        if (gFlags != null) {
            for (Map.Entry<String, Map<String, Boolean>> e : gFlags.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    pd.groupFlags.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
            }
        }
        return pd;
    }

    /**
     * 在主執行緒執行單筆稽核紀錄的還原（isRestore=false）或重做（isRestore=true），亦支援純預覽檢查（dryRun=true）。
     */
    public static ApplyOutcome applyRecord(Residence plugin, AuditRecord rec, boolean isRestore, boolean dryRun) {
        ResidenceSnapshot beforeTop = ResidenceSnapshot.fromJson(rec.beforeSnapshot);
        ResidenceSnapshot afterTop = ResidenceSnapshot.fromJson(rec.afterSnapshot);
        String relPath = rec.getRelativeSubzonePath();

        switch (rec.action) {
        case CREATE:
            return isRestore
                    ? recreateTopResidence(plugin, afterTop, dryRun)
                    : deleteTopResidence(plugin, rec, beforeTop != null ? beforeTop : afterTop, dryRun);
        case REMOVE:
            return isRestore
                    ? deleteTopResidence(plugin, rec, beforeTop, dryRun)
                    : recreateTopResidence(plugin, beforeTop, dryRun);
        case SUBZONE_CREATE:
            return isRestore
                    ? recreateSubzone(plugin, rec, afterTop, relPath, dryRun)
                    : deleteSubzone(plugin, rec, relPath, dryRun);
        case SUBZONE_REMOVE:
            return isRestore
                    ? deleteSubzone(plugin, rec, relPath, dryRun)
                    : recreateSubzone(plugin, rec, beforeTop, relPath, dryRun);
        case FLAG:
            return applyFlagState(plugin, rec, isRestore ? afterTop : beforeTop, relPath, dryRun);
        case OWNER:
            return applyOwnerState(plugin, rec, isRestore ? afterTop : beforeTop, relPath, dryRun);
        case AREA:
            return applyAreaState(plugin, rec, isRestore ? afterTop : beforeTop, relPath, dryRun);
        case RENAME:
            return applyRenameState(plugin, rec,
                    isRestore ? beforeTop : afterTop,
                    isRestore ? afterTop : beforeTop,
                    relPath, dryRun);
        case META:
            return applyMetaState(plugin, rec, isRestore ? afterTop : beforeTop, relPath, dryRun);
        default:
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "unknown action");
        }
    }

    private static ClaimedResidence findLiveTopResidence(Residence plugin, AuditRecord rec, ResidenceSnapshot fallbackSnap) {
        ResidenceManager rm = plugin.getResidenceManager();
        if (rec.resUuid != null) {
            ClaimedResidence byUuid = rm.getByUUID(rec.resUuid);
            if (byUuid != null) {
                return byUuid.getTopParent();
            }
        }
        if (fallbackSnap != null && fallbackSnap.uuid != null) {
            try {
                ClaimedResidence bySnapUuid = rm.getByUUID(UUID.fromString(fallbackSnap.uuid));
                if (bySnapUuid != null) {
                    return bySnapUuid.getTopParent();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!rec.topResKey.isEmpty()) {
            ClaimedResidence byName = rm.getByName(rec.topResKey);
            if (byName != null) {
                return byName.getTopParent();
            }
        }
        if (fallbackSnap != null && fallbackSnap.resName != null) {
            ClaimedResidence byName = rm.getByName(fallbackSnap.resName);
            if (byName != null) {
                return byName.getTopParent();
            }
        }
        return null;
    }

    private static ClaimedResidence resolveRelativeNode(ClaimedResidence top, String relPath) {
        if (top == null) {
            return null;
        }
        if (relPath == null || relPath.isEmpty()) {
            return top;
        }
        return top.getSubzone(relPath);
    }

    private static boolean isFrozenInAuction(Residence plugin, ClaimedResidence top) {
        return top != null
                && plugin.getAuctionManager() != null
                && plugin.getAuctionManager().isEnabled()
                && plugin.getAuctionManager().isResidenceInAuction(top);
    }

    private static ApplyOutcome deleteTopResidence(Residence plugin, AuditRecord rec, ResidenceSnapshot refSnap, boolean dryRun) {
        ClaimedResidence live = findLiveTopResidence(plugin, rec, refSnap);
        if (live == null) {
            return new ApplyOutcome(ApplyStatus.SKIPPED_NOOP, "residence already absent");
        }
        if (isFrozenInAuction(plugin, live)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, live.getName());
        }
        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, live.getName());
        }

        ResidenceManager rm = plugin.getResidenceManager();
        String lowerKey = live.getName().toLowerCase(Locale.ROOT);
        rm.removeChunkList(live);
        rm.residences.remove(lowerKey);
        rm.residencesByUUID.remove(live.getUniqueId());

        removeRecordsWithoutMoneyPayout(plugin, live);

        if (plugin.getCleanupReclaimManager() != null) {
            plugin.getCleanupReclaimManager().removeArchivedRecord(live);
        }
        if (plugin.getGuiMetaManager() != null) {
            plugin.getGuiMetaManager().onResidenceRemoved(live.getName());
        }
        if (plugin.getCrossServerManager() != null) {
            if (plugin.getCrossServerManager().usesGlobalQuota()) {
                plugin.getCrossServerManager().releaseResidenceReservation(null, lowerKey);
            }
            if (plugin.getCrossServerManager().isEnabled()) {
                plugin.getCrossServerManager().triggerAsyncSync();
            }
        }
        for (Player one : live.getPlayersInResidence()) {
            ResidencePlayer.get(one).updateResidenceLocation();
        }
        return new ApplyOutcome(ApplyStatus.APPLIED, live.getName());
    }

    private static void removeRecordsWithoutMoneyPayout(Residence plugin, ClaimedResidence res) {
        plugin.getLeaseManager().removeExpireTime(res);
        for (ClaimedResidence sub : res.getSubzones()) {
            removeRecordsWithoutMoneyPayout(plugin, sub);
        }
        plugin.getPlayerManager().removeResFromPlayer(res.getOwnerUUID(), res);
        plugin.getRentManager().removeRentable(res, true);
        plugin.getTransactionManager().removeFromSale(res, true);
        res.setMainResidence(false);
    }

    private static ApplyOutcome recreateTopResidence(Residence plugin, ResidenceSnapshot snap, boolean dryRun) {
        if (snap == null || snap.resName == null || snap.worldName == null || snap.areas == null || snap.areas.isEmpty()) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing snapshot");
        }
        World world = plugin.getServ().getWorld(snap.worldName);
        if (world == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_WORLD_MISSING, snap.worldName);
        }

        ResidenceManager rm = plugin.getResidenceManager();
        String key = snap.resName.toLowerCase(Locale.ROOT);
        UUID snapUuid = parseUuidOrRandom(snap.uuid);

        ClaimedResidence existingByUuid = rm.getByUUID(snapUuid);
        if (existingByUuid != null && existingByUuid.getName().equalsIgnoreCase(snap.resName)) {
            return new ApplyOutcome(ApplyStatus.SKIPPED_NOOP, "residence already exists");
        }
        if (rm.residences.containsKey(key)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_NAME_EXISTS, snap.resName);
        }

        for (Box box : snap.areas.values()) {
            CuboidArea area = toCuboid(world, box);
            String collide = rm.checkAreaCollision(area, null);
            if (collide != null) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_AREA_COLLISION, collide);
            }
        }

        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, snap.resName);
        }

        ClaimedResidence built = instantiateResidenceTree(plugin, world, snap, null);
        rm.residences.put(key, built);
        rm.residencesByUUID.put(built.getUniqueId(), built);
        rm.calculateChunks(built);
        registerPlayersRecursive(plugin, built);

        restoreCustodyState(plugin, built, snap);

        if (plugin.getCrossServerManager() != null) {
            if (plugin.getCrossServerManager().usesGlobalQuota()
                    && built.getOwnerUUID() != null
                    && !built.getOwnerUUID().equals(plugin.getServerUUID())) {
                plugin.getCrossServerManager().restoreResidenceReservation(built.getOwnerUUID(), key);
            }
            if (plugin.getCrossServerManager().isEnabled()) {
                plugin.getCrossServerManager().triggerAsyncSync();
            }
        }
        for (Player one : built.getPlayersInResidence()) {
            ResidencePlayer.get(one).updateResidenceLocation();
        }
        return new ApplyOutcome(ApplyStatus.APPLIED, built.getName());
    }

    private static ClaimedResidence instantiateResidenceTree(
            Residence plugin, World world, ResidenceSnapshot snap, ClaimedResidence parent) {
        UUID resUuid = parseUuidOrRandom(snap.uuid);
        UUID ownerUuid = resolveOwnerUuid(plugin, snap.ownerUuid, snap.ownerName);
        String ownerName = (snap.ownerName == null || snap.ownerName.isEmpty()) ? plugin.getServerLandName() : snap.ownerName;

        ClaimedResidence res = new ClaimedResidence(resUuid);
        res.parent = parent;
        res.setName(snap.resName);
        res.createTime = snap.createTime > 0 ? snap.createTime : System.currentTimeMillis();
        res.mainRes = snap.mainRes;
        res.BlockSellPrice = snap.moneyRefundedOnDelete ? 0D : snap.blockSellPrice;
        if (!snap.moneyRefundedOnDelete && snap.storedMoney > 0 && res.bank != null) {
            res.bank.setStoredMoney(snap.storedMoney);
        }

        res.perms = new ResidencePermissions(res, ownerName, ownerUuid, world.getName());
        if (parent != null && plugin.getConfigManager().flagsInherit()
                && (snap.permissions == null || snap.permissions.inheritParentFlags)) {
            res.perms.setParent(parent.getPermissions());
        } else {
            res.perms.setParent(null);
        }
        applyPermDataToLive(res, snap.permissions);
        applyMetaToLive(plugin, res, snap);

        for (Map.Entry<String, Box> areaEntry : snap.areas.entrySet()) {
            if (areaEntry.getKey() != null && areaEntry.getValue() != null) {
                res.areas.put(areaEntry.getKey().toLowerCase(Locale.ROOT), toCuboid(world, areaEntry.getValue()));
            }
        }

        if (snap.subzones != null) {
            for (Map.Entry<String, ResidenceSnapshot> subEntry : snap.subzones.entrySet()) {
                ResidenceSnapshot subSnap = subEntry.getValue();
                if (subSnap == null) {
                    continue;
                }
                ClaimedResidence child = instantiateResidenceTree(plugin, world, subSnap, res);
                res.subzones.put(subEntry.getKey().toLowerCase(Locale.ROOT), child);
            }
        }
        return res;
    }

    private static void registerPlayersRecursive(Residence plugin, ClaimedResidence res) {
        plugin.getPlayerManager().addPlayer(res.getOwner(), res.getOwnerUUID());
        if (res.getParent() == null || !res.getOwnerUUID().equals(res.getParent().getOwnerUUID())) {
            plugin.getPlayerManager().addResidence(res.getOwnerUUID(), res);
        }
        plugin.getPlayerManager().addTrustedPlayers(res);
        for (ClaimedResidence sub : res.getSubzones()) {
            registerPlayersRecursive(plugin, sub);
        }
    }

    private static ApplyOutcome deleteSubzone(Residence plugin, AuditRecord rec, String relPath, boolean dryRun) {
        if (relPath == null || relPath.isEmpty()) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "empty subzone path");
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, null);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_PARENT_MISSING, rec.topResKey);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }
        ClaimedResidence targetSub = top.getSubzone(relPath);
        if (targetSub == null) {
            return new ApplyOutcome(ApplyStatus.SKIPPED_NOOP, "subzone already absent");
        }
        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, targetSub.getName());
        }
        ClaimedResidence directParent = targetSub.getParent();
        if (directParent == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_PARENT_MISSING, rec.topResKey);
        }
        directParent.subzones.remove(targetSub.getResidenceName().toLowerCase(Locale.ROOT));
        plugin.getResidenceManager().removeChunkList(targetSub);
        removeRecordsWithoutMoneyPayout(plugin, targetSub);
        return new ApplyOutcome(ApplyStatus.APPLIED, targetSub.getName());
    }

    private static ApplyOutcome recreateSubzone(Residence plugin, AuditRecord rec, ResidenceSnapshot snapTop, String relPath, boolean dryRun) {
        if (snapTop == null || relPath == null || relPath.isEmpty()) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing subzone snapshot");
        }
        ResidenceSnapshot subSnap = snapTop.findNode(relPath);
        if (subSnap == null || subSnap.areas == null || subSnap.areas.isEmpty()) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, relPath);
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, snapTop);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_PARENT_MISSING, rec.topResKey);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }
        World world = plugin.getServ().getWorld(top.getPermissions().getWorldName());
        if (world == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_WORLD_MISSING, top.getPermissions().getWorldName());
        }

        int lastDot = relPath.lastIndexOf('.');
        String parentRel = lastDot >= 0 ? relPath.substring(0, lastDot) : "";
        String leafKey = (lastDot >= 0 ? relPath.substring(lastDot + 1) : relPath).toLowerCase(Locale.ROOT);

        ClaimedResidence directParent = resolveRelativeNode(top, parentRel);
        if (directParent == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_PARENT_MISSING, parentRel);
        }
        if (directParent.subzones.containsKey(leafKey)) {
            return new ApplyOutcome(ApplyStatus.SKIPPED_NOOP, "subzone already exists");
        }

        for (Box b : subSnap.areas.values()) {
            CuboidArea area = toCuboid(world, b);
            if (!directParent.containsLoc(area.getLowLocation()) || !directParent.containsLoc(area.getHighLocation())) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_AREA_COLLISION, "outside parent bounds");
            }
            for (ClaimedResidence sibling : directParent.subzones.values()) {
                if (sibling != null && sibling.checkCollision(area)) {
                    return new ApplyOutcome(ApplyStatus.CONFLICT_AREA_COLLISION, sibling.getName());
                }
            }
        }

        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, directParent.getName() + "." + subSnap.resName);
        }

        ClaimedResidence child = instantiateResidenceTree(plugin, world, subSnap, directParent);
        directParent.subzones.put(leafKey, child);
        registerPlayersRecursive(plugin, child);
        return new ApplyOutcome(ApplyStatus.APPLIED, child.getName());
    }

    private static ApplyOutcome applyFlagState(Residence plugin, AuditRecord rec, ResidenceSnapshot targetTop, String relPath, boolean dryRun) {
        if (targetTop == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing snapshot");
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, targetTop);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }
        ClaimedResidence targetNode = resolveRelativeNode(top, relPath);
        ResidenceSnapshot targetSnapNode = targetTop.findNode(relPath);
        if (targetNode == null || targetSnapNode == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
        }

        applyPermDataToLive(targetNode, targetSnapNode.permissions);
        refreshTrustedIndex(plugin, targetNode);

        if (top == targetNode && plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            plugin.getCrossServerManager().triggerAsyncSync();
        }
        return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
    }

    private static ApplyOutcome applyOwnerState(Residence plugin, AuditRecord rec, ResidenceSnapshot targetTop, String relPath, boolean dryRun) {
        if (targetTop == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing snapshot");
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, targetTop);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }
        ClaimedResidence targetNode = resolveRelativeNode(top, relPath);
        ResidenceSnapshot targetSnapNode = targetTop.findNode(relPath);
        if (targetNode == null || targetSnapNode == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
        }

        applyOwnerAndPermsRecursive(plugin, targetNode, targetSnapNode);
        if (targetNode.isTopArea()) {
            restoreCustodyState(plugin, top, targetTop);
            if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                plugin.getCrossServerManager().triggerAsyncSync();
            }
        }
        return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
    }

    private static void applyOwnerAndPermsRecursive(Residence plugin, ClaimedResidence live, ResidenceSnapshot snap) {
        UUID newOwnerUuid = resolveOwnerUuid(plugin, snap.ownerUuid, snap.ownerName);
        String newOwnerName = (snap.ownerName == null || snap.ownerName.isEmpty()) ? plugin.getServerLandName() : snap.ownerName;

        UUID oldOwnerUuid = live.getOwnerUUID();
        plugin.getPlayerManager().removeResFromPlayer(oldOwnerUuid, live);

        live.getPermissions().setOwnerUUID(newOwnerUuid);
        live.getPermissions().setOwnerLastKnownName(newOwnerName);
        plugin.getPlayerManager().addPlayer(newOwnerName, newOwnerUuid);
        plugin.getPlayerManager().addResidence(newOwnerUuid, live);

        applyPermDataToLive(live, snap.permissions);
        refreshTrustedIndex(plugin, live);

        if (live.isTopArea() && plugin.getCrossServerManager() != null && plugin.getCrossServerManager().usesGlobalQuota()) {
            String key = live.getName().toLowerCase(Locale.ROOT);
            if (newOwnerUuid != null && !newOwnerUuid.equals(plugin.getServerUUID())) {
                plugin.getCrossServerManager().restoreResidenceReservation(newOwnerUuid, key);
            } else {
                plugin.getCrossServerManager().releaseResidenceReservation(null, key);
            }
        }

        if (snap.subzones != null) {
            for (Map.Entry<String, ResidenceSnapshot> subEntry : snap.subzones.entrySet()) {
                ClaimedResidence child = live.subzones.get(subEntry.getKey().toLowerCase(Locale.ROOT));
                if (child != null && subEntry.getValue() != null) {
                    applyOwnerAndPermsRecursive(plugin, child, subEntry.getValue());
                }
            }
        }
    }

    private static ApplyOutcome applyAreaState(Residence plugin, AuditRecord rec, ResidenceSnapshot targetTop, String relPath, boolean dryRun) {
        if (targetTop == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing snapshot");
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, targetTop);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }
        ClaimedResidence targetNode = resolveRelativeNode(top, relPath);
        ResidenceSnapshot targetSnapNode = targetTop.findNode(relPath);
        if (targetNode == null || targetSnapNode == null || targetSnapNode.areas == null || targetSnapNode.areas.isEmpty()) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        World world = plugin.getServ().getWorld(top.getPermissions().getWorldName());
        if (world == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_WORLD_MISSING, top.getPermissions().getWorldName());
        }

        ResidenceManager rm = plugin.getResidenceManager();
        Map<String, CuboidArea> builtAreas = new LinkedHashMap<>();
        for (Map.Entry<String, Box> e : targetSnapNode.areas.entrySet()) {
            CuboidArea area = toCuboid(world, e.getValue());
            if (targetNode.isTopArea()) {
                String collide = rm.checkAreaCollision(area, targetNode);
                if (collide != null) {
                    return new ApplyOutcome(ApplyStatus.CONFLICT_AREA_COLLISION, collide);
                }
            } else {
                ClaimedResidence parent = targetNode.getParent();
                if (parent != null && (!parent.containsLoc(area.getLowLocation()) || !parent.containsLoc(area.getHighLocation()))) {
                    return new ApplyOutcome(ApplyStatus.CONFLICT_AREA_COLLISION, "outside parent bounds");
                }
                if (parent != null) {
                    for (ClaimedResidence sibling : parent.getSubzones()) {
                        if (sibling != targetNode && sibling.checkCollision(area)) {
                            return new ApplyOutcome(ApplyStatus.CONFLICT_AREA_COLLISION, sibling.getName());
                        }
                    }
                }
            }
            builtAreas.put(e.getKey().toLowerCase(Locale.ROOT), area);
        }

        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
        }

        rm.updateIndexedArea(targetNode, () -> {
            targetNode.areas.clear();
            targetNode.areas.putAll(builtAreas);
        });
        if (targetNode.isTopArea() && plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            plugin.getCrossServerManager().triggerAsyncSync();
        }
        return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
    }

    private static ApplyOutcome applyRenameState(
            Residence plugin, AuditRecord rec, ResidenceSnapshot fromTop, ResidenceSnapshot toTop, String relPath, boolean dryRun) {
        if (toTop == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing snapshot");
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, fromTop != null ? fromTop : toTop);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }

        if (relPath == null || relPath.isEmpty()) {
            String desiredName = toTop.resName;
            if (desiredName == null || desiredName.isEmpty()) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "empty name");
            }
            if (top.getResidenceName().equals(desiredName)) {
                return new ApplyOutcome(ApplyStatus.SKIPPED_NOOP, desiredName);
            }
            String desiredKey = desiredName.toLowerCase(Locale.ROOT);
            String currentName = top.getResidenceName();
            String currentKey = currentName.toLowerCase(Locale.ROOT);
            ResidenceManager rm = plugin.getResidenceManager();
            if (!currentKey.equals(desiredKey) && rm.residences.containsKey(desiredKey)) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_NAME_EXISTS, desiredName);
            }
            if (dryRun) {
                return new ApplyOutcome(ApplyStatus.APPLIED, currentName + " -> " + desiredName);
            }
            top.setName(desiredName);
            rm.residences.put(desiredKey, top);
            if (!currentKey.equals(desiredKey)) {
                rm.residences.remove(currentKey);
            }
            plugin.getSignUtil().updateSignResName(top);
            if (plugin.getCleanupReclaimManager() != null) {
                plugin.getCleanupReclaimManager().onResidenceRenamed(top, currentName, desiredName);
            }
            if (plugin.getGuiMetaManager() != null) {
                plugin.getGuiMetaManager().onResidenceRenamed(currentName, desiredName);
            }
            if (plugin.getCrossServerManager() != null) {
                if (plugin.getCrossServerManager().usesGlobalQuota() && !currentKey.equals(desiredKey)) {
                    plugin.getCrossServerManager().renameResidenceReservation(currentKey, desiredKey);
                }
                if (plugin.getCrossServerManager().isEnabled()) {
                    plugin.getCrossServerManager().triggerAsyncSync();
                }
            }
            return new ApplyOutcome(ApplyStatus.APPLIED, currentName + " -> " + desiredName);
        } else {
            // 子領地改名：定位父節點並將目前子領地名稱改回目標名稱
            int lastDot = relPath.lastIndexOf('.');
            String parentRel = lastDot >= 0 ? relPath.substring(0, lastDot) : "";
            ClaimedResidence parent = resolveRelativeNode(top, parentRel);
            ResidenceSnapshot parentToSnap = toTop.findNode(parentRel);
            ResidenceSnapshot parentFromSnap = fromTop != null ? fromTop.findNode(parentRel) : null;
            if (parent == null || parentToSnap == null) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_PARENT_MISSING, parentRel);
            }
            // 找出被改名的那個子節點（透過 UUID 配對最精準）
            ResidenceSnapshot desiredChildSnap = null;
            String currentChildKey = null;
            if (parentFromSnap != null && parentFromSnap.subzones != null && parentToSnap.subzones != null) {
                for (Map.Entry<String, ResidenceSnapshot> toEntry : parentToSnap.subzones.entrySet()) {
                    String toKey = toEntry.getKey();
                    if (!parentFromSnap.subzones.containsKey(toKey)) {
                        desiredChildSnap = toEntry.getValue();
                        break;
                    }
                }
                if (desiredChildSnap != null && desiredChildSnap.uuid != null) {
                    for (Map.Entry<String, ClaimedResidence> liveSub : parent.subzones.entrySet()) {
                        if (liveSub.getValue().getUniqueId() != null
                                && desiredChildSnap.uuid.equals(liveSub.getValue().getUniqueId().toString())) {
                            currentChildKey = liveSub.getKey();
                            break;
                        }
                    }
                }
            }
            if (desiredChildSnap == null || currentChildKey == null) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
            }
            String desiredKey = desiredChildSnap.resName.toLowerCase(Locale.ROOT);
            if (!currentChildKey.equals(desiredKey) && parent.subzones.containsKey(desiredKey)) {
                return new ApplyOutcome(ApplyStatus.CONFLICT_NAME_EXISTS, desiredChildSnap.resName);
            }
            if (dryRun) {
                return new ApplyOutcome(ApplyStatus.APPLIED, desiredChildSnap.resName);
            }
            ClaimedResidence child = parent.subzones.get(currentChildKey);
            child.setName(desiredChildSnap.resName);
            parent.subzones.put(desiredKey, child);
            if (!currentChildKey.equals(desiredKey)) {
                parent.subzones.remove(currentChildKey);
            }
            plugin.getSignUtil().updateSignResName(child);
            return new ApplyOutcome(ApplyStatus.APPLIED, child.getName());
        }
    }

    private static ApplyOutcome applyMetaState(Residence plugin, AuditRecord rec, ResidenceSnapshot targetTop, String relPath, boolean dryRun) {
        if (targetTop == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_INVALID_SNAPSHOT, "missing snapshot");
        }
        ClaimedResidence top = findLiveTopResidence(plugin, rec, targetTop);
        if (top == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (isFrozenInAuction(plugin, top)) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_AUCTION_FROZEN, top.getName());
        }
        ClaimedResidence targetNode = resolveRelativeNode(top, relPath);
        ResidenceSnapshot targetSnapNode = targetTop.findNode(relPath);
        if (targetNode == null || targetSnapNode == null) {
            return new ApplyOutcome(ApplyStatus.CONFLICT_TARGET_MISSING, rec.resName);
        }
        if (dryRun) {
            return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
        }
        applyMetaToLive(plugin, targetNode, targetSnapNode);
        if (targetNode.isTopArea() && plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            plugin.getCrossServerManager().triggerAsyncSync();
        }
        return new ApplyOutcome(ApplyStatus.APPLIED, targetNode.getName());
    }

    private static void applyPermDataToLive(ClaimedResidence res, PermData pd) {
        ResidencePermissions perms = res.getPermissions();
        if (perms == null) {
            return;
        }
        perms.clearFlags();
        if (pd == null) {
            return;
        }
        perms.setInheritParentFlags(pd.inheritParentFlags);
        if (res.getParent() != null) {
            if (Residence.getInstance().getConfigManager().flagsInherit() && pd.inheritParentFlags) {
                perms.setParent(res.getParent().getPermissions());
            } else {
                perms.setParent(null);
            }
        }
        if (pd.cuboidFlags != null) {
            for (Map.Entry<String, Boolean> e : pd.cuboidFlags.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    perms.setFlag(e.getKey(), e.getValue() ? FlagState.TRUE : FlagState.FALSE);
                }
            }
        }
        if (pd.playerFlags != null) {
            for (Map.Entry<String, Map<String, Boolean>> e : pd.playerFlags.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                UUID pUuid;
                try {
                    pUuid = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                for (Map.Entry<String, Boolean> f : e.getValue().entrySet()) {
                    if (f.getKey() != null && f.getValue() != null) {
                        perms.setPlayerFlag(pUuid, f.getKey(), f.getValue() ? FlagState.TRUE : FlagState.FALSE);
                    }
                }
            }
        }
        if (pd.playerFlagsByName != null) {
            for (Map.Entry<String, Map<String, Boolean>> e : pd.playerFlagsByName.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, Boolean> f : e.getValue().entrySet()) {
                    if (f.getKey() != null && f.getValue() != null) {
                        perms.setPlayerFlag(e.getKey(), f.getKey(), f.getValue() ? FlagState.TRUE : FlagState.FALSE);
                    }
                }
            }
        }
        if (pd.groupFlags != null) {
            for (Map.Entry<String, Map<String, Boolean>> e : pd.groupFlags.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, Boolean> f : e.getValue().entrySet()) {
                    if (f.getKey() != null && f.getValue() != null) {
                        perms.setGroupFlag(e.getKey(), f.getKey(), f.getValue() ? FlagState.TRUE : FlagState.FALSE);
                    }
                }
            }
        }
    }

    private static void applyMetaToLive(Residence plugin, ClaimedResidence res, ResidenceSnapshot snap) {
        res.enterMessage = snap.enterMessage;
        res.leaveMessage = snap.leaveMessage;
        res.ShopDesc = snap.shopDesc;
        res.ChatPrefix = snap.chatPrefix == null ? "" : snap.chatPrefix;
        if (snap.channelColor != null && !snap.channelColor.isEmpty()) {
            CMIChatColor c = CMIChatColor.getColor(snap.channelColor);
            res.ChannelColor = c != null ? c : CMIChatColor.WHITE;
        }
        if (snap.tpLoc != null) {
            res.tpLoc = new Vector(snap.tpLoc.x, snap.tpLoc.y, snap.tpLoc.z);
            res.PitchYaw = new Vector(snap.tpLoc.pitch, snap.tpLoc.yaw, 0);
        } else {
            res.tpLoc = null;
            res.PitchYaw = null;
        }

        res.cmdBlackList = snap.cmdBlackList == null ? new ArrayList<>() : new ArrayList<>(snap.cmdBlackList);
        res.cmdWhiteList = snap.cmdWhiteList == null ? new ArrayList<>() : new ArrayList<>(snap.cmdWhiteList);

        res.blacklist = new ResidenceItemList(plugin, res, ListType.BLACKLIST);
        if (snap.itemBlackList != null) {
            for (String mName : snap.itemBlackList) {
                CMIMaterial cm = CMIMaterial.get(mName);
                if (cm != null && cm.getMaterial() != null) {
                    res.blacklist.add(cm.getMaterial());
                }
            }
        }
        res.ignorelist = new ResidenceItemList(plugin, res, ListType.IGNORELIST);
        if (snap.itemIgnoreList != null) {
            for (String mName : snap.itemIgnoreList) {
                CMIMaterial cm = CMIMaterial.get(mName);
                if (cm != null && cm.getMaterial() != null) {
                    res.ignorelist.add(cm.getMaterial());
                }
            }
        }
    }

    private static void refreshTrustedIndex(Residence plugin, ClaimedResidence res) {
        // 先移除所有已知玩家對此領地的信任快取，再依最新旗標重新建立
        if (res.getPermissions() != null && res.getPermissions().getPlayerFlags() != null) {
            for (UUID uid : new ArrayList<>(res.getPermissions().getPlayerFlags().keySet())) {
                ResidencePlayer rp = ResidencePlayer.get(uid);
                if (rp != null && !res.isTrusted(uid)) {
                    rp.removeTrustedResidence(res);
                }
            }
        }
        plugin.getPlayerManager().addTrustedPlayers(res);
    }

    private static void restoreCustodyState(Residence plugin, ClaimedResidence top, ResidenceSnapshot topSnap) {
        CleanupReclaimManager reclaim = plugin.getCleanupReclaimManager();
        if (reclaim == null || top == null) {
            return;
        }
        if (reclaim.isArchivedHolder(top.getOwner()) && topSnap.archivedOriginalOwnerUuid != null) {
            try {
                UUID origUuid = UUID.fromString(topSnap.archivedOriginalOwnerUuid);
                reclaim.recordArchived(top, origUuid, topSnap.archivedOriginalOwnerName, topSnap.archivedAt);
            } catch (IllegalArgumentException ignored) {
            }
        } else if (!reclaim.isArchivedHolder(top.getOwner())) {
            reclaim.removeArchivedRecord(top);
        }
    }

    private static CuboidArea toCuboid(World world, Box b) {
        Location low = new Location(world, b.minX, b.minY, b.minZ);
        Location high = new Location(world, b.maxX, b.maxY, b.maxZ);
        return new CuboidArea(low, high);
    }

    private static UUID parseUuidOrRandom(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.randomUUID();
    }

    private static UUID resolveOwnerUuid(Residence plugin, String uuidRaw, String ownerName) {
        if (ownerName != null && (ownerName.equalsIgnoreCase("Server Land")
                || ownerName.equalsIgnoreCase(plugin.getServerLandName()))) {
            return plugin.getServerUUID();
        }
        if (uuidRaw != null && !uuidRaw.isEmpty()) {
            try {
                return UUID.fromString(uuidRaw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (ownerName != null && !ownerName.isEmpty()) {
            UUID found = ResidencePlayer.getUUID(ownerName);
            if (found != null) {
                return found;
            }
            return PlayerManager.createTempUUID(ownerName);
        }
        return plugin.getServerUUID();
    }
}
