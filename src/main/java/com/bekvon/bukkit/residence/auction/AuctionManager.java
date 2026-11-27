package com.bekvon.bukkit.residence.auction;

import java.awt.Color;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.AbortClaim;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.BidEntry;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.BidPlacementResult;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.CancelResult;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.CreateRequest;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.CreateResult;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.DueClaim;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.NotificationRecord;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.PayoutRecord;
import com.bekvon.bukkit.residence.auction.core.AuctionCore;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.SettlementBreakdown;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.ValidationError;
import com.bekvon.bukkit.residence.auction.core.AuctionPolicy;
import com.bekvon.bukkit.residence.auction.core.AuctionRecord;
import com.bekvon.bukkit.residence.auction.core.AuctionStatus;
import com.bekvon.bukkit.residence.auction.core.AuctionType;
import com.bekvon.bukkit.residence.auction.core.ClearMode;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.crossserver.RedisSyncBus;
import com.bekvon.bukkit.residence.economy.EconomyInterface;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager.ReclaimEntry;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.vaultinterface.ResidenceVaultAdapter;
import com.google.gson.Gson;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.Zrips.CMILib.Colors.CMIChatColor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

/**
 * 領地拍賣系統核心服務管理器。
 * 負責拍賣開拍、喊價、防狙擊、跨服中止、到期結標、Outbox 冪等出款、權限凍結與還原、
 * 全服廣播（Redis 即時／MariaDB 回退）與 DiscordSRV 分流機器人通知。
 */
public class AuctionManager {

    private static final Gson GSON = new Gson();
    private static final int MAX_PAYOUT_RETRIES = 20;

    private static final class RedisAuctionEvent {
        String action;
        long auctionId;
        String originServer;
        String broadcast;
    }

    public static final class ActionOutcome {
        public final boolean ok;
        public final String message;
        public final AuctionRecord auction;

        public ActionOutcome(boolean ok, String message, AuctionRecord auction) {
            this.ok = ok;
            this.message = message;
            this.auction = auction;
        }
    }

    private final Residence plugin;
    private volatile AuctionDatabase auctionDb;
    private final DiscordSrvHook discordHook;

    private final ConcurrentHashMap<Long, AuctionRecord> activeById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AuctionRecord> activeByResUuid = new ConcurrentHashMap<>();
    private final Set<UUID> internalMutationResUuids = ConcurrentHashMap.newKeySet();
    private final Set<Long> settlingInFlight = ConcurrentHashMap.newKeySet();

    private ScheduledTask pollTask;
    private volatile boolean initialized = false;

    public AuctionManager(Residence plugin) {
        this.plugin = plugin;
        this.discordHook = new DiscordSrvHook(plugin);
    }

    public boolean isEnabled() {
        return initialized
                && plugin.getConfigManager().isAuctionEnabled()
                && plugin.getConfigManager().enableEconomy()
                && auctionDb != null;
    }

    public AuctionDatabase getDatabase() {
        return auctionDb;
    }

