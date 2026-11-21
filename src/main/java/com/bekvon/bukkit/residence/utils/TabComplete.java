package com.bekvon.bukkit.residence.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import com.bekvon.bukkit.residence.Residence;

public class TabComplete implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completionList = new ArrayList<>();
        if (args.length >= 1) {
            Residence plugin = Residence.getInstance();
            boolean adminCommand = command.getName().equalsIgnoreCase("resadmin");
            boolean admin = plugin.getPermissionManager().isResidenceAdmin(sender);
            if (adminCommand && !admin)
                return completionList;

            boolean resadmin = !(sender instanceof Player) || (admin
                    && (adminCommand || (command.getName().equalsIgnoreCase("res") && plugin.getConfigManager().getAdminFullAccess())));
            Set<String> commands = plugin.getHelpPages().getSubCommands(sender, args, resadmin);
            String partOfCommand = args[args.length - 1];
            StringUtil.copyPartialMatches(partOfCommand, commands, completionList);
            Collections.sort(completionList);
        }
        return completionList;
    }
}
