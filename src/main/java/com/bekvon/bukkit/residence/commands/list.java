package com.bekvon.bukkit.residence.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.TargetInfo;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.gui.ResidenceListGui.FilterMode;
import com.bekvon.bukkit.residence.gui.ResidenceListGui.SortMode;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;

import net.Zrips.CMILib.Container.CMIWorld;
import net.Zrips.CMILib.FileHandler.ConfigReader;

public class list implements cmd {

    @Override
    @CommandAnnotation(simple = true, priority = 300)
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {

        boolean forceText = false;
        boolean forceGui = false;
        List<String> filteredArgs = new ArrayList<>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("text")) {
                forceText = true;
                continue;
            }
            if (arg.equalsIgnoreCase("gui")) {
                forceGui = true;
                continue;
            }
            filteredArgs.add(arg);
        }

        if (sender instanceof Player && !forceText) {
            Player player = (Player) sender;
            boolean guiEnabled = plugin.getConfigManager().isResidenceGuiEnabled();
            if (forceGui && !guiEnabled) {
                lm.Gui_Disabled.sendMessage(player);
                return true;
            }
            if (guiEnabled && (forceGui || plugin.getConfigManager().isResidenceGuiOpenOnListCommand())) {
                if (filteredArgs.isEmpty()) {
                    plugin.getResidenceListGui().openBrowser(player, FilterMode.MINE, SortMode.DEFAULT, null, resadmin, 1);
                    return true;
                }
                if (filteredArgs.size() == 1) {
                    String single = filteredArgs.get(0);
                    try {
                        int guiPage = Math.max(1, Integer.parseInt(single));
                        plugin.getResidenceListGui().openBrowser(player, FilterMode.MINE, SortMode.DEFAULT, null, resadmin, guiPage);
                        return true;
                    } catch (NumberFormatException ignored) {
                    }
                    TargetInfo targetInfo = new TargetInfo();
                    targetInfo.defaultIfNotValid(single);
                    if (targetInfo.isValid()) {
                        if (!targetInfo.isSame(sender) && !ResPerm.command_$1_others.hasPermission(sender, this.getClass().getSimpleName())) {
                            lm.General_NoCmdPermission.sendMessage(sender);
                            return true;
                        }
                        if (targetInfo.isSame(sender)) {
                            plugin.getResidenceListGui().openBrowser(player, FilterMode.MINE, SortMode.DEFAULT, null, resadmin, 1);
                        } else {
                            String resolvedName = com.bekvon.bukkit.residence.containers.ResidencePlayer.getName(targetInfo.getUniqueId());
                            plugin.getResidenceListGui().openBrowserForOwner(player, targetInfo.getUniqueId(),
                                    resolvedName != null ? resolvedName : single, resadmin, 1);
                        }
                        return true;
                    }
                }
                if (forceGui) {
                    plugin.getResidenceListGui().openBrowser(player, FilterMode.MINE, SortMode.DEFAULT, null, resadmin, 1);
                    return true;
                }
            }
        }

        int page = 1;
        World world = null;

        TargetInfo info = new TargetInfo();

        for (String arg : filteredArgs) {

            if (!info.isValid()) {
                info.defaultIfNotValid(arg);
                if (info.isValid()) {
                    continue;
                }
            }

            try {
                page = Integer.parseInt(arg);
                if (page < 1)
                    page = 1;
                continue;
            } catch (Exception ex) {
            }

            if (world == null) {
                World tempW = CMIWorld.getWorld(arg);
                if (tempW != null && tempW.getName().equalsIgnoreCase(arg)) {
                    world = tempW;
                    continue;
                }
            }
        }

        info.defaultIfNotValid(sender);

        if (!info.isValid()) {
            lm.Invalid_Player.sendMessage(sender);
            return false;
        }

        if (!info.isSame(sender) && !ResPerm.command_$1_others.hasPermission(sender, this.getClass().getSimpleName())) {
            lm.General_NoCmdPermission.sendMessage(sender);
            return true;
        }

        plugin.getResidenceManager().listResidences(sender, info.getUniqueId(), page, false, false, resadmin, world);

        return true;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "List Residences");
        c.get("Info", Arrays.asList("&eUsage: &6/res list [text|gui] <player> <page> <worldName>",
                "Opens the Residence GUI (or uses classic chat text with 'text').",
                "Lists all the residences a player owns (except hidden ones).",
                "If listing your own residences, shows hidden ones as well.",
                "To list everyones residences, use /res listall."));
        LocaleManager.addTabCompleteMain(this, "text%%gui%%[playername]", "[worldname]");
    }
}
