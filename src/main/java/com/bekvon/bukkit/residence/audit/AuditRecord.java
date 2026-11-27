package com.bekvon.bukkit.residence.audit;

import java.util.Locale;
import java.util.UUID;

/**
 * 單筆領地操作稽核紀錄。
 */
public final class AuditRecord {

    public final long id;
    public final String serverName;
    public final long createdAt;
    public final UUID actorUuid;
    public final String actorName;
    public final AuditAction action;
    public final UUID resUuid;
    public final String topResKey;
    public final String resKey;
    public final String resName;
    public final UUID ownerUuid;
    public final String ownerName;
    public final String worldName;
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;
    public final String summary;
    public final String beforeSnapshot;
    public final String afterSnapshot;
    public final boolean rolledBack;

    public AuditRecord(
            long id,
            String serverName,
            long createdAt,
            UUID actorUuid,
            String actorName,
            AuditAction action,
            UUID resUuid,
            String topResKey,
            String resKey,
            String resName,
            UUID ownerUuid,
            String ownerName,
            String worldName,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            String summary,
            String beforeSnapshot,
            String afterSnapshot,
            boolean rolledBack) {
        this.id = id;
        this.serverName = serverName == null ? "default" : serverName;
        this.createdAt = createdAt;
        this.actorUuid = actorUuid;
        this.actorName = (actorName == null || actorName.isEmpty()) ? "#system" : actorName;
        this.action = action;
        this.resUuid = resUuid;
        this.topResKey = topResKey == null ? "" : topResKey.toLowerCase(Locale.ROOT);
        this.resKey = resKey == null ? "" : resKey.toLowerCase(Locale.ROOT);
        this.resName = resName == null ? "" : resName;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.worldName = worldName == null ? "world" : worldName;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.summary = summary == null ? "" : summary;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
        this.rolledBack = rolledBack;
    }

    public int centerX() {
        return (int) (((long) minX + maxX) / 2L);
    }

    public int centerY() {
        return (int) (((long) minY + maxY) / 2L);
    }

    public int centerZ() {
        return (int) (((long) minZ + maxZ) / 2L);
    }

    /**
     * 取得相對於主領地的子領地路徑（主領地本身回傳空字串 ""）。
     */
    public String getRelativeSubzonePath() {
        if (topResKey.isEmpty() || resKey.equalsIgnoreCase(topResKey)) {
            return "";
        }
        String prefix = topResKey + ".";
        if (resKey.startsWith(prefix)) {
            return resKey.substring(prefix.length());
        }
        int dot = resKey.indexOf('.');
        return dot >= 0 ? resKey.substring(dot + 1) : "";
    }
}
