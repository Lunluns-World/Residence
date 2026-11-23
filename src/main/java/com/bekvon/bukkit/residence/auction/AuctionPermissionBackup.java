package com.bekvon.bukkit.residence.auction;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;
import com.google.gson.Gson;

/**
 * 領地拍賣權限備份、凍結與還原工具。
 * 開拍時完整備份主領地與所有子領地的區域／玩家／群組旗標；
 * 拍賣期間凍結所有修改與互動旗標，僅開放 move 與 tp 供看房；
 * 取消、流標或原領主回歸時精準還原原本設定；成交時徹底清空所有權限。
 */
public final class AuctionPermissionBackup {

    private static final Gson GSON = new Gson();

    /**
     * 拍賣凍結期間強制拒絕的旗標清單（防止任何破壞、放置、開箱、偷物、推拉、爆炸與商店異動）。
     */
    private static final List<String> FREEZE_DENY_FLAGS = Arrays.asList(
            "build", "place", "destroy", "container", "use",
            "door", "button", "lever", "pressure", "trapdoor", "fencegate",
            "piston", "pistonprotection", "tnt", "creeper", "fireball", "witherdestruction", "explode",
            "flow", "waterflow", "lavaflow", "igniting", "firespread", "fire",
            "itempickup", "itemdrop", "leash", "shear", "trample", "vehicledestroy",
            "trade", "shop", "animalkilling", "mobkilling", "pvp",
            "bucket", "bucketfill", "bucketempty", "cake", "beacon", "anvil",
            "brew", "enchant", "table", "note", "diode", "daylight",
            "flowerpot", "armorstand", "itemframe", "hook", "egg", "throw",
            "quickshop-remove");

    public static final class AreaBackup {
        public Map<String, Boolean> cuboidFlags = new LinkedHashMap<>();
        public Map<String, Map<String, Boolean>> playerFlags = new LinkedHashMap<>();
        public Map<String, Map<String, Boolean>> playerFlagsByName = new LinkedHashMap<>();
        public Map<String, Map<String, Boolean>> groupFlags = new LinkedHashMap<>();
    }

    public static final class BackupPayload {
        public Map<String, AreaBackup> areas = new LinkedHashMap<>();
    }

    private AuctionPermissionBackup() {
    }

    /**
     * 擷取主領地與全部遞迴子領地的完整權限備份，序列化為 JSON 字串。
     */
    public static String capture(ClaimedResidence topRes) {
        if (topRes == null) {
            return "{}";
        }
        BackupPayload payload = new BackupPayload();
        captureRecursive(topRes, "", payload);
        return GSON.toJson(payload);
    }

