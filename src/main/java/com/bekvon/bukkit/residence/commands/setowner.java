package com.bekvon.bukkit.residence.commands;

import java.util.Arrays;

import org.bukkit.command.CommandSender;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import net.Zrips.CMILib.FileHandler.ConfigReader;

public class setowner implements cmd {

    @Override
    @CommandAnnotation(simple = false, priority = 5500)
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {

        if (args.length < 2)
            return false;

        if (!resadmin) {
            lm.General_NoPermission.sendMessage(sender);
            return true;
        }

        boolean keepFlags = plugin.getConfigManager().isKeepFlagsOnOwnerTransfer();
        if (args.length > 2) {
            if (args[2].equalsIgnoreCase("-keepflags"))
                keepFlags = true;
            else if (args[2].equalsIgnoreCase("-resetflags"))
                keepFlags = false;
        }

        ClaimedResidence area = plugin.getResidenceManager().getByName(args[0]);

        if (area == null) {
            lm.Invalid_Residence.sendMessage(sender);
            return null;
        }

        if (area.getRaid().isRaidInitialized() && !resadmin) {
            lm.Raid_cantDo.sendMessage(sender);
            return null;
        }

        boolean isSpecialTarget = args[1].equalsIgnoreCase("Server Land")
                || args[1].equalsIgnoreCase(plugin.getServerLandName())
                || (plugin.getCleanupReclaimManager() != null && plugin.getCleanupReclaimManager().isArchivedHolder(args[1]));
        if (!isSpecialTarget && !plugin.getPlayerManager().isPlayerExist(sender, args[1], true)) {
            return null;
        }

        boolean wasArchived = plugin.getCleanupReclaimManager() != null
                && plugin.getCleanupReclaimManager().isArchivedHolder(area.getOwner());
        boolean nowArchived = plugin.getCleanupReclaimManager() != null
                && plugin.getCleanupReclaimManager().isArchivedHolder(args[1]);

        area.getPermissions().setOwner(args[1], !keepFlags);
        if (wasArchived && !nowArchived && plugin.getCleanupReclaimManager() != null) {
            plugin.getCleanupReclaimManager().removeArchivedRecord(area);
        }
        if (plugin.getRentManager().isForRent(area))
            plugin.getRentManager().removeRentable(area);
        if (plugin.getTransactionManager().isForSale(area))
            plugin.getTransactionManager().removeFromSale(area);

        plugin.getSignUtil().updateSignResName(area);

        if (area.getParent() == null) {
            lm.Residence_OwnerChange.sendMessage(sender, args[0], args[1]);
        } else {
            lm.Subzone_OwnerChange.sendMessage(sender, args[0].split("\\.")[args[0].split("\\.").length - 1], args[1]);
        }

        return true;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Change owner of a residence.");
        c.get("Info", Arrays.asList("&eUsage: &6/resadmin setowner [residence] [player] (-keepflags)"));
        LocaleManager.addTabCompleteMain(this, "[cresidence]", "[playername]", "-keepflags");
    }

}
