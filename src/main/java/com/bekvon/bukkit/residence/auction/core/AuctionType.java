package com.bekvon.bukkit.residence.auction.core;

/**
 * 領地拍賣類型。
 */
public enum AuctionType {
    /** 玩家拍賣自己的領地（一律現況交付） */
    PLAYER,
    /** 管理人員拍賣 Server_Unclaimed 保管中的過期領地（原領主回歸可中止歸還） */
    ARCHIVED,
    /** 管理人員拍賣伺服器沒收／違規領地（無原領主中止權） */
    ADMIN;

    public static AuctionType fromString(String raw) {
        if (raw == null)
            return PLAYER;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PLAYER;
        }
    }
}