    private static void captureRecursive(ClaimedResidence res, String relativePath, BackupPayload payload) {
        ResidencePermissions perms = res.getPermissions();
        AreaBackup area = new AreaBackup();
        if (perms != null) {
            if (perms.cuboidFlags != null) {
                area.cuboidFlags.putAll(perms.cuboidFlags);
            }
            Map<UUID, Map<String, Boolean>> pFlags = perms.getPlayerFlags();
            if (pFlags != null) {
                for (Map.Entry<UUID, Map<String, Boolean>> e : pFlags.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        area.playerFlags.put(e.getKey().toString(), new LinkedHashMap<>(e.getValue()));
                    }
                }
            }
            Map<String, Map<String, Boolean>> pByName = perms.getPlayerFlagsByName();
            if (pByName != null) {
                for (Map.Entry<String, Map<String, Boolean>> e : pByName.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        area.playerFlagsByName.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                    }
                }
            }
            Map<String, Map<String, Boolean>> gFlags = perms.getGroupFlags();
            if (gFlags != null) {
                for (Map.Entry<String, Map<String, Boolean>> e : gFlags.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        area.groupFlags.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                    }
                }
            }
        }
        payload.areas.put(relativePath, area);

        for (ClaimedResidence sub : res.getSubzones()) {
            String childPath = relativePath.isEmpty() ? sub.getResidenceName() : (relativePath + "." + sub.getResidenceName());
            captureRecursive(sub, childPath, payload);
        }
    }

    /**
     * 套用拍賣凍結旗標：清空個人與群組覆寫、停用所有建構與互動旗標，僅允許 move 與 tp 看房。
     */
    public static void applyFreeze(ClaimedResidence topRes) {
        if (topRes == null) {
            return;
        }
        applyFreezeRecursive(topRes);
    }

    private static void applyFreezeRecursive(ClaimedResidence res) {
        ResidencePermissions perms = res.getPermissions();
        if (perms != null) {
            perms.clearFlags();
            perms.setFlag("move", FlagState.TRUE);
            perms.setFlag("tp", FlagState.TRUE);
            for (String flag : FREEZE_DENY_FLAGS) {
                perms.setFlag(flag, FlagState.FALSE);
            }
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            applyFreezeRecursive(sub);
        }
    }

    /**
     * 依備份 JSON 精準還原主領地與所有子領地的原權限設定。
     */
    public static void restore(ClaimedResidence topRes, String backupJson) {
        if (topRes == null || backupJson == null || backupJson.trim().isEmpty()) {
            return;
        }
        BackupPayload payload;
        try {
            payload = GSON.fromJson(backupJson, BackupPayload.class);
        } catch (Exception ex) {
            return;
        }
        if (payload == null || payload.areas == null) {
            return;
        }
        restoreRecursive(topRes, "", payload.areas);
    }

    private static void restoreRecursive(ClaimedResidence res, String relativePath, Map<String, AreaBackup> areas) {
        ResidencePermissions perms = res.getPermissions();
        AreaBackup backup = areas.get(relativePath);
        if (perms != null && backup != null) {
            perms.clearFlags();
            if (backup.cuboidFlags != null) {
                for (Map.Entry<String, Boolean> e : backup.cuboidFlags.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        perms.setFlag(e.getKey(), e.getValue() ? FlagState.TRUE : FlagState.FALSE);
                    }
                }
            }
            if (backup.playerFlags != null) {
                for (Map.Entry<String, Map<String, Boolean>> e : backup.playerFlags.entrySet()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(e.getKey());
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    if (e.getValue() == null) {
                        continue;
                    }
                    for (Map.Entry<String, Boolean> f : e.getValue().entrySet()) {
                        if (f.getKey() != null && f.getValue() != null) {
                            perms.setPlayerFlag(uuid, f.getKey(), f.getValue() ? FlagState.TRUE : FlagState.FALSE);
                        }
                    }
                }
            }
            if (backup.playerFlagsByName != null) {
                for (Map.Entry<String, Map<String, Boolean>> e : backup.playerFlagsByName.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    for (Map.Entry<String, Boolean> f : e.getValue().entrySet()) {
                        if (f.getKey() != null && f.getValue() != null) {
                            perms.setPlayerFlag(e.getKey(), f.getKey(), f.getValue() ? FlagState.TRUE : FlagState.FALSE);
                        }
                    }
                }
            }
            if (backup.groupFlags != null) {
                for (Map.Entry<String, Map<String, Boolean>> e : backup.groupFlags.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    for (Map.Entry<String, Boolean> f : e.getValue().entrySet()) {
                        if (f.getKey() != null && f.getValue() != null) {
                            perms.setGroupFlag(e.getKey(), f.getKey(), f.getValue() ? FlagState.TRUE : FlagState.FALSE);
                        }
                    }
                }
            }
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            String childPath = relativePath.isEmpty() ? sub.getResidenceName() : (relativePath + "." + sub.getResidenceName());
            restoreRecursive(sub, childPath, areas);
        }
    }

    /**
     * 拍賣成交移轉給得標者時，清除主領地與全部子領地的既有權限設定（讓新領主從乾淨狀態開始設定）。
     */
    public static void clearAllFlagsForBuyer(ClaimedResidence topRes) {
        if (topRes == null) {
            return;
        }
        clearRecursive(topRes);
    }

    private static void clearRecursive(ClaimedResidence res) {
        ResidencePermissions perms = res.getPermissions();
        if (perms != null) {
            perms.clearFlags();
            perms.applyDefaultFlags();
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            clearRecursive(sub);
        }
    }

    /**
     * 遞迴移轉主領地與所有子領地的所有權給新領主（不清除子領地本身，但依參數決定是否重置旗標）。
     */
    public static void transferOwnershipRecursive(ClaimedResidence topRes, UUID newOwnerUuid, String newOwnerName, boolean resetFlags) {
        if (topRes == null || newOwnerUuid == null) {
            return;
        }
        topRes.getPermissions().setOwner(newOwnerUuid, resetFlags);
        if (newOwnerName != null && !newOwnerName.isEmpty()) {
            topRes.getPermissions().setOwnerLastKnownName(newOwnerName);
        }
        for (ClaimedResidence sub : topRes.getSubzones()) {
            transferOwnershipRecursive(sub, newOwnerUuid, newOwnerName, resetFlags);
        }
    }
}
