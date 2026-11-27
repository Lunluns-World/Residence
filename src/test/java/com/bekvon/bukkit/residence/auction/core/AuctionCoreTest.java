package com.bekvon.bukkit.residence.auction.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bekvon.bukkit.residence.auction.core.AuctionCore.AntiSnipeOutcome;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.SettlementBreakdown;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.ValidationError;
import com.bekvon.bukkit.residence.auction.core.AuctionCore.ValidationResult;

/**
 * 領地拍賣核心商業邏輯（AuctionCore）單元測試。
 */
class AuctionCoreTest {

    private static final UUID SELLER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORIG_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BIDDER_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BIDDER_B = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID STAFF = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID RES_UUID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private AuctionRecord sample(AuctionType type, AuctionStatus status, long startPrice, long minInc,
            long currentBid, UUID highestBidder, int bidCount, long endsAt, int snipeCount) {
        return new AuctionRecord(
                1L, "survival-1", RES_UUID, "myres", "MyRes", "world", 0, 64, 0,
                type, status, ClearMode.NONE, SELLER, "Seller", ORIG_OWNER, "OrigOwner",
                startPrice, minInc, currentBid, highestBidder, highestBidder == null ? null : "Bidder",
                bidCount, "desc", 1_000_000L, endsAt, snipeCount, "{}");
    }

    @Test
    void minRequiredBidHandlesInitialAndSubsequentBids() {
        AuctionRecord fresh = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 5000L, 250L, 0L, null, 0, 2_000_000L, 0);
        assertEquals(5000L, AuctionCore.calculateMinRequiredBid(fresh));

