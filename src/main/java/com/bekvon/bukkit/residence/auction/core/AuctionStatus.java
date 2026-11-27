package com.bekvon.bukkit.residence.auction.core;

/**
 * 拍賣生命週期狀態機。
 */
public enum AuctionStatus {
    /** 拍賣進行中，接受喊價並凍結領地 */
    ACTIVE,
    /** 原領主已於任一分流登入，鎖定等待領地所屬分流執行中止與權限還原 */
    ABORT_PENDING,
    /** 已因原領主回歸中止並歸還領地 */
    ABORTED,
    /** 已由賣方（無人喊價時）或管理員手動取消 */
    CANCELLED,
    /** 已達結標時間且有人喊價，鎖定執行成交移轉與結算 */
    SETTLING,
    /** 已完成成交移轉與款項結算 */
    SETTLED,
    /** 結標時無人喊價（流標），已還原領地權限 */
    NO_BID;

    public boolean isTerminal() {
        return this == ABORTED || this == CANCELLED || this == SETTLED || this == NO_BID;
    }

    public boolean isLiveLock() {
        return this == ACTIVE || this == ABORT_PENDING || this == SETTLING;
    }

    public static AuctionStatus fromString(String raw) {
        if (raw == null)
            return CANCELLED;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CANCELLED;
        }
    }
}
