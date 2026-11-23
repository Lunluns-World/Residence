package com.bekvon.bukkit.residence.auction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.bekvon.bukkit.residence.auction.core.AuctionCore;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.AntiSnipeOutcome;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.ValidationError;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.ValidationResult;
import com.bekvon.bukkit.residence.auction.core.AuctionPolicy;
import com.bekvon.bukkit.residence.auction.core.AuctionRecord;
import com.bekvon.bukkit.residence.auction.core.AuctionStatus;
import com.bekvon.bukkit.residence.auction.core.AuctionType;
import com.bekvon.bukkit.residence.auction.core.ClearMode;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.StorageType;

/**
 * 領地拍賣系統資料庫持久層（支援 MariaDB / MySQL / PostgreSQL / SQLite）。
 * 所有競標、狀態轉換與退款 Outbox 皆以單一資料庫交易搭配悲觀鎖（FOR UPDATE 或 SQLite 寫入鎖）保證原子性，
 * 避免跨服併發搶標、重複退款或吞錢問題。
 */
public class AuctionDatabase {

    public static final String PAYOUT_TYPE_REFUND = "REFUND";
    public static final String PAYOUT_TYPE_SETTLEMENT = "SETTLEMENT";

    public static final String PAYOUT_STATUS_PENDING = "PENDING";
    public static final String PAYOUT_STATUS_PROCESSING = "PROCESSING";
    public static final String PAYOUT_STATUS_COMPLETED = "COMPLETED";
    public static final String PAYOUT_STATUS_FAILED = "FAILED";

    public static final class CreateRequest {
        public final String originServer;
        public final UUID resUuid;
        public final String resKey;
        public final String resName;
        public final String worldName;
        public final double tpX;
        public final double tpY;
        public final double tpZ;
        public final AuctionType type;
        public final ClearMode clearMode;
        public final UUID creatorUuid;
        public final String creatorName;
        public final UUID originalOwnerUuid;
        public final String originalOwnerName;
        public final long startPrice;
        public final long minIncrement;
        public final long durationMinutes;
        public final String description;
        public final String permissionBackup;

        public CreateRequest(
                String originServer, UUID resUuid, String resKey, String resName, String worldName,
                double tpX, double tpY, double tpZ,
                AuctionType type, ClearMode clearMode,
                UUID creatorUuid, String creatorName,
                UUID originalOwnerUuid, String originalOwnerName,
                long startPrice, long minIncrement, long durationMinutes,
                String description, String permissionBackup) {
            this.originServer = originServer;
            this.resUuid = resUuid;
            this.resKey = resKey == null ? "" : resKey.toLowerCase(Locale.ROOT);
            this.resName = resName;
            this.worldName = worldName;
            this.tpX = tpX;
            this.tpY = tpY;
            this.tpZ = tpZ;
            this.type = type;
            this.clearMode = clearMode == null ? ClearMode.NONE : clearMode;
            this.creatorUuid = creatorUuid;
            this.creatorName = creatorName == null ? "" : creatorName;
            this.originalOwnerUuid = originalOwnerUuid;
            this.originalOwnerName = originalOwnerName == null ? "" : originalOwnerName;
            this.startPrice = startPrice;
            this.minIncrement = minIncrement;
            this.durationMinutes = durationMinutes;
            this.description = description == null ? "" : description;
            this.permissionBackup = permissionBackup;
        }
    }

    public static final class BidEntry {
        public final long id;
        public final long auctionId;
        public final UUID bidderUuid;
        public final String bidderName;
        public final long amount;
        public final long createdAt;

        public BidEntry(long id, long auctionId, UUID bidderUuid, String bidderName, long amount, long createdAt) {
            this.id = id;
            this.auctionId = auctionId;
            this.bidderUuid = bidderUuid;
            this.bidderName = bidderName == null ? "" : bidderName;
            this.amount = amount;
            this.createdAt = createdAt;
        }
    }

    public static final class PayoutRecord {
        public final long id;
        public final long auctionId;
        public final UUID recipientUuid;
        public final String recipientName;
        public final long amount;
        public final String payoutType;
        public final String status;
        public final int retryCount;
        public final String lastError;
        public final long createdAt;
        public final long updatedAt;

