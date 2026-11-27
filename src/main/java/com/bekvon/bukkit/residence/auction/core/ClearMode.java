package com.bekvon.bukkit.residence.auction.core;

/**
 * 拍賣成交時的領地清理模式（僅限管理員開啟拍賣時可設定，玩家拍賣固定為 NONE）。
 */
public enum ClearMode {
    /** 不清除，以領地現況原樣交付 */
    NONE,
    /** 僅清除領地內的容器物品、展示框／盔甲座物品與 QuickShop 商店，保留建築 */
    ITEMS_ONLY,
    /** 連同建築方塊（非基岩）與所有物品、QuickShop 商店與實體一併清空 */
    ALL;

    public ClearMode next() {
        ClearMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    public static ClearMode fromString(String raw) {
        if (raw == null)
            return NONE;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
