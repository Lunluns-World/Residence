package com.bekvon.bukkit.residence.auction.core;

/**
 * 拍賣系統規則參數（純 Java 值物件，與 Bukkit Config 解耦以利單元測試）。
 */
public final class AuctionPolicy {

    private final long defaultMinIncrement;
    private final long minStartPrice;
    private final int feePercent;
    private final int minDurationMinutes;
    private final int maxDurationHours;
    private final int antiSnipeSeconds;
    private final int antiSnipeExtendSeconds;
    private final int maxAntiSnipeExtensions;
    private final int relistCooldownHours;
    private final int maxActivePerPlayer;

    public AuctionPolicy(
            long defaultMinIncrement,
            long minStartPrice,
            int feePercent,
            int minDurationMinutes,
            int maxDurationHours,
            int antiSnipeSeconds,
            int antiSnipeExtendSeconds,
            int maxAntiSnipeExtensions,
            int relistCooldownHours,
            int maxActivePerPlayer) {
        this.defaultMinIncrement = Math.max(1L, defaultMinIncrement);
        this.minStartPrice = Math.max(1L, minStartPrice);
        this.feePercent = Math.max(0, Math.min(100, feePercent));
        this.minDurationMinutes = Math.max(1, minDurationMinutes);
        this.maxDurationHours = Math.max(1, maxDurationHours);
        this.antiSnipeSeconds = Math.max(0, antiSnipeSeconds);
        this.antiSnipeExtendSeconds = Math.max(0, antiSnipeExtendSeconds);
        this.maxAntiSnipeExtensions = Math.max(0, maxAntiSnipeExtensions);
        this.relistCooldownHours = Math.max(0, relistCooldownHours);
        this.maxActivePerPlayer = Math.max(1, maxActivePerPlayer);
    }

    public static AuctionPolicy defaults() {
        return new AuctionPolicy(100L, 100L, 10, 60, 336, 60, 60, 30, 24, 3);
    }

    public long getDefaultMinIncrement() {
        return defaultMinIncrement;
    }

    public long getMinStartPrice() {
        return minStartPrice;
    }

    public int getFeePercent() {
        return feePercent;
    }

    public int getMinDurationMinutes() {
        return minDurationMinutes;
    }

    public int getMaxDurationHours() {
        return maxDurationHours;
    }

    public int getAntiSnipeSeconds() {
        return antiSnipeSeconds;
    }

    public int getAntiSnipeExtendSeconds() {
        return antiSnipeExtendSeconds;
    }

    public int getMaxAntiSnipeExtensions() {
        return maxAntiSnipeExtensions;
    }

    public int getRelistCooldownHours() {
        return relistCooldownHours;
    }

    public int getMaxActivePerPlayer() {
        return maxActivePerPlayer;
    }
}
