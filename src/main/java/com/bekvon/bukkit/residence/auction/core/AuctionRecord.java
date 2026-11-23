package com.bekvon.bukkit.residence.auction.core;

import java.util.UUID;

/**
 * 單場領地拍賣的完整資料快照（純 Java 模型）。
 */
public final class AuctionRecord {

    private final long id;
    private final String originServer;
    private final UUID resUuid;
    private final String resKey;
    private final String resName;
    private final String worldName;
    private final double tpX;
    private final double tpY;
    private final double tpZ;
    private final AuctionType type;
    private final AuctionStatus status;
    private final ClearMode clearMode;
    private final UUID creatorUuid;
    private final String creatorName;
    private final UUID originalOwnerUuid;
    private final String originalOwnerName;
    private final long startPrice;
    private final long minIncrement;
    private final long currentBid;
    private final UUID highestBidderUuid;
    private final String highestBidderName;
    private final int bidCount;
    private final String description;
    private final long createdAt;
    private final long endsAt;
    private final int antiSnipeCount;
    private final String permissionBackup;

    public AuctionRecord(
            long id,
            String originServer,
            UUID resUuid,
            String resKey,
            String resName,
            String worldName,
            double tpX,
            double tpY,
            double tpZ,
            AuctionType type,
            AuctionStatus status,
            ClearMode clearMode,
            UUID creatorUuid,
            String creatorName,
            UUID originalOwnerUuid,
            String originalOwnerName,
            long startPrice,
            long minIncrement,
            long currentBid,
            UUID highestBidderUuid,
            String highestBidderName,
            int bidCount,
            String description,
            long createdAt,
            long endsAt,
            int antiSnipeCount,
            String permissionBackup) {
        this.id = id;
        this.originServer = originServer == null ? "default" : originServer;
        this.resUuid = resUuid;
        this.resKey = resKey == null ? "" : resKey.toLowerCase();
        this.resName = resName == null ? "" : resName;
        this.worldName = worldName == null ? "" : worldName;
        this.tpX = tpX;
        this.tpY = tpY;
        this.tpZ = tpZ;
        this.type = type == null ? AuctionType.PLAYER : type;
        this.status = status == null ? AuctionStatus.ACTIVE : status;
        this.clearMode = clearMode == null ? ClearMode.NONE : clearMode;
        this.creatorUuid = creatorUuid;
        this.creatorName = creatorName == null ? "" : creatorName;
        this.originalOwnerUuid = originalOwnerUuid;
        this.originalOwnerName = originalOwnerName == null ? "" : originalOwnerName;
        this.startPrice = startPrice;
        this.minIncrement = minIncrement;
        this.currentBid = currentBid;
        this.highestBidderUuid = highestBidderUuid;
        this.highestBidderName = highestBidderName;
        this.bidCount = bidCount;
        this.description = description == null ? "" : description;
        this.createdAt = createdAt;
        this.endsAt = endsAt;
        this.antiSnipeCount = antiSnipeCount;
        this.permissionBackup = permissionBackup;
    }

    public long getId() { return id; }
    public String getOriginServer() { return originServer; }
    public UUID getResUuid() { return resUuid; }
    public String getResKey() { return resKey; }
    public String getResName() { return resName; }
    public String getWorldName() { return worldName; }
    public double getTpX() { return tpX; }
    public double getTpY() { return tpY; }
    public double getTpZ() { return tpZ; }
    public AuctionType getType() { return type; }
    public AuctionStatus getStatus() { return status; }
    public ClearMode getClearMode() { return clearMode; }
    public UUID getCreatorUuid() { return creatorUuid; }
    public String getCreatorName() { return creatorName; }
    public UUID getOriginalOwnerUuid() { return originalOwnerUuid; }
    public String getOriginalOwnerName() { return originalOwnerName; }
    public long getStartPrice() { return startPrice; }
    public long getMinIncrement() { return minIncrement; }
    public long getCurrentBid() { return currentBid; }
    public UUID getHighestBidderUuid() { return highestBidderUuid; }
    public String getHighestBidderName() { return highestBidderName; }
    public int getBidCount() { return bidCount; }
    public String getDescription() { return description; }
    public long getCreatedAt() { return createdAt; }
    public long getEndsAt() { return endsAt; }
    public int getAntiSnipeCount() { return antiSnipeCount; }
    public String getPermissionBackup() { return permissionBackup; }

    public boolean hasBids() {
        return bidCount > 0 && highestBidderUuid != null && currentBid > 0L;
    }
}
