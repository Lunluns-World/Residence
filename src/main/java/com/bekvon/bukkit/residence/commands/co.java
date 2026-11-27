package com.bekvon.bukkit.residence.commands;

import java.util.Arrays;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.audit.AuditManager;
import com.bekvon.bukkit.residence.audit.AuditQuery;
import com.bekvon.bukkit.residence.audit.AuditQueryParser;
import com.bekvon.bukkit.residence.audit.AuditQueryParser.Mode;
import com.bekvon.bukkit.residence.audit.AuditQueryParser.ParseResult;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import net.Zrips.CMILib.FileHandler.ConfigReader;

/**
 * 類 CoreProtect 領地歷史查詢、操作還原與半徑內領地恢復指令：
 * - /res co lookup (l) [u:<user>] [r:<radius>] [t:<time>] [a:<action>] [res:<name>]
 * - /res co rollback (rb) <t:<time>|#<id>> [u:<user>] [r:<radius>] [a:<action>] [res:<name>] [#preview]
 * - /res co restore (rs) <t:<time>|#<id>> [filters...] [#preview]
 * - /res co recover <t:<time>|#<id>> [r:<radius>] [u:<user>] [o:<owner>] [#preview]
 * - /res co inspect (i)
 * - /res co info <#id>
 * - /res co purge t:<time>
 */
public class co implements cmd {

    @Override
    @CommandAnnotation(simple = false, priority = 970)
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        boolean authorized = resadmin
                || ResAdmin.isResAdmin(sender)
                || plugin.getPermissionManager().isResidenceAdmin(sender)
                || ResPerm.audit.hasPermission(sender, false);
        if (!authorized) {
            lm.General_AdminOnly.sendMessage(sender);
            return true;
        }

        AuditManager mgr = plugin.getAuditManager();
        if (mgr == null || !mgr.isReady()) {
            lm.Audit_NotReady.sendMessage(sender);
            return true;
        }

