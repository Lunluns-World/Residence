package com.bekvon.bukkit.residence.permissions;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

public class LuckPerms5Adapter implements PermissionsInterface {

    static LuckPerms api = null;

    public LuckPerms5Adapter() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            api = provider.getProvider();
        }
    }

    @Override
    public String getPlayerGroup(Player player) {
        return getPlayerGroup(player.getUniqueId(), player.getWorld().getName());
    }

    @Override
    @Deprecated
    public String getPlayerGroup(String player, String world) {
        User user = api.getUserManager().getUser(player);
        if (user == null)
            return "";
        return user.getPrimaryGroup();
    }

    @Override
    public String getPlayerGroup(UUID uuid, String world) {
        User user = api.getUserManager().getUser(uuid);
        if (user == null)
            return "";
        return user.getPrimaryGroup();
    }

    @Override
    public boolean hasPermission(OfflinePlayer player, String permission, String world) {
        if (api == null || player == null)
            return false;
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            try {
                user = api.getUserManager().loadUser(player.getUniqueId()).get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                return false;
            }
        }
        if (user == null)
            return false;
        return checkPermissionWithWorld(user, permission, world);
    }

    /**
     * Fail-safe offline permission check for AutoCleanUp.
     * Returns true (skip deletion) if LuckPerms is unavailable or loading the user fails/times out.
     */
    public boolean hasOfflinePermissionFailSafe(UUID uuid, String permission, String world) {
        if (api == null || uuid == null)
            return true;
        User user = api.getUserManager().getUser(uuid);
        if (user == null) {
            try {
                user = api.getUserManager().loadUser(uuid).get(3, TimeUnit.SECONDS);
            } catch (Exception ex) {
                return true;
            }
        }
        if (user == null)
            return true;
        return checkPermissionWithWorld(user, permission, world);
    }

    private static boolean checkPermissionWithWorld(User user, String permission, String world) {
        if (world != null && !world.isEmpty()) {
            MutableContextSet ctx = api != null
                    ? api.getContextManager().getStaticContext().mutableCopy()
                    : MutableContextSet.create();
            ctx.add("world", world.toLowerCase(Locale.ROOT));
            QueryOptions opts = QueryOptions.contextual(ctx.immutableCopy());
            return user.getCachedData().getPermissionData(opts).checkPermission(permission).asBoolean();
        }
        QueryOptions opts = api != null
                ? api.getContextManager().getStaticQueryOptions()
                : QueryOptions.nonContextual();
        return user.getCachedData().getPermissionData(opts).checkPermission(permission).asBoolean();
    }

    public static void loadUser(UUID uuid) {
        if (api != null && !api.getUserManager().isLoaded(uuid))
            api.getUserManager().loadUser(uuid);
    }
}
