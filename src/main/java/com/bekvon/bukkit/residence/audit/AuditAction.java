package com.bekvon.bukkit.residence.audit;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 領地操作紀錄類型（對齊 CoreProtect 的 a: 過濾語法）。
 */
public enum AuditAction {
    CREATE("create", "+res"),
    REMOVE("remove", "-res"),
    SUBZONE_CREATE("subzone_create", "+sub"),
    SUBZONE_REMOVE("subzone_remove", "-sub"),
    FLAG("flag", "flag"),
    OWNER("owner", "owner"),
    AREA("area", "area"),
    RENAME("rename", "rename"),
    META("meta", "meta");

    private final String id;
    private final String shortTag;

    AuditAction(String id, String shortTag) {
        this.id = id;
        this.shortTag = shortTag;
    }

    public String getId() {
        return id;
    }

    public String getShortTag() {
        return shortTag;
    }

    public static AuditAction fromDatabase(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return AuditAction.valueOf(norm);
        } catch (IllegalArgumentException ignored) {
            for (AuditAction a : values()) {
                if (a.id.equalsIgnoreCase(norm)) {
                    return a;
                }
            }
            return null;
        }
    }

    /**
     * 解析使用者在 a:<token> 輸入的操作別名（如 delete/remove/-res、create/+res、flag/perm/set/pset、subzone 等）。
     * 若無法辨識則回傳空集合。
     */
    public static Set<AuditAction> parseFilterToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Collections.emptySet();
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        switch (t) {
        case "all":
        case "*":
            return EnumSet.allOf(AuditAction.class);
        case "create":
        case "+res":
        case "+residence":
        case "add":
            return EnumSet.of(CREATE, SUBZONE_CREATE);
        case "topcreate":
        case "res_create":
            return EnumSet.of(CREATE);
        case "remove":
        case "delete":
        case "del":
        case "-res":
        case "-residence":
            return EnumSet.of(REMOVE, SUBZONE_REMOVE);
        case "topremove":
        case "topdelete":
        case "res_remove":
            return EnumSet.of(REMOVE);
        case "subzone":
        case "sub":
        case "sz":
            return EnumSet.of(SUBZONE_CREATE, SUBZONE_REMOVE);
        case "subzone_create":
        case "+subzone":
        case "+sub":
            return EnumSet.of(SUBZONE_CREATE);
        case "subzone_remove":
        case "subzone_delete":
        case "-subzone":
        case "-sub":
            return EnumSet.of(SUBZONE_REMOVE);
        case "flag":
        case "flags":
        case "perm":
        case "perms":
        case "permission":
        case "permissions":
        case "set":
        case "pset":
        case "gset":
        case "padd":
        case "pdel":
            return EnumSet.of(FLAG);
        case "owner":
        case "setowner":
        case "give":
        case "transfer":
        case "archive":
            return EnumSet.of(OWNER);
        case "area":
        case "areas":
        case "resize":
        case "expand":
        case "contract":
            return EnumSet.of(AREA);
        case "rename":
            return EnumSet.of(RENAME);
        case "meta":
        case "tp":
        case "tpset":
        case "message":
        case "msg":
        case "shop":
            return EnumSet.of(META);
        default:
            return Collections.emptySet();
        }
    }
}
