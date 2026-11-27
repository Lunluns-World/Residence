package com.bekvon.bukkit.residence.commands;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;

import net.Zrips.CMILib.FileHandler.ConfigReader;

/**
 * 允許指定玩家（或所有人）在此領地旁小於最小領地間距（RangeGaps）建立或擴展領地：
 * - /res neighbor <player|*> [true|false|remove]
 * - /res neighbor <residence> <player|*> [true|false|remove]
 */
public class neighbor implements cmd {

    @Override
    @CommandAnnotation(simple = true, priority = 410, regVar = { 1, 2, 3 }, consoleVar = { 2, 3 })
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        if (args.length < 1 || args.length > 3) {
            return false;
        }

        ClaimedResidence res = null;
        String targetToken = null;
        FlagState state = FlagState.TRUE;

        if (args.length == 1) {
            if (!(sender instanceof Player)) {
                return false;
            }
            res = plugin.getResidenceManager().getByLoc(((Player) sender).getLocation());
            targetToken = args[0];
        } else if (args.length == 2) {
            FlagState maybeState = FlagPermissions.stringToFlagState(args[1]);
            if (maybeState != FlagState.INVALID) {
                if (!(sender instanceof Player)) {
                    return false;
                }
                res = plugin.getResidenceManager().getByLoc(((Player) sender).getLocation());
                targetToken = args[0];
                state = maybeState;
            } else {
                res = plugin.getResidenceManager().getByName(args[0]);
                targetToken = args[1];
            }
        } else {
            res = plugin.getResidenceManager().getByName(args[0]);
            targetToken = args[1];
            state = FlagPermissions.stringToFlagState(args[2]);
            if (state == FlagState.INVALID) {
                lm.Invalid_FlagState.sendMessage(sender);
                return true;
            }
        }

        if (res == null) {
            lm.Invalid_Residence.sendMessage(sender);
            return true;
        }
        if (!res.isTopArea()) {
            res = res.getTopParent();
        }

        String normalizedTarget = targetToken.trim().toLowerCase(Locale.ROOT);
        if ("*".equals(normalizedTarget) || "all".equals(normalizedTarget)) {
            res.getPermissions().setFlag(sender, Flags.neighbor.toString(), state, resadmin);
            return true;
        }

        if (!plugin.getPlayerManager().isPlayerExist(sender, targetToken, true)) {
            return true;
        }
        UUID targetUuid = ResidencePlayer.getUUID(targetToken);
        if (targetUuid == null) {
            lm.Invalid_Player.sendMessage(sender);
            return true;
        }
        res.getPermissions().setPlayerFlag(sender, targetUuid, Flags.neighbor.toString(), state, resadmin, true);
        return true;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Allow a player (or everyone) to claim closer than minimum residence gap.");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/res neighbor [residence] <player|*> [true|false|remove]",
                "Grants or revokes the 'neighbor' flag so friends can build residences right next to yours."));
        LocaleManager.addTabCompleteMain(this, "[residence]%%[playername]%%*", "[playername]%%*%%true%%false%%remove", "true%%false%%remove");
    }
}