        AuctionRecord active = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 5000L, 250L, 6000L, BIDDER_A, 2, 2_000_000L, 0);
        assertEquals(6250L, AuctionCore.calculateMinRequiredBid(active));
    }

    @Test
    void validateBidEnforcesAllRoleAndAmountRules() {
        long now = 1_500_000L;
        long ends = 2_000_000L;
        AuctionRecord playerAuc = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 1000L, 100L, 0L, null, 0, ends, 0);

        // 賣方不可對自己喊價
        assertEquals(ValidationError.CANNOT_BID_OWN_AUCTION,
                AuctionCore.validateBid(playerAuc, SELLER, 1000L, 50000D, false, now).getError());

        // 喊價過低
        ValidationResult low = AuctionCore.validateBid(playerAuc, BIDDER_A, 999L, 50000D, false, now);
        assertFalse(low.isOk());
        assertEquals(ValidationError.BID_TOO_LOW, low.getError());
        assertEquals(1000L, low.getDetailNumber());

        // 餘額不足
        assertEquals(ValidationError.INSUFFICIENT_BALANCE,
                AuctionCore.validateBid(playerAuc, BIDDER_A, 1000L, 999.5D, false, now).getError());

        // 正常喊價通過
        assertTrue(AuctionCore.validateBid(playerAuc, BIDDER_A, 1000L, 1000D, false, now).isOk());

        // 已是最高出價者不可重複加價
        AuctionRecord withBid = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 1000L, 100L, 1000L, BIDDER_A, 1, ends, 0);
        assertEquals(ValidationError.ALREADY_HIGHEST_BIDDER,
                AuctionCore.validateBid(withBid, BIDDER_A, 1200L, 50000D, false, now).getError());
        assertTrue(AuctionCore.validateBid(withBid, BIDDER_B, 1100L, 50000D, false, now).isOk());

        // 已過期與非進行中
        assertEquals(ValidationError.AUCTION_ENDED,
                AuctionCore.validateBid(playerAuc, BIDDER_A, 1000L, 50000D, false, ends).getError());
        AuctionRecord aborted = sample(AuctionType.PLAYER, AuctionStatus.ABORT_PENDING, 1000L, 100L, 0L, null, 0, ends, 0);
        assertEquals(ValidationError.AUCTION_NOT_ACTIVE,
                AuctionCore.validateBid(aborted, BIDDER_A, 1000L, 50000D, false, now).getError());

        // 保管領地：原領地主與管理人員不得喊價
        AuctionRecord archived = sample(AuctionType.ARCHIVED, AuctionStatus.ACTIVE, 1000L, 100L, 0L, null, 0, ends, 0);
        assertEquals(ValidationError.ORIGINAL_OWNER_CANNOT_BID,
                AuctionCore.validateBid(archived, ORIG_OWNER, 1000L, 50000D, false, now).getError());
        assertEquals(ValidationError.STAFF_CANNOT_BID,
                AuctionCore.validateBid(archived, STAFF, 1000L, 50000D, true, now).getError());
        assertTrue(AuctionCore.validateBid(archived, BIDDER_A, 1000L, 50000D, false, now).isOk());
    }

    @Test
    void antiSnipeExtendsWithinWindowAndRespectsCap() {
        AuctionPolicy policy = new AuctionPolicy(100L, 100L, 10, 60, 336, 60, 60, 2, 24, 3);
        long ends = 10_000_000L;

        // 剩餘 120 秒：不觸發
        AuctionRecord early = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 1000L, 100L, 0L, null, 0, ends, 0);
        AntiSnipeOutcome o1 = AuctionCore.computeAntiSnipe(early, policy, ends - 120_000L);
        assertFalse(o1.isExtended());
        assertEquals(ends, o1.getNewEndsAt());

        // 剩餘 30 秒：觸發延時 +60 秒
        AntiSnipeOutcome o2 = AuctionCore.computeAntiSnipe(early, policy, ends - 30_000L);
        assertTrue(o2.isExtended());
        assertEquals(ends + 60_000L, o2.getNewEndsAt());
        assertEquals(1, o2.getNewExtensionCount());

        // 已達延時上限（2 次）：不再延長
        AuctionRecord capped = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 1000L, 100L, 1500L, BIDDER_A, 3, ends, 2);
        AntiSnipeOutcome o3 = AuctionCore.computeAntiSnipe(capped, policy, ends - 10_000L);
        assertFalse(o3.isExtended());
        assertEquals(ends, o3.getNewEndsAt());
    }

    @Test
    void settlementFeeAndNetAlwaysSumToGross() {
        SettlementBreakdown s10 = AuctionCore.calculateSettlement(10_000L, 10);
        assertEquals(10_000L, s10.getGrossAmount());
        assertEquals(1_000L, s10.getFeeAmount());
        assertEquals(9_000L, s10.getNetPayout());

        SettlementBreakdown odd = AuctionCore.calculateSettlement(333L, 10);
        assertEquals(odd.getGrossAmount(), odd.getFeeAmount() + odd.getNetPayout());
        assertEquals(33L, odd.getFeeAmount());
        assertEquals(300L, odd.getNetPayout());

        SettlementBreakdown zeroFee = AuctionCore.calculateSettlement(5000L, 0);
        assertEquals(0L, zeroFee.getFeeAmount());
        assertEquals(5000L, zeroFee.getNetPayout());
    }

    @Test
    void validateCreateEnforcesClearModeCooldownAndLimits() {
        AuctionPolicy policy = AuctionPolicy.defaults();
        long now = 100_000_000L;

        // 玩家拍賣不可設定清空模式
        assertEquals(ValidationError.CLEAR_MODE_ADMIN_ONLY,
                AuctionCore.validateCreate(AuctionType.PLAYER, ClearMode.ITEMS_ONLY, 1000L, 100L, 120L, 0, 0L, false, policy, now).getError());
        assertTrue(AuctionCore.validateCreate(AuctionType.ARCHIVED, ClearMode.ALL, 1000L, 100L, 120L, 0, 0L, true, policy, now).isOk());

        // 24 小時重上架冷卻中（以領地 UUID 追蹤）
        long oneHourAgo = now - 3_600_000L;
        ValidationResult cd = AuctionCore.validateCreate(AuctionType.PLAYER, ClearMode.NONE, 1000L, 100L, 120L, 0, oneHourAgo, false, policy, now);
        assertEquals(ValidationError.RELIST_COOLDOWN_ACTIVE, cd.getError());
        assertTrue(cd.getDetailNumber() > 0L);

        // 管理員可繞過冷卻
        assertTrue(AuctionCore.validateCreate(AuctionType.PLAYER, ClearMode.NONE, 1000L, 100L, 120L, 0, oneHourAgo, true, policy, now).isOk());

        // 超過 24 小時冷卻解除
        long twentyFiveHoursAgo = now - (25L * 3_600_000L);
        assertTrue(AuctionCore.validateCreate(AuctionType.PLAYER, ClearMode.NONE, 1000L, 100L, 120L, 0, twentyFiveHoursAgo, false, policy, now).isOk());

        // 同時活躍場次上限
        assertEquals(ValidationError.MAX_ACTIVE_REACHED,
                AuctionCore.validateCreate(AuctionType.PLAYER, ClearMode.NONE, 1000L, 100L, 120L, 3, 0L, false, policy, now).getError());
    }

    @Test
    void validateCancelAndStateTransitions() {
        AuctionRecord noBid = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 1000L, 100L, 0L, null, 0, 2_000_000L, 0);
        AuctionRecord withBid = sample(AuctionType.PLAYER, AuctionStatus.ACTIVE, 1000L, 100L, 1200L, BIDDER_A, 1, 2_000_000L, 0);

        assertTrue(AuctionCore.validateCancel(noBid, SELLER, false).isOk());
        assertEquals(ValidationError.CANNOT_CANCEL_AFTER_BID, AuctionCore.validateCancel(withBid, SELLER, false).getError());
        assertEquals(ValidationError.NOT_AUTHORIZED, AuctionCore.validateCancel(noBid, BIDDER_A, false).getError());
        assertTrue(AuctionCore.validateCancel(withBid, STAFF, true).isOk());

        assertTrue(AuctionCore.canTransition(AuctionStatus.ACTIVE, AuctionStatus.ABORT_PENDING));
        assertTrue(AuctionCore.canTransition(AuctionStatus.ABORT_PENDING, AuctionStatus.ABORTED));
        assertTrue(AuctionCore.canTransition(AuctionStatus.ACTIVE, AuctionStatus.SETTLING));
        assertTrue(AuctionCore.canTransition(AuctionStatus.SETTLING, AuctionStatus.SETTLED));
        assertFalse(AuctionCore.canTransition(AuctionStatus.SETTLED, AuctionStatus.ACTIVE));
        assertFalse(AuctionCore.canTransition(AuctionStatus.CANCELLED, AuctionStatus.SETTLING));
    }
}