    /**
     * 啟動拍賣系統：建立資料表、註冊 Redis 訂閱、還原未完成結標並啟動排程輪詢。
     */
    public synchronized void start() {
        stop();
        if (!plugin.getConfigManager().isAuctionEnabled()) {
            return;
        }
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized()) {
            plugin.getLogger().warning("[Auction] 領地拍賣系統需要資料庫儲存（SQLite / MariaDB / MySQL / PostgreSQL），目前為 YAML 模式故停用。");
            return;
        }
        try {
            auctionDb = new AuctionDatabase(db);
            auctionDb.initialize();
            initialized = true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "[Auction] 初始化拍賣資料表失敗，已停用拍賣系統。", ex);
            auctionDb = null;
            initialized = false;
            return;
        }

        // 註冊共用 RedisSyncBus 監聽器（若 Redis 未啟用或斷線則完全由下方輪詢承接）
        RedisSyncBus bus = plugin.getRedisSyncBus();
        if (bus != null) {
            bus.addListener(this::handleRedisMessage);
        }

        // 啟動時先同步快取並處理未完成的結標／中止／退款任務
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            refreshActiveCacheNow();
            processAbortPendingNow();
            resumeSettlingAuctionsNow();
            processDueAuctionsNow();
            processPendingPayoutsNow();
        });

        // 每 5 秒定期檢查到期拍賣、跨服中止、未完成出款與快取刷新（Redis 離線時的完整回退保障）
        pollTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!isEnabled()) {
                return;
            }
            refreshActiveCacheNow();
            processAbortPendingNow();
            processDueAuctionsNow();
            processPendingPayoutsNow();
        }, 5L, 5L, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        activeById.clear();
        activeByResUuid.clear();
        settlingInFlight.clear();
        initialized = false;
    }

    /**
     * 判斷指定領地（或其所屬頂層領地）目前是否處於 ACTIVE 拍賣凍結狀態。
     * 供 Compat-Residence（透過反射）與 AuctionProtectionListener 呼叫。
     */
    public boolean isResidenceInAuction(ClaimedResidence res) {
        if (!isEnabled() || res == null) {
            return false;
        }
        ClaimedResidence top = res.getTopParent();
        if (top == null || top.getUniqueId() == null) {
            return false;
        }
        AuctionRecord rec = activeByResUuid.get(top.getUniqueId());
        return rec != null && rec.getStatus() == AuctionStatus.ACTIVE;
    }

    /**
     * 供 CleanupReclaimManager 檢查：該領地是否正在進行中／中止中／結標中的拍賣，避免競態重複歸還。
     */
    public boolean isResidenceBlockedForReclaim(UUID resUuid) {
        if (!isEnabled() || resUuid == null) {
            return false;
        }
        if (activeByResUuid.containsKey(resUuid)) {
            return true;
        }
        try {
            return auctionDb.isResidenceInActiveOrPendingAuction(resUuid);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 檢查領地拍賣狀態失敗，為安全起見暫停自動歸還", ex);
            return true;
        }
    }

    public boolean isInternalMutationInProgress(ClaimedResidence res) {
        if (res == null) {
            return false;
        }
        ClaimedResidence top = res.getTopParent();
        return top != null && top.getUniqueId() != null && internalMutationResUuids.contains(top.getUniqueId());
    }

    public List<AuctionRecord> getCachedActiveAuctions() {
        List<AuctionRecord> list = new ArrayList<>(activeById.values());
        list.sort((a, b) -> Long.compare(a.getEndsAt(), b.getEndsAt()));
        return list;
    }

    public AuctionRecord getCachedActiveAuction(long id) {
        return activeById.get(id);
    }

    public AuctionRecord getCachedActiveAuctionByRes(ClaimedResidence res) {
        if (res == null) {
            return null;
        }
        ClaimedResidence top = res.getTopParent();
        if (top == null || top.getUniqueId() == null) {
            return null;
        }
        return activeByResUuid.get(top.getUniqueId());
    }

    public boolean isStaffBidder(Player player) {
        if (player == null) {
            return false;
        }
        String staffPerm = plugin.getConfigManager().getAuctionStaffBypassPermission();
        if (staffPerm != null && !staffPerm.trim().isEmpty() && player.hasPermission(staffPerm.trim())) {
            return true;
        }
        return plugin.getPermissionManager().isResidenceAdmin(player);
    }

    /**
     * 檢查一塊領地是否符合開拍前置條件（回傳 null 表示通過，否則回傳已翻譯的錯誤訊息）。
     */
    public String checkPreconditionsForCreate(ClaimedResidence res, Player actor, boolean adminMode) {
        if (!isEnabled()) {
            return lm.Auction_Disabled.getMessage();
        }
        if (res == null) {
            return lm.Invalid_Residence.getMessage();
        }
        if (!res.isTopArea()) {
            return lm.Auction_TopLevelOnly.getMessage();
        }
        if (res.getUniqueId() == null) {
            return lm.Invalid_Residence.getMessage();
        }
        if (isResidenceInAuction(res)) {
            return lm.Auction_AlreadyActive.getMessage(res.getName());
        }
        if (res.getOwner().equalsIgnoreCase("server land") || res.getOwner().equalsIgnoreCase(plugin.getServerLandName())) {
            return lm.Auction_CannotAuctionServerLand.getMessage();
        }

        boolean isArchived = plugin.getCleanupReclaimManager() != null
                && plugin.getCleanupReclaimManager().isArchivedHolder(res.getOwner());

        if (!adminMode) {
            if (isArchived) {
                return lm.Auction_ArchivedAdminOnly.getMessage();
            }
            if (!res.isOwner(actor)) {
                return lm.Residence_NotOwner.getMessage();
            }
        }

        // 檢查主領地與所有子領地不可出租中／待租中／出售中，且不可包含第三方子領地
        UUID allowedOwner1 = res.getOwnerUUID();
        UUID allowedOwner2 = null;
        if (isArchived && plugin.getCleanupReclaimManager() != null) {
            ReclaimEntry entry = plugin.getCleanupReclaimManager().getArchivedEntry(res);
            if (entry != null) {
                allowedOwner2 = entry.getOriginalOwnerUuid();
            }
        }
        String conflict = checkMarketAndSubzoneConflicts(res, allowedOwner1, allowedOwner2);
        if (conflict != null) {
            return conflict;
        }
        return null;
    }

    private String checkMarketAndSubzoneConflicts(ClaimedResidence res, UUID allowedOwner1, UUID allowedOwner2) {
        if (plugin.getTransactionManager().isForSale(res)) {
            return lm.Auction_ConflictForSale.getMessage(res.getName());
        }
        if (plugin.getConfigManager().enabledRentSystem() && (plugin.getRentManager().isForRent(res) || res.isRented())) {
            return lm.Auction_ConflictRent.getMessage(res.getName());
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            UUID subOwner = sub.getOwnerUUID();
            boolean matchesMain = (subOwner != null && subOwner.equals(allowedOwner1))
                    || (subOwner != null && allowedOwner2 != null && subOwner.equals(allowedOwner2));
            if (!matchesMain) {
                return lm.Auction_ConflictThirdPartySubzone.getMessage(sub.getName());
            }
            String subConflict = checkMarketAndSubzoneConflicts(sub, allowedOwner1, allowedOwner2);
            if (subConflict != null) {
                return subConflict;
            }
        }
        return null;
    }

    /**
     * 發起拍賣（主執行緒前置檢核與權限備份 -> 非同步資料庫寫入 -> 主執行緒凍結領地與公告）。
     */
    public void createAuction(
            Player actor,
            ClaimedResidence res,
            long startPrice,
            long minIncrement,
            long durationMinutes,
            String description,
            ClearMode requestedClearMode,
            boolean adminMode,
            Consumer<ActionOutcome> callback) {
        String preErr = checkPreconditionsForCreate(res, actor, adminMode);
        if (preErr != null) {
            deliverOutcome(actor, callback, new ActionOutcome(false, preErr, null));
            return;
        }

        boolean isArchived = plugin.getCleanupReclaimManager() != null
                && plugin.getCleanupReclaimManager().isArchivedHolder(res.getOwner());
        AuctionType type = !adminMode ? AuctionType.PLAYER : (isArchived ? AuctionType.ARCHIVED : AuctionType.ADMIN);
        ClearMode effectiveClearMode = (type == AuctionType.PLAYER)
                ? ClearMode.NONE
                : (requestedClearMode == null ? ClearMode.NONE : requestedClearMode);

        UUID originalOwnerUuid = res.getOwnerUUID();
        String originalOwnerName = res.getOwner();
        if (type == AuctionType.ARCHIVED && plugin.getCleanupReclaimManager() != null) {
            ReclaimEntry archived = plugin.getCleanupReclaimManager().getArchivedEntry(res);
            if (archived != null) {
                originalOwnerUuid = archived.getOriginalOwnerUuid();
                originalOwnerName = archived.getOriginalOwnerName();
            } else {
                originalOwnerUuid = null;
                originalOwnerName = "";
            }
        }

        // 取得領地傳送座標（供跨服瀏覽與得標通知顯示）
        CuboidArea main = res.getMainArea();
        Location tp = main != null ? res.getTeleportLocation(null, false) : null;
        double tpX = tp != null ? tp.getX() : (main != null ? (main.getLowVector().getBlockX() + main.getHighVector().getBlockX()) / 2.0 : 0);
        double tpY = tp != null ? tp.getY() : (main != null ? main.getHighVector().getBlockY() : 64);
        double tpZ = tp != null ? tp.getZ() : (main != null ? (main.getLowVector().getBlockZ() + main.getHighVector().getBlockZ()) / 2.0 : 0);

        String backupJson = AuctionPermissionBackup.capture(res);
        String cleanDesc = description == null ? "" : description.trim();
        if (cleanDesc.length() > 450) {
            cleanDesc = cleanDesc.substring(0, 450);
        }

        CreateRequest req = new CreateRequest(
                plugin.getStorageServerName(),
                res.getUniqueId(),
                res.getName().toLowerCase(Locale.ROOT),
                res.getName(),
                res.getWorldName(),
                tpX, tpY, tpZ,
                type, effectiveClearMode,
                actor.getUniqueId(), actor.getName(),
                originalOwnerUuid, originalOwnerName,
                startPrice, minIncrement, durationMinutes,
                cleanDesc, backupJson);

        AuctionPolicy policy = plugin.getConfigManager().getAuctionPolicy();
        boolean bypassLimits = adminMode || plugin.getPermissionManager().isResidenceAdmin(actor);
        UUID resUuid = res.getUniqueId();
        String resName = res.getName();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            CreateResult result;
            try {
                result = auctionDb.createAuction(req, policy, bypassLimits);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Auction] 建立拍賣失敗: " + resName, ex);
                deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_DbError.getMessage(), null));
                return;
            }

            if (!result.ok) {
                String msg = result.alreadyInAuction
                        ? lm.Auction_AlreadyActive.getMessage(resName)
                        : translateValidationError(result.error, result.errorDetail);
                deliverOutcome(actor, callback, new ActionOutcome(false, msg, null));
                return;
            }

            AuctionRecord created = result.auction;
            activeById.put(created.getId(), created);
            if (created.getResUuid() != null) {
                activeByResUuid.put(created.getResUuid(), created);
            }

            // 回到主執行緒套用領地凍結旗標並持久化
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                ClaimedResidence live = plugin.getResidenceManager().getByUUID(resUuid);
                if (live != null) {
                    runInternalMutation(live, () -> AuctionPermissionBackup.applyFreeze(live));
                    plugin.saveResidenceDataNow();
                    if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                        plugin.getCrossServerManager().triggerAsyncSync();
                    }
                }

                String serverDisplay = plugin.getConfigManager().getCrossServerDisplayName(created.getOriginServer());
                String broadcast = lm.Auction_Broadcast_Created.getMessage(
                        created.getId(),
                        created.getResName(),
                        serverDisplay,
                        formatMoney(created.getStartPrice()),
                        formatDurationShort(created.getEndsAt() - System.currentTimeMillis()));
                publishAndBroadcast("CREATE", created.getId(), created.getOriginServer(), broadcast);

                // 發送 DiscordSRV Embed（由發起開拍的本分流機器人發送）
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put(lm.Auction_Discord_FieldResidence.getMessage(), created.getResName());
                fields.put(lm.Auction_Discord_FieldType.getMessage(), formatTypeLabel(created.getType()));
                fields.put(lm.Auction_Discord_FieldStartPrice.getMessage(), formatMoney(created.getStartPrice()));
                fields.put(lm.Auction_Discord_FieldMinIncrement.getMessage(), formatMoney(created.getMinIncrement()));
                fields.put(lm.Auction_Discord_FieldClearMode.getMessage(), formatClearModeLabel(created.getClearMode()));
                fields.put(lm.Auction_Discord_FieldDuration.getMessage(), formatDurationShort(created.getEndsAt() - System.currentTimeMillis()));
                discordHook.sendEmbedAsync(
                        lm.Auction_Discord_CreatedTitle.getMessage(created.getId(), created.getResName()),
                        created.getDescription().isEmpty() ? lm.Auction_Discord_CreatedDesc.getMessage(created.getCreatorName()) : created.getDescription(),
                        new Color(46, 204, 113),
                        fields);

                deliverOutcome(actor, callback, new ActionOutcome(true,
                        lm.Auction_CreatedSuccess.getMessage(created.getId(), created.getResName(), formatMoney(created.getStartPrice())),
                        created));
            });
        });
    }

    /**
     * 玩家喊價（跨服安全悲觀鎖 + 扣款 + Outbox 退還前一位出價者 + 防尾盤狙擊 + 全服公告與 Discord 通知）。
     */
    public void placeBid(Player bidder, long auctionId, long bidAmount, Consumer<ActionOutcome> callback) {
        if (!isEnabled()) {
            deliverOutcome(bidder, callback, new ActionOutcome(false, lm.Auction_Disabled.getMessage(), null));
            return;
        }
        UUID bidderUuid = bidder.getUniqueId();
        String bidderName = bidder.getName();
        boolean isStaff = isStaffBidder(bidder);
        double balance = getBalance(bidder);
        AuctionPolicy policy = plugin.getConfigManager().getAuctionPolicy();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            BidPlacementResult res;
            try {
                res = auctionDb.placeBid(auctionId, bidderUuid, bidderName, bidAmount, balance, isStaff, policy,
                        () -> withdrawMoney(bidder, bidAmount));
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Auction] 喊價資料庫交易失敗 (auctionId=" + auctionId + ")", ex);
                deliverOutcome(bidder, callback, new ActionOutcome(false, lm.Auction_DbError.getMessage(), null));
                return;
            }

            if (!res.ok) {
                String msg = translateValidationError(res.error, res.errorDetail);
                deliverOutcome(bidder, callback, new ActionOutcome(false, msg, res.updatedAuction));
                return;
            }

            AuctionRecord updated = res.updatedAuction;
            activeById.put(updated.getId(), updated);
            if (updated.getResUuid() != null) {
                activeByResUuid.put(updated.getResUuid(), updated);
            }

            // 立即透過 Outbox 冪等退還前一位最高出價者款項
            if (res.refundPayout != null) {
                executePayoutNow(res.refundPayout);
                String notifTitle = lm.Auction_Notify_OutbidTitle.getMessage(updated.getResName());
                String notifMsg = lm.Auction_Notify_OutbidMessage.getMessage(
                        updated.getResName(),
                        bidderName,
                        formatMoney(bidAmount),
                        formatMoney(res.previousBidAmount));
                safeInsertNotification(updated.getId(), res.previousBidderUuid, "OUTBID", notifTitle, notifMsg);
                sendLiveNoticeIfOnline(res.previousBidderUuid, notifMsg);
            }

            String broadcast = (res.antiSnipe != null && res.antiSnipe.isExtended())
                    ? lm.Auction_Broadcast_BidExtended.getMessage(
                            updated.getId(), updated.getResName(), bidderName, formatMoney(bidAmount),
                            policy.getAntiSnipeExtendSeconds())
                    : lm.Auction_Broadcast_Bid.getMessage(
                            updated.getId(), updated.getResName(), bidderName, formatMoney(bidAmount));
            publishAndBroadcast("BID", updated.getId(), updated.getOriginServer(), broadcast);

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(lm.Auction_Discord_FieldResidence.getMessage(), updated.getResName());
            fields.put(lm.Auction_Discord_FieldBidder.getMessage(), bidderName);
            fields.put(lm.Auction_Discord_FieldCurrentBid.getMessage(), formatMoney(bidAmount));
            fields.put(lm.Auction_Discord_FieldRemaining.getMessage(), formatDurationShort(updated.getEndsAt() - System.currentTimeMillis()));
            discordHook.sendEmbedAsync(
                    lm.Auction_Discord_BidTitle.getMessage(updated.getId(), updated.getResName()),
                    (res.antiSnipe != null && res.antiSnipe.isExtended())
                            ? lm.Auction_Discord_BidExtendedDesc.getMessage(policy.getAntiSnipeExtendSeconds())
                            : lm.Auction_Discord_BidDesc.getMessage(bidderName, formatMoney(bidAmount)),
                    new Color(241, 196, 15),
                    fields);

            deliverOutcome(bidder, callback, new ActionOutcome(true,
                    lm.Auction_BidSuccess.getMessage(updated.getResName(), formatMoney(bidAmount)),
                    updated));
        });
    }

    /**
     * 取消拍賣（賣方僅限無人出價時取消；管理員可隨時取消並全額退還最高出價者）。
     */
    public void cancelAuction(CommandSender actor, long auctionId, boolean isAdmin, String reason, Consumer<ActionOutcome> callback) {
        if (!isEnabled()) {
            deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_Disabled.getMessage(), null));
            return;
        }
        UUID actorUuid = actor instanceof Player ? ((Player) actor).getUniqueId() : null;
        String actorName = actor != null ? actor.getName() : "CONSOLE";

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            CancelResult res;
            try {
                res = auctionDb.cancelAuction(auctionId, actorUuid, actorName, isAdmin, reason);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Auction] 取消拍賣失敗 (id=" + auctionId + ")", ex);
                deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_DbError.getMessage(), null));
                return;
            }
            if (!res.ok) {
                deliverOutcome(actor, callback, new ActionOutcome(false, translateValidationError(res.error, 0L), res.auction));
                return;
            }

            AuctionRecord cancelled = res.auction;
            removeFromActiveCache(cancelled);

            if (res.refundPayout != null) {
                executePayoutNow(res.refundPayout);
            }

            // 若領地在本分流則立即還原權限備份；若在遠端分流則透過 Redis 通知遠端還原
            if (isLocalServer(cancelled.getOriginServer())) {
                restoreFrozenResidenceOnMain(cancelled);
            }

            if (plugin.getConfigManager().isAuctionAnnounceBiddersOnCancel()) {
                String title = lm.Auction_Notify_CancelledTitle.getMessage(cancelled.getResName());
                String msg = lm.Auction_Notify_CancelledMessage.getMessage(cancelled.getResName(), actorName);
                for (UUID u : res.distinctBidders) {
                    safeInsertNotification(cancelled.getId(), u, "CANCELLED", title, msg);
                    sendLiveNoticeIfOnline(u, msg);
                }
            }

            String broadcast = lm.Auction_Broadcast_Cancelled.getMessage(cancelled.getId(), cancelled.getResName(), actorName);
            publishAndBroadcast("CANCEL", cancelled.getId(), cancelled.getOriginServer(), broadcast);

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(lm.Auction_Discord_FieldResidence.getMessage(), cancelled.getResName());
            fields.put(lm.Auction_Discord_FieldOperator.getMessage(), actorName);
            discordHook.sendEmbedAsync(
                    lm.Auction_Discord_CancelledTitle.getMessage(cancelled.getId(), cancelled.getResName()),
                    reason == null || reason.trim().isEmpty() ? lm.Auction_Discord_CancelledDesc.getMessage() : reason.trim(),
                    new Color(231, 76, 60),
                    fields);

            deliverOutcome(actor, callback, new ActionOutcome(true,
                    lm.Auction_CancelSuccess.getMessage(cancelled.getId(), cancelled.getResName()),
                    cancelled));
        });
    }

    /**
     * 修改進行中拍賣的最低加價金額。
     */
    public void updateMinIncrement(CommandSender actor, long auctionId, boolean isAdmin, long newIncrement, Consumer<ActionOutcome> callback) {
        if (!isEnabled()) {
            deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_Disabled.getMessage(), null));
            return;
        }
        if (newIncrement < 1L) {
            deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_Err_InvalidIncrement.getMessage(), null));
            return;
        }
        UUID actorUuid = actor instanceof Player ? ((Player) actor).getUniqueId() : null;
        String actorName = actor != null ? actor.getName() : "CONSOLE";

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                AuctionRecord updated = auctionDb.updateMinIncrement(auctionId, actorUuid, actorName, isAdmin, newIncrement);
                if (updated == null) {
                    deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_Err_NotAuthorizedOrInactive.getMessage(), null));
                    return;
                }
                activeById.put(updated.getId(), updated);
                if (updated.getResUuid() != null) {
                    activeByResUuid.put(updated.getResUuid(), updated);
                }
                publishAndBroadcast("UPDATE_INC", updated.getId(), updated.getOriginServer(), null);
                deliverOutcome(actor, callback, new ActionOutcome(true,
                        lm.Auction_IncrementUpdated.getMessage(updated.getId(), formatMoney(newIncrement)),
                        updated));
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Auction] 更新最低加價失敗", ex);
                deliverOutcome(actor, callback, new ActionOutcome(false, lm.Auction_DbError.getMessage(), null));
            }
        });
    }

    /**
     * 玩家登入任一分流時觸發：
     * 1. 若為保管拍賣的原領地主回歸，立即在資料庫原子標記 ABORT_PENDING 並通知領地所在分流執行中止還原。
     * 2. 若有未確認（「我知道了」）的拍賣通知，於登入後提醒玩家開啟通知介面。
     */
    public void handlePlayerJoin(Player player) {
        if (!isEnabled() || player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<AuctionRecord> aborted = auctionDb.markAbortPendingByOriginalOwner(uuid);
                for (AuctionRecord rec : aborted) {
                    removeFromActiveCache(rec);
                    publishAndBroadcast("ABORT_PENDING", rec.getId(), rec.getOriginServer(), null);
                    if (isLocalServer(rec.getOriginServer())) {
                        processAbortPendingNow();
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Auction] 檢查原領主回歸中止拍賣時發生錯誤", ex);
            }

            try {
                int unacked = auctionDb.countUnackedNotifications(uuid);
                if (unacked > 0) {
                    player.getScheduler().runDelayed(plugin, t -> {
                        if (player.isOnline()) {
                            lm.Auction_Notify_LoginReminder.sendMessage(player, unacked);
                        }
                    }, null, 30L);
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Auction] 查詢未讀拍賣通知失敗", ex);
            }
        });
    }

    /**
     * 由領地所屬分流執行所有 ABORT_PENDING 拍賣的最終中止：退款、還原原權限、歸還領地給原領主、通知與公告。
     */
    public void processAbortPendingNow() {
        if (!isEnabled()) {
            return;
        }
        List<AbortClaim> claims;
        try {
            claims = auctionDb.claimAbortPendingForServer(plugin.getStorageServerName());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 領取待中止拍賣失敗", ex);
            return;
        }
        for (AbortClaim claim : claims) {
            AuctionRecord rec = claim.auction;
            removeFromActiveCache(rec);
            if (claim.refundPayout != null) {
                executePayoutNow(claim.refundPayout);
            }

            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                ClaimedResidence live = rec.getResUuid() != null ? plugin.getResidenceManager().getByUUID(rec.getResUuid()) : null;
                if (live == null) {
                    live = plugin.getResidenceManager().getByName(rec.getResName());
                }
                if (live != null) {
                    final ClaimedResidence target = live;
                    runInternalMutation(target, () -> {
                        AuctionPermissionBackup.restore(target, rec.getPermissionBackup());
                        if (rec.getOriginalOwnerUuid() != null) {
                            AuctionPermissionBackup.transferOwnershipRecursive(target, rec.getOriginalOwnerUuid(), rec.getOriginalOwnerName(), false);
                        }
                    });
                    plugin.getSignUtil().updateSignResName(target);
                    // 移除保管紀錄，避免 CleanupReclaimManager 重複處理
                    deleteReclaimRecordSilently(target);
                    plugin.saveResidenceDataNow();
                    if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                        plugin.getCrossServerManager().triggerAsyncSync();
                    }
                }
            });

            // 通知原領主與所有曾喊價玩家
            if (rec.getOriginalOwnerUuid() != null) {
                String ownerTitle = lm.Auction_Notify_AbortedOwnerTitle.getMessage(rec.getResName());
                String ownerMsg = lm.Auction_Notify_AbortedOwnerMessage.getMessage(rec.getResName());
                safeInsertNotification(rec.getId(), rec.getOriginalOwnerUuid(), "ABORTED_OWNER", ownerTitle, ownerMsg);
                sendLiveNoticeIfOnline(rec.getOriginalOwnerUuid(), ownerMsg);
            }
            String bidTitle = lm.Auction_Notify_AbortedBidderTitle.getMessage(rec.getResName());
            String bidMsg = lm.Auction_Notify_AbortedBidderMessage.getMessage(rec.getResName(), rec.getOriginalOwnerName());
            for (UUID bidderUuid : claim.distinctBidders) {
                safeInsertNotification(rec.getId(), bidderUuid, "ABORTED_BIDDER", bidTitle, bidMsg);
                sendLiveNoticeIfOnline(bidderUuid, bidMsg);
            }

            String broadcast = lm.Auction_Broadcast_Aborted.getMessage(rec.getId(), rec.getResName(), rec.getOriginalOwnerName());
            publishAndBroadcast("ABORTED", rec.getId(), rec.getOriginServer(), broadcast);

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(lm.Auction_Discord_FieldResidence.getMessage(), rec.getResName());
            fields.put(lm.Auction_Discord_FieldOriginalOwner.getMessage(), rec.getOriginalOwnerName());
            discordHook.sendEmbedAsync(
                    lm.Auction_Discord_AbortedTitle.getMessage(rec.getId(), rec.getResName()),
                    lm.Auction_Discord_AbortedDesc.getMessage(rec.getOriginalOwnerName()),
                    new Color(230, 126, 34),
                    fields);
        }
    }

    /**
     * 處理到期拍賣（僅限 origin_server 執行）。
     */
    public void processDueAuctionsNow() {
        if (!isEnabled()) {
            return;
        }
        List<DueClaim> claims;
        try {
            claims = auctionDb.claimDueAuctionsForServer(plugin.getStorageServerName(), System.currentTimeMillis());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 查詢到期拍賣失敗", ex);
            return;
        }
        for (DueClaim claim : claims) {
            AuctionRecord rec = claim.auction;
            removeFromActiveCache(rec);
            if (!claim.hasBids) {
                handleNoBidEnd(rec);
            } else {
                executeSettlement(rec);
            }
        }
    }

    /**
     * 伺服器重啟時恢復先前中斷在 SETTLING 狀態的拍賣。
     */
    private void resumeSettlingAuctionsNow() {
        if (!isEnabled()) {
            return;
        }
        try {
            List<AuctionRecord> settling = auctionDb.loadSettlingAuctionsForServer(plugin.getStorageServerName());
            for (AuctionRecord rec : settling) {
                executeSettlement(rec);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 恢復未完成結標拍賣失敗", ex);
        }
    }

    private void handleNoBidEnd(AuctionRecord rec) {
        restoreFrozenResidenceOnMain(rec);

        if (rec.getType() == AuctionType.PLAYER && rec.getCreatorUuid() != null) {
            String title = lm.Auction_Notify_NoBidTitle.getMessage(rec.getResName());
            String msg = lm.Auction_Notify_NoBidMessage.getMessage(rec.getResName());
            safeInsertNotification(rec.getId(), rec.getCreatorUuid(), "NO_BID", title, msg);
            sendLiveNoticeIfOnline(rec.getCreatorUuid(), msg);
        }

        String broadcast = lm.Auction_Broadcast_NoBid.getMessage(rec.getId(), rec.getResName());
        publishAndBroadcast("NO_BID", rec.getId(), rec.getOriginServer(), broadcast);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(lm.Auction_Discord_FieldResidence.getMessage(), rec.getResName());
        discordHook.sendEmbedAsync(
                lm.Auction_Discord_NoBidTitle.getMessage(rec.getId(), rec.getResName()),
                lm.Auction_Discord_NoBidDesc.getMessage(),
                new Color(149, 165, 166),
                fields);
    }

    /**
     * 執行成交結標：直接移轉領地（忽略 MaxRes）、清除權限、依管理員設定分批清空、直接入帳實收金額、寄送「我知道了」確認通知。
     */
    private void executeSettlement(AuctionRecord rec) {
        if (!settlingInFlight.add(rec.getId())) {
            return;
        }
        int feePercent = plugin.getConfigManager().getAuctionPolicy().getFeePercent();
        SettlementBreakdown money = AuctionCore.calculateSettlement(rec.getCurrentBid(), feePercent);

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                ClaimedResidence live = rec.getResUuid() != null ? plugin.getResidenceManager().getByUUID(rec.getResUuid()) : null;
                if (live == null) {
                    live = plugin.getResidenceManager().getByName(rec.getResName());
                }
                if (live != null && rec.getHighestBidderUuid() != null) {
                    final ClaimedResidence target = live;
                    runInternalMutation(target, () -> {
                        // 直接轉讓主領地與全部子領地給得標者（不受 MaxRes 上限限制），並清除所有既有權限
                        AuctionPermissionBackup.transferOwnershipRecursive(target, rec.getHighestBidderUuid(), rec.getHighestBidderName(), false);
                        AuctionPermissionBackup.clearAllFlagsForBuyer(target);
                    });
                    plugin.getSignUtil().updateSignResName(target);
                    if (rec.getType() == AuctionType.ARCHIVED) {
                        deleteReclaimRecordSilently(target);
                    }
                    plugin.saveResidenceDataNow();
                    if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                        plugin.getCrossServerManager().triggerAsyncSync();
                    }

                    // 僅限管理員／保管拍賣且有設定清空模式時，於脫離 ACTIVE 後安全分批執行清空
                    if (rec.getType() != AuctionType.PLAYER && rec.getClearMode() != ClearMode.NONE) {
                        AuctionClearTask.execute(plugin, target, rec.getClearMode(), null);
                    }
                }

                // 回到非同步執行緒完成資料庫 SETTLED 標記、Outbox 直接入帳與通知發送
                Bukkit.getAsyncScheduler().runNow(plugin, t -> {
                    try {
                        UUID recipientUuid = null;
                        String recipientName = "";
                        if (rec.getType() == AuctionType.PLAYER) {
                            recipientUuid = rec.getCreatorUuid();
                            recipientName = rec.getCreatorName();
                        } else if (rec.getType() == AuctionType.ARCHIVED) {
                            recipientUuid = rec.getOriginalOwnerUuid();
                            recipientName = rec.getOriginalOwnerName();
                        }

                        PayoutRecord payout = auctionDb.finalizeSettledAuction(
                                rec.getId(), recipientUuid, recipientName,
                                money.getGrossAmount(), money.getFeeAmount(), money.getNetPayout());
                        if (payout != null) {
                            executePayoutNow(payout);
                        }

                        String serverDisplay = plugin.getConfigManager().getCrossServerDisplayName(rec.getOriginServer());
                        String coords = String.format("%s (%.0f, %.0f, %.0f)", rec.getWorldName(), rec.getTpX(), rec.getTpY(), rec.getTpZ());

                        // 得標者待確認通知
                        if (rec.getHighestBidderUuid() != null) {
                            String winTitle = lm.Auction_Notify_WonTitle.getMessage(rec.getResName());
                            String winMsg = lm.Auction_Notify_WonMessage.getMessage(
                                    rec.getResName(), serverDisplay, coords, formatMoney(money.getGrossAmount()));
                            safeInsertNotification(rec.getId(), rec.getHighestBidderUuid(), "WON", winTitle, winMsg);
                            sendLiveNoticeIfOnline(rec.getHighestBidderUuid(), winMsg);
                        }

                        // 賣方／原領主待確認通知（實收款項已直接入帳）
                        if (recipientUuid != null) {
                            String soldTitle = lm.Auction_Notify_SoldTitle.getMessage(rec.getResName());
                            String soldMsg = lm.Auction_Notify_SoldMessage.getMessage(
                                    rec.getResName(), rec.getHighestBidderName(),
                                    formatMoney(money.getGrossAmount()),
                                    formatMoney(money.getFeeAmount()),
                                    formatMoney(money.getNetPayout()));
                            safeInsertNotification(rec.getId(), recipientUuid, "SOLD", soldTitle, soldMsg);
                            sendLiveNoticeIfOnline(recipientUuid, soldMsg);
                        }

                        String broadcast = lm.Auction_Broadcast_Settled.getMessage(
                                rec.getId(), rec.getResName(), rec.getHighestBidderName(), formatMoney(money.getGrossAmount()));
                        publishAndBroadcast("SETTLED", rec.getId(), rec.getOriginServer(), broadcast);

                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put(lm.Auction_Discord_FieldResidence.getMessage(), rec.getResName());
                        fields.put(lm.Auction_Discord_FieldWinner.getMessage(), rec.getHighestBidderName());
                        fields.put(lm.Auction_Discord_FieldFinalPrice.getMessage(), formatMoney(money.getGrossAmount()));
                        fields.put(lm.Auction_Discord_FieldClearMode.getMessage(), formatClearModeLabel(rec.getClearMode()));
                        discordHook.sendEmbedAsync(
                                lm.Auction_Discord_SettledTitle.getMessage(rec.getId(), rec.getResName()),
                                lm.Auction_Discord_SettledDesc.getMessage(rec.getHighestBidderName(), formatMoney(money.getGrossAmount())),
                                new Color(52, 152, 219),
                                fields);
                    } catch (SQLException ex) {
                        plugin.getLogger().log(Level.SEVERE, "[Auction] 完成結標資料庫更新失敗 (id=" + rec.getId() + ")", ex);
                    } finally {
                        settlingInFlight.remove(rec.getId());
                    }
                });
            } catch (Throwable ex) {
                settlingInFlight.remove(rec.getId());
                plugin.getLogger().log(Level.SEVERE, "[Auction] 主執行緒執行結標轉讓失敗 (id=" + rec.getId() + ")", ex);
            }
        });
    }

    /**
     * 執行單筆 Outbox 出款（冪等搶佔 PENDING -> PROCESSING -> Vault 入帳 -> COMPLETED）。
     */
    public void executePayoutNow(PayoutRecord payout) {
        if (!isEnabled() || payout == null || payout.amount <= 0L || payout.recipientUuid == null) {
            return;
        }
        try {
            if (!auctionDb.claimPayoutForProcessing(payout.id)) {
                return;
            }
            boolean deposited = depositMoney(payout.recipientUuid, payout.recipientName, payout.amount);
            if (deposited) {
                auctionDb.markPayoutCompleted(payout.id);
            } else {
                boolean permanent = payout.retryCount + 1 >= MAX_PAYOUT_RETRIES;
                auctionDb.markPayoutFailed(payout.id, "Vault deposit returned false", permanent);
            }
        } catch (Throwable ex) {
            try {
                boolean permanent = payout.retryCount + 1 >= MAX_PAYOUT_RETRIES;
                auctionDb.markPayoutFailed(payout.id, ex.getMessage(), permanent);
            } catch (SQLException ignored) {
            }
            plugin.getLogger().log(Level.WARNING, "[Auction] 執行 Outbox 出款失敗 (payoutId=" + payout.id + ")", ex);
        }
    }

    public void processPendingPayoutsNow() {
        if (!isEnabled()) {
            return;
        }
        try {
            List<PayoutRecord> pending = auctionDb.loadRetryablePayouts(MAX_PAYOUT_RETRIES);
            for (PayoutRecord p : pending) {
                executePayoutNow(p);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 掃描待重試 Outbox 出款失敗", ex);
        }
    }

    /**
     * 刷新本地進行中拍賣快取；若本分流有先前進行中的拍賣已於遠端被管理員取消，同步偵測並還原其權限備份。
     */
    public synchronized void refreshActiveCacheNow() {
        if (!isEnabled()) {
            return;
        }
        try {
            List<AuctionRecord> active = auctionDb.loadAllActiveAuctions();
            Map<Long, AuctionRecord> prevLocal = new HashMap<>();
            for (AuctionRecord old : activeById.values()) {
                if (isLocalServer(old.getOriginServer())) {
                    prevLocal.put(old.getId(), old);
                }
            }

            Set<Long> nextIds = new HashSet<>();
            for (AuctionRecord rec : active) {
                nextIds.add(rec.getId());
                activeById.put(rec.getId(), rec);
                if (rec.getResUuid() != null) {
                    activeByResUuid.put(rec.getResUuid(), rec);
                }
            }
            for (Long oldId : new ArrayList<>(activeById.keySet())) {
                if (!nextIds.contains(oldId)) {
                    AuctionRecord removed = activeById.remove(oldId);
                    if (removed != null && removed.getResUuid() != null) {
                        activeByResUuid.remove(removed.getResUuid(), removed);
                    }
                    // 若是本分流的拍賣被遠端分流管理員取消，確保本分流還原其權限備份
                    if (removed != null && prevLocal.containsKey(oldId)) {
                        AuctionRecord latest = auctionDb.loadAuctionById(oldId);
                        if (latest != null && latest.getStatus() == AuctionStatus.CANCELLED) {
                            restoreFrozenResidenceOnMain(latest);
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 刷新進行中拍賣快取失敗", ex);
        }
    }

    private void handleRedisMessage(String topic, String payload) {
        if (!RedisSyncBus.TOPIC_AUCTION.equals(topic) || !isEnabled() || payload == null) {
            return;
        }
        RedisAuctionEvent ev;
        try {
            ev = GSON.fromJson(payload, RedisAuctionEvent.class);
        } catch (Exception ex) {
            return;
        }
        if (ev == null) {
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            refreshActiveCacheNow();
            if ("ABORT_PENDING".equals(ev.action) && isLocalServer(ev.originServer)) {
                processAbortPendingNow();
            } else if ("CANCEL".equals(ev.action) && isLocalServer(ev.originServer)) {
                try {
                    AuctionRecord rec = auctionDb.loadAuctionById(ev.auctionId);
                    if (rec != null && rec.getStatus() == AuctionStatus.CANCELLED) {
                        restoreFrozenResidenceOnMain(rec);
                    }
                } catch (SQLException ignored) {
                }
            }
            if (plugin.getAuctionGui() != null) {
                plugin.getAuctionGui().refreshOpenViewersAsync();
            }
        });

        if (ev.broadcast != null && !ev.broadcast.isEmpty() && plugin.getConfigManager().isAuctionBroadcastEnabled()) {
            broadcastLocal(ev.broadcast);
        }
    }

    private void publishAndBroadcast(String action, long auctionId, String originServer, String broadcastMessage) {
        if (broadcastMessage != null && !broadcastMessage.isEmpty() && plugin.getConfigManager().isAuctionBroadcastEnabled()) {
            broadcastLocal(broadcastMessage);
        }
        RedisSyncBus bus = plugin.getRedisSyncBus();
        if (bus != null && bus.isEnabled()) {
            RedisAuctionEvent ev = new RedisAuctionEvent();
            ev.action = action;
            ev.auctionId = auctionId;
            ev.originServer = originServer;
            ev.broadcast = broadcastMessage;
            bus.publish(RedisSyncBus.TOPIC_AUCTION, GSON.toJson(ev));
        }
        if (plugin.getAuctionGui() != null) {
            plugin.getAuctionGui().refreshOpenViewersAsync();
        }
    }

    private void broadcastLocal(String message) {
        String colored = CMIChatColor.translate(message);
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(colored);
            }
            plugin.getLogger().info(CMIChatColor.stripColor(colored));
        });
    }

    private void restoreFrozenResidenceOnMain(AuctionRecord rec) {
        if (rec == null) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            ClaimedResidence live = rec.getResUuid() != null ? plugin.getResidenceManager().getByUUID(rec.getResUuid()) : null;
            if (live == null) {
                live = plugin.getResidenceManager().getByName(rec.getResName());
            }
            if (live != null) {
                final ClaimedResidence target = live;
                runInternalMutation(target, () -> AuctionPermissionBackup.restore(target, rec.getPermissionBackup()));
                plugin.saveResidenceDataNow();
                if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                    plugin.getCrossServerManager().triggerAsyncSync();
                }
            }
        });
    }

    private void deleteReclaimRecordSilently(ClaimedResidence target) {
        CleanupReclaimManager reclaim = plugin.getCleanupReclaimManager();
        if (reclaim == null || target == null) {
            return;
        }
        reclaim.removeArchivedRecord(target);
    }

    private void runInternalMutation(ClaimedResidence res, Runnable work) {
        UUID id = res.getTopParent().getUniqueId();
        if (id != null) {
            internalMutationResUuids.add(id);
        }
        try {
            work.run();
        } finally {
            if (id != null) {
                internalMutationResUuids.remove(id);
            }
        }
    }

    private void removeFromActiveCache(AuctionRecord rec) {
        if (rec == null) {
            return;
        }
        activeById.remove(rec.getId());
        if (rec.getResUuid() != null) {
            activeByResUuid.remove(rec.getResUuid());
        }
    }

    private boolean isLocalServer(String serverName) {
        return serverName != null && serverName.equalsIgnoreCase(plugin.getStorageServerName());
    }

    private void safeInsertNotification(long auctionId, UUID playerUuid, String type, String title, String message) {
        if (playerUuid == null) {
            return;
        }
        try {
            auctionDb.insertNotification(auctionId, playerUuid, type, title, message);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 寫入待確認通知失敗", ex);
        }
    }

    private void sendLiveNoticeIfOnline(UUID playerUuid, String message) {
        if (playerUuid == null || message == null) {
            return;
        }
        Player online = Bukkit.getPlayer(playerUuid);
        if (online != null && online.isOnline()) {
            online.getScheduler().run(plugin, t -> {
                if (online.isOnline()) {
                    online.sendMessage(CMIChatColor.translate(message));
                    lm.Auction_Notify_ClickToAckHint.sendMessage(online);
                }
            }, null);
        }
    }

    private void deliverOutcome(CommandSender actor, Consumer<ActionOutcome> callback, ActionOutcome outcome) {
        if (callback == null) {
            return;
        }
        if (actor instanceof Player) {
            Player p = (Player) actor;
            p.getScheduler().run(plugin, t -> callback.accept(outcome), null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> callback.accept(outcome));
        }
    }

    // ==================== 經濟介面封裝（Vault OfflinePlayer UUID 安全） ====================

    public double getBalance(Player player) {
        if (player == null) {
            return 0D;
        }
        Economy vault = ResidenceVaultAdapter.economy;
        if (vault != null) {
            return vault.getBalance(player);
        }
        EconomyInterface eco = plugin.getEconomyManager();
        return eco == null ? 0D : eco.getBalance(player);
    }

    private boolean withdrawMoney(Player player, double amount) {
        if (player == null || amount <= 0D) {
            return false;
        }
        Economy vault = ResidenceVaultAdapter.economy;
        if (vault != null) {
            EconomyResponse resp = vault.withdrawPlayer(player, amount);
            return resp != null && resp.transactionSuccess();
        }
        EconomyInterface eco = plugin.getEconomyManager();
        return eco != null && eco.subtract(player, amount);
    }

    private boolean depositMoney(UUID playerUuid, String fallbackName, double amount) {
        if (playerUuid == null || amount <= 0D) {
            return false;
        }
        Economy vault = ResidenceVaultAdapter.economy;
        if (vault != null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(playerUuid);
            EconomyResponse resp = vault.depositPlayer(off, amount);
            if (resp != null && resp.transactionSuccess()) {
                return true;
            }
            if (fallbackName != null && !fallbackName.isEmpty()) {
                @SuppressWarnings("deprecation")
                EconomyResponse fallbackResp = vault.depositPlayer(fallbackName, amount);
                return fallbackResp != null && fallbackResp.transactionSuccess();
            }
            return false;
        }
        EconomyInterface eco = plugin.getEconomyManager();
        if (eco == null) {
            return false;
        }
        if (eco.add(playerUuid, amount)) {
            return true;
        }
        return fallbackName != null && !fallbackName.isEmpty() && eco.add(fallbackName, amount);
    }

    public String formatMoney(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }

    public String formatTypeLabel(AuctionType type) {
        if (type == null) {
            return "";
        }
        switch (type) {
        case ARCHIVED:
            return lm.Auction_Type_Archived.getMessage();
        case ADMIN:
            return lm.Auction_Type_Admin.getMessage();
        case PLAYER:
        default:
            return lm.Auction_Type_Player.getMessage();
        }
    }

    public String formatClearModeLabel(ClearMode mode) {
        if (mode == null) {
            return lm.Auction_ClearMode_None.getMessage();
        }
        switch (mode) {
        case ITEMS_ONLY:
            return lm.Auction_ClearMode_ItemsOnly.getMessage();
        case ALL:
            return lm.Auction_ClearMode_All.getMessage();
        case NONE:
        default:
            return lm.Auction_ClearMode_None.getMessage();
        }
    }

    public String formatDurationShort(long millis) {
        if (millis <= 0L) {
            return lm.Auction_Time_Ended.getMessage();
        }
        long totalSec = millis / 1000L;
        long days = totalSec / 86400L;
        long hours = (totalSec % 86400L) / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (days > 0) {
            return lm.Auction_Time_DaysHours.getMessage(days, hours);
        }
        if (hours > 0) {
            return lm.Auction_Time_HoursMins.getMessage(hours, mins);
        }
        if (mins > 0) {
            return lm.Auction_Time_MinsSecs.getMessage(mins, secs);
        }
        return lm.Auction_Time_Secs.getMessage(secs);
    }

    public String translateValidationError(ValidationError error, long detail) {
        if (error == null) {
            return lm.Auction_DbError.getMessage();
        }
        switch (error) {
        case AUCTION_NOT_ACTIVE:
            return lm.Auction_Err_NotActive.getMessage();
        case AUCTION_ENDED:
            return lm.Auction_Err_Ended.getMessage();
        case CANNOT_BID_OWN_AUCTION:
            return lm.Auction_Err_OwnAuction.getMessage();
        case ORIGINAL_OWNER_CANNOT_BID:
            return lm.Auction_Err_OriginalOwnerCannotBid.getMessage();
        case STAFF_CANNOT_BID:
            return lm.Auction_Err_StaffCannotBid.getMessage();
        case ALREADY_HIGHEST_BIDDER:
            return lm.Auction_Err_AlreadyHighest.getMessage();
        case BID_TOO_LOW:
            return lm.Auction_Err_BidTooLow.getMessage(formatMoney(detail));
        case INSUFFICIENT_BALANCE:
            return lm.Auction_Err_InsufficientBalance.getMessage(formatMoney(detail));
        case INVALID_START_PRICE:
            return lm.Auction_Err_StartPriceTooLow.getMessage(formatMoney(detail));
        case INVALID_MIN_INCREMENT:
            return lm.Auction_Err_InvalidIncrement.getMessage();
        case DURATION_TOO_SHORT:
            return lm.Auction_Err_DurationTooShort.getMessage(detail);
        case DURATION_TOO_LONG:
            return lm.Auction_Err_DurationTooLong.getMessage(detail);
        case CLEAR_MODE_ADMIN_ONLY:
            return lm.Auction_Err_ClearModeAdminOnly.getMessage();
        case MAX_ACTIVE_REACHED:
            return lm.Auction_Err_MaxActiveReached.getMessage(detail);
        case RELIST_COOLDOWN_ACTIVE:
            return lm.Auction_Err_RelistCooldown.getMessage(formatDurationShort(detail));
        case CANNOT_CANCEL_AFTER_BID:
            return lm.Auction_Err_CannotCancelAfterBid.getMessage();
        case NOT_AUTHORIZED:
            return lm.Auction_Err_NotAuthorizedOrInactive.getMessage();
        default:
            return lm.Auction_DbError.getMessage();
        }
    }

    public List<BidEntry> loadBidHistory(long auctionId, int limit) throws SQLException {
        return auctionDb == null ? Collections.emptyList() : auctionDb.loadBidsForAuction(auctionId, limit);
    }

    public List<NotificationRecord> loadUnackedNotifications(UUID playerUuid) throws SQLException {
        return auctionDb == null ? Collections.emptyList() : auctionDb.loadUnackedNotifications(playerUuid);
    }

    public boolean acknowledgeNotification(UUID playerUuid, long notifId) throws SQLException {
        return auctionDb != null && auctionDb.acknowledgeNotification(playerUuid, notifId);
    }

    public int acknowledgeAllNotifications(UUID playerUuid) throws SQLException {
        return auctionDb == null ? 0 : auctionDb.acknowledgeAllNotifications(playerUuid);
    }
}
