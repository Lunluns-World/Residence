package com.bekvon.bukkit.residence.crossserver;

import java.util.UUID;

public final class RemoteResidenceEntry {

    private final String serverName;
    private final String resKey;
    private final String resName;
    private final UUID resUuid;
    private final String worldName;
    private final UUID ownerUuid;
    private final String ownerName;
    private final double tpX;
    private final double tpY;
    private final double tpZ;
    private final float tpYaw;
    private final float tpPitch;
    private final boolean hidden;
    private final boolean tpPublic;
    private final boolean listed;
    private final String alias;
    private final String description;
    private final String icon;
    private final int likes;
    private final long createTime;
    private final boolean local;

    public RemoteResidenceEntry(
            String serverName,
            String resKey,
            String resName,
            UUID resUuid,
            String worldName,
            UUID ownerUuid,
            String ownerName,
            double tpX,
            double tpY,
            double tpZ,
            float tpYaw,
            float tpPitch,
            boolean hidden,
            boolean tpPublic,
            boolean listed,
            String alias,
            String description,
            String icon,
            int likes,
            long createTime,
            boolean local) {
        this.serverName = serverName == null ? "default" : serverName;
        this.resKey = resKey == null ? "" : resKey.toLowerCase();
        this.resName = resName == null ? "" : resName;
        this.resUuid = resUuid;
        this.worldName = worldName == null ? "" : worldName;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName == null ? "Unknown" : ownerName;
        this.tpX = tpX;
        this.tpY = tpY;
        this.tpZ = tpZ;
        this.tpYaw = tpYaw;
        this.tpPitch = tpPitch;
        this.hidden = hidden;
        this.tpPublic = tpPublic;
        this.listed = listed;
        this.alias = alias;
        this.description = description;
        this.icon = icon;
        this.likes = likes;
        this.createTime = createTime;
        this.local = local;
    }

    public String getServerName() {
        return serverName;
    }

    public String getResKey() {
        return resKey;
    }

    public String getResName() {
        return resName;
    }

    public String getQualifiedKey() {
        return serverName.toLowerCase() + ":" + resKey;
    }

    public String getQualifiedName() {
        return serverName + ":" + resName;
    }

    public UUID getResUuid() {
        return resUuid;
    }

    public String getWorldName() {
        return worldName;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public double getTpX() {
        return tpX;
    }

    public double getTpY() {
        return tpY;
    }

    public double getTpZ() {
        return tpZ;
    }

    public float getTpYaw() {
        return tpYaw;
    }

    public float getTpPitch() {
        return tpPitch;
    }

    public boolean isHidden() {
        return hidden;
    }

    public boolean isTpPublic() {
        return tpPublic;
    }

    public boolean isListed() {
        return listed;
    }

    public String getAlias() {
        return alias;
    }

    public String getDisplayName() {
        return (alias != null && !alias.trim().isEmpty()) ? alias : resName;
    }

    public String getDescription() {
        return description;
    }

    public String getIcon() {
        return icon;
    }

    public int getLikes() {
        return likes;
    }

    public long getCreateTime() {
        return createTime;
    }

    public boolean isLocal() {
        return local;
    }

    public boolean isOwner(UUID playerUuid) {
        return playerUuid != null && ownerUuid != null && ownerUuid.equals(playerUuid);
    }
}