        public PayoutRecord(
                long id, long auctionId, UUID recipientUuid, String recipientName,
                long amount, String payoutType, String status, int retryCount,
                String lastError, long createdAt, long updatedAt) {
            this.id = id;
            this.auctionId = auctionId;
            this.recipientUuid = recipientUuid;
            this.recipientName = recipientName == null ? "" : recipientName;
            this.amount = amount;
            this.payoutType = payoutType;
            this.status = status;
            this.retryCount = retryCount;
            this.lastError = lastError == null ? "" : lastError;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static final class NotificationRecord {
        public final long id;
        public final long auctionId;
        public final UUID playerUuid;
        public final String notifType;
        public final String title;
        public final String message;
        public final long createdAt;
        public final long ackAt;

        public NotificationRecord(
                long id, long auctionId, UUID playerUuid, String notifType,
                String title, String message, long createdAt, long ackAt) {
            this.id = id;
            this.auctionId = auctionId;
            this.playerUuid = playerUuid;
            this.notifType = notifType == null ? "" : notifType;
            this.title = title == null ? "" : title;
            this.message = message == null ? "" : message;
            this.createdAt = createdAt;
            this.ackAt = ackAt;
        }
    }

    public static final class CreateResult {
        public final boolean ok;
        public final boolean alreadyInAuction;
        public final ValidationError error;
        public final long errorDetail;
        public final AuctionRecord auction;

        private CreateResult(boolean ok, boolean alreadyInAuction, ValidationError error, long errorDetail, AuctionRecord auction) {
            this.ok = ok;
            this.alreadyInAuction = alreadyInAuction;
            this.error = error;
            this.errorDetail = errorDetail;
            this.auction = auction;
        }

        public static CreateResult success(AuctionRecord record) {
            return new CreateResult(true, false, ValidationError.NONE, 0L, record);
        }

        public static CreateResult duplicate() {
            return new CreateResult(false, true, ValidationError.AUCTION_NOT_ACTIVE, 0L, null);
        }

        public static CreateResult fail(ValidationError error, long detail) {
            return new CreateResult(false, false, error, detail, null);
        }
    }

    public static final class BidPlacementResult {
        public final boolean ok;
        public final ValidationError error;
        public final long errorDetail;
        public final boolean withdrawFailed;
        public final AuctionRecord updatedAuction;
        public final UUID previousBidderUuid;
        public final String previousBidderName;
        public final long previousBidAmount;
        public final PayoutRecord refundPayout;
        public final AntiSnipeOutcome antiSnipe;

        public BidPlacementResult(
                boolean ok, ValidationError error, long errorDetail, boolean withdrawFailed,
                AuctionRecord updatedAuction, UUID previousBidderUuid, String previousBidderName,
                long previousBidAmount, PayoutRecord refundPayout, AntiSnipeOutcome antiSnipe) {
            this.ok = ok;
            this.error = error;
            this.errorDetail = errorDetail;
            this.withdrawFailed = withdrawFailed;
            this.updatedAuction = updatedAuction;
            this.previousBidderUuid = previousBidderUuid;
            this.previousBidderName = previousBidderName;
            this.previousBidAmount = previousBidAmount;
            this.refundPayout = refundPayout;
            this.antiSnipe = antiSnipe;
        }
    }

    public static final class CancelResult {
        public final boolean ok;
        public final ValidationError error;
        public final AuctionRecord auction;
        public final PayoutRecord refundPayout;
        public final List<UUID> distinctBidders;

        public CancelResult(boolean ok, ValidationError error, AuctionRecord auction, PayoutRecord refundPayout, List<UUID> distinctBidders) {
            this.ok = ok;
            this.error = error;
            this.auction = auction;
            this.refundPayout = refundPayout;
            this.distinctBidders = distinctBidders == null ? Collections.emptyList() : distinctBidders;
        }
    }

    public static final class AbortClaim {
        public final AuctionRecord auction;
        public final PayoutRecord refundPayout;
        public final List<UUID> distinctBidders;

        public AbortClaim(AuctionRecord auction, PayoutRecord refundPayout, List<UUID> distinctBidders) {
            this.auction = auction;
            this.refundPayout = refundPayout;
            this.distinctBidders = distinctBidders == null ? Collections.emptyList() : distinctBidders;
        }
    }

    public static final class DueClaim {
        public final AuctionRecord auction;
        public final boolean hasBids;

        public DueClaim(AuctionRecord auction, boolean hasBids) {
            this.auction = auction;
            this.hasBids = hasBids;
        }
    }

    private final DatabaseStorageManager db;

    public AuctionDatabase(DatabaseStorageManager db) {
        this.db = db;
    }

    private String t(String suffix) {
        return db.getTablePrefix() + suffix;
    }

    private boolean isSqlite() {
        return db.getStorageType() == StorageType.SQLITE;
    }

    private boolean isPostgres() {
        return db.getStorageType() == StorageType.POSTGRESQL;
    }

    private String forUpdateClause() {
        return isSqlite() ? "" : " FOR UPDATE";
    }

    /**
     * 建立所有拍賣系統資料表與索引（支援 MariaDB / PostgreSQL / SQLite）。
     */
    public void initialize() throws SQLException {
        if (db == null || !db.isInitialized()) {
            return;
        }
        String autoId = isSqlite()
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : (isPostgres() ? "BIGSERIAL PRIMARY KEY" : "BIGINT AUTO_INCREMENT PRIMARY KEY");
        String longText = (db.getStorageType() == StorageType.MYSQL || db.getStorageType() == StorageType.MARIADB)
                ? "LONGTEXT" : "TEXT";

        try (Connection conn = db.openConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("auctions") + " ("
                    + "id " + autoId + ","
                    + "origin_server VARCHAR(64) NOT NULL,"
                    + "res_uuid VARCHAR(36) NOT NULL,"
                    + "res_key VARCHAR(128) NOT NULL,"
                    + "res_name VARCHAR(128) NOT NULL,"
                    + "world_name VARCHAR(128) NOT NULL,"
                    + "tp_x DOUBLE PRECISION NOT NULL,"
                    + "tp_y DOUBLE PRECISION NOT NULL,"
                    + "tp_z DOUBLE PRECISION NOT NULL,"
                    + "auction_type VARCHAR(16) NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "clear_mode VARCHAR(16) NOT NULL DEFAULT 'NONE',"
                    + "creator_uuid VARCHAR(36) NOT NULL,"
                    + "creator_name VARCHAR(64) NOT NULL,"
                    + "original_owner_uuid VARCHAR(36),"
                    + "original_owner_name VARCHAR(64),"
                    + "start_price BIGINT NOT NULL,"
                    + "min_increment BIGINT NOT NULL,"
                    + "current_bid BIGINT NOT NULL DEFAULT 0,"
                    + "highest_bidder_uuid VARCHAR(36),"
                    + "highest_bidder_name VARCHAR(64),"
                    + "bid_count INT NOT NULL DEFAULT 0,"
                    + "description VARCHAR(512),"
                    + "created_at BIGINT NOT NULL,"
                    + "ends_at BIGINT NOT NULL,"
                    + "anti_snipe_count INT NOT NULL DEFAULT 0,"
                    + "permission_backup " + longText
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("auction_bids") + " ("
                    + "id " + autoId + ","
                    + "auction_id BIGINT NOT NULL,"
                    + "bidder_uuid VARCHAR(36) NOT NULL,"
                    + "bidder_name VARCHAR(64) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "created_at BIGINT NOT NULL"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("auction_payouts") + " ("
                    + "id " + autoId + ","
                    + "auction_id BIGINT NOT NULL,"
                    + "recipient_uuid VARCHAR(36) NOT NULL,"
                    + "recipient_name VARCHAR(64) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "payout_type VARCHAR(24) NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "retry_count INT NOT NULL DEFAULT 0,"
                    + "last_error VARCHAR(255),"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("auction_notifications") + " ("
                    + "id " + autoId + ","
                    + "auction_id BIGINT NOT NULL,"
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "notif_type VARCHAR(32) NOT NULL,"
                    + "title VARCHAR(255) NOT NULL,"
                    + "message " + longText + " NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "ack_at BIGINT NOT NULL DEFAULT 0"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("auction_cooldowns") + " ("
                    + "res_uuid VARCHAR(36) PRIMARY KEY,"
                    + "cooldown_anchor_at BIGINT NOT NULL"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("auction_tx_log") + " ("
                    + "id " + autoId + ","
                    + "auction_id BIGINT NOT NULL,"
                    + "actor_uuid VARCHAR(36),"
                    + "actor_name VARCHAR(64),"
                    + "event_type VARCHAR(32) NOT NULL,"
                    + "amount BIGINT NOT NULL DEFAULT 0,"
                    + "detail VARCHAR(512),"
                    + "created_at BIGINT NOT NULL"
                    + ")");

            createIndexSafe(st, "idx_" + t("auctions") + "_status", t("auctions"), "status, ends_at");
            createIndexSafe(st, "idx_" + t("auctions") + "_res_uuid", t("auctions"), "res_uuid, status");
            createIndexSafe(st, "idx_" + t("auctions") + "_orig_owner", t("auctions"), "original_owner_uuid, status");
            createIndexSafe(st, "idx_" + t("auction_bids") + "_auction", t("auction_bids"), "auction_id, id");
            createIndexSafe(st, "idx_" + t("auction_payouts") + "_status", t("auction_payouts"), "status, retry_count");
            createIndexSafe(st, "idx_" + t("auction_notif") + "_player", t("auction_notifications"), "player_uuid, ack_at");
        }
    }

    private void createIndexSafe(Statement st, String idxName, String tableName, String columns) {
        try {
            st.executeUpdate("CREATE INDEX " + idxName + " ON " + tableName + " (" + columns + ")");
        } catch (SQLException ignored) {
            // 索引已存在時安全略過
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection conn) throws SQLException;
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        if (isSqlite()) {
            synchronized (db.getSqliteWriteLock()) {
                return runTransaction(work);
            }
        }
        return runTransaction(work);
    }

    private <T> T runTransaction(SqlWork<T> work) throws SQLException {
        try (Connection conn = db.openConnection()) {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = work.execute(conn);
                conn.commit();
                return result;
            } catch (Throwable ex) {
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                if (ex instanceof SQLException) {
                    throw (SQLException) ex;
                }
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new SQLException(ex);
            } finally {
                try {
                    conn.setAutoCommit(prevAuto);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /**
     * 在單一交易內檢查重複開拍、重上架冷卻與活躍場次上限後，建立新拍賣。
     */
    public CreateResult createAuction(CreateRequest req, AuctionPolicy policy, boolean bypassLimits) throws SQLException {
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();

            // 1. 檢查同一領地 UUID 是否已有進行中／中止中／結標中拍賣
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM " + t("auctions") + " WHERE res_uuid = ? AND status IN ('ACTIVE','ABORT_PENDING','SETTLING')" + forUpdateClause())) {
                ps.setString(1, req.resUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return CreateResult.duplicate();
                    }
                }
            }

            // 2. 檢查該玩家目前活躍拍賣場次
            int activeCount = 0;
            if (req.type == AuctionType.PLAYER) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM " + t("auctions") + " WHERE creator_uuid = ? AND auction_type = 'PLAYER' AND status = 'ACTIVE'")) {
                    ps.setString(1, req.creatorUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            activeCount = rs.getInt(1);
                        }
                    }
                }
            }

            // 3. 檢查該領地 UUID 重上架冷卻基準時間
            long cooldownAnchor = 0L;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cooldown_anchor_at FROM " + t("auction_cooldowns") + " WHERE res_uuid = ?")) {
                ps.setString(1, req.resUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cooldownAnchor = rs.getLong(1);
                    }
                }
            }

