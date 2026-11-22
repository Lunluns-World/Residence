package com.bekvon.bukkit.residence.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Zrips.CMILib.FileHandler.ConfigReader;
import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.gui.ResidenceListGui.FilterMode;
import com.bekvon.bukkit.residence.gui.ResidenceListGui.SortMode;

public class listall implements cmd {

    @Override
    @CommandAnnotation(simple = true, priority = 4200)
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
                    plugin.getResidenceListGui().openBrowser(player, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, 1);
                    return true;
                }
                if (filteredArgs.size() == 1) {
                    try {
                        int guiPage = Math.max(1, Integer.parseInt(filteredArgs.get(0)));
                        plugin.getResidenceListGui().openBrowser(player, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, guiPage);
                        return true;
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (forceGui) {
                    plugin.getResidenceListGui().openBrowser(player, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, 1);
                    return true;
                }
            }
        }

        int page = 1;
        World world = null;

        c: for (String arg : filteredArgs) {
            try {
                page = Integer.parseInt(arg);
                if (page < 1)
                    page = 1;
                continue;
            } catch (Exception ex) {
            }

            if (arg.equalsIgnoreCase("-a") && !(sender instanceof Player)) {
                page = -1;
                continue;
            }
            if (arg.equalsIgnoreCase("-f") && !(sender instanceof Player)) {
                page = -2;
                continue;
            }

            for (World w : Bukkit.getWorlds()) {
                if (w.getName().equalsIgnoreCase(arg)) {
                    world = w;
                    continue c;
                }
            }
        }

        plugin.getResidenceManager().listAllResidences(sender, page, resadmin, world);
        return true;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "List All Residences");
        c.get("Info", Arrays.asList("&eUsage: &6/res listall [text|gui] <page> <worldName> <-a/-f>",
                "Opens the All Public Residence GUI (or uses classic chat text with 'text')."));
        LocaleManager.addTabCompleteMain(this, "text%%gui%%[worldname]");
    }
}
