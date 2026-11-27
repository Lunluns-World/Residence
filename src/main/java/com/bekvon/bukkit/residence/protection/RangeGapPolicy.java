package com.bekvon.bukkit.residence.protection;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * 最小領地間距（Global.AntiGreef.RangeGaps）判定策略。
 * <p>
 * 將全域繞過（管理員模式、residence.bypass.rangegap）與單一鄰居授權
 * （同一擁有者、鄰居領地開放 neighbor 旗標或 admin 旗標）抽離為純邏輯，
 * 確保當擴張範圍同時涵蓋「已授權好友領地」與「未授權陌生人領地」時，仍能精準攔截未授權的一方。
 */
public final class RangeGapPolicy {

    private RangeGapPolicy() {
    }

    public static boolean hasGlobalBypass(boolean resadmin, boolean resAdminToggle, boolean bypassPermission) {
        return resadmin || resAdminToggle || bypassPermission;
    }

    public static boolean canIgnoreNeighbor(
            boolean resadmin,
            boolean resAdminToggle,
            boolean bypassPermission,
            boolean isSameOwner,
            boolean neighborFlagAllowed,
            boolean adminFlagAllowed) {
        if (hasGlobalBypass(resadmin, resAdminToggle, bypassPermission)) {
            return true;
        }
        return isSameOwner || neighborFlagAllowed || adminFlagAllowed;
    }

    /**
     * 檢查兩個 3D 立方體在向外擴張 gap 格後是否發生碰撞（含邊界接觸）。
     */
    public static boolean collidesWithinGap(
            int aMinX, int aMinY, int aMinZ, int aMaxX, int aMaxY, int aMaxZ,
            int bMinX, int bMinY, int bMinZ, int bMaxX, int bMaxY, int bMaxZ,
            int gap) {
        if (gap < 0) {
            gap = 0;
        }
        long exMinX = (long) aMinX - gap;
        long exMinY = (long) aMinY - gap;
        long exMinZ = (long) aMinZ - gap;
        long exMaxX = (long) aMaxX + gap;
        long exMaxY = (long) aMaxY + gap;
        long exMaxZ = (long) aMaxZ + gap;

        return exMaxX >= bMinX && exMinX <= bMaxX
                && exMaxY >= bMinY && exMinY <= bMaxY
                && exMaxZ >= bMinZ && exMinZ <= bMaxZ;
    }

    /**
     * 從候選領地集合中找出第一個違反最小間距且未獲授權的領地；若全部皆允許則回傳 null。
     */
    public static <T> T findFirstBlockingNeighbor(
            Collection<T> candidates,
            Predicate<T> isSelfOrIgnored,
            Predicate<T> collidesWithExpandedArea,
            Predicate<T> isNeighborAuthorized) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (T candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (isSelfOrIgnored != null && isSelfOrIgnored.test(candidate)) {
                continue;
            }
            if (!collidesWithExpandedArea.test(candidate)) {
                continue;
            }
            if (isNeighborAuthorized != null && isNeighborAuthorized.test(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }
}