            // 4. 呼叫純 Java 核心規則檢核
            ValidationResult vr = AuctionCore.validateCreate(
                    req.type, req.clearMode, req.startPrice, req.minIncrement, req.durationMinutes,
                    activeCount, cooldownAnchor, bypassLimits, policy, now);
            if (!vr.isOk()) {
                return CreateResult.fail(vr.getError(), vr.getDetailNumber());
            }

            long endsAt = now + (req.durationMinutes * 60_000L);
            long generatedId;
            String insertSql = "INSERT INTO " + t("auctions") + " ("
                    + "origin_server, res_uuid, res_key, res_name, world_name, tp_x, tp_y, tp_z, "
                    + "auction_type, status, clear_mode, creator_uuid, creator_name, "
                    + "original_owner_uuid, original_owner_name, start_price, min_increment, "
                    + "current_bid, highest_bidder_uuid, highest_bidder_name, bid_count, "
                    + "description, created_at, ends_at, anti_snipe_count, permission_backup"
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,NULL,NULL,0,?,?,?,0,?)";

            try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, req.originServer);
                ps.setString(2, req.resUuid.toString());
                ps.setString(3, req.resKey);
                ps.setString(4, req.resName);
                ps.setString(5, req.worldName);
                ps.setDouble(6, req.tpX);
                ps.setDouble(7, req.tpY);
                ps.setDouble(8, req.tpZ);
                ps.setString(9, req.type.name());
                ps.setString(10, AuctionStatus.ACTIVE.name());
                ps.setString(11, req.clearMode.name());
                ps.setString(12, req.creatorUuid.toString());
                ps.setString(13, req.creatorName);
                ps.setString(14, req.originalOwnerUuid == null ? null : req.originalOwnerUuid.toString());
                ps.setString(15, req.originalOwnerName);
                ps.setLong(16, req.startPrice);
                ps.setLong(17, req.minIncrement);
                ps.setString(18, req.description);
                ps.setLong(19, now);
                ps.setLong(20, endsAt);
                ps.setString(21, req.permissionBackup);
                ps.executeUpdate();
                generatedId = readGeneratedId(conn, ps);
            }

            insertTxLog(conn, generatedId, req.creatorUuid, req.creatorName, "CREATE", req.startPrice,
                    "type=" + req.type.name() + ",clearMode=" + req.clearMode.name(), now);

            AuctionRecord created = new AuctionRecord(
                    generatedId, req.originServer, req.resUuid, req.resKey, req.resName, req.worldName,
                    req.tpX, req.tpY, req.tpZ, req.type, AuctionStatus.ACTIVE, req.clearMode,
                    req.creatorUuid, req.creatorName, req.originalOwnerUuid, req.originalOwnerName,
                    req.startPrice, req.minIncrement, 0L, null, null, 0,
                    req.description, now, endsAt, 0, req.permissionBackup);
            return CreateResult.success(created);
        });
    }

    /**
     * 原子化喊價交易：鎖定拍賣列 -> 規則驗證 -> 執行扣款 -> 更新最高出價與延時 -> 寫入前一位出價者的 Outbox 退款單 -> COMMIT。
     */
    public BidPlacementResult placeBid(
            long auctionId,
            UUID bidderUuid,
            String bidderName,
            long bidAmount,
            double bidderBalance,
            boolean bidderIsStaff,
            AuctionPolicy policy,
            BooleanSupplier withdrawCallback) throws SQLException {
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();
            AuctionRecord current = loadAuctionLocked(conn, auctionId);
            ValidationResult vr = AuctionCore.validateBid(current, bidderUuid, bidAmount, bidderBalance, bidderIsStaff, now);
            if (!vr.isOk()) {
                return new BidPlacementResult(false, vr.getError(), vr.getDetailNumber(), false,
                        current, null, null, 0L, null, null);
            }

            // 確認在交易鎖定下扣款成功；若扣款失敗則不寫入任何資料直接返回
            if (withdrawCallback != null && !withdrawCallback.getAsBoolean()) {
                return new BidPlacementResult(false, ValidationError.INSUFFICIENT_BALANCE, bidAmount, true,
                        current, null, null, 0L, null, null);
            }

            AntiSnipeOutcome snipe = AuctionCore.computeAntiSnipe(current, policy, now);
            long newEndsAt = snipe.getNewEndsAt();
            int newSnipeCount = snipe.getNewExtensionCount();
            int newBidCount = current.getBidCount() + 1;

            UUID prevBidderUuid = current.hasBids() ? current.getHighestBidderUuid() : null;
            String prevBidderName = current.hasBids() ? current.getHighestBidderName() : null;
            long prevBidAmount = current.hasBids() ? current.getCurrentBid() : 0L;

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auctions") + " SET current_bid=?, highest_bidder_uuid=?, highest_bidder_name=?, "
                            + "bid_count=?, ends_at=?, anti_snipe_count=? WHERE id=?")) {
                ps.setLong(1, bidAmount);
                ps.setString(2, bidderUuid.toString());
                ps.setString(3, bidderName);
                ps.setInt(4, newBidCount);
                ps.setLong(5, newEndsAt);
                ps.setInt(6, newSnipeCount);
                ps.setLong(7, auctionId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + t("auction_bids") + " (auction_id, bidder_uuid, bidder_name, amount, created_at) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, auctionId);
                ps.setString(2, bidderUuid.toString());
                ps.setString(3, bidderName);
                ps.setLong(4, bidAmount);
                ps.setLong(5, now);
                ps.executeUpdate();
            }

            PayoutRecord refundPayout = null;
            if (prevBidderUuid != null && prevBidAmount > 0L) {
                refundPayout = insertPayout(conn, auctionId, prevBidderUuid, prevBidderName, prevBidAmount, PAYOUT_TYPE_REFUND, now);
            }

            insertTxLog(conn, auctionId, bidderUuid, bidderName, "BID", bidAmount,
                    snipe.isExtended() ? "anti_snipe_extended" : "", now);

            AuctionRecord updated = new AuctionRecord(
                    current.getId(), current.getOriginServer(), current.getResUuid(), current.getResKey(),
                    current.getResName(), current.getWorldName(), current.getTpX(), current.getTpY(), current.getTpZ(),
                    current.getType(), current.getStatus(), current.getClearMode(),
                    current.getCreatorUuid(), current.getCreatorName(),
                    current.getOriginalOwnerUuid(), current.getOriginalOwnerName(),
                    current.getStartPrice(), current.getMinIncrement(),
                    bidAmount, bidderUuid, bidderName, newBidCount,
                    current.getDescription(), current.getCreatedAt(), newEndsAt, newSnipeCount,
                    current.getPermissionBackup());

            return new BidPlacementResult(true, ValidationError.NONE, 0L, false,
                    updated, prevBidderUuid, prevBidderName, prevBidAmount, refundPayout, snipe);
        });
    }

    /**
     * 原子化取消拍賣（賣方僅能於無人喊價時取消；管理員可隨時取消並產生退款 Outbox）。
     */
    public CancelResult cancelAuction(long auctionId, UUID actorUuid, String actorName, boolean isAdmin, String reason) throws SQLException {
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();
            AuctionRecord current = loadAuctionLocked(conn, auctionId);
            ValidationResult vr = AuctionCore.validateCancel(current, actorUuid, isAdmin);
            if (!vr.isOk()) {
                return new CancelResult(false, vr.getError(), current, null, Collections.emptyList());
            }

            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("auctions") + " SET status=? WHERE id=?")) {
                ps.setString(1, AuctionStatus.CANCELLED.name());
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }

            PayoutRecord refund = null;
            if (current.hasBids()) {
                refund = insertPayout(conn, auctionId, current.getHighestBidderUuid(), current.getHighestBidderName(),
                        current.getCurrentBid(), PAYOUT_TYPE_REFUND, now);
            }

            // 記錄重上架冷卻（以領地 UUID 為準，改名無效）
            upsertCooldown(conn, current.getResUuid(), now);

            List<UUID> bidders = loadDistinctBidders(conn, auctionId);
            insertTxLog(conn, auctionId, actorUuid, actorName, "CANCEL", current.getCurrentBid(),
                    reason == null ? "" : reason, now);

            AuctionRecord updated = withStatus(current, AuctionStatus.CANCELLED);
            return new CancelResult(true, ValidationError.NONE, updated, refund, bidders);
        });
    }

    /**
     * 更新進行中拍賣的最低加價金額（僅套用於後續喊價）。
     */
    public AuctionRecord updateMinIncrement(long auctionId, UUID actorUuid, String actorName, boolean isAdmin, long newIncrement) throws SQLException {
        if (newIncrement < 1L) {
            return null;
        }
        return inTransaction(conn -> {
            AuctionRecord current = loadAuctionLocked(conn, auctionId);
            if (current == null || current.getStatus() != AuctionStatus.ACTIVE) {
                return null;
            }
            if (!isAdmin && (actorUuid == null || !actorUuid.equals(current.getCreatorUuid()))) {
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("auctions") + " SET min_increment=? WHERE id=?")) {
                ps.setLong(1, newIncrement);
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }
            insertTxLog(conn, auctionId, actorUuid, actorName, "UPDATE_INCREMENT", newIncrement, "", System.currentTimeMillis());
            return new AuctionRecord(
                    current.getId(), current.getOriginServer(), current.getResUuid(), current.getResKey(),
                    current.getResName(), current.getWorldName(), current.getTpX(), current.getTpY(), current.getTpZ(),
                    current.getType(), current.getStatus(), current.getClearMode(),
                    current.getCreatorUuid(), current.getCreatorName(),
                    current.getOriginalOwnerUuid(), current.getOriginalOwnerName(),
                    current.getStartPrice(), newIncrement,
                    current.getCurrentBid(), current.getHighestBidderUuid(), current.getHighestBidderName(),
                    current.getBidCount(), current.getDescription(), current.getCreatedAt(), current.getEndsAt(),
                    current.getAntiSnipeCount(), current.getPermissionBackup());
        });
    }

    /**
     * 保管領地原領主登入任一分流時呼叫：原子化將所有進行中的該原領主保管拍賣標記為 ABORT_PENDING，
     * 立即鎖死任何分流的新出價與結標。
     */
    public List<AuctionRecord> markAbortPendingByOriginalOwner(UUID originalOwnerUuid) throws SQLException {
        if (originalOwnerUuid == null) {
            return Collections.emptyList();
        }
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();
            List<AuctionRecord> affected = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM " + t("auctions") + " WHERE original_owner_uuid = ? AND auction_type = 'ARCHIVED' AND status = 'ACTIVE'" + forUpdateClause())) {
                ps.setString(1, originalOwnerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        affected.add(mapAuction(rs));
                    }
                }
            }
            if (affected.isEmpty()) {
                return affected;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auctions") + " SET status = ? WHERE id = ?")) {
                for (AuctionRecord rec : affected) {
                    ps.setString(1, AuctionStatus.ABORT_PENDING.name());
                    ps.setLong(2, rec.getId());
                    ps.executeUpdate();
                    insertTxLog(conn, rec.getId(), originalOwnerUuid, rec.getOriginalOwnerName(),
                            "ABORT_PENDING", rec.getCurrentBid(), "original_owner_returned", now);
                }
            }
            List<AuctionRecord> result = new ArrayList<>(affected.size());
            for (AuctionRecord rec : affected) {
                result.add(withStatus(rec, AuctionStatus.ABORT_PENDING));
            }
            return result;
        });
    }

    /**
     * 由領地所屬分流（origin_server）領取所有 ABORT_PENDING 拍賣，轉為 ABORTED 並產生最高價者退款 Outbox。
     */
    public List<AbortClaim> claimAbortPendingForServer(String originServer) throws SQLException {
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();
            List<AuctionRecord> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM " + t("auctions") + " WHERE origin_server = ? AND status = 'ABORT_PENDING'" + forUpdateClause())) {
                ps.setString(1, originServer);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(mapAuction(rs));
                    }
                }
            }
            List<AbortClaim> claims = new ArrayList<>();
            for (AuctionRecord rec : rows) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("auctions") + " SET status = ? WHERE id = ?")) {
                    ps.setString(1, AuctionStatus.ABORTED.name());
                    ps.setLong(2, rec.getId());
                    ps.executeUpdate();
                }
                PayoutRecord refund = null;
                if (rec.hasBids()) {
                    refund = insertPayout(conn, rec.getId(), rec.getHighestBidderUuid(), rec.getHighestBidderName(),
                            rec.getCurrentBid(), PAYOUT_TYPE_REFUND, now);
                }
                List<UUID> bidders = loadDistinctBidders(conn, rec.getId());
                insertTxLog(conn, rec.getId(), rec.getOriginalOwnerUuid(), rec.getOriginalOwnerName(),
                        "ABORTED", rec.getCurrentBid(), "refund_and_restored", now);
                claims.add(new AbortClaim(withStatus(rec, AuctionStatus.ABORTED), refund, bidders));
            }
            return claims;
        });
    }

    /**
     * 由領地所屬分流（origin_server）原子化領取到期拍賣：
     * - 無人喊價 -> 直接標記 NO_BID（並記錄重上架冷卻）
     * - 有人喊價 -> 標記 SETTLING（交由主執行緒完成轉讓、可選清空與放款）
     */
    public List<DueClaim> claimDueAuctionsForServer(String originServer, long nowMs) throws SQLException {
        return inTransaction(conn -> {
            List<AuctionRecord> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM " + t("auctions") + " WHERE origin_server = ? AND status = 'ACTIVE' AND ends_at <= ?" + forUpdateClause())) {
                ps.setString(1, originServer);
                ps.setLong(2, nowMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(mapAuction(rs));
                    }
                }
            }
            List<DueClaim> claims = new ArrayList<>();
            for (AuctionRecord rec : rows) {
                if (rec.hasBids()) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("auctions") + " SET status = ? WHERE id = ?")) {
                        ps.setString(1, AuctionStatus.SETTLING.name());
                        ps.setLong(2, rec.getId());
                        ps.executeUpdate();
                    }
                    insertTxLog(conn, rec.getId(), rec.getHighestBidderUuid(), rec.getHighestBidderName(),
                            "SETTLING", rec.getCurrentBid(), "", nowMs);
                    claims.add(new DueClaim(withStatus(rec, AuctionStatus.SETTLING), true));
                } else {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("auctions") + " SET status = ? WHERE id = ?")) {
                        ps.setString(1, AuctionStatus.NO_BID.name());
                        ps.setLong(2, rec.getId());
                        ps.executeUpdate();
                    }
                    if (rec.getType() == AuctionType.PLAYER) {
                        upsertCooldown(conn, rec.getResUuid(), nowMs);
                    }
                    insertTxLog(conn, rec.getId(), rec.getCreatorUuid(), rec.getCreatorName(), "NO_BID", 0L, "", nowMs);
                    claims.add(new DueClaim(withStatus(rec, AuctionStatus.NO_BID), false));
                }
            }
            return claims;
        });
    }

    /**
     * 完成成交移轉後，原子將 SETTLING -> SETTLED 並建立賣方／原領主實收款 Outbox（直接入帳用）。
     */
    public PayoutRecord finalizeSettledAuction(
            long auctionId, UUID recipientUuid, String recipientName, long gross, long fee, long net) throws SQLException {
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();
            AuctionRecord current = loadAuctionLocked(conn, auctionId);
            if (current == null || current.getStatus() != AuctionStatus.SETTLING) {
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("auctions") + " SET status = ? WHERE id = ?")) {
                ps.setString(1, AuctionStatus.SETTLED.name());
                ps.setLong(2, auctionId);
                ps.executeUpdate();
            }
            PayoutRecord payout = null;
            if (recipientUuid != null && net > 0L) {
                payout = insertPayout(conn, auctionId, recipientUuid, recipientName == null ? "" : recipientName,
                        net, PAYOUT_TYPE_SETTLEMENT, now);
            }
            insertTxLog(conn, auctionId, recipientUuid, recipientName, "SETTLED", gross,
                    "fee=" + fee + ",net=" + net, now);
            return payout;
        });
    }

    /**
     * 冪等搶佔退款／入帳任務（只有從 PENDING 成功更新為 PROCESSING 的執行緒才可呼叫 Vault 付款）。
     */
    public boolean claimPayoutForProcessing(long payoutId) throws SQLException {
        return inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auction_payouts") + " SET status=?, updated_at=? WHERE id=? AND status=?")) {
                ps.setString(1, PAYOUT_STATUS_PROCESSING);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, payoutId);
                ps.setString(4, PAYOUT_STATUS_PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public void markPayoutCompleted(long payoutId) throws SQLException {
        inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auction_payouts") + " SET status=?, last_error='', updated_at=? WHERE id=?")) {
                ps.setString(1, PAYOUT_STATUS_COMPLETED);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, payoutId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void markPayoutFailed(long payoutId, String error, boolean permanentFail) throws SQLException {
        inTransaction(conn -> {
            String nextStatus = permanentFail ? PAYOUT_STATUS_FAILED : PAYOUT_STATUS_PENDING;
            String errTrim = error == null ? "" : (error.length() > 240 ? error.substring(0, 240) : error);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auction_payouts") + " SET status=?, retry_count=retry_count+1, last_error=?, updated_at=? WHERE id=?")) {
                ps.setString(1, nextStatus);
                ps.setString(2, errTrim);
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, payoutId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 復原超過 60 秒卡在 PROCESSING 的異常任務（例如付款前瞬間當機），並載入所有待重試的 PENDING 出款單。
     */
    public List<PayoutRecord> loadRetryablePayouts(int maxRetries) throws SQLException {
        long staleBefore = System.currentTimeMillis() - 60_000L;
        inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auction_payouts") + " SET status=? WHERE status=? AND updated_at < ?")) {
                ps.setString(1, PAYOUT_STATUS_PENDING);
                ps.setString(2, PAYOUT_STATUS_PROCESSING);
                ps.setLong(3, staleBefore);
                ps.executeUpdate();
            }
            return null;
        });

        List<PayoutRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auction_payouts") + " WHERE status=? AND retry_count < ? ORDER BY id ASC LIMIT 50")) {
            ps.setString(1, PAYOUT_STATUS_PENDING);
            ps.setInt(2, maxRetries);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPayout(rs));
                }
            }
        }
        return list;
    }

    public List<PayoutRecord> loadRecentPayouts(int limit) throws SQLException {
        List<PayoutRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auction_payouts") + " ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPayout(rs));
                }
            }
        }
        return list;
    }

    public long insertNotification(long auctionId, UUID playerUuid, String notifType, String title, String message) throws SQLException {
        if (playerUuid == null) {
            return -1L;
        }
        return inTransaction(conn -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + t("auction_notifications") + " (auction_id, player_uuid, notif_type, title, message, created_at, ack_at) VALUES (?,?,?,?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, auctionId);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, notifType == null ? "INFO" : notifType);
                ps.setString(4, title == null ? "" : title);
                ps.setString(5, message == null ? "" : message);
                ps.setLong(6, now);
                ps.executeUpdate();
                return readGeneratedId(conn, ps);
            }
        });
    }

    public List<NotificationRecord> loadUnackedNotifications(UUID playerUuid) throws SQLException {
        if (playerUuid == null) {
            return Collections.emptyList();
        }
        List<NotificationRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auction_notifications") + " WHERE player_uuid=? AND ack_at=0 ORDER BY id DESC LIMIT 45")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapNotification(rs));
                }
            }
        }
        return list;
    }

    public int countUnackedNotifications(UUID playerUuid) throws SQLException {
        if (playerUuid == null) {
            return 0;
        }
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + t("auction_notifications") + " WHERE player_uuid=? AND ack_at=0")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean acknowledgeNotification(UUID playerUuid, long notifId) throws SQLException {
        if (playerUuid == null) {
            return false;
        }
        return inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auction_notifications") + " SET ack_at=? WHERE id=? AND player_uuid=? AND ack_at=0")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, notifId);
                ps.setString(3, playerUuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public int acknowledgeAllNotifications(UUID playerUuid) throws SQLException {
        if (playerUuid == null) {
            return 0;
        }
        return inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t("auction_notifications") + " SET ack_at=? WHERE player_uuid=? AND ack_at=0")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, playerUuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    public List<AuctionRecord> loadAllActiveAuctions() throws SQLException {
        List<AuctionRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auctions") + " WHERE status = 'ACTIVE' ORDER BY ends_at ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAuction(rs));
                }
            }
        }
        return list;
    }

    public List<AuctionRecord> loadSettlingAuctionsForServer(String originServer) throws SQLException {
        List<AuctionRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auctions") + " WHERE origin_server=? AND status='SETTLING' ORDER BY id ASC")) {
            ps.setString(1, originServer);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAuction(rs));
                }
            }
        }
        return list;
    }

    public AuctionRecord loadAuctionById(long id) throws SQLException {
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + t("auctions") + " WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapAuction(rs) : null;
            }
        }
    }

    public AuctionRecord loadActiveAuctionByResUuid(UUID resUuid) throws SQLException {
        if (resUuid == null) {
            return null;
        }
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auctions") + " WHERE res_uuid=? AND status='ACTIVE' LIMIT 1")) {
            ps.setString(1, resUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapAuction(rs) : null;
            }
        }
    }

    public boolean isResidenceInActiveOrPendingAuction(UUID resUuid) throws SQLException {
        if (resUuid == null) {
            return false;
        }
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM " + t("auctions") + " WHERE res_uuid=? AND status IN ('ACTIVE','ABORT_PENDING','SETTLING') LIMIT 1")) {
            ps.setString(1, resUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<BidEntry> loadBidsForAuction(long auctionId, int limit) throws SQLException {
        List<BidEntry> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM " + t("auction_bids") + " WHERE auction_id=? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, auctionId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new BidEntry(
                            rs.getLong("id"),
                            rs.getLong("auction_id"),
                            parseUuid(rs.getString("bidder_uuid")),
                            rs.getString("bidder_name"),
                            rs.getLong("amount"),
                            rs.getLong("created_at")));
                }
            }
        }
        return list;
    }

    public Set<Long> loadParticipantAuctionIds(UUID playerUuid) throws SQLException {
        if (playerUuid == null) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        try (Connection conn = db.openConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DISTINCT auction_id FROM " + t("auction_bids") + " WHERE bidder_uuid=?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM " + t("auctions") + " WHERE creator_uuid=? OR original_owner_uuid=?")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
        }
        return ids;
    }

    public long getRelistCooldownAnchor(UUID resUuid) throws SQLException {
        if (resUuid == null) {
            return 0L;
        }
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT cooldown_anchor_at FROM " + t("auction_cooldowns") + " WHERE res_uuid=?")) {
            ps.setString(1, resUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private AuctionRecord loadAuctionLocked(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM " + t("auctions") + " WHERE id=?" + forUpdateClause())) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapAuction(rs) : null;
            }
        }
    }

    private PayoutRecord insertPayout(
            Connection conn, long auctionId, UUID recipientUuid, String recipientName,
            long amount, String payoutType, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + t("auction_payouts") + " ("
                        + "auction_id, recipient_uuid, recipient_name, amount, payout_type, status, retry_count, last_error, created_at, updated_at"
                        + ") VALUES (?,?,?,?,?,?,0,'',?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, auctionId);
            ps.setString(2, recipientUuid.toString());
            ps.setString(3, recipientName == null ? "" : recipientName);
            ps.setLong(4, amount);
            ps.setString(5, payoutType);
            ps.setString(6, PAYOUT_STATUS_PENDING);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
            long id = readGeneratedId(conn, ps);
            return new PayoutRecord(id, auctionId, recipientUuid, recipientName, amount, payoutType, PAYOUT_STATUS_PENDING, 0, "", now, now);
        }
    }

    private void upsertCooldown(Connection conn, UUID resUuid, long anchorAt) throws SQLException {
        if (resUuid == null) {
            return;
        }
        String sql;
        if (isPostgres() || isSqlite()) {
            sql = "INSERT INTO " + t("auction_cooldowns") + " (res_uuid, cooldown_anchor_at) VALUES (?,?) "
                    + "ON CONFLICT(res_uuid) DO UPDATE SET cooldown_anchor_at=excluded.cooldown_anchor_at";
        } else {
            sql = "INSERT INTO " + t("auction_cooldowns") + " (res_uuid, cooldown_anchor_at) VALUES (?,?) "
                    + "ON DUPLICATE KEY UPDATE cooldown_anchor_at=VALUES(cooldown_anchor_at)";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resUuid.toString());
            ps.setLong(2, anchorAt);
            ps.executeUpdate();
        }
    }

    private void insertTxLog(
            Connection conn, long auctionId, UUID actorUuid, String actorName,
            String eventType, long amount, String detail, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + t("auction_tx_log") + " (auction_id, actor_uuid, actor_name, event_type, amount, detail, created_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setLong(1, auctionId);
            ps.setString(2, actorUuid == null ? null : actorUuid.toString());
            ps.setString(3, actorName);
            ps.setString(4, eventType);
            ps.setLong(5, amount);
            ps.setString(6, detail == null ? "" : (detail.length() > 500 ? detail.substring(0, 500) : detail));
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    private List<UUID> loadDistinctBidders(Connection conn, long auctionId) throws SQLException {
        Set<UUID> set = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT bidder_uuid FROM " + t("auction_bids") + " WHERE auction_id=?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID u = parseUuid(rs.getString(1));
                    if (u != null) {
                        set.add(u);
                    }
                }
            }
        }
        return new ArrayList<>(set);
    }

    private long readGeneratedId(Connection conn, PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
        } catch (SQLException ignored) {
        }
        if (isSqlite()) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return -1L;
    }

    private static AuctionRecord mapAuction(ResultSet rs) throws SQLException {
        return new AuctionRecord(
                rs.getLong("id"),
                rs.getString("origin_server"),
                parseUuid(rs.getString("res_uuid")),
                rs.getString("res_key"),
                rs.getString("res_name"),
                rs.getString("world_name"),
                rs.getDouble("tp_x"),
                rs.getDouble("tp_y"),
                rs.getDouble("tp_z"),
                AuctionType.fromString(rs.getString("auction_type")),
                AuctionStatus.fromString(rs.getString("status")),
                ClearMode.fromString(rs.getString("clear_mode")),
                parseUuid(rs.getString("creator_uuid")),
                rs.getString("creator_name"),
                parseUuid(rs.getString("original_owner_uuid")),
                rs.getString("original_owner_name"),
                rs.getLong("start_price"),
                rs.getLong("min_increment"),
                rs.getLong("current_bid"),
                parseUuid(rs.getString("highest_bidder_uuid")),
                rs.getString("highest_bidder_name"),
                rs.getInt("bid_count"),
                rs.getString("description"),
                rs.getLong("created_at"),
                rs.getLong("ends_at"),
                rs.getInt("anti_snipe_count"),
                rs.getString("permission_backup"));
    }

    private static PayoutRecord mapPayout(ResultSet rs) throws SQLException {
        return new PayoutRecord(
                rs.getLong("id"),
                rs.getLong("auction_id"),
                parseUuid(rs.getString("recipient_uuid")),
                rs.getString("recipient_name"),
                rs.getLong("amount"),
                rs.getString("payout_type"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                rs.getString("last_error"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static NotificationRecord mapNotification(ResultSet rs) throws SQLException {
        return new NotificationRecord(
                rs.getLong("id"),
                rs.getLong("auction_id"),
                parseUuid(rs.getString("player_uuid")),
                rs.getString("notif_type"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getLong("created_at"),
                rs.getLong("ack_at"));
    }

    private static AuctionRecord withStatus(AuctionRecord r, AuctionStatus status) {
        return new AuctionRecord(
                r.getId(), r.getOriginServer(), r.getResUuid(), r.getResKey(), r.getResName(), r.getWorldName(),
                r.getTpX(), r.getTpY(), r.getTpZ(), r.getType(), status, r.getClearMode(),
                r.getCreatorUuid(), r.getCreatorName(), r.getOriginalOwnerUuid(), r.getOriginalOwnerName(),
                r.getStartPrice(), r.getMinIncrement(), r.getCurrentBid(), r.getHighestBidderUuid(),
                r.getHighestBidderName(), r.getBidCount(), r.getDescription(), r.getCreatedAt(), r.getEndsAt(),
                r.getAntiSnipeCount(), r.getPermissionBackup());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
