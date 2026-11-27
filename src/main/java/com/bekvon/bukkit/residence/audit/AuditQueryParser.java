package com.bekvon.bukkit.residence.audit;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CoreProtect 風格參數解析器（純邏輯、零 Bukkit 相依）。
 * <p>
 * 支援語法：
 * <ul>
 *   <li>{@code u:<player1,player2>} / {@code user:} / {@code -u:<player>}（操作者）</li>
 *   <li>{@code o:<owner1,owner2>} / {@code owner:}（領地擁有者）</li>
 *   <li>{@code t:<duration>} / {@code time:}（如 {@code 30m}, {@code 12h}, {@code 7d}, {@code 1w2d3h}, {@code 2d-1d}）</li>
 *   <li>{@code r:<radius|#global|#world>} / {@code radius:}（以玩家為中心的方塊半徑）</li>
 *   <li>{@code a:<actions>} / {@code action:} / {@code -a:<actions>}（如 {@code a:create,remove,flag,owner,area,rename}）</li>
 *   <li>{@code res:<name>} / {@code residence:}（指定領地或子領地名稱）</li>
 *   <li>{@code w:<world>} / {@code world:}（指定世界）</li>
 *   <li>{@code id:<id1,id2>} 或 {@code #<id>}（指定紀錄編號）</li>
 *   <li>{@code #preview}（預覽模式，不實際變更）、{@code #global}、{@code #allservers}</li>
 * </ul>
 */
public final class AuditQueryParser {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([wdhms])", Pattern.CASE_INSENSITIVE);

    public enum Mode {
        LOOKUP,
        ROLLBACK,
        RESTORE,
        RECOVER
    }

    public enum ErrorCode {
        NONE,
        INVALID_TIME,
        MISSING_TIME,
        INVALID_RADIUS,
        RADIUS_REQUIRES_LOCATION,
        INVALID_ACTION,
        INVALID_ID,
        UNKNOWN_PARAMETER
    }

    public static final class ParseResult {
        public final boolean ok;
        public final ErrorCode error;
        public final String badToken;
        public final AuditQuery query;

        private ParseResult(boolean ok, ErrorCode error, String badToken, AuditQuery query) {
            this.ok = ok;
            this.error = error;
            this.badToken = badToken;
            this.query = query;
        }

        public static ParseResult success(AuditQuery query) {
            return new ParseResult(true, ErrorCode.NONE, null, query);
        }

        public static ParseResult fail(ErrorCode error, String badToken) {
            return new ParseResult(false, error, badToken, null);
        }
    }

    private AuditQueryParser() {
    }

    /**
     * 解析類似 CoreProtect 的時間字串（如 "1w2d3h4m5s"、"1.5h"、"90d"），回傳毫秒數；無效時回傳 -1。
     */
    public static long parseDurationMillis(String input) {
        if (input == null) {
            return -1L;
        }
        String clean = input.trim();
        if (clean.isEmpty()) {
            return -1L;
        }
        Matcher m = DURATION_PART.matcher(clean);
        double totalMs = 0D;
        int matchedEnd = 0;
        boolean found = false;
        while (m.find()) {
            String between = clean.substring(matchedEnd, m.start()).trim();
            if (!between.isEmpty() && !",".equals(between)) {
                return -1L;
            }
            matchedEnd = m.end();
            found = true;
            double val;
            try {
                val = Double.parseDouble(m.group(1));
            } catch (NumberFormatException ex) {
                return -1L;
            }
            if (val < 0 || Double.isNaN(val) || Double.isInfinite(val)) {
                return -1L;
            }
            char unit = Character.toLowerCase(m.group(2).charAt(0));
            switch (unit) {
            case 'w':
                totalMs += val * 7D * 86_400_000D;
                break;
            case 'd':
                totalMs += val * 86_400_000D;
                break;
            case 'h':
                totalMs += val * 3_600_000D;
                break;
            case 'm':
                totalMs += val * 60_000D;
                break;
            case 's':
                totalMs += val * 1_000D;
                break;
            default:
                return -1L;
            }
        }
        if (!found || !clean.substring(matchedEnd).trim().isEmpty()) {
            return -1L;
        }
        long rounded = Math.round(totalMs);
        return rounded > 0 ? rounded : -1L;
    }

    public static ParseResult parse(
            String[] tokens,
            int startIndex,
            Mode mode,
            String playerWorld,
            Integer playerX,
            Integer playerZ,
            long nowEpochMs) {

        Set<Long> entryIds = new LinkedHashSet<>();
        Set<String> actors = new LinkedHashSet<>();
        Set<String> excludedActors = new LinkedHashSet<>();
        Set<String> owners = new LinkedHashSet<>();
        EnumSet<AuditAction> includedActions = EnumSet.noneOf(AuditAction.class);
        EnumSet<AuditAction> excludedActions = EnumSet.noneOf(AuditAction.class);
        Set<String> residences = new LinkedHashSet<>();

        String worldName = null;
        Integer radius = null;
        boolean explicitGlobal = false;
        Long sinceEpochMs = null;
        Long untilEpochMs = null;
        boolean preview = false;
        boolean allServers = false;
        int page = 1;

        if (tokens != null) {
            for (int i = Math.max(0, startIndex); i < tokens.length; i++) {
                String raw = tokens[i];
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String token = raw.trim();
                String lower = token.toLowerCase(Locale.ROOT);

                if ("#preview".equals(lower) || "-preview".equals(lower) || "preview".equals(lower)) {
                    preview = true;
                    continue;
                }
                if ("#global".equals(lower)) {
                    explicitGlobal = true;
                    radius = null;
                    worldName = null;
                    continue;
                }
                if ("#allservers".equals(lower) || "#crossserver".equals(lower)) {
                    allServers = true;
                    continue;
                }
                if (token.startsWith("#") && token.length() > 1) {
                    String numPart = token.substring(1);
                    try {
                        long id = Long.parseLong(numPart);
                        if (id <= 0) {
                            return ParseResult.fail(ErrorCode.INVALID_ID, token);
                        }
                        entryIds.add(id);
                        continue;
                    } catch (NumberFormatException ignored) {
                        // 非數字標籤（若未命中已知 #preview / #global 則視為未知參數）
                        return ParseResult.fail(ErrorCode.UNKNOWN_PARAMETER, token);
                    }
                }

                int colon = token.indexOf(':');
                if (colon <= 0) {
                    // Lookup 模式下支援直接輸入頁碼或單一領地名稱（例如 /res co l 2 或 /res co l MyCastle）
                    if (mode == Mode.LOOKUP) {
                        try {
                            int p = Integer.parseInt(token);
                            if (p >= 1) {
                                page = p;
                                continue;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                        residences.add(token.toLowerCase(Locale.ROOT));
                        continue;
                    }
                    return ParseResult.fail(ErrorCode.UNKNOWN_PARAMETER, token);
                }

                String key = token.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = token.substring(colon + 1).trim();
                if (val.isEmpty()) {
                    return ParseResult.fail(ErrorCode.UNKNOWN_PARAMETER, token);
                }

                switch (key) {
                case "u":
                case "user":
                case "users":
                case "p":
                case "player": {
                    for (String part : val.split(",")) {
                        String clean = part.trim();
                        if (clean.isEmpty())
                            continue;
                        if (clean.startsWith("!")) {
                            String ex = clean.substring(1).trim().toLowerCase(Locale.ROOT);
                            if (!ex.isEmpty())
                                excludedActors.add(ex);
                        } else {
                            actors.add(clean.toLowerCase(Locale.ROOT));
                        }
                    }
                    break;
                }
                case "-u":
                case "-user": {
                    for (String part : val.split(",")) {
                        String clean = part.trim().toLowerCase(Locale.ROOT);
                        if (!clean.isEmpty())
                            excludedActors.add(clean);
                    }
                    break;
                }
                case "o":
                case "owner":
                case "owners": {
                    for (String part : val.split(",")) {
                        String clean = part.trim().toLowerCase(Locale.ROOT);
                        if (!clean.isEmpty())
                            owners.add(clean);
                    }
                    break;
                }
                case "res":
                case "residence": {
                    for (String part : val.split(",")) {
                        String clean = part.trim().toLowerCase(Locale.ROOT);
                        if (!clean.isEmpty())
                            residences.add(clean);
                    }
                    break;
                }
                case "w":
                case "world": {
                    worldName = val.trim();
                    break;
                }
                case "id":
                case "ids": {
                    for (String part : val.split(",")) {
                        String clean = part.trim();
                        if (clean.startsWith("#"))
                            clean = clean.substring(1);
                        try {
                            long id = Long.parseLong(clean);
                            if (id <= 0)
                                return ParseResult.fail(ErrorCode.INVALID_ID, token);
                            entryIds.add(id);
                        } catch (NumberFormatException ex) {
                            return ParseResult.fail(ErrorCode.INVALID_ID, token);
                        }
                    }
                    break;
                }
                case "page": {
                    try {
                        page = Math.max(1, Integer.parseInt(val));
                    } catch (NumberFormatException ex) {
                        return ParseResult.fail(ErrorCode.UNKNOWN_PARAMETER, token);
                    }
                    break;
                }
                case "r":
                case "radius": {
                    String rLow = val.toLowerCase(Locale.ROOT);
                    if ("#global".equals(rLow) || "global".equals(rLow)) {
                        explicitGlobal = true;
                        radius = null;
                        worldName = null;
                    } else if ("#world".equals(rLow) || "world".equals(rLow)) {
                        if (playerWorld == null && worldName == null) {
                            return ParseResult.fail(ErrorCode.RADIUS_REQUIRES_LOCATION, token);
                        }
                        if (worldName == null) {
                            worldName = playerWorld;
                        }
                        radius = null;
                    } else {
                        if (playerWorld == null || playerX == null || playerZ == null) {
                            return ParseResult.fail(ErrorCode.RADIUS_REQUIRES_LOCATION, token);
                        }
                        try {
                            int r = Integer.parseInt(val);
                            if (r <= 0 || r > 100_000) {
                                return ParseResult.fail(ErrorCode.INVALID_RADIUS, token);
                            }
                            radius = r;
                            if (worldName == null && !explicitGlobal) {
                                worldName = playerWorld;
                            }
                        } catch (NumberFormatException ex) {
                            return ParseResult.fail(ErrorCode.INVALID_RADIUS, token);
                        }
                    }
                    break;
                }
                case "t":
                case "time": {
                    int dash = val.indexOf('-');
                    if (dash > 0) {
                        long older = parseDurationMillis(val.substring(0, dash));
                        long newer = parseDurationMillis(val.substring(dash + 1));
                        if (older <= 0 || newer < 0) {
                            return ParseResult.fail(ErrorCode.INVALID_TIME, token);
                        }
                        long maxDur = Math.max(older, newer);
                        long minDur = Math.min(older, newer);
                        sinceEpochMs = Math.max(0L, nowEpochMs - maxDur);
                        untilEpochMs = Math.max(0L, nowEpochMs - minDur);
                    } else {
                        long dur = parseDurationMillis(val);
                        if (dur <= 0) {
                            return ParseResult.fail(ErrorCode.INVALID_TIME, token);
                        }
                        sinceEpochMs = Math.max(0L, nowEpochMs - dur);
                        untilEpochMs = null;
                    }
                    break;
                }
                case "a":
                case "action":
                case "actions": {
                    for (String part : val.split(",")) {
                        String p = part.trim();
                        if (p.isEmpty())
                            continue;
                        boolean exclude = false;
                        if (p.startsWith("!") || p.startsWith("-")) {
                            // 注意 "-res" 與 "-sub" 本身是刪除動作的別名，只有當後面不是 res/sub 時才視為排除
                            Set<AuditAction> direct = AuditAction.parseFilterToken(p);
                            if (!direct.isEmpty()) {
                                includedActions.addAll(direct);
                                continue;
                            }
                            exclude = true;
                            p = p.substring(1).trim();
                        }
                        Set<AuditAction> parsed = AuditAction.parseFilterToken(p);
                        if (parsed.isEmpty()) {
                            return ParseResult.fail(ErrorCode.INVALID_ACTION, token);
                        }
                        if (exclude) {
                            excludedActions.addAll(parsed);
                        } else {
                            includedActions.addAll(parsed);
                        }
                    }
                    break;
                }
                case "-a":
                case "-action": {
                    for (String part : val.split(",")) {
                        String p = part.trim();
                        if (p.isEmpty())
                            continue;
                        Set<AuditAction> parsed = AuditAction.parseFilterToken(p);
                        if (parsed.isEmpty()) {
                            return ParseResult.fail(ErrorCode.INVALID_ACTION, token);
                        }
                        excludedActions.addAll(parsed);
                    }
                    break;
                }
                default:
                    return ParseResult.fail(ErrorCode.UNKNOWN_PARAMETER, token);
                }
            }
        }

        if (mode == Mode.RECOVER && includedActions.isEmpty()) {
            includedActions.add(AuditAction.REMOVE);
            includedActions.add(AuditAction.SUBZONE_REMOVE);
        }

        EnumSet<AuditAction> finalActions;
        if (includedActions.isEmpty() && excludedActions.isEmpty()) {
            finalActions = EnumSet.noneOf(AuditAction.class);
        } else {
            finalActions = includedActions.isEmpty() ? EnumSet.allOf(AuditAction.class) : EnumSet.copyOf(includedActions);
            finalActions.removeAll(excludedActions);
        }

        // 安全防護：與 CoreProtect 一致，執行 rollback / restore / recover 時必須指定 t:<時間> 或 #id（避免空參數誤還原開服以來所有紀錄）
        if ((mode == Mode.ROLLBACK || mode == Mode.RESTORE || mode == Mode.RECOVER)
                && sinceEpochMs == null && entryIds.isEmpty()) {
            return ParseResult.fail(ErrorCode.MISSING_TIME, "t:<time>");
        }

        Integer cX = radius != null ? playerX : null;
        Integer cZ = radius != null ? playerZ : null;

        AuditQuery query = new AuditQuery(
                entryIds, actors, excludedActors, owners, finalActions, residences,
                worldName, radius, cX, cZ, sinceEpochMs, untilEpochMs, preview, allServers, page);
        return ParseResult.success(query);
    }
}
