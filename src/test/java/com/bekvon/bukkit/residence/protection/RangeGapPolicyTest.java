package com.bekvon.bukkit.residence.protection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class RangeGapPolicyTest {

    private static final class DummyRes {
        final String name;
        final boolean sameOwner;
        final boolean neighborFlag;
        final boolean adminFlag;
        final int minX, minY, minZ, maxX, maxY, maxZ;

        DummyRes(String name, boolean sameOwner, boolean neighborFlag, boolean adminFlag,
                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.name = name;
            this.sameOwner = sameOwner;
            this.neighborFlag = neighborFlag;
            this.adminFlag = adminFlag;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    @Test
    void allowsBuildingNextToAuthorizedFriendWhileStillBlockingStranger() {
        // 新領地為 [0..10]，最小間距 gap = 8
        // 好友領地 [12..20]（距離 2 < 8，但已開放 neighbor=true）
        // 路人領地 [-5..-1]（距離 1 < 8，未開放 neighbor）
        DummyRes friendRes = new DummyRes("FriendHome", false, true, false, 12, 60, 0, 20, 80, 10);
        DummyRes farStranger = new DummyRes("FarStranger", false, false, false, 50, 60, 0, 60, 80, 10);

        List<DummyRes> onlyAuthorizedOrFar = Arrays.asList(friendRes, farStranger);
        DummyRes blocker1 = RangeGapPolicy.findFirstBlockingNeighbor(
                onlyAuthorizedOrFar,
                r -> false,
                r -> RangeGapPolicy.collidesWithinGap(0, 60, 0, 10, 80, 10, r.minX, r.minY, r.minZ, r.maxX, r.maxY, r.maxZ, 8),
                r -> RangeGapPolicy.canIgnoreNeighbor(false, false, false, r.sameOwner, r.neighborFlag, r.adminFlag));
        assertNull(blocker1, "Friend residence with neighbor=true must allow building within minimum gap");

        // 同時緊鄰好友與未授權路人時，必須精準攔截未授權路人
        DummyRes nearStranger = new DummyRes("StrangerBase", false, false, false, -5, 60, 0, -1, 80, 10);
        List<DummyRes> mixed = Arrays.asList(friendRes, nearStranger);
        DummyRes blocker2 = RangeGapPolicy.findFirstBlockingNeighbor(
                mixed,
                r -> false,
                r -> RangeGapPolicy.collidesWithinGap(0, 60, 0, 10, 80, 10, r.minX, r.minY, r.minZ, r.maxX, r.maxY, r.maxZ, 8),
                r -> RangeGapPolicy.canIgnoreNeighbor(false, false, false, r.sameOwner, r.neighborFlag, r.adminFlag));
        assertNotNull(blocker2);
        assertEquals("StrangerBase", blocker2.name);
    }

    @Test
    void globalBypassAndSameOwnerAndAdminFlagAllowWithinGap() {
        assertTrue(RangeGapPolicy.hasGlobalBypass(true, false, false));
        assertTrue(RangeGapPolicy.hasGlobalBypass(false, true, false));
        assertTrue(RangeGapPolicy.hasGlobalBypass(false, false, true));
        assertFalse(RangeGapPolicy.hasGlobalBypass(false, false, false));

        assertTrue(RangeGapPolicy.canIgnoreNeighbor(false, false, false, true, false, false));
        assertTrue(RangeGapPolicy.canIgnoreNeighbor(false, false, false, false, false, true));
        assertFalse(RangeGapPolicy.canIgnoreNeighbor(false, false, false, false, false, false));
    }
}
