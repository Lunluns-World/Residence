package com.bekvon.bukkit.residence.listeners;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityPortalExitEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import io.papermc.paper.event.block.BlockPreDispenseEvent;

import com.bekvon.bukkit.residence.ConfigManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.commands.auto;
import com.bekvon.bukkit.residence.containers.ResidenceBlockData;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.utils.Utils;

import net.Zrips.CMILib.ActionBar.CMIActionBar;
import net.Zrips.CMILib.Container.CMIVectorInt3D;
import net.Zrips.CMILib.Items.CMIMC;
import net.Zrips.CMILib.Items.CMIMaterial;
import net.Zrips.CMILib.Version.Version;

import org.jspecify.annotations.Nullable;

public class ResidenceBlockListener implements Listener {

    private final List<UUID> MessageInformed = new ArrayList<>();

    private final Residence plugin;

    public ResidenceBlockListener(Residence residence) {
        this.plugin = residence;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnvilInventoryClick(InventoryClickEvent e) {
        // Paper 1.16.5+ uses AnvilDamagedEvent
        if (Version.isCurrentEqualOrHigher(Version.v1_16_5) && Version.isPaperBranch()) {
            return;
        }
        // Disabling listener if flag disabled globally
        if (!Flags.anvilbreak.isGlobalyEnabled())
            return;
        Inventory inv = e.getInventory();
        try {
            if (inv == null || inv.getType() != InventoryType.ANVIL || e.getInventory().getLocation() == null)
                return;
        } catch (Exception | NoSuchMethodError ex) {
            return;
        }
        Block b = e.getInventory().getLocation().getBlock();
        if (b == null || !CMIMaterial.isAnvil(b.getType()))
            return;

        ClaimedResidence res = plugin.getResidenceManager().getByLoc(e.getInventory().getLocation());
        if (res == null)
            return;

        // Fix anvil only when item is picked up
        if (e.getRawSlot() != 2)
            return;
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR)
            return;
        if (!res.getPermissions().has(Flags.anvilbreak, FlagCombo.OnlyFalse))
            return;

        if (Version.isCurrentLower(Version.v1_13_0)) {
            try {
                b.getClass().getMethod("setData", byte.class).invoke(b, (byte) 1);
            } catch (Throwable e1) {
                e1.printStackTrace();
            }
        } else {
            ResidenceBlockData.updateAnvilFacing(b);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlantGrow(BlockGrowEvent event) {

        Block block = event.getBlock();

        if (plugin.isDisabledWorldListener(block)) {
            return;
        }
        if (Flags.grow.isGlobalyEnabled()) {
            FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
            if (!perms.has(Flags.grow, true)) {
                event.setCancelled(true);
                return;
            }
        }
        if (Flags.build.isGlobalyEnabled()) {
            ClaimedResidence targetRes = ClaimedResidence.getByLoc(block.getLocation());
            if (targetRes != null && targetRes.getPermissions().has(Flags.build, FlagCombo.OnlyFalse)) {
                Block sourceBlock = findGrowSourceBlock(block, event.getNewState().getType());
                if (sourceBlock != null && !sourceBlock.equals(block)) {
                    ClaimedResidence sourceRes = ClaimedResidence.getByLoc(sourceBlock.getLocation());
                    if (sourceRes == null || (sourceRes != targetRes && !sourceRes.isOwner(targetRes.getOwner()))) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    private static Block findGrowSourceBlock(Block grownBlock, Material newType) {
        if (newType == null) {
            return null;
        }
        String name = newType.name();
        if (name.equals("MELON") || name.equals("PUMPKIN")) {
            String stemName = name + "_STEM";
            String attachedName = "ATTACHED_" + name + "_STEM";
            for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
                Block adj = grownBlock.getRelative(face);
                String adjName = adj.getType().name();
                if (adjName.equals(stemName) || adjName.equals(attachedName)) {
                    return adj;
                }
            }
            return null;
        }
        if (name.startsWith("WEEPING_VINES") || name.startsWith("CAVE_VINES")) {
            Block above = grownBlock.getRelative(BlockFace.UP);
            if (!above.getType().isAir()) {
                return above;
            }
        }
        Block below = grownBlock.getRelative(BlockFace.DOWN);
        if (!below.getType().isAir() && grownBlock.getType().isAir()) {
            return below;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        Block sponge = event.getBlock();
        if (plugin.isDisabledWorldListener(sponge)) {
            return;
        }
        ClaimedResidence sourceRes = ClaimedResidence.getByLoc(sponge.getLocation());
        if (sourceRes != null && Flags.build.isGlobalyEnabled()
                && sourceRes.getPermissions().has(Flags.build, FlagCombo.OnlyFalse)
                && sourceRes.getPermissions().has(Flags.destroy, FlagCombo.OnlyFalse)) {
            event.setCancelled(true);
            return;
        }
        event.getBlocks().removeIf(state -> {
            ClaimedResidence targetRes = ClaimedResidence.getByLoc(state.getLocation());
            if (targetRes == null) {
                return false;
            }
            if (sourceRes != null && (sourceRes == targetRes || sourceRes.isOwner(targetRes.getOwner()))) {
                return false;
            }
            FlagPermissions perms = targetRes.getPermissions();
            return (Flags.destroy.isGlobalyEnabled() && !perms.has(Flags.destroy, perms.has(Flags.build, true)))
                    || (Flags.build.isGlobalyEnabled() && !perms.has(Flags.build, true));
        });
        if (event.getBlocks().isEmpty()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {

        Block block = event.getBlock();

        if (plugin.isDisabledWorldListener(block)) {
            return;
        }
        Block source = event.getSource();
        CMIMaterial type = CMIMaterial.get(source.getType());

        if (Flags.grow.isGlobalyEnabled() && (type == CMIMaterial.VINE || type.name().contains("_VINES"))) {
            if (FlagPermissions.has(block.getLocation(), Flags.grow, FlagCombo.OnlyFalse)) {
                event.setCancelled(true);
                return;
            }

        } else if (Flags.skulk.isGlobalyEnabled() && type == CMIMaterial.SCULK_CATALYST) {
            if (FlagPermissions.has(block.getLocation(), Flags.skulk, FlagCombo.OnlyFalse)) {
                event.setCancelled(true);
                return;
            }

        } else if (Flags.spread.isGlobalyEnabled()) {
            if (FlagPermissions.has(block.getLocation(), Flags.spread, FlagCombo.OnlyFalse)) {
                event.setCancelled(true);
                return;
            }

        }
        // Prevent griefers from introducing vines, sculk blocks, etc. from outside into Residences
        if (Flags.build.isGlobalyEnabled() && type != CMIMaterial.GRASS_BLOCK && type != CMIMaterial.MYCELIUM) {
            ClaimedResidence sourceRes = ClaimedResidence.getByLoc(source.getLocation());
            if (shouldDenySpread(event.getNewState(), sourceRes, null)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeaveDecay(LeavesDecayEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.decay, block)) {
            return;
        }
        FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
        if (!perms.has(Flags.decay, true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTreeGrow(StructureGrowEvent event) {

        if (plugin.isDisabledWorldListener(event.getWorld())) {
            return;
        }
        if (Flags.grow.isGlobalyEnabled()) {
            if (FlagPermissions.has(event.getLocation(), Flags.grow, FlagCombo.OnlyFalse)) {
                event.setCancelled(true);
                return;
            }
        }
        // Prevent griefers from placing tree-related blocks from outside into Residences
        if (Flags.build.isGlobalyEnabled()) {
            if (handlePlantGrowCrossResidence(event.getBlocks(), event.getLocation(), event.getPlayer())) {
                event.setCancelled(true);
            }
        }
    }

    public static boolean handlePlantGrowCrossResidence(@NotNull List<BlockState> spreadList, @NotNull Location sourceLoc, @Nullable Player player) {

        if (player != null) {
            if (ResPerm.bypass_build.hasPermission(player, 10000L)) {
                return false;
            }
            // cancel the event if the player lacks build permission at the source location
            if (FlagPermissions.shouldDenyAndNotify(player, sourceLoc, Flags.build, null)) {
                return true;
            }
        }
        // player has build permission at the source location, or event is not player-triggered
        // check build permission for spread blocks
        ClaimedResidence sourceRes = ClaimedResidence.getByLoc(sourceLoc);
        spreadList.removeIf(spread -> shouldDenySpread(spread, sourceRes, player));
        return false;
    }

    private static boolean shouldDenySpread(@NotNull BlockState spread, @Nullable ClaimedResidence sourceRes, @Nullable Player player) {

        ClaimedResidence spreadRes = ClaimedResidence.getByLoc(spread.getLocation());
        // spread-block not in Res, skip check
        if (spreadRes == null) {
            return false;
        }
        // source & spread-block in same Res, or have same Res owner, skip check
        if (sourceRes != null && (sourceRes == spreadRes || sourceRes.isOwner(spreadRes.getOwner()))) {
            return false;
        }
        // source & spread-block not in same Res, not same Res owner
        if (player != null) {
            return spreadRes.getPermissions().playerHas(player, Flags.build, FlagCombo.OnlyFalse);
        } else {
            return spreadRes.getPermissions().has(Flags.build, FlagCombo.OnlyFalse);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {

        if (!Flags.destroy.isGlobalyEnabled()) {
            return;
        }
        if (!canBreakBlock(event.getPlayer(), event.getBlock(), true)) {
            event.setCancelled(true);
        }
    }

    public static boolean canBreakBlock(@NotNull Player player, @NotNull Block block, boolean inform) {
        // disabling event on world
        if (Residence.getInstance().isDisabledWorldListener(block)) {
            return true;
        }
        if (ResAdmin.isResAdmin(player)) {
            return true;
        }
        ClaimedResidence res = ClaimedResidence.getByLoc(block.getLocation());
        FlagPermissions perms;

        if (res != null) {
            if (Residence.getInstance().getConfigManager().enabledRentSystem()
                    && Residence.getInstance().getConfigManager().preventRentModify()
                    && res.isRented()) {
                if (inform) {
                    lm.Rent_ModifyDeny.sendMessage(player);
                }
                return false;
            }
            // In Residence, get Residence permission status
            perms = res.getPermissions();
        } else {
            // Not in Residence, get World permission status
            perms = Residence.getInstance().getWorldFlags().getPerms(player);
        }
        boolean canBreak = perms.playerHas(player, Flags.destroy, perms.playerHas(player, Flags.build, true));
        // Residence raid active (not vanilla raid)
        if (res != null && ConfigManager.RaidEnabled && res.getRaid().isUnderRaid()) {
            if ((ConfigManager.RaidAttackerBlockBreak && res.getRaid().isAttacker(player.getUniqueId()))
                    || (ConfigManager.RaidDefenderBlockBreak && res.getRaid().isDefender(player.getUniqueId()))) {
                canBreak = true;
            }
        }
        if (canBreak) {
            return true;
        }
        Material mat = block.getType();

        if (Residence.getInstance().getItemManager().isIgnored(player, mat, block.getWorld())) {
            return true;
        }
        if (res != null && res.getItemIgnoreList().isListed(mat)) {
            return true;
        }
        if (ResPerm.bypass_destroy.hasPermission(player, 10000L)) {
            // With destroy bypass, breaking chests still requires container permission
            if (Utils.isContainer(mat) && !perms.playerHas(player, Flags.container, true)) {
                if (inform) {
                    lm.Flag_Deny.sendMessage(player, Flags.container);
                }
                return false;
            }
            return true;
        }
        if (inform) {
            lm.Flag_Deny.sendMessage(player, Flags.destroy);
        }
        return false;
    }

    public static boolean canBreakBlock(@NotNull Player player, @NotNull Location loc, boolean inform) {
        return canBreakBlock(player, loc.getBlock(), inform);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent event) {

        Block block = event.getBlock();

        if (plugin.isDisabledWorldListener(block)) {
            return;
        }
        Entity entity = event.getEntity();

        if (Flags.place.isGlobalyEnabled() && entity instanceof Player) {
            Player player = (Player) entity;
            // Prevent Frosted Ice generation from Frost Walker without notifying players
            if (FlagPermissions.shouldDenyAndNotify(player, block, Flags.place, Flags.build, false)) {
                event.setCancelled(true);
            }

        } else if (Flags.snowtrail.isGlobalyEnabled() && entity instanceof Snowman) {
            FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
            if (!perms.has(Flags.snowtrail, true)) {
                event.setCancelled(true);
            }

        } else if (Flags.animalgriefing.isGlobalyEnabled() && Utils.isAnimal(entity)) {
            FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
            if (!perms.has(Flags.animalgriefing, perms.has(Flags.build, true))) {
                event.setCancelled(true);
            }

        } else if (Flags.mobgriefing.isGlobalyEnabled() && Utils.isMonster(entity)) {
            FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
            if (!perms.has(Flags.mobgriefing, perms.has(Flags.build, true))) {
                event.setCancelled(true);
            }

        } else if (Flags.build.isGlobalyEnabled()) {
            FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
            if (!perms.has(Flags.build, true)) {
                event.setCancelled(true);
            }

        }
    }

    private boolean isNotIceOrSnow(Material material) {
        switch (CMIMaterial.get(material)) {
        case FROSTED_ICE:
        case ICE:
        case SNOW:
            return false;
        default:
            return true;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onIceForm(BlockFormEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.iceform, block)) {
            return;
        }
        // SnowGolem already has SnowTrail Flag
        if (event instanceof EntityBlockFormEvent
                && ((EntityBlockFormEvent) event).getEntity() instanceof Snowman) {
            return;
        }
        if (isNotIceOrSnow(event.getNewState().getType())) {
            return;
        }
        FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
        if (!perms.has(Flags.iceform, true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onIceMelt(BlockFadeEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.icemelt, block)) {
            return;
        }
        if (isNotIceOrSnow(block.getType())) {
            return;
        }
        FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
        if (!perms.has(Flags.icemelt, true)) {
            event.setCancelled(true);
        }
    }

    public static final String SourceResidenceName = "SourceResidenceName";

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFallingBlockPortal(EntityPortalEvent event) {
        Location destination = event.getTo();
        if (event.getEntityType() != EntityType.FALLING_BLOCK || destination == null
                || FlagPermissions.shouldIgnoreCheck(Flags.fallinprotection, destination.getWorld()))
            return;

        ClaimedResidence target = ClaimedResidence.getByLoc(destination);
        if (target != null && target != ClaimedResidence.getByLoc(event.getFrom())
                && target.getPermissions().has(Flags.fallinprotection, FlagCombo.TrueOrNone))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockPortalExit(EntityPortalExitEvent event) {
        if (!Flags.fallinprotection.isGlobalyEnabled() || event.getEntityType() != EntityType.FALLING_BLOCK)
            return;
        Location exitLoc = event.getTo();
        if (exitLoc == null)
            return;
        ClaimedResidence exitRes = plugin.getResidenceManager().getByLoc(exitLoc);
        String resName = exitRes == null ? "NULL" : exitRes.getName();
        event.getEntity().setMetadata(SourceResidenceName, new FixedMetadataValue(plugin, resName));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockSpawn(EntitySpawnEvent event) {
        if (!Flags.fallinprotection.isGlobalyEnabled() || event.getEntityType() != EntityType.FALLING_BLOCK)
            return;
        Entity ent = event.getEntity();
        if (ent.hasMetadata(SourceResidenceName))
            return;
        ClaimedResidence res = plugin.getResidenceManager().getByLoc(event.getLocation());
        String resName = res == null ? "NULL" : res.getName();
        ent.setMetadata(SourceResidenceName, new FixedMetadataValue(plugin, resName));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // Disabling listener if flag disabled globally
        if (FlagPermissions.shouldIgnoreCheck(Flags.fallinprotection, event.getBlock()))
            return;
        if (event.getEntityType() != EntityType.FALLING_BLOCK)
            return;
        Entity ent = event.getEntity();

        if (event.getTo() == Material.AIR) {
            if (!ent.hasMetadata(SourceResidenceName)) {
                ClaimedResidence res = plugin.getResidenceManager().getByLoc(event.getBlock().getLocation());
                String resName = res == null ? "NULL" : res.getName();
                ent.setMetadata(SourceResidenceName, new FixedMetadataValue(plugin, resName));
            }
            return;
        }

        ClaimedResidence res = plugin.getResidenceManager().getByLoc(event.getBlock().getLocation());

        if (res != null && res.getPermissions().has(Flags.fallinprotection, FlagCombo.OnlyFalse))
            return;

        String resName = res == null ? "NULL" : res.getName();

        String saved = "NULL";
        if (!ent.hasMetadata(SourceResidenceName))
            return;

        @NotNull
        List<MetadataValue> meta = ent.getMetadata(SourceResidenceName);

        if (meta.isEmpty())
            return;

        saved = ent.getMetadata(SourceResidenceName).get(0).asString();

        if (res == null || saved.equalsIgnoreCase(resName))
            return;

        if (!"NULL".equalsIgnoreCase(saved)) {
            ClaimedResidence sourceRes = plugin.getResidenceManager().getByName(saved);
            if (sourceRes != null && (sourceRes == res
                    || sourceRes.isOwner(res.getOwner())
                    || sourceRes.getTopParent() == res.getTopParent())) {
                return;
            }
        }

        event.setCancelled(true);
        ent.remove();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFall(EntityChangeBlockEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.fallinprotection, block)) {
            return;
        }
        if (!plugin.getConfigManager().isBlockFall())
            return;

        if ((event.getEntityType() != EntityType.FALLING_BLOCK))
            return;

        Material typeTo = event.getTo();
        if (typeTo.hasGravity() || CMIMaterial.get(typeTo).equals(CMIMaterial.SCAFFOLDING))
            return;

        if (!plugin.getConfigManager().getBlockFallWorlds().contains(block.getLocation().getWorld().getName()))
            return;

        if (block.getY() <= plugin.getConfigManager().getBlockFallLevel())
            return;

        ClaimedResidence res = plugin.getResidenceManager().getByLoc(block.getLocation());

        if (res != null)
            event.getEntity().setMetadata(SourceResidenceName, new FixedMetadataValue(plugin, res.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestPlace(BlockPlaceEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getBlock()))
            return;
        if (!plugin.getConfigManager().ShowNoobMessage())
            return;

        Player player = event.getPlayer();
        if (ResAdmin.isResAdmin(player))
            return;
        Block block = event.getBlock();
        if (!CMIMaterial.get(block.getType()).containsCriteria(CMIMC.CHEST))
            return;

        if (plugin.getPlayerManager().getResidenceCount(player.getUniqueId()) != 0)
            return;

        if (MessageInformed.contains(player.getUniqueId()))
            return;

        if (!ResPerm.newguyresidence.hasPermission(player))
            return;

        lm.General_NewPlayerInfo.sendMessage(player);

        MessageInformed.add(player.getUniqueId());
    }

    private boolean checkBlock(ClaimedResidence orRes, Location loc, Vector offset, Material type, boolean isShelf, Player player) {
        Block b = loc.clone().add(offset).getBlock();
        if (isShelf) {
            if (!CMIMaterial.get(b.getType()).containsCriteria(CMIMC.SHELF))
                return false;
        } else if (b.getType() != type) {
            return false;
        }
        ClaimedResidence res = plugin.getResidenceManager().getByLoc(b.getLocation());
        if (Objects.equals(res, orRes))
            return false;
        boolean deniedTarget = res != null && !res.isOwner(player) && !res.isTrusted(player);
        boolean deniedOrigin = orRes != null && !orRes.isOwner(player) && !orRes.isTrusted(player);
        return deniedTarget || deniedOrigin;
    }

    private static final List<Vector> chestVectors = new ArrayList<Vector>(Arrays.asList(
            new Vector(0, 0, -1),
            new Vector(0, 0, 1),
            new Vector(1, 0, 0),
            new Vector(-1, 0, 0)));

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestPlaceNearResidence(BlockPlaceEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getBlock()))
            return;

        Player player = event.getPlayer();
        if (ResAdmin.isResAdmin(player))
            return;

        Block block = event.getBlock();

        Material type = block.getType();
        CMIMaterial cmiType = CMIMaterial.get(type);
        boolean isChest = cmiType.containsCriteria(CMIMC.CHEST);
        boolean isShelf = cmiType.containsCriteria(CMIMC.SHELF);

        if (!isChest && !isShelf)
            return;

        ClaimedResidence orRes = plugin.getResidenceManager().getByLoc(block.getLocation());

        for (Vector vector : chestVectors) {
            if (!checkBlock(orRes, block.getLocation(), vector, type, isShelf, player))
                continue;

            CMIActionBar.send(player, lm.General_CantPlaceChest.getMessage());
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestPlaceCreateRes(BlockPlaceEvent event) {
        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getBlock()))
            return;
        if (!plugin.getConfigManager().isNewPlayerUse())
            return;

        Player player = event.getPlayer();

        if (ResAdmin.isResAdmin(player))
            return;

        Block block = event.getBlock();

        if (!CMIMaterial.get(block.getType()).containsCriteria(CMIMC.CHEST))
            return;

        ResidencePlayer rp = ResidencePlayer.get(player);

        if (rp.getResAmount() > 0 || rp.getData().ownedResidence())
            return;

        Location loc = block.getLocation();

        plugin.getSelectionManager().placeLoc1(player, new Location(loc.getWorld(), loc.getBlockX() - 1, loc.getBlockY() - 1, loc.getBlockZ() - 1), true);
        plugin.getSelectionManager().placeLoc2(player, new Location(loc.getWorld(), loc.getBlockX() + 1, loc.getBlockY() + 1, loc.getBlockZ() + 1), true);

        CMIVectorInt3D max = new CMIVectorInt3D(plugin.getConfigManager().getNewPlayerRangeX() * 2,
                plugin.getConfigManager().getNewPlayerRangeY() * 2,
                plugin.getConfigManager().getNewPlayerRangeZ() * 2);

        boolean checkBalance = !plugin.getConfigManager().isNewPlayerFree()
                && plugin.getConfigManager().isChargeOnCreation()
                && plugin.getConfigManager().enableEconomy();
        auto.optimizedResize(player, plugin.getSelectionManager().getSelectionCuboid(player), checkBalance, max);

        boolean created = plugin.getResidenceManager().addResidence(player, player.getName(), false,
                !plugin.getConfigManager().isNewPlayerFree());
        if (created) {
            rp.getData().ownedResidence(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        if (!Flags.place.isGlobalyEnabled()) {
            return;
        }
        CMIMaterial mat = CMIMaterial.get(event.getBlockPlaced().getType());
        // Avoid upstream dupe bug(https://github.com/PaperMC/Paper/issues/13586)
        // End Portal Frame & Bedrock podium are pre-validated in PlayerInteractEvent
        if (mat == CMIMaterial.BEDROCK || mat == CMIMaterial.END_PORTAL_FRAME) {
            return;
        }
        Player player = event.getPlayer();
        for (BlockState one : event.getReplacedBlockStates()) {
            if (!canPlaceBlock(player, one.getBlock(), true)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // BlockMultiPlaceEvent uses separate check logic
        if (event instanceof BlockMultiPlaceEvent) {
            return;
        }
        if (!Flags.place.isGlobalyEnabled()) {
            return;
        }
        Block block = event.getBlock();
        Player player = event.getPlayer();

        if (canPlaceBlock(player, block, true)
                && !shouldDenyGolemOrWitherBuild(block, block.getType(), player, null)) {
            return;
        }
        event.setCancelled(true);
        // Powder snow placement issue: https://github.com/Zrips/Residence/issues/784
        // Paper 1.18.2+ already fixed this: https://github.com/PaperMC/Paper/pull/6751
        if (Version.isCurrentEqualOrHigher(Version.v1_17_0)
                && !(Version.isPaperBranch() && Version.isCurrentEqualOrHigher(Version.v1_18_2))) {
            ResidenceListener1_17.handlePowderSnow(block);
        }
    }

    private static final BlockFace[] SIX_FACES = new BlockFace[] {
            BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private static BlockFace[] getPerpendicularAxes(BlockFace spineDir) {
        if (spineDir == BlockFace.UP || spineDir == BlockFace.DOWN) {
            return new BlockFace[] { BlockFace.EAST, BlockFace.SOUTH };
        }
        if (spineDir == BlockFace.NORTH || spineDir == BlockFace.SOUTH) {
            return new BlockFace[] { BlockFace.EAST, BlockFace.UP };
        }
        return new BlockFace[] { BlockFace.SOUTH, BlockFace.UP };
    }

    private static boolean isPumpkinHead(Material mat) {
        if (mat == null) {
            return false;
        }
        CMIMaterial cmi = CMIMaterial.get(mat);
        return cmi == CMIMaterial.CARVED_PUMPKIN || cmi == CMIMaterial.JACK_O_LANTERN || cmi == CMIMaterial.PUMPKIN;
    }

    private static boolean isWitherSkull(Material mat) {
        if (mat == null) {
            return false;
        }
        CMIMaterial cmi = CMIMaterial.get(mat);
        return cmi == CMIMaterial.WITHER_SKELETON_SKULL || cmi == CMIMaterial.WITHER_SKELETON_WALL_SKULL;
    }

    private static boolean isSkullAt(Block candidate, Block placedHead) {
        return candidate.equals(placedHead) || isWitherSkull(candidate.getType());
    }

    private static boolean isSoulBlock(Material mat) {
        if (mat == null) {
            return false;
        }
        CMIMaterial cmi = CMIMaterial.get(mat);
        return cmi == CMIMaterial.SOUL_SAND || cmi == CMIMaterial.SOUL_SOIL;
    }

    private static boolean isCopperGolemBaseBlock(Material mat) {
        if (mat == null || !Version.isCurrentEqualOrHigher(Version.v1_21_9)) {
            return false;
        }
        if (ResidenceListener1_14.isBlockTag(mat, "copper")) {
            return true;
        }
        String name = mat.name();
        return name.equals("COPPER_BLOCK")
                || name.endsWith("_COPPER_BLOCK")
                || name.endsWith("_COPPER")
                || name.equals("CUT_COPPER");
    }

    public static boolean shouldDenyGolemOrWitherBuild(Block headBlock, Material placedType, @Nullable Player player, @Nullable ClaimedResidence dispenserSourceRes) {
        if (headBlock == null || placedType == null) {
            return false;
        }
        if (player != null && ResAdmin.isResAdmin(player)) {
            return false;
        }
        if (isPumpkinHead(placedType)) {
            for (BlockFace spineDir : SIX_FACES) {
                Block b1 = headBlock.getRelative(spineDir, 1);
                if (isCopperGolemBaseBlock(b1.getType())) {
                    if (shouldDenyStructurePart(b1, player, dispenserSourceRes, true)
                            || shouldDenyStructurePart(headBlock, player, dispenserSourceRes, true)) {
                        return true;
                    }
                }
                Block b2 = headBlock.getRelative(spineDir, 2);
                if (b1.getType() == Material.SNOW_BLOCK && b2.getType() == Material.SNOW_BLOCK) {
                    if (shouldDenyStructurePart(b1, player, dispenserSourceRes, true)
                            || shouldDenyStructurePart(b2, player, dispenserSourceRes, true)
                            || shouldDenyStructurePart(headBlock, player, dispenserSourceRes, true)) {
                        return true;
                    }
                }
                if (b1.getType() == Material.IRON_BLOCK && b2.getType() == Material.IRON_BLOCK) {
                    for (BlockFace armDir : getPerpendicularAxes(spineDir)) {
                        Block arm1 = b1.getRelative(armDir, 1);
                        Block arm2 = b1.getRelative(armDir.getOppositeFace(), 1);
                        if (arm1.getType() == Material.IRON_BLOCK && arm2.getType() == Material.IRON_BLOCK) {
                            if (shouldDenyStructurePart(b1, player, dispenserSourceRes, true)
                                    || shouldDenyStructurePart(b2, player, dispenserSourceRes, true)
                                    || shouldDenyStructurePart(arm1, player, dispenserSourceRes, true)
                                    || shouldDenyStructurePart(arm2, player, dispenserSourceRes, true)
                                    || shouldDenyStructurePart(headBlock, player, dispenserSourceRes, true)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
        if (isWitherSkull(placedType)) {
            for (BlockFace spineDir : SIX_FACES) {
                for (BlockFace armDir : getPerpendicularAxes(spineDir)) {
                    for (int offset = -1; offset <= 1; offset++) {
                        Block topCenter = offset == 0 ? headBlock : headBlock.getRelative(armDir, offset);
                        Block s1 = topCenter;
                        Block s2 = topCenter.getRelative(armDir, 1);
                        Block s3 = topCenter.getRelative(armDir.getOppositeFace(), 1);
                        if (!isSkullAt(s1, headBlock) || !isSkullAt(s2, headBlock) || !isSkullAt(s3, headBlock)) {
                            continue;
                        }
                        Block b1 = topCenter.getRelative(spineDir, 1);
                        Block b2 = topCenter.getRelative(spineDir, 2);
                        Block arm1 = b1.getRelative(armDir, 1);
                        Block arm2 = b1.getRelative(armDir.getOppositeFace(), 1);
                        if (!isSoulBlock(b1.getType()) || !isSoulBlock(b2.getType())
                                || !isSoulBlock(arm1.getType()) || !isSoulBlock(arm2.getType())) {
                            continue;
                        }
                        for (Block part : new Block[] { s1, s2, s3, b1, b2, arm1, arm2 }) {
                            if (shouldDenyStructurePart(part, player, dispenserSourceRes, false)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean shouldDenyStructurePart(Block part, @Nullable Player player, @Nullable ClaimedResidence dispenserSourceRes, boolean isAnimalGolem) {
        if (player != null) {
            if (!canBreakBlock(player, part, true) || !canPlaceBlock(player, part, true)) {
                return true;
            }
        } else {
            ClaimedResidence partRes = ClaimedResidence.getByLoc(part.getLocation());
            if (partRes != null && (dispenserSourceRes == null || (dispenserSourceRes != partRes && !dispenserSourceRes.isOwner(partRes.getOwner())))) {
                FlagPermissions perms = partRes.getPermissions();
                if ((Flags.build.isGlobalyEnabled() && !perms.has(Flags.build, true))
                        || (Flags.destroy.isGlobalyEnabled() && !perms.has(Flags.destroy, perms.has(Flags.build, true)))) {
                    return true;
                }
            }
        }
        FlagPermissions partPerms = FlagPermissions.getPerms(part.getLocation());
        if (isAnimalGolem) {
            if ((Flags.animals.isGlobalyEnabled() && !partPerms.has(Flags.animals, true))
                    || (Flags.canimals.isGlobalyEnabled() && partPerms.has(Flags.canimals, FlagCombo.OnlyFalse))) {
                if (player != null) {
                    lm.Flag_Deny.sendMessage(player, Flags.canimals);
                }
                return true;
            }
        } else {
            if ((Flags.witherspawn.isGlobalyEnabled() && partPerms.has(Flags.witherspawn, FlagCombo.OnlyFalse))
                    || (Flags.monsters.isGlobalyEnabled() && partPerms.has(Flags.monsters, FlagCombo.OnlyFalse))
                    || (Flags.cmonsters.isGlobalyEnabled() && partPerms.has(Flags.cmonsters, FlagCombo.OnlyFalse))) {
                if (player != null) {
                    lm.Flag_Deny.sendMessage(player, Flags.witherspawn);
                }
                return true;
            }
        }
        return false;
    }

    public static boolean canPlaceBlock(@NotNull Player player, @NotNull Block block, boolean informPlayer) {
        // disabling event on world
        if (Residence.getInstance().isDisabledWorldListener(block)) {
            return true;
        }
        if (ResAdmin.isResAdmin(player)) {
            return true;
        }
        ClaimedResidence res = ClaimedResidence.getByLoc(block.getLocation());
        Material mat = block.getType();
        FlagPermissions perms;

        if (res != null) {
            if (Residence.getInstance().getConfigManager().enabledRentSystem()
                    && Residence.getInstance().getConfigManager().preventRentModify()
                    && Residence.getInstance().getRentManager().isRented(res)) {
                if (informPlayer) {
                    lm.Rent_ModifyDeny.sendMessage(player);
                }
                return false;
            }
            // Residence item blacklist
            if (!CMIMaterial.get(mat).isNone() && !res.getItemBlacklist().isAllowed(mat)) {
                if (informPlayer) {
                    lm.General_ItemBlacklisted.sendMessage(player);
                }
                return false;
            }
            // In Residence, get Residence permission status
            perms = res.getPermissions();
        } else {
            // Not in Residence, get World permission status
            perms = Residence.getInstance().getWorldFlags().getPerms(player);
        }
        boolean canPlace = perms.playerHas(player, Flags.place, perms.playerHas(player, Flags.build, true));
        // Residence raid active (not vanilla raid)
        if (res != null && ConfigManager.RaidEnabled && res.getRaid().isUnderRaid()) {
            if ((ConfigManager.RaidAttackerBlockPlace && res.getRaid().isAttacker(player.getUniqueId()))
                    || (ConfigManager.RaidDefenderBlockPlace && res.getRaid().isDefender(player.getUniqueId()))) {
                canPlace = true;
            }
        }
        if (canPlace) {
            return true;
        }
        if (Residence.getInstance().getItemManager().isIgnored(player, mat, block.getWorld())) {
            return true;
        }
        if (ResPerm.bypass_build.hasPermission(player, 10000L)) {
            return true;
        }
        if (informPlayer) {
            lm.Flag_Deny.sendMessage(player, Flags.place);
        }
        return false;
    }

    private static boolean isPistonBlockedByLocation(ClaimedResidence pistonRes, Location loc) {
        ClaimedResidence targetRes = ClaimedResidence.getByLoc(loc);
        if (targetRes == null) {
            return false;
        }
        if (pistonRes != null && (pistonRes == targetRes || pistonRes.isOwner(targetRes.getOwner()))) {
            return false;
        }
        return targetRes.getPermissions().has(Flags.pistonprotection, FlagCombo.OnlyTrue);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {

        Block piston = event.getBlock();

        if (plugin.isDisabledWorldListener(piston)) {
            return;
        }
        ClaimedResidence pistonRes = ClaimedResidence.getByLoc(piston.getLocation());

        if (Flags.piston.isGlobalyEnabled()) {
            FlagPermissions perms = (pistonRes != null)
                    ? pistonRes.getPermissions()
                    : Residence.getInstance().getWorldFlags().getPerms(piston.getWorld());

            if (!perms.has(Flags.piston, true)) {
                event.setCancelled(true);
                return;
            }
        }

        // Disabling cross-border check if pistonprotection flag disabled globally
        if (!Flags.pistonprotection.isGlobalyEnabled())
            return;

        if (!event.isSticky())
            return;

        List<Block> blocks = Utils.getPistonRetractBlocks(event);
        BlockFace direction = event.getDirection();

        for (Block block : blocks) {
            if (isPistonBlockedByLocation(pistonRes, block.getLocation())
                    || isPistonBlockedByLocation(pistonRes, block.getRelative(direction).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {

        Block piston = event.getBlock();

        if (plugin.isDisabledWorldListener(piston)) {
            return;
        }
        ClaimedResidence pistonRes = ClaimedResidence.getByLoc(piston.getLocation());

        if (Flags.piston.isGlobalyEnabled()) {
            FlagPermissions perms = (pistonRes != null)
                    ? pistonRes.getPermissions()
                    : Residence.getInstance().getWorldFlags().getPerms(piston.getWorld());

            if (!perms.has(Flags.piston, true)) {
                event.setCancelled(true);
                return;
            }
        }

        // Disabling cross-border check if pistonprotection flag disabled globally
        if (!Flags.pistonprotection.isGlobalyEnabled())
            return;

        BlockFace direction = event.getDirection();
        if (isPistonBlockedByLocation(pistonRes, piston.getRelative(direction).getLocation())) {
            event.setCancelled(true);
            return;
        }

        List<Block> blocks = event.getBlocks();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (isPistonBlockedByLocation(pistonRes, block.getLocation())
                    || isPistonBlockedByLocation(pistonRes, block.getRelative(direction).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {

        Block block = event.getBlock();
        // disabling event on world
        if (plugin.isDisabledWorldListener(block)) {
            return;
        }
        Block toBlock = event.getToBlock();

        ClaimedResidence targetRes = ClaimedResidence.getByLoc(toBlock.getLocation());

        FlagPermissions perms = (targetRes != null)
                ? targetRes.getPermissions()
                : Residence.getInstance().getWorldFlags().getPerms(toBlock.getWorld());

        if (targetRes != null && perms.has(Flags.flowinprotection, FlagCombo.TrueOrNone)) {

            ClaimedResidence sourceRes = ClaimedResidence.getByLoc(block.getLocation());

            if (sourceRes == null || (sourceRes != targetRes && !sourceRes.isOwner(targetRes.getOwner()))) {
                event.setCancelled(true);
                return;
            }
        }

        if (perms.has(Flags.flow, FlagCombo.OnlyFalse)) {
            event.setCancelled(true);
            return;
        }

        Material mat = block.getType();
        Flags flag;

        if (mat == Material.LAVA) {
            flag = Flags.lavaflow;
        } else if (mat == Material.WATER) {
            flag = Flags.waterflow;
        } else {
            return;
        }
        if (!perms.has(flag, perms.has(Flags.flow, true))) {
            event.setCancelled(true);
            return;
        }
        if (targetRes == null) {
            ConfigManager cfg = plugin.getConfigManager();

            boolean shouldDenyFlow = (cfg.isNoLava() && mat == Material.LAVA) || (cfg.isNoWater() && mat == Material.WATER);

            if (!shouldDenyFlow) {
                return;
            }
            if (toBlock.getY() < cfg.getFlowLevel()) {
                return;
            }
            if (!cfg.getNoFlowWorlds().contains(toBlock.getWorld().getName())) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLandDryFade(BlockFadeEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.dryup, block)) {
            return;
        }
        // Moved to separate class
        if (Version.isCurrentEqualOrHigher(Version.v1_13_0)) {
            if (ResidenceListener1_13.shouldCancelFarmLandChange(block)) {
                event.setCancelled(true);
            }
            return;
        }
        CMIMaterial mat = CMIMaterial.get(block.getType());
        if (!mat.equals(CMIMaterial.FARMLAND))
            return;

        FlagPermissions perms = FlagPermissions.getPerms(event.getNewState().getLocation());
        if (!perms.has(Flags.dryup, true)) {
            try {
                byte value = (byte) block.getClass().getMethod("getData").invoke(block);
                if (value < (byte) 2)
                    block.getClass().getMethod("setData", byte.class).invoke(block, (byte) 7);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e1) {
                e1.printStackTrace();
            }
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLandDryPhysics(BlockPhysicsEvent event) {

        Block block = event.getBlock();

        if (FlagPermissions.shouldIgnoreCheck(Flags.dryup, block)) {
            return;
        }
        // Moved to separate class
        if (Version.isCurrentEqualOrHigher(Version.v1_13_0)) {
            if (ResidenceListener1_13.shouldCancelFarmLandChange(block)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!block.getWorld().isChunkLoaded((int) Math.floor(block.getLocation().getX()) >> 4, ((int) Math.floor(block.getLocation().getZ()) >> 4))) {
            return;
        }
        CMIMaterial mat = CMIMaterial.get(block.getType());
        if (!mat.equals(CMIMaterial.FARMLAND))
            return;

        FlagPermissions perms = FlagPermissions.getPerms(block.getLocation());
        if (perms.has(Flags.dryup, FlagCombo.OnlyFalse)) {
            try {
                byte value = (byte) block.getClass().getMethod("getData").invoke(block);
                if (value < (byte) 2)
                    block.getClass().getMethod("setData", byte.class).invoke(block, (byte) 7);
            } catch (Throwable e1) {
                e1.printStackTrace();
            }
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreDispense(BlockPreDispenseEvent event) {
        if (shouldCancelDispenseOrCraft(event.getBlock(), event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (shouldCancelDispenseOrCraft(event.getBlock(), event.getResult())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (shouldCancelDispenseOrCraft(event.getBlock(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    private boolean shouldCancelDispenseOrCraft(Block block, ItemStack itemStack) {
        if (block == null || plugin.isDisabledWorldListener(block)) {
            return false;
        }
        BlockData blockData = block.getBlockData();
        if (!(blockData instanceof Directional)) {
            return false;
        }
        BlockFace blockFace = ((Directional) blockData).getFacing();
        Block target = block.getRelative(blockFace);
        Block target2 = block.getRelative(blockFace, 2);

        ClaimedResidence sourceRes = ClaimedResidence.getByLoc(block.getLocation());
        ClaimedResidence targetRes = ClaimedResidence.getByLoc(target.getLocation());
        ClaimedResidence target2Res = ClaimedResidence.getByLoc(target2.getLocation());

        CMIMaterial mat = CMIMaterial.get(itemStack);

        if (targetRes == null) {
            ConfigManager cfg = plugin.getConfigManager();
            boolean shouldDenyWorldPlace = (cfg.isNoLavaPlace() && mat == CMIMaterial.LAVA_BUCKET)
                    || (cfg.isNoWaterPlace() && (mat == CMIMaterial.WATER_BUCKET || mat.containsCriteria(CMIMC.BUCKETANIMAL)));
            if (shouldDenyWorldPlace && target.getY() >= cfg.getPlaceLevel()
                    && cfg.getNoPlaceWorlds().contains(target.getWorld().getName())) {
                return true;
            }
        }

        if (isCrossResidenceDispenseDenied(sourceRes, targetRes, target, mat)) {
            return true;
        }
        if (isCrossResidenceDispenseDenied(sourceRes, target2Res, target2, mat)) {
            return true;
        }
        if (itemStack != null && (isPumpkinHead(itemStack.getType()) || isWitherSkull(itemStack.getType()))) {
            if (shouldDenyGolemOrWitherBuild(target, itemStack.getType(), null, sourceRes)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCrossResidenceDispenseDenied(ClaimedResidence sourceRes, ClaimedResidence targetRes, Block targetBlock, CMIMaterial dispensedMat) {
        if (targetRes == null) {
            return false;
        }
        if (sourceRes != null && (sourceRes == targetRes || sourceRes.isOwner(targetRes.getOwner()))) {
            return false;
        }
        FlagPermissions perms = targetRes.getPermissions();
        if (Flags.build.isGlobalyEnabled() && !perms.has(Flags.build, true)) {
            return true;
        }
        if (Flags.container.isGlobalyEnabled() && Utils.isContainer(targetBlock.getType()) && !perms.has(Flags.container, true)) {
            return true;
        }
        if (dispensedMat != null) {
            if ((dispensedMat.containsCriteria(CMIMC.BOAT) || dispensedMat.containsCriteria(CMIMC.MINECART))
                    && Flags.vehicleplacing.isGlobalyEnabled()
                    && !perms.has(Flags.vehicleplacing, perms.has(Flags.build, true))) {
                return true;
            }
            if ((dispensedMat == CMIMaterial.FLINT_AND_STEEL || dispensedMat == CMIMaterial.FIRE_CHARGE)
                    && Flags.ignite.isGlobalyEnabled()
                    && !perms.has(Flags.ignite, perms.has(Flags.build, true))) {
                return true;
            }
            if (dispensedMat == CMIMaterial.TNT
                    && Flags.tnt.isGlobalyEnabled()
                    && !perms.has(Flags.tnt, perms.has(Flags.explode, true))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {

        if (FlagPermissions.shouldIgnoreCheck(Flags.firespread, event.getBlock())) {
            return;
        }
        FlagPermissions perms = FlagPermissions.getPerms(event.getBlock().getLocation());
        if (!perms.has(Flags.firespread, true))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onNetherPortalCreate(PortalCreateEvent event) {

        World world = event.getWorld();

        if (FlagPermissions.shouldIgnoreCheck(Flags.build, world)) {
            return;
        }

        Entity entity = event.getEntity();
        Player player = entity instanceof Player ? (Player) entity : null;
        if (player != null && (player.hasMetadata("NPC") || ResAdmin.isResAdmin(player))) {
            return;
        }

        boolean isEndPlatform = event.getReason() == PortalCreateEvent.CreateReason.END_PLATFORM;
        FlagPermissions worldPerms = null;
        for (BlockState block : event.getBlocks()) {
            Location loc = block.getLocation();
            ClaimedResidence res = ClaimedResidence.getByLoc(loc);
            if (res == null && worldPerms == null) {
                worldPerms = (player == null || isEndPlatform)
                        ? plugin.getWorldFlags().getPerms(world)
                        : plugin.getWorldFlags().getPerms(world.getName(), ResidencePlayer.get(player).getGroup(world.getName(), true).getGroupName());
            }
            FlagPermissions perms = res == null ? worldPerms : res.getPermissions();
            boolean canBuild;
            if (player == null || isEndPlatform) {
                canBuild = perms.has(Flags.build, true);
            } else {
                canBuild = perms.playerHas(player, Flags.place, perms.playerHas(player, Flags.build, true));
            }
            if (!canBuild) {
                if (player != null && !isEndPlatform)
                    lm.Invalid_PortalDestination.sendMessage(player);
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {

        Block block = event.getBlock();
        // disabling event on world
        if (plugin.isDisabledWorldListener(block)) {
            return;
        }
        if (Flags.firespread.isGlobalyEnabled() && event.getCause() == IgniteCause.SPREAD) {
            if (FlagPermissions.has(block.getLocation(), Flags.firespread, FlagCombo.OnlyFalse)) {
                event.setCancelled(true);
            }

        } else if (Flags.ignite.isGlobalyEnabled()) {
            Player player = event.getPlayer();
            if (player != null) {
                if (FlagPermissions.shouldDenyAndNotify(player, block, Flags.ignite, null)) {
                    event.setCancelled(true);
                }
            } else {
                if (FlagPermissions.has(block.getLocation(), Flags.ignite, FlagCombo.OnlyFalse)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractTNT(PlayerInteractEvent event) {

        Block block = event.getClickedBlock();
        if (block == null)
            return;

        if (FlagPermissions.shouldIgnoreCheck(Flags.ignite, block)) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        if (!CMIMaterial.get(block.getType()).equals(CMIMaterial.TNT))
            return;

        CMIMaterial held = CMIMaterial.get(event.getItem());
        if (held != CMIMaterial.FLINT_AND_STEEL && held != CMIMaterial.FIRE_CHARGE) {
            return;
        }
        Player player = event.getPlayer();

        if (FlagPermissions.shouldDenyAndNotify(player, block, Flags.ignite, null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMoveItem(InventoryMoveItemEvent event) {
        List<Location> sourceLocs = getInventoryLocations(event.getSource());
        List<Location> destLocs = getInventoryLocations(event.getDestination());

        // Prevent trolls from pushing derailed hopper minecarts into the Residence to steal items from containers
        if (Flags.minecartsuction.isGlobalyEnabled()) {
            InventoryHolder holder = Utils.getHolderNoSnapshot(event.getInitiator());
            if (!(holder instanceof HopperMinecart)) {
                InventoryHolder destHolder = Utils.getHolderNoSnapshot(event.getDestination());
                if (destHolder instanceof HopperMinecart) {
                    holder = destHolder;
                }
            }
            if (holder instanceof HopperMinecart) {
                Location cartLoc = ((HopperMinecart) holder).getLocation();
                if (!CMIMaterial.get(cartLoc.getBlock().getType()).containsCriteria(CMIMC.RAIL)) {
                    if (isMinecartSuctionDenied(cartLoc)) {
                        event.setCancelled(true);
                        return;
                    }
                    for (Location sLoc : sourceLocs) {
                        if (isMinecartSuctionDenied(sLoc)) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                    for (Location dLoc : destLocs) {
                        if (isMinecartSuctionDenied(dLoc)) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            }
        }
        // Protect containers at the edge of the Residence area from theft
        if (!Flags.container.isGlobalyEnabled() || !plugin.getConfigManager().getHopperCrossResidenceCheck()) {
            return;
        }
        for (Location sourceLoc : sourceLocs) {
            for (Location destLoc : destLocs) {
                if (!isHopperTransferAllowed(sourceLoc, destLoc)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        if (item == null || plugin.isDisabledWorldListener(item)) {
            return;
        }
        Location itemLoc = item.getLocation();
        if (Flags.itempickup.isGlobalyEnabled() && FlagPermissions.has(itemLoc, Flags.itempickup, FlagCombo.OnlyFalse)) {
            event.setCancelled(true);
            return;
        }

        Inventory inv = event.getInventory();
        InventoryHolder holder = Utils.getHolderNoSnapshot(inv);
        if (Flags.minecartsuction.isGlobalyEnabled() && holder instanceof HopperMinecart) {
            Location cartLoc = ((HopperMinecart) holder).getLocation();
            if (!CMIMaterial.get(cartLoc.getBlock().getType()).containsCriteria(CMIMC.RAIL)
                    && (isMinecartSuctionDenied(cartLoc) || isMinecartSuctionDenied(itemLoc))) {
                event.setCancelled(true);
                return;
            }
        }

        if (!Flags.container.isGlobalyEnabled() || !plugin.getConfigManager().getHopperCrossResidenceCheck()) {
            return;
        }
        for (Location invLoc : getInventoryLocations(inv)) {
            if (!isHopperTransferAllowed(itemLoc, invLoc)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static boolean isMinecartSuctionDenied(Location loc) {
        if (loc == null) {
            return false;
        }
        FlagPermissions perms = FlagPermissions.getPerms(loc);
        return !perms.has(Flags.minecartsuction, perms.has(Flags.container, true));
    }

    private static boolean isHopperTransferAllowed(Location sourceLoc, Location destLoc) {
        ClaimedResidence sourceRes = ClaimedResidence.getByLoc(sourceLoc);
        ClaimedResidence destRes = ClaimedResidence.getByLoc(destLoc);
        // Source and Dest not in Res
        if (sourceRes == null && destRes == null) {
            return true;
        }
        // Source and Dest in Res
        if (sourceRes != null && destRes != null) {
            // in Same Res, or have Same Res owner
            if (sourceRes == destRes || sourceRes.isOwner(destRes.getOwner())) {
                return true;
            }
            // Not in Same Res and not Same Res owner; hopper can be Source or Dest
            return sourceRes.getPermissions().has(Flags.container, true)
                    && destRes.getPermissions().has(Flags.container, true);
        }
        if (sourceRes != null) {
            return sourceRes.getPermissions().has(Flags.container, true);
        }
        return destRes.getPermissions().has(Flags.container, true);
    }

    private static List<Location> getInventoryLocations(Inventory inventory) {
        if (inventory == null) {
            return Collections.emptyList();
        }
        if (inventory instanceof DoubleChestInventory) {
            DoubleChestInventory doubleInv = (DoubleChestInventory) inventory;
            List<Location> locs = new ArrayList<>(2);
            Location leftLoc = doubleInv.getLeftSide() != null ? doubleInv.getLeftSide().getLocation() : null;
            Location rightLoc = doubleInv.getRightSide() != null ? doubleInv.getRightSide().getLocation() : null;
            if (leftLoc != null) {
                locs.add(leftLoc);
            }
            if (rightLoc != null) {
                locs.add(rightLoc);
            }
            if (!locs.isEmpty()) {
                return locs;
            }
        }
        Location loc = inventory.getLocation();
        if (loc != null) {
            return Collections.singletonList(loc);
        }
        InventoryHolder holder = Utils.getHolderNoSnapshot(inventory);
        if (holder instanceof BlockState) {
            return Collections.singletonList(((BlockState) holder).getLocation());
        }
        if (holder instanceof Entity) {
            return Collections.singletonList(((Entity) holder).getLocation());
        }
        return Collections.emptyList();
    }
}