        if (args.length == 0) {
            return false;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
        case "i":
        case "inspect":
        case "inspector": {
            if (!(sender instanceof Player)) {
                lm.Invalid_Ingame.sendMessage(sender);
                return true;
            }
            Player player = (Player) sender;
            boolean nowOn = mgr.toggleInspector(player.getUniqueId());
            if (nowOn) {
                lm.Audit_Inspect_Enabled.sendMessage(player);
            } else {
                lm.Audit_Inspect_Disabled.sendMessage(player);
            }
            return true;
        }
        case "info":
        case "show":
        case "detail": {
            if (args.length < 2) {
                return false;
            }
            String rawId = args[1].startsWith("#") ? args[1].substring(1) : args[1];
            try {
                long id = Long.parseLong(rawId);
                mgr.showRecordInfoAsync(sender, id);
            } catch (NumberFormatException ex) {
                lm.Audit_Parse_InvalidId.sendMessage(sender, args[1]);
            }
            return true;
        }
        case "tp":
        case "teleport": {
            if (!(sender instanceof Player) || args.length < 2) {
                return false;
            }
            String rawId = args[1].startsWith("#") ? args[1].substring(1) : args[1];
            try {
                long id = Long.parseLong(rawId);
                mgr.teleportToRecordAsync((Player) sender, id);
            } catch (NumberFormatException ex) {
                lm.Audit_Parse_InvalidId.sendMessage(sender, args[1]);
            }
            return true;
        }
        case "purge": {
            if (args.length < 2) {
                return false;
            }
            String rawDur = args[1];
            if (rawDur.toLowerCase(Locale.ROOT).startsWith("t:")) {
                rawDur = rawDur.substring(2);
            } else if (rawDur.toLowerCase(Locale.ROOT).startsWith("time:")) {
                rawDur = rawDur.substring(5);
            }
            long ms = AuditQueryParser.parseDurationMillis(rawDur);
            if (ms < 86_400_000L) {
                lm.Audit_Purge_MinOneDay.sendMessage(sender);
                return true;
            }
            mgr.purgeAsync(sender, ms, rawDur);
            return true;
        }
        case "l":
        case "lookup":
        case "search":
        case "log":
            return handleQueryOrExecute(plugin, mgr, sender, args, Mode.LOOKUP);
        case "rb":
        case "rollback":
        case "undo":
            return handleQueryOrExecute(plugin, mgr, sender, args, Mode.ROLLBACK);
        case "rs":
        case "restore":
        case "redo":
            return handleQueryOrExecute(plugin, mgr, sender, args, Mode.RESTORE);
        case "recover":
            return handleQueryOrExecute(plugin, mgr, sender, args, Mode.RECOVER);
        default:
            return false;
        }
    }

    private Boolean handleQueryOrExecute(Residence plugin, AuditManager mgr, CommandSender sender, String[] args, Mode mode) {
        String pWorld = null;
        Integer pX = null;
        Integer pZ = null;
        if (sender instanceof Player) {
            Location loc = ((Player) sender).getLocation();
            if (loc.getWorld() != null) {
                pWorld = loc.getWorld().getName();
            }
            pX = loc.getBlockX();
            pZ = loc.getBlockZ();
        }

        String[] effectiveArgs = args;
        // 貼心預設：玩家站在領地內直接打 `/res co l` 時自動查該領地；站在野外打 `/res co l` 時自動查周圍 r:20（連已刪除領地都查得到）
        if (mode == Mode.LOOKUP && args.length == 1 && sender instanceof Player) {
            Player player = (Player) sender;
            ClaimedResidence standing = plugin.getResidenceManager().getByLoc(player.getLocation());
            if (standing != null) {
                effectiveArgs = new String[] { args[0], "res:" + standing.getTopParentName() };
            } else {
                effectiveArgs = new String[] { args[0], "r:20" };
            }
        }

        ParseResult parsed = AuditQueryParser.parse(effectiveArgs, 1, mode, pWorld, pX, pZ, System.currentTimeMillis());
        if (!parsed.ok) {
            sendParseError(sender, parsed);
            return true;
        }

        AuditQuery q = parsed.query;
        if (mode == Mode.LOOKUP) {
            StringBuilder baseCmd = new StringBuilder("res co l");
            for (int i = 1; i < effectiveArgs.length; i++) {
                String tok = effectiveArgs[i];
                if (tok.toLowerCase(Locale.ROOT).startsWith("page:"))
                    continue;
                try {
                    Integer.parseInt(tok);
                    continue;
                } catch (NumberFormatException ignored) {
                }
                baseCmd.append(' ').append(tok);
            }
            mgr.lookupAsync(sender, q, baseCmd.toString());
        } else if (mode == Mode.ROLLBACK) {
            mgr.executeBatchAsync(sender, q, false, false);
        } else if (mode == Mode.RESTORE) {
            mgr.executeBatchAsync(sender, q, true, false);
        } else if (mode == Mode.RECOVER) {
            mgr.executeBatchAsync(sender, q, false, true);
        }
        return true;
    }

    private static void sendParseError(CommandSender sender, ParseResult res) {
        switch (res.error) {
        case MISSING_TIME:
            lm.Audit_Parse_MissingTime.sendMessage(sender);
            break;
        case INVALID_TIME:
            lm.Audit_Parse_InvalidTime.sendMessage(sender, res.badToken);
            break;
        case INVALID_RADIUS:
            lm.Audit_Parse_InvalidRadius.sendMessage(sender, res.badToken);
            break;
        case RADIUS_REQUIRES_LOCATION:
            lm.Audit_Parse_RadiusNeedsPlayer.sendMessage(sender, res.badToken);
            break;
        case INVALID_ACTION:
            lm.Audit_Parse_InvalidAction.sendMessage(sender, res.badToken);
            break;
        case INVALID_ID:
            lm.Audit_Parse_InvalidId.sendMessage(sender, res.badToken);
            break;
        default:
            lm.Audit_Parse_UnknownParam.sendMessage(sender, res.badToken);
            break;
        }
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "CoreProtect-style residence audit lookup, rollback, restore, and radius recovery.");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/res co l [u:<player>] [r:<radius>] [t:<time>] [a:<action>] [res:<name>]",
                "&eUsage: &6/res co rb <t:<time>|#<id>> [u:<player>] [r:<radius>] [a:<action>] [#preview]",
                "&eUsage: &6/res co rs <t:<time>|#<id>> [filters...] [#preview]",
                "&eUsage: &6/res co recover <t:<time>> [r:<radius>] [o:<owner>] [#preview]",
                "&eUsage: &6/res co i &7(Toggle block-click residence history inspector)"));
        LocaleManager.addTabCompleteMain(
                this,
                "l%%lookup%%rb%%rollback%%rs%%restore%%recover%%i%%inspect%%info%%purge",
                "t:1h%%t:24h%%t:7d%%t:30d%%r:30%%r:100%%u:%%o:%%res:%%a:remove%%a:flag%%a:create%%a:owner%%a:area%%#preview",
                "r:30%%r:100%%u:%%a:remove%%a:flag%%#preview");
    }
}
