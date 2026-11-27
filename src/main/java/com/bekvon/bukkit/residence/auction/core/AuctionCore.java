package com.bekvon.bukkit.residence.auction.core;

import java.util.UUID;

/**
 * 領地拍賣核心商業邏輯（完全與 Bukkit API 隔離，供單元測試與服務層共用）。
 * 包含喊價驗證、狀態機轉換檢查、防尾盤狙擊延時、手續費計算、開拍與取消檢核。
 */
public final class AuctionCore {

    public enum ValidationError {
        NONE,
        AUCTION_NOT_ACTIVE,
        AUCTION_ENDED,
        CANNOT_BID_OWN_AUCTION,
        ORIGINAL_OWNER_CANNOT_BID,
        STAFF_CANNOT_BID,
        ALREADY_HIGHEST_BIDDER,
        BID_TOO_LOW,
        INSUFFICIENT_BALANCE,
        INVALID_START_PRICE,
        INVALID_MIN_INCREMENT,
        DURATION_TOO_SHORT,
        DURATION_TOO_LONG,
        CLEAR_MODE_ADMIN_ONLY,
        MAX_ACTIVE_REACHED,
        RELIST_COOLDOWN_ACTIVE,
        CANNOT_CANCEL_AFTER_BID,
        NOT_AUTHORIZED
    }

    public static final class ValidationResult {
        private final ValidationError error;
        private final long detailNumber;

        private ValidationResult(ValidationError error, long detailNumber) {
            this.error = error;
            this.detailNumber = detailNumber;
        }

        public static ValidationResult ok() {
            return new ValidationResult(ValidationError.NONE, 0L);
        }

        public static ValidationResult fail(ValidationError error) {
            return new ValidationResult(error, 0L);
        }

        public static ValidationResult fail(ValidationError error, long detailNumber) {
            return new ValidationResult(error, detailNumber);
        }

        public boolean isOk() {
            return error == ValidationError.NONE;
        }

        public ValidationError getError() {
            return error;
        }

        public long getDetailNumber() {
            return detailNumber;
        }
    }

    public static final class SettlementBreakdown {
        private final long grossAmount;
        private final long feeAmount;
        private final long netPayout;

        public SettlementBreakdown(long grossAmount, long feeAmount, long netPayout) {
            this.grossAmount = grossAmount;
            this.feeAmount = feeAmount;
            this.netPayout = netPayout;
        }

        public long getGrossAmount() { return grossAmount; }
        public long getFeeAmount() { return feeAmount; }
        public long getNetPayout() { return netPayout; }
    }

    public static final class AntiSnipeOutcome {
        private final boolean extended;
        private final long newEndsAt;
        private final int newExtensionCount;

        public AntiSnipeOutcome(boolean extended, long newEndsAt, int newExtensionCount) {
            this.extended = extended;
            this.newEndsAt = newEndsAt;
            this.newExtensionCount = newExtensionCount;
        }

        public boolean isExtended() { return extended; }
        public long getNewEndsAt() { return newEndsAt; }
        public int getNewExtensionCount() { return newExtensionCount; }
    }

    private AuctionCore() {
    }

    /**
     * 計算下一口有效喊價的最低要求金額。
     * 尚無人出價時等於起標價；已有出價時等於目前最高價 + 最低加價金額。
     */
    public static long calculateMinRequiredBid(AuctionRecord auction) {
        if (auction == null) {
            return Long.MAX_VALUE;
        }
        if (!auction.hasBids()) {
            return Math.max(1L, auction.getStartPrice());
        }
        long sum = auction.getCurrentBid() + Math.max(1L, auction.getMinIncrement());
        return sum < auction.getCurrentBid() ? Long.MAX_VALUE : sum;
    }

