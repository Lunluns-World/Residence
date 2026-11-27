package com.bekvon.bukkit.residence.commands;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.permissions.PermissionGroup;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;

import net.Zrips.CMILib.FileHandler.ConfigReader;

public class reset implements cmd {

    private static boolean hasDeniedFlagDiff(CommandSender sender, Map<String, Boolean> current, Map<String, Boolean> target) {
        Set<String> keys = new HashSet<>();
        if (current != null)
            keys.addAll(current.keySet());
        if (target != null)
            keys.addAll(target.keySet());
        for (String flag : keys) {
            Boolean oldVal = current == null ? null : current.get(flag);
            Boolean newVal = target == null ? null : target.get(flag);
            if (!Objects.equals(oldVal, newVal) && FlagPermissions.isFlagPermissionDenied(sender, flag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeniedOwnerFlagReset(Residence plugin, CommandSender sender, ClaimedResidence res) {
        Map<String, Boolean> current = res.getPermissions().getPlayerFlags(res.getOwnerUUID());
        Map<String, Boolean> target = plugin.getConfigManager().getGlobalCreatorDefaultFlags().getFlags();
        return hasDeniedFlagDiff(sender, current, target);
    }

    private static boolean hasDeniedFullReset(Residence plugin, CommandSender sender, ClaimedResidence res) {
        ResidencePermissions perms = res.getPermissions();

        Map<String, Boolean> targetArea = new HashMap<>(plugin.getConfigManager().getGlobalResidenceDefaultFlags().getFlags());
        Map<String, Map<String, Boolean>> targetGroups = new HashMap<>();
        for (Entry<String, FlagPermissions> entry : plugin.getConfigManager().getGlobalGroupDefaultFlags().entrySet()) {
            targetGroups.put(entry.getKey().toLowerCase(), new HashMap<>(entry.getValue().getFlags()));
        }

        ResidencePlayer owner = ResidencePlayer.get(perms.getOwner());
        if (owner != null) {
            PermissionGroup group = owner.getGroup(perms.getWorldName());
            if (group != null) {
                for (Entry<String, Boolean> next : group.getDefaultResidenceFlags()) {
                    if (perms.checkValidFlag(next.getKey(), true)) {
                        targetArea.put(next.getKey(), next.getValue());
                    }
                }
                for (Entry<String, Map<String, Boolean>> entry : group.getDefaultGroupFlags()) {
                    targetGroups.computeIfAbsent(entry.getKey().toLowerCase(), k -> new HashMap<>()).putAll(entry.getValue());
                }
            }
        }

        if (hasDeniedFlagDiff(sender, perms.getFlags(), targetArea))
            return true;

        UUID ownerUuid = perms.getOwnerUUID();
        Map<String, Boolean> creatorDefaults = plugin.getConfigManager().getGlobalCreatorDefaultFlags().getFlags();
        Set<UUID> allUuids = new HashSet<>(perms.getPlayerFlags().keySet());
        if (ownerUuid != null)
            allUuids.add(ownerUuid);
        for (UUID uuid : allUuids) {
            Map<String, Boolean> currentPlayer = perms.getPlayerFlags(uuid);
            Map<String, Boolean> targetPlayer = uuid.equals(ownerUuid) ? creatorDefaults : null;
            if (hasDeniedFlagDiff(sender, currentPlayer, targetPlayer))
                return true;
        }
        for (Map<String, Boolean> namedFlags : perms.getPlayerFlagsByName().values()) {
            if (hasDeniedFlagDiff(sender, namedFlags, null))
                return true;
        }

        Set<String> allGroups = new HashSet<>(targetGroups.keySet());
        for (String groupName : perms.getGroupFlags().keySet()) {
            allGroups.add(groupName.toLowerCase());
        }
        for (String groupName : allGroups) {
            Map<String, Boolean> currentGroup = perms.getGroupFlags().get(groupName);
            Map<String, Boolean> targetGroup = targetGroups.get(groupName);
            if (hasDeniedFlagDiff(sender, currentGroup, targetGroup))
                return true;
        }

        return false;
    }

    @Override
    @CommandAnnotation(simple = true, priority = 4400)
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {

        if (args.length > 2) {
            return false;
        }
        if (args.length == 2 && !args[1].equalsIgnoreCase("-ownerflag")) {
            return false;
        }
        String residenceName = null;
        boolean isAllResidence = false;
        boolean isOwnerFlag = false;

        if (args.length >= 1) {

            residenceName = args[0];
            isAllResidence = residenceName.equalsIgnoreCase("all");

            if (args.length == 2) {
                // - /res reset all -ownerflag
                if (isAllResidence) {
                    if (!resadmin) {
                        lm.General_AdminOnly.sendMessage(sender);
                        return true;
                    }
                    isOwnerFlag = true;

                    // - /res reset [ResName] -ownerflag
                } else {
                    ClaimedResidence oneRes = ClaimedResidence.getByName(residenceName);
                    if (oneRes == null) {
                        lm.Invalid_Residence.sendMessage(sender);
                        return true;
                    }
                    if (!resadmin && !oneRes.isOwner(sender)) {
                        lm.Residence_NotOwner.sendMessage(sender);
                        return true;
                    }
                    if (!resadmin && hasDeniedOwnerFlagReset(plugin, sender, oneRes)) {
                        lm.General_NoPermission.sendMessage(sender);
                        return true;
                    }
                    if (plugin.getAuditManager() != null) {
                        plugin.getAuditManager().captureBeforeMutation(sender, oneRes, com.bekvon.bukkit.residence.audit.AuditAction.FLAG);
                    }
                    oneRes.getPermissions().resetGlobalCreatorDefaultFlags();
                    lm.Flag_resetOwnerFlags.sendMessage(sender, oneRes.getName());
                    return true;
                }
            }
        }

        ClaimedResidence res = null;
        if (residenceName != null && !isAllResidence)
            res = ClaimedResidence.getByName(residenceName);
        if (args.length == 0 && sender instanceof Player)
            res = ClaimedResidence.getByLoc(((Player) sender).getLocation());

        if (residenceName != null && !isAllResidence && res == null || args.length == 0 && res == null) {
            lm.Invalid_Residence.sendMessage(sender);
            return true;
        }

        if (res != null) {
            if (!resadmin && !res.isOwner(sender)) {
                lm.Residence_NotOwner.sendMessage(sender);
                return true;
            }

            if (res.getRaid().isRaidInitialized() && !resadmin) {
                lm.Raid_cantDo.sendMessage(sender);
                return true;
            }

            if (!resadmin && hasDeniedFullReset(plugin, sender, res)) {
                lm.General_NoPermission.sendMessage(sender);
                return true;
            }

            res.getPermissions().applyDefaultFlags();
            lm.Flag_reset.sendMessage(sender, res.getName());
            return true;
        }

        if (!resadmin) {
            lm.General_AdminOnly.sendMessage(sender);
            return true;
        }

        int count = 0;
        for (World oneW : Bukkit.getWorlds()) {
            for (ClaimedResidence one : plugin.getResidenceManager().getFromAllResidences(true, false, oneW)) {
                if (!isOwnerFlag) {
                    one.getPermissions().applyDefaultFlags();
                } else {
                    if (plugin.getAuditManager() != null) {
                        plugin.getAuditManager().captureBeforeMutation(sender, one, com.bekvon.bukkit.residence.audit.AuditAction.FLAG);
                    }
                    one.getPermissions().resetGlobalCreatorDefaultFlags();
                }
                count++;
            }
        }

        if (!isOwnerFlag) {
            lm.Flag_resetAll.sendMessage(sender, count);
        } else {
            lm.Flag_resetAllOwnerFlags.sendMessage(sender, count);
        }
        return true;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Reset residence to default flags.");
        c.get("Info", Arrays.asList("&eUsage: &6/res reset <residence/all> (-ownerflag)",
                "Resets the flags on a residence to their default.  You must be the owner or an admin to do this.",
                "-ownerflag: Reset only owner's flags."));
        LocaleManager.addTabCompleteMain(this, "[residence]%%all", "-ownerflag%%");

    }
}
