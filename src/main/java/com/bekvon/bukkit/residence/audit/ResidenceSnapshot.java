package com.bekvon.bukkit.residence.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;

/**
 * 自包含、純資料結構的領地完整樹狀快照（不依賴 MinimizeFlags / MinimizeMessages 暫存表，亦不依賴 Bukkit 執行期類別）。
 * 支援主領地與遞迴子領地的區域座標、權限旗標、擁有者、傳送點、進出訊息、黑白名單與保管原領主紀錄完整序列化與差異摘要。
 */
public final class ResidenceSnapshot {

    private static final Gson GSON = new Gson();

    public static final class Box {
        public int minX;
        public int minY;
        public int minZ;
        public int maxX;
        public int maxY;
        public int maxZ;

        public Box() {
        }

        public Box(int x1, int y1, int z1, int x2, int y2, int z2) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
        }

        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeY() {
            return maxY - minY + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }

        public String dimensionsText() {
            return sizeX() + "x" + sizeY() + "x" + sizeZ();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof Box))
                return false;
            Box b = (Box) o;
            return minX == b.minX && minY == b.minY && minZ == b.minZ
                    && maxX == b.maxX && maxY == b.maxY && maxZ == b.maxZ;
        }

        @Override
        public int hashCode() {
            return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public static final class TpPoint {
        public double x;
        public double y;
        public double z;
        public float pitch;
        public float yaw;

        public TpPoint() {
        }

        public TpPoint(double x, double y, double z, float pitch, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof TpPoint))
                return false;
            TpPoint p = (TpPoint) o;
            return Math.abs(x - p.x) < 0.001 && Math.abs(y - p.y) < 0.001 && Math.abs(z - p.z) < 0.001
                    && Math.abs(pitch - p.pitch) < 0.01f && Math.abs(yaw - p.yaw) < 0.01f;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Math.round(x * 100), Math.round(y * 100), Math.round(z * 100));
        }
    }

    public static final class PermData {
        public Map<String, Boolean> cuboidFlags = new LinkedHashMap<>();
        public Map<String, Map<String, Boolean>> playerFlags = new LinkedHashMap<>();
        public Map<String, String> playerNames = new LinkedHashMap<>();
        public Map<String, Map<String, Boolean>> playerFlagsByName = new LinkedHashMap<>();
        public Map<String, Map<String, Boolean>> groupFlags = new LinkedHashMap<>();
        public boolean inheritParentFlags = true;

        public boolean samePermissions(PermData other) {
            if (other == null) {
                return false;
            }
            return inheritParentFlags == other.inheritParentFlags
                    && Objects.equals(cuboidFlags, other.cuboidFlags)
                    && Objects.equals(normalizeNested(playerFlags), normalizeNested(other.playerFlags))
                    && Objects.equals(normalizeNested(playerFlagsByName), normalizeNested(other.playerFlagsByName))
                    && Objects.equals(normalizeNested(groupFlags), normalizeNested(other.groupFlags));
        }

        private static Map<String, Map<String, Boolean>> normalizeNested(Map<String, Map<String, Boolean>> src) {
            Map<String, Map<String, Boolean>> clean = new LinkedHashMap<>();
            if (src == null) {
                return clean;
            }
            for (Map.Entry<String, Map<String, Boolean>> e : src.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    clean.put(e.getKey(), e.getValue());
                }
            }
            return clean;
        }
    }

    public String uuid;
    public String resName;
    public String fullName;
    public String worldName;
    public String ownerUuid;
    public String ownerName;
    public long createTime;
    public boolean mainRes;
    public double blockSellPrice;
    public double storedMoney;
    public boolean moneyRefundedOnDelete;

    public String enterMessage;
    public String leaveMessage;
    public String shopDesc;
    public String chatPrefix = "";
    public String channelColor = "WHITE";
    public TpPoint tpLoc;

    public List<String> cmdBlackList = new ArrayList<>();
    public List<String> cmdWhiteList = new ArrayList<>();
    public List<String> itemBlackList = new ArrayList<>();
    public List<String> itemIgnoreList = new ArrayList<>();

    public Map<String, Box> areas = new LinkedHashMap<>();
    public PermData permissions = new PermData();
    public Map<String, ResidenceSnapshot> subzones = new LinkedHashMap<>();

    public String archivedOriginalOwnerUuid;
    public String archivedOriginalOwnerName;
    public long archivedAt;

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ResidenceSnapshot fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, ResidenceSnapshot.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 依相對子領地路徑（例如 "" 代表主領地、"shop.f1" 代表兩層子領地）定位對應的快照節點。
     */
    public ResidenceSnapshot findNode(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return this;
        }
        String[] parts = relativePath.trim().toLowerCase(Locale.ROOT).split("\\.");
        ResidenceSnapshot cur = this;
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (cur.subzones == null) {
                return null;
            }
            cur = cur.subzones.get(p);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    public Box computeEnvelope() {
        if (areas == null || areas.isEmpty()) {
            if (tpLoc != null) {
                int x = (int) Math.floor(tpLoc.x);
                int y = (int) Math.floor(tpLoc.y);
                int z = (int) Math.floor(tpLoc.z);
                return new Box(x, y, z, x, y, z);
            }
            return new Box(0, 0, 0, 0, 0, 0);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Box b : areas.values()) {
            if (b == null) {
                continue;
            }
            minX = Math.min(minX, b.minX);
            minY = Math.min(minY, b.minY);
            minZ = Math.min(minZ, b.minZ);
            maxX = Math.max(maxX, b.maxX);
            maxY = Math.max(maxY, b.maxY);
            maxZ = Math.max(maxZ, b.maxZ);
        }
        if (minX == Integer.MAX_VALUE) {
            return new Box(0, 0, 0, 0, 0, 0);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int countTotalSubzones() {
        if (subzones == null || subzones.isEmpty()) {
            return 0;
        }
        int total = subzones.size();
        for (ResidenceSnapshot child : subzones.values()) {
            if (child != null) {
                total += child.countTotalSubzones();
            }
        }
        return total;
    }

    public boolean hasPermissionDiffRecursive(ResidenceSnapshot other) {
        if (other == null) {
            return true;
        }
        PermData a = permissions == null ? new PermData() : permissions;
        PermData b = other.permissions == null ? new PermData() : other.permissions;
        if (!a.samePermissions(b)) {
            return true;
        }
        Map<String, ResidenceSnapshot> subA = subzones == null ? new LinkedHashMap<>() : subzones;
        Map<String, ResidenceSnapshot> subB = other.subzones == null ? new LinkedHashMap<>() : other.subzones;
        if (!subA.keySet().equals(subB.keySet())) {
            return true;
        }
        for (Map.Entry<String, ResidenceSnapshot> e : subA.entrySet()) {
            if (e.getValue().hasPermissionDiffRecursive(subB.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasOwnerDiffRecursive(ResidenceSnapshot other) {
        if (other == null) {
            return true;
        }
        if (!Objects.equals(ownerUuid, other.ownerUuid)
                || !Objects.equals(ownerName, other.ownerName)
                || !Objects.equals(archivedOriginalOwnerUuid, other.archivedOriginalOwnerUuid)) {
            return true;
        }
        Map<String, ResidenceSnapshot> subA = subzones == null ? new LinkedHashMap<>() : subzones;
        Map<String, ResidenceSnapshot> subB = other.subzones == null ? new LinkedHashMap<>() : other.subzones;
        if (!subA.keySet().equals(subB.keySet())) {
            return true;
        }
        for (Map.Entry<String, ResidenceSnapshot> e : subA.entrySet()) {
            if (e.getValue().hasOwnerDiffRecursive(subB.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAreaDiff(ResidenceSnapshot other) {
        if (other == null) {
            return true;
        }
        return !Objects.equals(areas, other.areas);
    }

    public boolean hasMetaDiff(ResidenceSnapshot other) {
        if (other == null) {
            return true;
        }
        return !Objects.equals(tpLoc, other.tpLoc)
                || !Objects.equals(enterMessage, other.enterMessage)
                || !Objects.equals(leaveMessage, other.leaveMessage)
                || !Objects.equals(shopDesc, other.shopDesc)
                || !Objects.equals(chatPrefix, other.chatPrefix)
                || !Objects.equals(channelColor, other.channelColor)
                || !Objects.equals(cmdBlackList, other.cmdBlackList)
                || !Objects.equals(cmdWhiteList, other.cmdWhiteList)
                || !Objects.equals(itemBlackList, other.itemBlackList)
                || !Objects.equals(itemIgnoreList, other.itemIgnoreList);
    }

    /**
     * 產生供 /res co lookup 顯示的精簡可讀差異摘要。
     */
    public static String summarize(AuditAction action, ResidenceSnapshot beforeTop, ResidenceSnapshot afterTop, String relativePath) {
        ResidenceSnapshot before = beforeTop == null ? null : beforeTop.findNode(relativePath);
        ResidenceSnapshot after = afterTop == null ? null : afterTop.findNode(relativePath);

        switch (action) {
        case CREATE:
        case SUBZONE_CREATE: {
            ResidenceSnapshot node = after != null ? after : afterTop;
            if (node == null) {
                return "created";
            }
            Box env = node.computeEnvelope();
            return "created (" + env.dimensionsText() + ", owner=" + safe(node.ownerName) + ")";
        }
        case REMOVE:
        case SUBZONE_REMOVE: {
            ResidenceSnapshot node = before != null ? before : beforeTop;
            if (node == null) {
                return "removed";
            }
            Box env = node.computeEnvelope();
            int subs = node.countTotalSubzones();
            return "removed (" + env.dimensionsText() + ", owner=" + safe(node.ownerName)
                    + (subs > 0 ? ", subzones=" + subs : "") + ")";
        }
        case RENAME: {
            String oldN = before != null ? safe(before.fullName) : (beforeTop != null ? safe(beforeTop.fullName) : "?");
            String newN = after != null ? safe(after.fullName) : (afterTop != null ? safe(afterTop.fullName) : "?");
            return "rename: " + oldN + " -> " + newN;
        }
        case OWNER: {
            ResidenceSnapshot b = before != null ? before : beforeTop;
            ResidenceSnapshot a = after != null ? after : afterTop;
            String oldOwner = b != null ? safe(b.ownerName) : "?";
            String newOwner = a != null ? safe(a.ownerName) : "?";
            if (!oldOwner.equals(newOwner)) {
                return "owner: " + oldOwner + " -> " + newOwner;
            }
            String oldOrig = b != null && b.archivedOriginalOwnerName != null && !b.archivedOriginalOwnerName.isEmpty()
                    ? b.archivedOriginalOwnerName : "unset";
            String newOrig = a != null && a.archivedOriginalOwnerName != null && !a.archivedOriginalOwnerName.isEmpty()
                    ? a.archivedOriginalOwnerName : "unset";
            return "custody-owner: " + oldOrig + " -> " + newOrig;
        }
        case AREA: {
            ResidenceSnapshot b = before != null ? before : beforeTop;
            ResidenceSnapshot a = after != null ? after : afterTop;
            if (b == null || a == null) {
                return "area updated";
            }
            return summarizeAreaChanges(b.areas, a.areas);
        }
        case FLAG: {
            ResidenceSnapshot b = before != null ? before : beforeTop;
            ResidenceSnapshot a = after != null ? after : afterTop;
            if (b == null || a == null) {
                return "flags updated";
            }
            return summarizeFlagChanges(b.permissions, a.permissions);
        }
        case META: {
            ResidenceSnapshot b = before != null ? before : beforeTop;
            ResidenceSnapshot a = after != null ? after : afterTop;
            if (b != null && a != null) {
                if (!Objects.equals(b.tpLoc, a.tpLoc) && a.tpLoc != null) {
                    return String.format(Locale.ROOT, "tpset -> (%.1f, %.1f, %.1f)", a.tpLoc.x, a.tpLoc.y, a.tpLoc.z);
                }
                if (!Objects.equals(b.enterMessage, a.enterMessage)) {
                    return "enter-msg updated";
                }
                if (!Objects.equals(b.leaveMessage, a.leaveMessage)) {
                    return "leave-msg updated";
                }
                if (!Objects.equals(b.shopDesc, a.shopDesc)) {
                    return "shop-desc updated";
                }
            }
            return "metadata updated";
        }
        default:
            return action.getId();
        }
    }

    private static String summarizeAreaChanges(Map<String, Box> oldAreas, Map<String, Box> newAreas) {
        Map<String, Box> a = oldAreas == null ? new LinkedHashMap<>() : oldAreas;
        Map<String, Box> b = newAreas == null ? new LinkedHashMap<>() : newAreas;
        Set<String> allKeys = new LinkedHashSet<>(a.keySet());
        allKeys.addAll(b.keySet());
        List<String> parts = new ArrayList<>();
        for (String key : allKeys) {
            Box oldBox = a.get(key);
            Box newBox = b.get(key);
            if (Objects.equals(oldBox, newBox)) {
                continue;
            }
            if (oldBox == null && newBox != null) {
                parts.add("+" + key + "(" + newBox.dimensionsText() + ")");
            } else if (oldBox != null && newBox == null) {
                parts.add("-" + key + "(" + oldBox.dimensionsText() + ")");
            } else if (oldBox != null && newBox != null) {
                parts.add(key + ": " + oldBox.dimensionsText() + " -> " + newBox.dimensionsText());
            }
        }
        if (parts.isEmpty()) {
            return "area updated";
        }
        return trimSummary(String.join(", ", parts));
    }

    private static String summarizeFlagChanges(PermData before, PermData after) {
        PermData b = before == null ? new PermData() : before;
        PermData a = after == null ? new PermData() : after;
        List<String> changes = new ArrayList<>();

        diffFlatFlags("", b.cuboidFlags, a.cuboidFlags, changes);

        Set<String> pKeys = new LinkedHashSet<>();
        if (b.playerFlags != null)
            pKeys.addAll(b.playerFlags.keySet());
        if (a.playerFlags != null)
            pKeys.addAll(a.playerFlags.keySet());
        for (String uuid : pKeys) {
            String display = null;
            if (a.playerNames != null)
                display = a.playerNames.get(uuid);
            if ((display == null || display.isEmpty()) && b.playerNames != null)
                display = b.playerNames.get(uuid);
            if (display == null || display.isEmpty())
                display = uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
            Map<String, Boolean> bm = b.playerFlags == null ? null : b.playerFlags.get(uuid);
            Map<String, Boolean> am = a.playerFlags == null ? null : a.playerFlags.get(uuid);
            diffFlatFlags("p:" + display + " ", bm, am, changes);
        }

        Set<String> pnKeys = new LinkedHashSet<>();
        if (b.playerFlagsByName != null)
            pnKeys.addAll(b.playerFlagsByName.keySet());
        if (a.playerFlagsByName != null)
            pnKeys.addAll(a.playerFlagsByName.keySet());
        for (String name : pnKeys) {
            Map<String, Boolean> bm = b.playerFlagsByName == null ? null : b.playerFlagsByName.get(name);
            Map<String, Boolean> am = a.playerFlagsByName == null ? null : a.playerFlagsByName.get(name);
            diffFlatFlags("p:" + name + " ", bm, am, changes);
        }

        Set<String> gKeys = new LinkedHashSet<>();
        if (b.groupFlags != null)
            gKeys.addAll(b.groupFlags.keySet());
        if (a.groupFlags != null)
            gKeys.addAll(a.groupFlags.keySet());
        for (String grp : gKeys) {
            Map<String, Boolean> bm = b.groupFlags == null ? null : b.groupFlags.get(grp);
            Map<String, Boolean> am = a.groupFlags == null ? null : a.groupFlags.get(grp);
            diffFlatFlags("g:" + grp + " ", bm, am, changes);
        }

        if (b.inheritParentFlags != a.inheritParentFlags) {
            changes.add("inherit=" + a.inheritParentFlags);
        }

        if (changes.isEmpty()) {
            return "flags updated";
        }
        if (changes.size() <= 3) {
            return trimSummary(String.join(", ", changes));
        }
        return trimSummary(changes.get(0) + ", " + changes.get(1) + ", " + changes.get(2)
                + " (+" + (changes.size() - 3) + " more)");
    }

    private static void diffFlatFlags(String prefix, Map<String, Boolean> before, Map<String, Boolean> after, List<String> out) {
        Map<String, Boolean> b = before == null ? new LinkedHashMap<>() : before;
        Map<String, Boolean> a = after == null ? new LinkedHashMap<>() : after;
        Set<String> keys = new LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        for (String flag : keys) {
            Boolean oldVal = b.get(flag);
            Boolean newVal = a.get(flag);
            if (Objects.equals(oldVal, newVal)) {
                continue;
            }
            String oldText = oldVal == null ? "unset" : String.valueOf(oldVal);
            String newText = newVal == null ? "unset" : String.valueOf(newVal);
            out.add(prefix + flag + ":" + oldText + "->" + newText);
        }
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "Unknown" : s;
    }

    private static String trimSummary(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 480 ? s.substring(0, 477) + "..." : s;
    }
}