    /**
     * 驗證一筆喊價是否合法。
     */
    public static ValidationResult validateBid(
            AuctionRecord auction,
            UUID bidderUuid,
            long bidAmount,
            double bidderBalance,
            boolean bidderIsStaff,
            long nowMs) {
        if (auction == null || auction.getStatus() != AuctionStatus.ACTIVE) {
            return ValidationResult.fail(ValidationError.AUCTION_NOT_ACTIVE);
        }
        if (nowMs >= auction.getEndsAt()) {
            return ValidationResult.fail(ValidationError.AUCTION_ENDED);
        }
        if (bidderUuid == null) {
            return ValidationResult.fail(ValidationError.NOT_AUTHORIZED);
        }
        if (auction.getType() == AuctionType.PLAYER && bidderUuid.equals(auction.getCreatorUuid())) {
            return ValidationResult.fail(ValidationError.CANNOT_BID_OWN_AUCTION);
        }
        if (auction.getType() == AuctionType.ARCHIVED) {
            if (bidderUuid.equals(auction.getOriginalOwnerUuid())) {
                return ValidationResult.fail(ValidationError.ORIGINAL_OWNER_CANNOT_BID);
            }
            if (bidderIsStaff) {
                return ValidationResult.fail(ValidationError.STAFF_CANNOT_BID);
            }
        }
        if (auction.getType() == AuctionType.ADMIN && bidderIsStaff) {
            return ValidationResult.fail(ValidationError.STAFF_CANNOT_BID);
        }
        if (auction.hasBids() && bidderUuid.equals(auction.getHighestBidderUuid())) {
            return ValidationResult.fail(ValidationError.ALREADY_HIGHEST_BIDDER);
        }
        long minRequired = calculateMinRequiredBid(auction);
        if (bidAmount < minRequired) {
            return ValidationResult.fail(ValidationError.BID_TOO_LOW, minRequired);
        }
        if (!Double.isFinite(bidderBalance) || bidderBalance + 1e-6 < bidAmount) {
            return ValidationResult.fail(ValidationError.INSUFFICIENT_BALANCE, bidAmount);
        }
        return ValidationResult.ok();
    }

    /**
     * 驗證建立拍賣的各項參數與冷卻限制。
     */
    public static ValidationResult validateCreate(
            AuctionType type,
            ClearMode clearMode,
            long startPrice,
            long minIncrement,
            long durationMinutes,
            int creatorActiveCount,
            long lastCooldownTimestampMs,
            boolean bypassLimits,
            AuctionPolicy policy,
            long nowMs) {
        if (policy == null) {
            policy = AuctionPolicy.defaults();
        }
        if (startPrice < policy.getMinStartPrice()) {
            return ValidationResult.fail(ValidationError.INVALID_START_PRICE, policy.getMinStartPrice());
        }
        if (minIncrement < 1L) {
            return ValidationResult.fail(ValidationError.INVALID_MIN_INCREMENT, 1L);
        }
        if (durationMinutes < policy.getMinDurationMinutes()) {
            return ValidationResult.fail(ValidationError.DURATION_TOO_SHORT, policy.getMinDurationMinutes());
        }
        long maxMinutes = policy.getMaxDurationHours() * 60L;
        if (durationMinutes > maxMinutes) {
            return ValidationResult.fail(ValidationError.DURATION_TOO_LONG, policy.getMaxDurationHours());
        }
        if (type == AuctionType.PLAYER && clearMode != null && clearMode != ClearMode.NONE) {
            return ValidationResult.fail(ValidationError.CLEAR_MODE_ADMIN_ONLY);
        }
        if (!bypassLimits && type == AuctionType.PLAYER && creatorActiveCount >= policy.getMaxActivePerPlayer()) {
            return ValidationResult.fail(ValidationError.MAX_ACTIVE_REACHED, policy.getMaxActivePerPlayer());
        }
        if (!bypassLimits && lastCooldownTimestampMs > 0L && policy.getRelistCooldownHours() > 0) {
            long cooldownMs = policy.getRelistCooldownHours() * 3_600_000L;
            long remainingMs = (lastCooldownTimestampMs + cooldownMs) - nowMs;
            if (remainingMs > 0L) {
                return ValidationResult.fail(ValidationError.RELIST_COOLDOWN_ACTIVE, remainingMs);
            }
        }
        return ValidationResult.ok();
    }

