package com.bekvon.bukkit.residence.commands;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.storage.PlayerLimitManager;
import com.bekvon.bukkit.residence.storage.PlayerLimitManager.LimitType;
import com.bekvon.bukkit.residence.storage.PlayerLimitManager.PlayerLimitData;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.FileHandler.ConfigReader;

public class limit implements cmd {

    @Override
    @CommandAnnotation(simple = false, priority = 950, regVar = { 2, 3, 4 }, consoleVar = { 2, 3, 4 })
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        if (!resadmin) {
            lm.General_AdminOnly.sendMessage(sender);
            return true;
        }
        if (args.length < 2) {
            return false;
        }

        String playerArg = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);

        ResolvedPlayer target = resolvePlayer(plugin, playerArg);
        if (target == null) {
            lm.Invalid_Player.sendMessage(sender);
            return true;
        }

        PlayerLimitManager mgr = plugin.getPlayerLimitManager();
        if (mgr == null) {
            sender.sendMessage(CMIChatColor.RED + "[Residence] PlayerLimitManager is not initialized.");
            return true;
        }

        if (action.equals("info") || action.equals("check") || action.equals("get")) {
            printInfo(plugin, sender, target);
            return true;
        }

        if (action.equals("reset") || action.equals("clear")) {
            if (args.length < 3)
                return false;
            String typeRaw = args[2].toLowerCase(Locale.ROOT);
            try {
                if (typeRaw.equals("all")) {
                    mgr.resetLimit(target.uuid, target.name, null);
                    sender.sendMessage(CMIChatColor.GREEN + "[Residence] Reset ALL custom limits for " + target.name + ".");
                } else if (typeRaw.equals("size") || typeRaw.equals("xz")) {
                    mgr.resetLimit(target.uuid, target.name, LimitType.X);
                    mgr.resetLimit(target.uuid, target.name, LimitType.Z);
                    sender.sendMessage(CMIChatColor.GREEN + "[Residence] Reset X/Z size limits for " + target.name + ".");
                } else {
                    LimitType type = LimitType.parse(typeRaw);
                    if (type == null)
                        return false;
                    mgr.resetLimit(target.uuid, target.name, type);
                    sender.sendMessage(CMIChatColor.GREEN + "[Residence] Reset " + type.getKey() + " limit for " + target.name + ".");
                }
            } catch (SQLException ex) {
                sender.sendMessage(CMIChatColor.RED + "[Residence] Database error while resetting limit: " + ex.getMessage());
            }
            return true;
        }

        if (args.length < 4) {
            return false;
        }

        String typeRaw = args[2].toLowerCase(Locale.ROOT);
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            lm.Invalid_Amount.sendMessage(sender);
            return true;
        }

        if ((action.equals("add") || action.equals("give") || action.equals("remove") || action.equals("take")) && amount <= 0) {
            lm.Invalid_Amount.sendMessage(sender);
            return true;
        }
        if (action.equals("set") && amount < 0) {
            lm.Invalid_Amount.sendMessage(sender);
            return true;
        }

        LimitType[] targets;
        if (typeRaw.equals("size") || typeRaw.equals("xz")) {
            targets = new LimitType[] { LimitType.X, LimitType.Z };
        } else {
            LimitType parsed = LimitType.parse(typeRaw);
            if (parsed == null)
                return false;
            targets = new LimitType[] { parsed };
        }

        try {
            switch (action) {
            case "add":
            case "give":
                for (LimitType t : targets) {
                    mgr.addLimit(target.uuid, target.name, t, amount);
                }
                sender.sendMessage(CMIChatColor.GREEN + "[Residence] Added +" + amount + " to " + typeRaw + " limit for " + target.name
                        + " (" + formatCurrentSummary(plugin, target, targets) + ").");
                return true;
            case "remove":
            case "take":
                for (LimitType t : targets) {
                    mgr.addLimit(target.uuid, target.name, t, -amount);
                }
                sender.sendMessage(CMIChatColor.GREEN + "[Residence] Removed -" + amount + " from " + typeRaw + " limit for " + target.name
                        + " (" + formatCurrentSummary(plugin, target, targets) + ").");
                return true;
            case "set":
                for (LimitType t : targets) {
                    mgr.setLimit(target.uuid, target.name, t, amount);
                }
                sender.sendMessage(CMIChatColor.GREEN + "[Residence] Set " + typeRaw + " limit for " + target.name + " to " + amount
                        + " (" + formatCurrentSummary(plugin, target, targets) + ").");
                return true;
            default:
                return false;
            }
        } catch (SQLException ex) {
            sender.sendMessage(CMIChatColor.RED + "[Residence] Database error while updating limit: " + ex.getMessage());
            return true;
        }
    }

    private static String formatCurrentSummary(Residence plugin, ResolvedPlayer target, LimitType[] types) {
        ResidencePlayer rp = plugin.getPlayerManager().getResidencePlayer(target.uuid);
        PlayerLimitData data = plugin.getPlayerLimitManager().get(target.uuid);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            LimitType t = types[i];
            if (i > 0) sb.append(", ");
            int extra = data.getExtra(t);
            Integer ov = data.getOverride(t);
            sb.append(t.getKey()).append("=");
            if (rp != null) {
                sb.append(getEffective(rp, t));
            } else if (ov != null) {
                sb.append(ov + extra);
            } else {
                sb.append("group+").append(extra);
            }
            if (ov != null) {
                sb.append(" [set:").append(ov);
                if (extra > 0) sb.append(" +").append(extra);
                sb.append("]");
            } else if (extra > 0) {
                sb.append(" [+").append(extra).append("]");
            }
        }
        return sb.toString();
    }

    private static int getEffective(ResidencePlayer rp, LimitType t) {
        switch (t) {
        case RES: return rp.getMaxRes();
        case X: return rp.getMaxX();
        case Y: return rp.getMaxY();
        case Z: return rp.getMaxZ();
        case SUBZONES: return rp.getMaxSubzones();
        case RENTS: return rp.getMaxRents();
        default: return 0;
        }
    }

    private static void printInfo(Residence plugin, CommandSender sender, ResolvedPlayer target) {
        ResidencePlayer rp = plugin.getPlayerManager().getResidencePlayer(target.uuid);
        PlayerLimitData data = plugin.getPlayerLimitManager().get(target.uuid);
        sender.sendMessage(CMIChatColor.GOLD + "=== Residence Limits: " + target.name + " ===");
        for (LimitType t : LimitType.values()) {
            Integer ov = data.getOverride(t);
            int extra = data.getExtra(t);
            String effStr = rp != null ? String.valueOf(getEffective(rp, t)) : (ov != null ? String.valueOf(ov + extra) : "group+" + extra);
            sender.sendMessage(CMIChatColor.YELLOW + " - " + t.getKey() + ": " + CMIChatColor.WHITE + effStr
                    + CMIChatColor.GRAY + " (override=" + (ov == null ? "none" : ov) + ", extra=+" + extra + ")");
        }
    }

    private static final class ResolvedPlayer {
        final UUID uuid;
        final String name;

        ResolvedPlayer(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private static ResolvedPlayer resolvePlayer(Residence plugin, String input) {
        if (input == null || input.trim().isEmpty())
            return null;
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new ResolvedPlayer(online.getUniqueId(), online.getName());
        }
        try {
            UUID parsed = UUID.fromString(input);
            OfflinePlayer off = Bukkit.getOfflinePlayer(parsed);
            return new ResolvedPlayer(parsed, off.getName() != null ? off.getName() : input);
        } catch (IllegalArgumentException ignored) {
        }
        ResidencePlayer rp = plugin.getPlayerManager().getResidencePlayer(input);
        if (rp != null && rp.getUniqueId() != null) {
            return new ResolvedPlayer(rp.getUniqueId(), rp.getName() != null ? rp.getName() : input);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && cached.getUniqueId() != null) {
            return new ResolvedPlayer(cached.getUniqueId(), cached.getName() != null ? cached.getName() : input);
        }
        return null;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Manage per-player cross-server residence count and size limits.");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/resadmin limit <player> info",
                "&eUsage: &6/resadmin limit <player> <add|remove|set> <res|x|y|z|size|subzones|rents> <amount>",
                "&eUsage: &6/resadmin limit <player> reset <res|x|y|z|size|subzones|rents|all>"));
        LocaleManager.addTabCompleteMain(this, "[playername]", "add%%remove%%set%%reset%%info", "res%%x%%y%%z%%size%%subzones%%rents%%all");
    }
}
