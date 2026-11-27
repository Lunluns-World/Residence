package com.bekvon.bukkit.residence.audit;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 不可變的領地稽核查詢／還原過濾條件（對齊 CoreProtect 參數模型）。
 */
public final class AuditQuery {

    public final Set<Long> entryIds;
    public final Set<String> actors;
    public final Set<String> excludedActors;
    public final Set<String> owners;
    public final Set<AuditAction> actions;
    public final Set<String> residences;
    public final String worldName;
    public final Integer radius;
    public final Integer centerX;
    public final Integer centerZ;
    public final Long sinceEpochMs;
    public final Long untilEpochMs;
    public final boolean preview;
    public final boolean allServers;
    public final int page;

    public AuditQuery(
            Set<Long> entryIds,
            Set<String> actors,
            Set<String> excludedActors,
            Set<String> owners,
            Set<AuditAction> actions,
            Set<String> residences,
            String worldName,
            Integer radius,
            Integer centerX,
            Integer centerZ,
            Long sinceEpochMs,
            Long untilEpochMs,
            boolean preview,
            boolean allServers,
            int page) {
        this.entryIds = entryIds == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(entryIds));
        this.actors = actors == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(actors));
        this.excludedActors = excludedActors == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(excludedActors));
        this.owners = owners == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(owners));
        this.actions = actions == null ? Collections.emptySet()
                : (actions.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(actions)));
        this.residences = residences == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(residences));
        this.worldName = worldName;
        this.radius = radius;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.sinceEpochMs = sinceEpochMs;
        this.untilEpochMs = untilEpochMs;
        this.preview = preview;
        this.allServers = allServers;
        this.page = Math.max(1, page);
    }

    public AuditQuery withPage(int newPage) {
        return new AuditQuery(
                entryIds, actors, excludedActors, owners, actions, residences,
                worldName, radius, centerX, centerZ, sinceEpochMs, untilEpochMs,
                preview, allServers, Math.max(1, newPage));
    }

    /**
     * 檢查空間座標包圍盒是否落在查詢半徑（2D AABB 方塊距離 <= radius）內。
     */
    public boolean matchesSpatial(String recWorld, int minX, int maxX, int minZ, int maxZ) {
        if (worldName != null && !worldName.equalsIgnoreCase(recWorld)) {
            return false;
        }
        if (radius == null || centerX == null || centerZ == null) {
            return true;
        }
        long qMinX = (long) centerX - radius;
        long qMaxX = (long) centerX + radius;
        long qMinZ = (long) centerZ - radius;
        long qMaxZ = (long) centerZ + radius;
        return maxX >= qMinX && minX <= qMaxX && maxZ >= qMinZ && minZ <= qMaxZ;
    }
}