    /**
     * 驗證取消拍賣權限與條件：玩家拍賣一旦有人出價，賣方即不得取消；管理員可隨時取消。
     */
    public static ValidationResult validateCancel(AuctionRecord auction, UUID actorUuid, boolean isAdmin) {
        if (auction == null || auction.getStatus() != AuctionStatus.ACTIVE) {
            return ValidationResult.fail(ValidationError.AUCTION_NOT_ACTIVE);
        }
        if (isAdmin) {
            return ValidationResult.ok();
        }
        if (auction.getType() != AuctionType.PLAYER || actorUuid == null || !actorUuid.equals(auction.getCreatorUuid())) {
            return ValidationResult.fail(ValidationError.NOT_AUTHORIZED);
        }
        if (auction.hasBids()) {
            return ValidationResult.fail(ValidationError.CANNOT_CANCEL_AFTER_BID);
        }
        return ValidationResult.ok();
    }

    /**
     * 計算成交手續費與賣方／原領主實收金額，確保手續費 + 實收 = 成交總額。
     */
    public static SettlementBreakdown calculateSettlement(long winningBid, int feePercent) {
        long safeBid = Math.max(0L, winningBid);
        int clampedFee = Math.max(0, Math.min(100, feePercent));
        long fee = Math.round(safeBid * (clampedFee / 100.0d));
        if (fee > safeBid) {
            fee = safeBid;
        }
        long net = safeBid - fee;
        return new SettlementBreakdown(safeBid, fee, net);
    }

    /**
     * 計算防尾盤狙擊延時：當剩餘時間 ≤ antiSnipeSeconds 且未達延時次數上限時延長結束時間。
     */
    public static AntiSnipeOutcome computeAntiSnipe(AuctionRecord auction, AuctionPolicy policy, long nowMs) {
        if (auction == null || policy == null) {
            return new AntiSnipeOutcome(false, auction == null ? 0L : auction.getEndsAt(), auction == null ? 0 : auction.getAntiSnipeCount());
        }
        if (policy.getAntiSnipeSeconds() <= 0 || policy.getAntiSnipeExtendSeconds() <= 0) {
            return new AntiSnipeOutcome(false, auction.getEndsAt(), auction.getAntiSnipeCount());
        }
        if (policy.getMaxAntiSnipeExtensions() > 0 && auction.getAntiSnipeCount() >= policy.getMaxAntiSnipeExtensions()) {
            return new AntiSnipeOutcome(false, auction.getEndsAt(), auction.getAntiSnipeCount());
        }
        long remainingMs = auction.getEndsAt() - nowMs;
        long thresholdMs = policy.getAntiSnipeSeconds() * 1000L;
        if (remainingMs > 0L && remainingMs <= thresholdMs) {
            long extendedEndsAt = auction.getEndsAt() + (policy.getAntiSnipeExtendSeconds() * 1000L);
            return new AntiSnipeOutcome(true, extendedEndsAt, auction.getAntiSnipeCount() + 1);
        }
        return new AntiSnipeOutcome(false, auction.getEndsAt(), auction.getAntiSnipeCount());
    }

    /**
     * 嚴格檢查拍賣狀態轉換是否合法。
     */
    public static boolean canTransition(AuctionStatus from, AuctionStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        switch (from) {
        case ACTIVE:
            return to == AuctionStatus.ABORT_PENDING
                    || to == AuctionStatus.CANCELLED
                    || to == AuctionStatus.SETTLING
                    || to == AuctionStatus.NO_BID;
        case ABORT_PENDING:
            return to == AuctionStatus.ABORTED;
        case SETTLING:
            return to == AuctionStatus.SETTLED;
        default:
            return false;
        }
    }
}
