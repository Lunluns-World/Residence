package com.bekvon.bukkit.residence.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.commands.cleanup;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.crossserver.CrossServerManager;
import com.bekvon.bukkit.residence.crossserver.RemoteResidenceEntry;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager.ReclaimEntry;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Container.CMIText;
import net.Zrips.CMILib.Container.PageInfo;
import net.Zrips.CMILib.Enchants.CMIEnchantEnum;
import net.Zrips.CMILib.GUI.CMIGui;
import net.Zrips.CMILib.GUI.CMIGuiButton;
import net.Zrips.CMILib.GUI.GUIManager.GUIClickType;
import net.Zrips.CMILib.GUI.GUIManager.GUIRows;
import net.Zrips.CMILib.Items.CMIMaterial;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ResidenceListGui implements Listener {

    public static final int MAX_ALIAS_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 256;

    public enum FilterMode {
        ALL,
        MINE,
        PINNED;

        public FilterMode next() {
            FilterMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    public enum SortMode {
        DEFAULT,
        LIKES,
        NAME,
        NEWEST;

        public SortMode next() {
            SortMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    private enum EditField {
        ALIAS,
        DESCRIPTION,
        ADD_MEMBER,
        ORIGINAL_OWNER
    }

    private static final class PendingEdit {
        final String serverName;
        final String resName;
        final EditField field;
        final boolean resadmin;
        final Runnable reopenCallback;
        final long expiresAt;

        PendingEdit(String serverName, String resName, EditField field, boolean resadmin) {
            this(serverName, resName, field, resadmin, null);
        }

        PendingEdit(String serverName, String resName, EditField field, boolean resadmin, Runnable reopenCallback) {
            this.serverName = serverName;
            this.resName = resName;
            this.field = field;
            this.resadmin = resadmin;
            this.reopenCallback = reopenCallback;
            this.expiresAt = System.currentTimeMillis() + 60_000L;
        }
    }

    private final Residence plugin;
    private final Map<UUID, PendingEdit> pendingEdits = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> batchSelections = new ConcurrentHashMap<>();

    public ResidenceListGui(Residence plugin) {
        this.plugin = plugin;
    }

    public boolean canUseAdminBatch(Player player, boolean resadmin) {
        if (player == null)
            return false;
        return resadmin || ResAdmin.isResAdmin(player) || plugin.getPermissionManager().isResidenceAdmin(player);
    }

    private Set<String> getOrCreateBatchSelection(UUID adminUuid) {
        return batchSelections.computeIfAbsent(adminUuid, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
    }

    public void openBrowser(Player player, FilterMode filter, SortMode sort, String serverFilter, boolean resadmin, int page) {
        openBrowserInternal(player, filter, sort, serverFilter, null, null, false, false, resadmin, page);
    }

    public void openBrowserForOwner(Player player, UUID ownerUuid, String ownerName, boolean resadmin, int page) {
        openBrowserInternal(player, FilterMode.ALL, SortMode.DEFAULT, null, ownerUuid, ownerName, false, false, resadmin, page);
    }

    public void openBrowserForArchivedHolder(Player player, String holderName, boolean resadmin, int page) {
        openBrowserInternal(player, FilterMode.ALL, SortMode.DEFAULT, null, null, holderName, true, false, resadmin, page);
    }

    public void openBatchBrowser(Player player, FilterMode filter, SortMode sort, String serverFilter, boolean resadmin, int page) {
        if (!canUseAdminBatch(player, resadmin)) {
            lm.General_AdminOnly.sendMessage(player);
            return;
        }
        openBrowserInternal(player, filter, sort, serverFilter, null, null, false, true, true, page);
    }

    public void openBrowser(Player player, FilterMode filter, SortMode sort, String serverFilter,
            UUID ownerFilter, String ownerFilterName, boolean resadmin, int page) {
        openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName, false, false, resadmin, page);
    }

    private void openBrowserInternal(Player player, FilterMode filter, SortMode sort, String serverFilter,
            UUID ownerFilter, String ownerFilterName, boolean archivedHolderOnly, boolean batchMode, boolean resadmin, int page) {
        if (player == null || !player.isOnline())
            return;

        if (!plugin.getConfigManager().isResidenceGuiEnabled()) {
            lm.Gui_Disabled.sendMessage(player);
            return;
        }

        boolean adminCapable = canUseAdminBatch(player, resadmin);
        boolean effectiveBatch = batchMode && adminCapable;
        boolean effectiveResAdmin = effectiveBatch ? true : resadmin;

        List<RemoteResidenceEntry> entries = collectFilteredEntries(
                player, filter, sort, serverFilter, ownerFilter, archivedHolderOnly, effectiveResAdmin);
        int maxPages = Math.max(1, (entries.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, entries.size(), safePage);

        Set<String> selectedKeys = effectiveBatch ? getOrCreateBatchSelection(player.getUniqueId()) : Collections.emptySet();
        String title = effectiveBatch
                ? lm.Gui_Batch_Title.getMessage(selectedKeys.size(), pi.getCurrentPage(), Math.max(1, pi.getTotalPages()))
                : buildBrowserTitle(filter, serverFilter, ownerFilterName, pi);

        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                        archivedHolderOnly, effectiveBatch, resadmin, newPage);
            }
        };
        gui.setTitle(title);
        gui.setInvSize(GUIRows.r6);

        int slot = 0;
        for (RemoteResidenceEntry entry : entries) {
            if (pi.isContinue())
                continue;
            if (pi.isBreak())
                break;

            final RemoteResidenceEntry target = entry;
            if (effectiveBatch) {
                String resKey = target.getResName().toLowerCase(Locale.ROOT);
                boolean isSelected = target.isLocal() && selectedKeys.contains(resKey);
                ItemStack batchItem = buildBatchEntryItem(player, target, isSelected);
                CMIGuiButton btn = new CMIGuiButton(slot++, batchItem) {
                    @Override
                    public void click(GUIClickType type) {
                        if (type == GUIClickType.Right) {
                            player.closeInventory();
                            plugin.getCrossServerManager().teleportToRemoteEntry(player, target, true);
                            return;
                        }
                        if (type == GUIClickType.RightShift) {
                            Runnable backToBatch = () -> openBrowserInternal(
                                    player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                    archivedHolderOnly, true, true, pi.getCurrentPage());
                            openManageGuiWithBack(player, target, true, backToBatch);
                            return;
                        }
                        if (!target.isLocal()) {
                            return;
                        }
                        if (selectedKeys.contains(resKey)) {
                            selectedKeys.remove(resKey);
                        } else {
                            selectedKeys.add(resKey);
                        }
                        openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                archivedHolderOnly, true, resadmin, pi.getCurrentPage());
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
                continue;
            }

            ItemStack item = buildEntryItem(player, target, effectiveResAdmin);
            CMIGuiButton btn = new CMIGuiButton(slot++, item) {
                @Override
                public void click(GUIClickType type) {
                    boolean canManage = effectiveResAdmin || target.isOwner(player.getUniqueId());
                    if (type == GUIClickType.MiddleMouse && adminCapable && target.isLocal()) {
                        Set<String> sel = getOrCreateBatchSelection(player.getUniqueId());
                        String rk = target.getResName().toLowerCase(Locale.ROOT);
                        if (sel.contains(rk))
                            sel.remove(rk);
                        else
                            sel.add(rk);
                        openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                archivedHolderOnly, true, resadmin, pi.getCurrentPage());
                        return;
                    }
                    if (type == GUIClickType.Left) {
                        player.closeInventory();
                        plugin.getCrossServerManager().teleportToRemoteEntry(player, target, effectiveResAdmin);
                        return;
                    }
                    if (type == GUIClickType.LeftShift) {
                        if (!plugin.getConfigManager().isResidenceGuiAllowPin())
                            return;
                        boolean nowPinned = plugin.getGuiMetaManager().togglePin(player.getUniqueId(), target.getServerName(), target.getResName());
                        if (nowPinned)
                            lm.Gui_Msg_Pinned.sendMessage(player, target.getDisplayName());
                        else
                            lm.Gui_Msg_Unpinned.sendMessage(player, target.getDisplayName());
                        openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                archivedHolderOnly, false, resadmin, pi.getCurrentPage());
                        return;
                    }
                    if (type == GUIClickType.Right && canManage) {
                        Runnable backToBrowser = () -> openBrowserInternal(
                                player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                archivedHolderOnly, false, resadmin, pi.getCurrentPage());
                        openManageGuiWithBack(player, target, effectiveResAdmin, backToBrowser);
                        return;
                    }
                    if (type == GUIClickType.Right || type == GUIClickType.RightShift || type == GUIClickType.MiddleMouse) {
                        if (!plugin.getConfigManager().isResidenceGuiAllowLike())
                            return;
                        boolean nowLiked = plugin.getGuiMetaManager().toggleLike(player.getUniqueId(), target.getServerName(), target.getResName());
                        if (nowLiked)
                            lm.Gui_Msg_Liked.sendMessage(player, target.getDisplayName());
                        else
                            lm.Gui_Msg_Unliked.sendMessage(player, target.getDisplayName());
                        openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                archivedHolderOnly, false, resadmin, pi.getCurrentPage());
                    }
                }
            };
            btn.hideItemFlags();
            gui.addButton(btn);
        }

        // Bottom control bar (slots 45..53; slots 45, 49, 53 are pagination)
        String currentFilterText = (ownerFilter != null || archivedHolderOnly) && ownerFilterName != null
                ? ownerFilterName
                : filterLabel(filter);

        // Slot 46: Filter mode
        gui.addButton(new CMIGuiButton(46, buildControlItem(
                CMIMaterial.HOPPER,
                lm.Gui_Button_Filter_Name.getMessage(currentFilterText),
                lm.Gui_Button_Filter_Lore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                FilterMode nextFilter = (ownerFilter != null || archivedHolderOnly) ? FilterMode.ALL : filter.next();
                openBrowserInternal(player, nextFilter, sort, serverFilter, null, null, false, effectiveBatch, resadmin, 1);
            }
        });

        // Slot 48: Sort mode
        gui.addButton(new CMIGuiButton(48, buildControlItem(
                CMIMaterial.COMPARATOR,
                lm.Gui_Button_Sort_Name.getMessage(sortLabel(sort)),
                lm.Gui_Button_Sort_Lore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBrowserInternal(player, filter, sort.next(), serverFilter, ownerFilter, ownerFilterName,
                        archivedHolderOnly, effectiveBatch, resadmin, 1);
            }
        });

        if (effectiveBatch) {
            // Slot 47: Selection Controls (Select page / Select all filtered / Clear)
            gui.addButton(new CMIGuiButton(47, buildControlItem(
                    CMIMaterial.CHEST_MINECART,
                    lm.Gui_Batch_Btn_SelectName.getMessage(selectedKeys.size()),
                    lm.Gui_Batch_Btn_SelectLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    if (type == GUIClickType.Right || type == GUIClickType.RightShift) {
                        selectedKeys.clear();
                    } else if (type == GUIClickType.LeftShift) {
                        for (RemoteResidenceEntry e : entries) {
                            if (e.isLocal()) {
                                selectedKeys.add(e.getResName().toLowerCase(Locale.ROOT));
                            }
                        }
                    } else {
                        int start = Math.max(0, pi.getStart());
                        int end = Math.min(entries.size() - 1, pi.getEnd());
                        for (int idx = start; idx <= end; idx++) {
                            RemoteResidenceEntry e = entries.get(idx);
                            if (e.isLocal()) {
                                selectedKeys.add(e.getResName().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                    openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                            archivedHolderOnly, true, resadmin, pi.getCurrentPage());
                }
            });

            // Slot 50: Quick Batch hidden flag (true / false / remove)
            gui.addButton(new CMIGuiButton(50, buildControlItem(
                    CMIMaterial.ENDER_EYE,
                    lm.Gui_Batch_Btn_QuickHiddenName.getMessage(selectedKeys.size()),
                    lm.Gui_Batch_Btn_QuickHiddenLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    FlagState targetState;
                    if (type == GUIClickType.Left) {
                        targetState = FlagState.TRUE;
                    } else if (type == GUIClickType.Right) {
                        targetState = FlagState.FALSE;
                    } else {
                        targetState = FlagState.NEITHER;
                    }
                    applyBatchFlagOnMain(player, selectedKeys, Flags.hidden.toString(), targetState);
                    openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                            archivedHolderOnly, true, resadmin, pi.getCurrentPage());
                }
            });

            // Slot 51: Open Full /res set Batch Flag Picker GUI
            gui.addButton(new CMIGuiButton(51, buildControlItem(
                    CMIMaterial.ANVIL,
                    lm.Gui_Batch_Btn_AllFlagsName.getMessage(),
                    lm.Gui_Batch_Btn_AllFlagsLore.getMessageList(String.valueOf(selectedKeys.size())))) {
                @Override
                public void click(GUIClickType type) {
                    if (selectedKeys.isEmpty()) {
                        lm.Gui_Batch_Msg_NoneSelected.sendMessage(player);
                        return;
                    }
                    openBatchFlagPickerGui(player, 1, () -> openBrowserInternal(
                            player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                            archivedHolderOnly, true, resadmin, pi.getCurrentPage()));
                }
            });

            // Slot 52: Exit Batch Mode
            gui.addButton(new CMIGuiButton(52, buildControlItem(
                    CMIMaterial.BARRIER,
                    lm.Gui_Batch_Btn_ExitName.getMessage(),
                    lm.Gui_Batch_Btn_ExitLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                            archivedHolderOnly, false, resadmin, pi.getCurrentPage());
                }
            });

            gui.addPagination(pi);
            gui.fillEmptyButtons();
            gui.open();
            return;
        }

        // Slot 47: Server filter (when cross-server is active)
        CrossServerManager csm = plugin.getCrossServerManager();
        if (csm != null && csm.isEnabled() && plugin.getConfigManager().isResidenceGuiShowCrossServer()) {
            List<String> servers = new ArrayList<>(csm.getAvailableServers());
            String currentLabel = serverFilter == null ? lm.Gui_Server_All.getMessage() : displayServerName(serverFilter);
            List<String> serverLabels = new ArrayList<>(servers.size());
            for (String srv : servers) {
                serverLabels.add(displayServerName(srv) + "&7");
            }
            gui.addButton(new CMIGuiButton(47, buildControlItem(
                    CMIMaterial.ENDER_EYE,
                    lm.Gui_Button_Server_Name.getMessage(currentLabel),
                    Arrays.asList(
                            lm.Gui_Button_Server_Lore1.getMessage(),
                            lm.Gui_Button_Server_Lore2.getMessage(String.join(", ", serverLabels))))) {
                @Override
                public void click(GUIClickType type) {
                    String nextSrv = nextServerFilter(serverFilter, servers);
                    openBrowserInternal(player, filter, sort, nextSrv, ownerFilter, ownerFilterName,
                            archivedHolderOnly, false, resadmin, 1);
                }
            });
        }

        // Slot 50: Open Auction GUI
        if (plugin.getAuctionManager() != null && plugin.getAuctionManager().isEnabled()) {
            gui.addButton(new CMIGuiButton(50, buildControlItem(
                    CMIMaterial.GOLD_BLOCK,
                    lm.Gui_Auction_Btn_OpenName.getMessage(),
                    lm.Gui_Auction_Btn_OpenLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    plugin.getAuctionGui().openBrowser(player, AuctionGui.AuctionFilter.ALL, AuctionGui.AuctionSort.ENDING_SOON, resadmin, 1);
                }
            });
        }

        // Slot 51: Refresh (Right-click for Admins: Enter Batch Flag Mode)
        List<String> refreshLore = new ArrayList<>();
        refreshLore.add(lm.Gui_Button_Refresh_Lore.getMessage());
        if (adminCapable) {
            refreshLore.add(lm.Gui_Button_Refresh_AdminHint.getMessage());
        }
        gui.addButton(new CMIGuiButton(51, buildControlItem(
                CMIMaterial.SUNFLOWER,
                lm.Gui_Button_Refresh_Name.getMessage(),
                refreshLore)) {
            @Override
            public void click(GUIClickType type) {
                if (adminCapable && (type == GUIClickType.Right || type == GUIClickType.RightShift)) {
                    openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                            archivedHolderOnly, true, resadmin, pi.getCurrentPage());
                    return;
                }
                if (csm != null) {
                    csm.refreshAsyncThen(() -> {
                        if (player.isOnline()) {
                            openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                                    archivedHolderOnly, false, resadmin, pi.getCurrentPage());
                        }
                    });
                } else {
                    openBrowserInternal(player, filter, sort, serverFilter, ownerFilter, ownerFilterName,
                            archivedHolderOnly, false, resadmin, pi.getCurrentPage());
                }
            }
        });

        // Slot 52: Open Market GUI (when economy is enabled)
        if (plugin.getConfigManager().enableEconomy()) {
            gui.addButton(new CMIGuiButton(52, buildControlItem(
                    CMIMaterial.EMERALD,
                    "&a領地市場",
                    Arrays.asList(
                            "&7點擊開啟領地買賣／租賃市場 GUI",
                            "&8也可直接輸入 /res market"))) {
                @Override
                public void click(GUIClickType type) {
                    plugin.getMarketGui().openMarketBrowser(player, MarketGui.MarketFilter.ALL, MarketGui.MarketSort.PRICE_ASC, resadmin, 1);
                }
            });
        }

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    public void openManageGui(Player player, RemoteResidenceEntry entry, FilterMode backFilter, SortMode backSort, String backServer, boolean resadmin, int backPage) {
        openManageGui(player, entry, backFilter, backSort, backServer, null, null, resadmin, backPage);
    }

    public void openManageGui(Player player, RemoteResidenceEntry entry, FilterMode backFilter, SortMode backSort,
            String backServer, UUID backOwnerFilter, String backOwnerName, boolean resadmin, int backPage) {
        openManageGuiWithBack(player, entry, resadmin,
                () -> openBrowser(player, backFilter, backSort, backServer, backOwnerFilter, backOwnerName, resadmin, backPage));
    }

    private void openManageGuiWithBack(Player player, RemoteResidenceEntry entry, boolean resadmin, Runnable backToBrowser) {
        if (player == null || !player.isOnline() || entry == null)
            return;
        if (!resadmin && !entry.isOwner(player.getUniqueId())) {
            lm.Residence_NotOwner.sendMessage(player);
            return;
        }

        ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
        ResidenceGuiMetadata meta = metaMgr.getMetadata(entry.getServerName(), entry.getResName());
        ClaimedResidence localRes = entry.isLocal() ? plugin.getResidenceManager().getByName(entry.getResName()) : null;
        Runnable reopenCurrentManage = () -> openManageGuiWithBack(player, refreshEntry(entry), resadmin, backToBrowser);

        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Manage_Title.getMessage(entry.getResName()));
        gui.setInvSize(GUIRows.r4);

        // Row 2: Appearance, TP, Info (slots 10..16)
        // Slot 10: Toggle Public / Listed
        gui.addButton(new CMIGuiButton(10, buildControlItem(
                meta.isListed() ? CMIMaterial.LIME_DYE : CMIMaterial.GRAY_DYE,
                meta.isListed() ? lm.Gui_Manage_VisibilityPublic.getMessage() : lm.Gui_Manage_VisibilityHidden.getMessage(),
                lm.Gui_Manage_VisibilityLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                if (!verifyCanStillManage(player, entry, resadmin)) {
                    player.closeInventory();
                    return;
                }
                ResidenceGuiMetadata updated = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
                updated.setListed(!updated.isListed());
                metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), updated);
                reopenCurrentManage.run();
            }
        });

        // Slot 11: Set Icon from Main Hand
        String currentIcon = meta.getIcon() == null ? plugin.getConfigManager().getResidenceGuiDefaultIcon() : meta.getIcon();
        gui.addButton(new CMIGuiButton(11, buildControlItem(
                resolveIconMaterial(meta.getIcon()),
                lm.Gui_Manage_IconName.getMessage(currentIcon),
                lm.Gui_Manage_IconLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                if (!verifyCanStillManage(player, entry, resadmin)) {
                    player.closeInventory();
                    return;
                }
                ResidenceGuiMetadata updated = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
                if (type == GUIClickType.Right) {
                    updated.setIcon(null);
                    metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), updated);
                    lm.Gui_Msg_IconReset.sendMessage(player);
                } else {
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand == null || hand.getType() == Material.AIR) {
                        lm.Gui_Msg_IconHoldItem.sendMessage(player);
                        return;
                    }
                    updated.setIcon(hand.getType().name());
                    metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), updated);
                    lm.Gui_Msg_IconUpdated.sendMessage(player, entry.getResName(), hand.getType().name());
                }
                reopenCurrentManage.run();
            }
        });

        // Slot 12: Edit Alias
        List<String> aliasLore = new ArrayList<>();
        aliasLore.add(lm.Gui_Manage_Current.getMessage(meta.getAlias() == null ? lm.Gui_Manage_None.getMessage() : meta.getAlias()));
        aliasLore.addAll(lm.Gui_Manage_AliasLore.getMessageList());
        gui.addButton(new CMIGuiButton(12, buildControlItem(
                CMIMaterial.NAME_TAG,
                lm.Gui_Manage_AliasName.getMessage(),
                aliasLore)) {
            @Override
            public void click(GUIClickType type) {
                if (!verifyCanStillManage(player, entry, resadmin)) {
                    player.closeInventory();
                    return;
                }
                if (type == GUIClickType.Right) {
                    ResidenceGuiMetadata updated = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
                    updated.setAlias(null);
                    metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), updated);
                    lm.Gui_Msg_AliasCleared.sendMessage(player, entry.getResName());
                    reopenCurrentManage.run();
                    return;
                }
                player.closeInventory();
                pendingEdits.put(player.getUniqueId(), new PendingEdit(entry.getServerName(), entry.getResName(), EditField.ALIAS, resadmin, reopenCurrentManage));
                lm.Gui_Msg_AliasPrompt.sendMessage(player, entry.getResName());
            }
        });

        // Slot 13: Edit Description
        List<String> descLore = new ArrayList<>();
        descLore.add(lm.Gui_Manage_Current.getMessage(meta.getDescription() == null ? lm.Gui_Manage_None.getMessage() : meta.getDescription()));
        descLore.addAll(lm.Gui_Manage_DescLore.getMessageList());
        gui.addButton(new CMIGuiButton(13, buildControlItem(
                CMIMaterial.WRITABLE_BOOK,
                lm.Gui_Manage_DescName.getMessage(),
                descLore)) {
            @Override
            public void click(GUIClickType type) {
                if (!verifyCanStillManage(player, entry, resadmin)) {
                    player.closeInventory();
                    return;
                }
                if (type == GUIClickType.Right) {
                    ResidenceGuiMetadata updated = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
                    updated.setDescription(null);
                    metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), updated);
                    lm.Gui_Msg_DescCleared.sendMessage(player, entry.getResName());
                    reopenCurrentManage.run();
                    return;
                }
                player.closeInventory();
                pendingEdits.put(player.getUniqueId(), new PendingEdit(entry.getServerName(), entry.getResName(), EditField.DESCRIPTION, resadmin, reopenCurrentManage));
                lm.Gui_Msg_DescPrompt.sendMessage(player, entry.getResName());
            }
        });

        // Slot 14: Set TP Point (local only)
        if (localRes != null) {
            gui.addButton(new CMIGuiButton(14, buildControlItem(
                    CMIMaterial.ENDER_PEARL,
                    lm.Gui_Manage_SetTpName.getMessage(),
                    lm.Gui_Manage_SetTpLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    player.closeInventory();
                    ClaimedResidence current = plugin.getResidenceManager().getByName(entry.getResName());
                    if (current == null) {
                        lm.Invalid_Residence.sendMessage(player);
                        return;
                    }
                    if (!resadmin && !current.isOwner(player)) {
                        lm.Residence_NotOwner.sendMessage(player);
                        return;
                    }
                    current.setTpLoc(player, resadmin);
                    if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled())
                        plugin.getCrossServerManager().triggerAsyncSync();
                }
            });
        }

        // Slot 15: Teleport Now
        gui.addButton(new CMIGuiButton(15, buildControlItem(
                CMIMaterial.COMPASS,
                lm.Gui_Manage_TeleportName.getMessage(),
                lm.Gui_Manage_TeleportLore.getMessage(entry.getResName()))) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                plugin.getCrossServerManager().teleportToRemoteEntry(player, entry, resadmin);
            }
        });

        // Slot 16: Residence Info
        List<String> infoLore = buildResidenceInfoLore(entry, localRes);
        gui.addButton(new CMIGuiButton(16, buildControlItem(
                CMIMaterial.BOOK,
                lm.Gui_Manage_InfoName.getMessage(),
                infoLore)) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                if (localRes != null) {
                    plugin.getResidenceManager().printAreaInfo(localRes, player, resadmin);
                } else {
                    lm.Gui_Msg_RemoteInfo.sendMessage(player,
                            displayServerName(entry.getServerName()),
                            entry.getResName(),
                            entry.getOwnerName(),
                            entry.getWorldName(),
                            (int) Math.floor(entry.getTpX()),
                            (int) Math.floor(entry.getTpY()),
                            (int) Math.floor(entry.getTpZ()));
                }
            }
        });

        // Row 3: Local Management Actions (slots 19..25)
        if (localRes != null) {
            Runnable reopenManage = reopenCurrentManage;

            // Slot 19: Edit Residence Flags
            gui.addButton(new CMIGuiButton(19, buildControlItem(
                    CMIMaterial.COMPARATOR,
                    lm.Gui_Manage_FlagsName.getMessage(),
                    lm.Gui_Manage_FlagsLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    ClaimedResidence current = plugin.getResidenceManager().getByName(entry.getResName());
                    if (current == null) {
                        lm.Invalid_Residence.sendMessage(player);
                        player.closeInventory();
                        return;
                    }
                    if (!resadmin && !current.isOwner(player)) {
                        lm.Residence_NotOwner.sendMessage(player);
                        player.closeInventory();
                        return;
                    }
                    plugin.getFlagUtilManager().openSetFlagGui(player, current, resadmin, 1);
                }
            });

            // Slot 20: Members & Player Flags (/res pset GUI)
            gui.addButton(new CMIGuiButton(20, buildControlItem(
                    CMIMaterial.PLAYER_HEAD,
                    lm.Gui_Manage_MembersName.getMessage(),
                    lm.Gui_Manage_MembersLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    openMembersGui(player, localRes, false, resadmin, 1, reopenManage);
                }
            });

            // Slot 21: Subzones List GUI
            gui.addButton(new CMIGuiButton(21, buildControlItem(
                    CMIMaterial.OAK_FENCE,
                    lm.Gui_Manage_SubzonesName.getMessage(),
                    lm.Gui_Manage_SubzonesLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    openSubzonesGui(player, localRes, resadmin, 1, reopenManage);
                }
            });

            // Slot 22: Market Management (Sell / Rent)
            if (plugin.getConfigManager().enableEconomy()) {
                boolean isForSale = plugin.getTransactionManager().isForSale(localRes);
                boolean isForRent = plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isForRent(localRes);
                String status = isForSale ? "&a出售中" : (isForRent ? (localRes.isRented() ? "&e已租出" : "&b待租中") : "&7未上架");
                gui.addButton(new CMIGuiButton(22, buildControlItem(
                        CMIMaterial.EMERALD,
                        "&a市場設定（出售／出租）",
                        Arrays.asList(
                                "&7目前狀態：" + status,
                                "&7點擊上架出售、上架出租或下架"))) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence live = plugin.getResidenceManager().getByName(entry.getResName());
                        if (live == null) {
                            lm.Invalid_Residence.sendMessage(player);
                            player.closeInventory();
                            return;
                        }
                        plugin.getMarketGui().openOwnerMarketGui(player, live, resadmin, reopenManage);
                    }
                });
            }

            // Slot 23: 發起領地拍賣／查看進行中拍賣（需先經條款同意頁）
            if (plugin.getAuctionManager() != null && plugin.getAuctionManager().isEnabled()) {
                com.bekvon.bukkit.residence.auction.core.AuctionRecord activeAuc = plugin.getAuctionManager().getCachedActiveAuctionByRes(localRes);
                gui.addButton(new CMIGuiButton(23, buildControlItem(
                        activeAuc != null ? CMIMaterial.GOLD_BLOCK : CMIMaterial.GOLD_INGOT,
                        activeAuc != null ? lm.Gui_Manage_AuctionActiveName.getMessage() : lm.Gui_Manage_AuctionStartName.getMessage(),
                        activeAuc != null ? lm.Gui_Manage_AuctionActiveLore.getMessageList() : lm.Gui_Manage_AuctionStartLore.getMessageList())) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence live = plugin.getResidenceManager().getByName(entry.getResName());
                        if (live == null) {
                            lm.Invalid_Residence.sendMessage(player);
                            player.closeInventory();
                            return;
                        }
                        com.bekvon.bukkit.residence.auction.core.AuctionRecord currentAuc = plugin.getAuctionManager().getCachedActiveAuctionByRes(live);
                        if (currentAuc != null) {
                            plugin.getAuctionGui().openDetailGui(player, currentAuc.getId(), resadmin, reopenManage);
                        } else {
                            plugin.getAuctionGui().openTermsAgreementGui(player, live, resadmin, reopenManage);
                        }
                    }
                });
            }

            // Slot 24: 管理員設定／變更 Server_Unclaimed 保管領地的原領地主
            CleanupReclaimManager reclaimMgr = plugin.getCleanupReclaimManager();
            if (canUseAdminBatch(player, resadmin) && reclaimMgr != null && reclaimMgr.isArchivedHolder(localRes.getOwner())) {
                ReclaimEntry arcEntry = reclaimMgr.getArchivedEntry(localRes);
                String curOrig = (arcEntry != null && !arcEntry.getOriginalOwnerName().isEmpty())
                        ? arcEntry.getOriginalOwnerName()
                        : lm.Gui_Manage_OriginalOwnerUnset.getMessage();
                List<String> origLore = new ArrayList<>();
                origLore.add(lm.Gui_Manage_OriginalOwnerCurrent.getMessage(curOrig));
                if (arcEntry != null && arcEntry.getOriginalOwnerUuid() != null && plugin.getCrossServerManager() != null) {
                    origLore.add(lm.Gui_Entry_OriginalLastSeen.getMessage(
                            plugin.getCrossServerManager().formatLastSeenValue(arcEntry.getOriginalOwnerUuid(), true)));
                }
                origLore.add("");
                origLore.addAll(lm.Gui_Manage_OriginalOwnerLore.getMessageList());
                gui.addButton(new CMIGuiButton(24, buildControlItem(
                        CMIMaterial.WRITTEN_BOOK,
                        lm.Gui_Manage_OriginalOwnerName.getMessage(),
                        origLore)) {
                    @Override
                    public void click(GUIClickType type) {
                        if (!canUseAdminBatch(player, resadmin)) {
                            lm.General_AdminOnly.sendMessage(player);
                            return;
                        }
                        if (type == GUIClickType.Right) {
                            cleanup.handleSetOriginalOwner(plugin, player, entry.getResName(), "clear", null, false, reopenManage);
                            return;
                        }
                        player.closeInventory();
                        pendingEdits.put(player.getUniqueId(),
                                new PendingEdit(entry.getServerName(), entry.getResName(), EditField.ORIGINAL_OWNER, true, reopenManage));
                        lm.Gui_Msg_OriginalOwnerPrompt.sendMessage(player, entry.getResName());
                    }
                });
            }

            // Slot 25: Delete Residence (with Confirmation GUI)
            gui.addButton(new CMIGuiButton(25, buildControlItem(
                    CMIMaterial.BARRIER,
                    lm.Gui_Manage_DeleteName.getMessage(),
                    lm.Gui_Manage_DeleteLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    openDeleteConfirmGui(player, localRes, resadmin, reopenManage, () -> {
                        if (player.isOnline() && backToBrowser != null) {
                            backToBrowser.run();
                        }
                    });
                }
            });
        }

        // Row 4: Navigation
        // Slot 31: Back
        gui.addButton(new CMIGuiButton(31, buildControlItem(
                CMIMaterial.ARROW,
                lm.Gui_Button_Back_Name.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backToBrowser != null) {
                    backToBrowser.run();
                } else {
                    player.closeInventory();
                }
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    public void openSubzonesGui(Player player, ClaimedResidence parentRes, boolean resadmin, int page, Runnable backAction) {
        if (player == null || !player.isOnline() || parentRes == null)
            return;
        ClaimedResidence liveParent = plugin.getResidenceManager().getByName(parentRes.getName());
        if (liveParent == null) {
            lm.Invalid_Residence.sendMessage(player);
            return;
        }
        if (!resadmin && !liveParent.isOwner(player) && !liveParent.getPermissions().playerHas(player, Flags.admin, false)) {
            lm.General_NoPermission.sendMessage(player);
            return;
        }

        List<ClaimedResidence> subzones = new ArrayList<>(liveParent.getSubzones());
        subzones.sort(Comparator.comparing(ClaimedResidence::getResidenceName, String.CASE_INSENSITIVE_ORDER));

        int maxPages = Math.max(1, (subzones.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, subzones.size(), safePage);

        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openSubzonesGui(player, liveParent, resadmin, newPage, backAction);
            }
        };
        gui.setTitle(lm.Gui_Subzones_Title.getMessage(liveParent.getName(), pi.getCurrentPage(), Math.max(1, pi.getTotalPages())));
        gui.setInvSize(GUIRows.r6);

        if (subzones.isEmpty()) {
            gui.addButton(new CMIGuiButton(22, buildControlItem(
                    CMIMaterial.PAPER,
                    lm.Gui_Subzones_EmptyName.getMessage(),
                    lm.Gui_Subzones_EmptyLore.getMessageList())));
        } else {
            int slot = 0;
            for (ClaimedResidence sub : subzones) {
                if (pi.isContinue())
                    continue;
                if (pi.isBreak())
                    break;

                final ClaimedResidence targetSub = sub;
                List<String> lore = new ArrayList<>();
                lore.add(lm.Gui_Entry_Owner.getMessage(targetSub.getOwner()));
                lore.add(lm.Gui_Subzones_Size.getMessage(targetSub.getTotalSize()));
                int nested = targetSub.getSubzonesAmount(true);
                if (nested > 0) {
                    lore.add(lm.Gui_Subzones_Nested.getMessage(nested));
                }
                lore.add("");
                lore.add(lm.Gui_Subzones_ClickTp.getMessage());
                lore.add(lm.Gui_Subzones_ClickFlags.getMessage());
                lore.add(lm.Gui_Subzones_ClickMembers.getMessage());
                if (nested > 0) {
                    lore.add(lm.Gui_Subzones_ClickNested.getMessage());
                }
                lore.add(lm.Gui_Subzones_ClickDelete.getMessage());

                Runnable reopenSubzones = () -> openSubzonesGui(player, liveParent, resadmin, pi.getCurrentPage(), backAction);
                CMIGuiButton btn = new CMIGuiButton(slot++, buildControlItem(
                        CMIMaterial.OAK_FENCE,
                        "&e" + targetSub.getResidenceName(),
                        lore)) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence liveSub = plugin.getResidenceManager().getByName(targetSub.getName());
                        if (liveSub == null) {
                            lm.Invalid_Residence.sendMessage(player);
                            reopenSubzones.run();
                            return;
                        }
                        if (type == GUIClickType.Left) {
                            player.closeInventory();
                            liveSub.tpToResidence(player, player, resadmin);
                            return;
                        }
                        if (type == GUIClickType.Right) {
                            if (!resadmin && !liveSub.getPermissions().hasResidencePermission(player, false)) {
                                lm.General_NoPermission.sendMessage(player);
                                return;
                            }
                            plugin.getFlagUtilManager().openSetFlagGui(player, liveSub, resadmin, 1);
                            return;
                        }
                        if (type == GUIClickType.LeftShift) {
                            openMembersGui(player, liveSub, false, resadmin, 1, reopenSubzones);
                            return;
                        }
                        if (type == GUIClickType.MiddleMouse && liveSub.getSubzonesAmount(true) > 0) {
                            openSubzonesGui(player, liveSub, resadmin, 1, reopenSubzones);
                            return;
                        }
                        if (type == GUIClickType.RightShift) {
                            openDeleteConfirmGui(player, liveSub, resadmin, reopenSubzones, reopenSubzones);
                        }
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
            }
        }

        // Slot 46: Back
        gui.addButton(new CMIGuiButton(46, buildControlItem(
                CMIMaterial.ARROW,
                lm.Gui_Button_BackManage_Name.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null)
                    backAction.run();
                else
                    player.closeInventory();
            }
        });

        // Slot 51: Refresh
        gui.addButton(new CMIGuiButton(51, buildControlItem(
                CMIMaterial.SUNFLOWER,
                lm.Gui_Button_Refresh_Name.getMessage(),
                lm.Gui_Button_Refresh_Lore.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                openSubzonesGui(player, liveParent, resadmin, pi.getCurrentPage(), backAction);
            }
        });

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    public void openMembersGui(Player player, ClaimedResidence targetRes, boolean showOnlinePlayers, boolean resadmin, int page, Runnable backAction) {
        if (player == null || !player.isOnline() || targetRes == null)
            return;
        ClaimedResidence liveRes = plugin.getResidenceManager().getByName(targetRes.getName());
        if (liveRes == null) {
            lm.Invalid_Residence.sendMessage(player);
            return;
        }
        if (!resadmin && !liveRes.isOwner(player) && !liveRes.getPermissions().playerHas(player, Flags.admin, false)) {
            lm.General_NoPermission.sendMessage(player);
            return;
        }

        if (showOnlinePlayers) {
            openOnlinePlayersPicker(player, liveRes, resadmin, page, backAction);
            return;
        }

        List<Entry<UUID, Map<String, Boolean>>> members = new ArrayList<>();
        for (Entry<UUID, Map<String, Boolean>> e : liveRes.getPermissions().getPlayerFlags().entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty())
                continue;
            members.add(e);
        }
        members.sort(Comparator.comparing(e -> resolveDisplayPlayerName(e.getKey()), String.CASE_INSENSITIVE_ORDER));

        int maxPages = Math.max(1, (members.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, members.size(), safePage);

        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openMembersGui(player, liveRes, false, resadmin, newPage, backAction);
            }
        };
        gui.setTitle(lm.Gui_Members_Title.getMessage(liveRes.getName(), pi.getCurrentPage(), Math.max(1, pi.getTotalPages())));
        gui.setInvSize(GUIRows.r6);

        if (members.isEmpty()) {
            gui.addButton(new CMIGuiButton(22, buildControlItem(
                    CMIMaterial.PAPER,
                    lm.Gui_Members_EmptyName.getMessage(),
                    lm.Gui_Members_EmptyLore.getMessageList())));
        } else {
            int slot = 0;
            for (Entry<UUID, Map<String, Boolean>> member : members) {
                if (pi.isContinue())
                    continue;
                if (pi.isBreak())
                    break;

                final UUID memberUuid = member.getKey();
                final String memberName = resolveDisplayPlayerName(memberUuid);
                String flagsSummary = liveRes.getPermissions().listPlayerFlags(memberUuid);
                if (flagsSummary.length() > 80) {
                    flagsSummary = flagsSummary.substring(0, 77) + "...";
                }

                List<String> lore = new ArrayList<>();
                lore.add(lm.Gui_Members_FlagsLabel.getMessage(flagsSummary));
                lore.add("");
                lore.add(lm.Gui_Members_ClickEdit.getMessage());
                lore.add(lm.Gui_Members_ClickRemoveAll.getMessage());

                CMIGuiButton btn = new CMIGuiButton(slot++, buildPlayerHeadItem(memberUuid, "&e" + memberName, lore)) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence current = plugin.getResidenceManager().getByName(liveRes.getName());
                        if (current == null) {
                            lm.Invalid_Residence.sendMessage(player);
                            player.closeInventory();
                            return;
                        }
                        if (!resadmin && !current.isOwner(player) && !current.getPermissions().playerHas(player, Flags.admin, false)) {
                            lm.General_NoPermission.sendMessage(player);
                            player.closeInventory();
                            return;
                        }
                        if (type == GUIClickType.RightShift) {
                            current.getPermissions().removeAllPlayerFlags(player, memberUuid, resadmin);
                            openMembersGui(player, current, false, resadmin, pi.getCurrentPage(), backAction);
                            return;
                        }
                        plugin.getFlagUtilManager().openPsetFlagGui(player, memberUuid, current, resadmin, 1);
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
            }
        }

        // Slot 46: Back
        gui.addButton(new CMIGuiButton(46, buildControlItem(
                CMIMaterial.ARROW,
                lm.Gui_Button_BackManage_Name.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null)
                    backAction.run();
                else
                    player.closeInventory();
            }
        });

        // Slot 47: Add Member via Chat Input
        gui.addButton(new CMIGuiButton(47, buildControlItem(
                CMIMaterial.EMERALD,
                lm.Gui_Members_AddChatName.getMessage(),
                lm.Gui_Members_AddChatLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                pendingEdits.put(player.getUniqueId(), new PendingEdit(plugin.getStorageServerName(), liveRes.getName(), EditField.ADD_MEMBER, resadmin));
                lm.Gui_Msg_AddMemberPrompt.sendMessage(player, liveRes.getName());
            }
        });

        // Slot 48: Pick Online Player
        gui.addButton(new CMIGuiButton(48, buildControlItem(
                CMIMaterial.COMPASS,
                lm.Gui_Members_OnlineBtnName.getMessage(),
                lm.Gui_Members_OnlineBtnLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openMembersGui(player, liveRes, true, resadmin, 1, backAction);
            }
        });

        // Slot 51: Refresh
        gui.addButton(new CMIGuiButton(51, buildControlItem(
                CMIMaterial.SUNFLOWER,
                lm.Gui_Button_Refresh_Name.getMessage(),
                lm.Gui_Button_Refresh_Lore.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                openMembersGui(player, liveRes, false, resadmin, pi.getCurrentPage(), backAction);
            }
        });

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    private void openOnlinePlayersPicker(Player player, ClaimedResidence liveRes, boolean resadmin, int page, Runnable backAction) {
        List<Player> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!resadmin && !player.canSee(p))
                continue;
            if (!resadmin && p.getUniqueId().equals(liveRes.getOwnerUUID()))
                continue;
            online.add(p);
        }
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int maxPages = Math.max(1, (online.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, online.size(), safePage);

        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openOnlinePlayersPicker(player, liveRes, resadmin, newPage, backAction);
            }
        };
        gui.setTitle(lm.Gui_Members_OnlineTitle.getMessage(liveRes.getName(), pi.getCurrentPage(), Math.max(1, pi.getTotalPages())));
        gui.setInvSize(GUIRows.r6);

        if (online.isEmpty()) {
            gui.addButton(new CMIGuiButton(22, buildControlItem(
                    CMIMaterial.PAPER,
                    lm.Gui_Members_NoOnline.getMessage())));
        } else {
            int slot = 0;
            for (Player target : online) {
                if (pi.isContinue())
                    continue;
                if (pi.isBreak())
                    break;
                final UUID targetUuid = target.getUniqueId();
                List<String> lore = Arrays.asList(lm.Gui_Members_OnlineClick.getMessage());
                CMIGuiButton btn = new CMIGuiButton(slot++, buildPlayerHeadItem(targetUuid, "&e" + target.getName(), lore)) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence current = plugin.getResidenceManager().getByName(liveRes.getName());
                        if (current == null) {
                            lm.Invalid_Residence.sendMessage(player);
                            player.closeInventory();
                            return;
                        }
                        if (!resadmin && !current.isOwner(player) && !current.getPermissions().playerHas(player, Flags.admin, false)) {
                            lm.General_NoPermission.sendMessage(player);
                            player.closeInventory();
                            return;
                        }
                        plugin.getFlagUtilManager().openPsetFlagGui(player, targetUuid, current, resadmin, 1);
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
            }
        }

        // Slot 46: Back to Configured Members
        gui.addButton(new CMIGuiButton(46, buildControlItem(
                CMIMaterial.ARROW,
                lm.Gui_Members_ShowMembersName.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                openMembersGui(player, liveRes, false, resadmin, 1, backAction);
            }
        });

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    public void openDeleteConfirmGui(Player player, ClaimedResidence targetRes, boolean resadmin, Runnable cancelAction, Runnable afterDeleteAction) {
        if (player == null || !player.isOnline() || targetRes == null)
            return;
        ClaimedResidence liveRes = plugin.getResidenceManager().getByName(targetRes.getName());
        if (liveRes == null) {
            lm.Invalid_Residence.sendMessage(player);
            return;
        }
        if (!canDeleteResidence(player, liveRes, resadmin))
            return;

        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Delete_Title.getMessage(liveRes.getName()));
        gui.setInvSize(GUIRows.r3);

        int subzoneCount = liveRes.getSubzonesAmount(true);
        List<String> confirmLore = new ArrayList<>();
        confirmLore.add(lm.Gui_Delete_ConfirmLore1.getMessage(liveRes.getName()));
        confirmLore.add(lm.Gui_Delete_ConfirmLore2.getMessage());
        if (subzoneCount > 0) {
            confirmLore.add(lm.Gui_Delete_SubzoneWarning.getMessage(subzoneCount));
        }

        // Slot 11: Confirm Delete
        gui.addButton(new CMIGuiButton(11, buildControlItem(
                CMIMaterial.RED_WOOL,
                lm.Gui_Delete_ConfirmName.getMessage(),
                confirmLore)) {
            @Override
            public void click(GUIClickType type) {
                ClaimedResidence current = plugin.getResidenceManager().getByName(liveRes.getName());
                if (current == null) {
                    lm.Invalid_Residence.sendMessage(player);
                    player.closeInventory();
                    return;
                }
                if (!canDeleteResidence(player, current, resadmin)) {
                    player.closeInventory();
                    return;
                }
                player.closeInventory();
                plugin.getResidenceManager().removeResidence(player, current, resadmin);
                if (afterDeleteAction != null && player.isOnline()
                        && plugin.getResidenceManager().getByName(liveRes.getName()) == null) {
                    afterDeleteAction.run();
                }
            }
        });

        // Slot 13: Summary Paper
        List<String> summaryLore = new ArrayList<>();
        summaryLore.add(lm.Gui_Entry_Owner.getMessage(liveRes.getOwner()));
        summaryLore.add(lm.Gui_Manage_InfoAreas.getMessage(liveRes.getAreaMap().size(), liveRes.getTotalSize()));
        summaryLore.add(lm.Gui_Manage_InfoSubzones.getMessage(subzoneCount));
        gui.addButton(new CMIGuiButton(13, buildControlItem(
                CMIMaterial.PAPER,
                "&e" + liveRes.getName(),
                summaryLore)));

        // Slot 15: Cancel
        gui.addButton(new CMIGuiButton(15, buildControlItem(
                CMIMaterial.LIME_WOOL,
                lm.Gui_Delete_CancelName.getMessage(),
                lm.Gui_Delete_CancelLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                if (cancelAction != null)
                    cancelAction.run();
                else
                    player.closeInventory();
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    private boolean canDeleteResidence(Player player, ClaimedResidence res, boolean resadmin) {
        if (res.isSubzone() && !resadmin && !ResPerm.delete_subzone.hasPermission(player, lm.Subzone_CantDelete)) {
            return false;
        }
        if (res.isSubzone()
                && !resadmin
                && plugin.getConfigManager().isPreventSubZoneRemoval()
                && (res.getParent() == null || !res.getParent().isOwner(player))
                && !res.getPermissions().playerHas(player, Flags.admin, FlagCombo.OnlyTrue)) {
            lm.Subzone_CantDeleteNotOwnerOfParent.sendMessage(player);
            return false;
        }
        if (res.isSubzone()
                && !resadmin
                && !res.isOwner(player)
                && (res.getParent() == null || !res.getParent().isOwner(player))
                && !res.getPermissions().playerHas(player, Flags.admin, FlagCombo.OnlyTrue)) {
            lm.Subzone_CantDelete.sendMessage(player);
            return false;
        }
        if (!res.isSubzone()
                && !resadmin
                && !res.isOwner(player)
                && !ResPerm.admin.hasPermission(player, lm.Residence_CantDeleteResidence)) {
            return false;
        }
        if (!res.isSubzone() && !resadmin && !ResPerm.delete.hasPermission(player, lm.Residence_CantDeleteResidence)) {
            return false;
        }
        if (res.getRaid().isRaidInitialized() && !resadmin) {
            lm.Raid_noRemoval.sendMessage(player);
            return false;
        }
        return true;
    }

    private boolean verifyCanStillManage(Player player, RemoteResidenceEntry entry, boolean resadmin) {
        if (entry == null)
            return false;
        return verifyCanStillManage(player, entry.getServerName(), entry.getResName(), resadmin);
    }

    private boolean verifyCanStillManage(Player player, String serverName, String resName, boolean resadmin) {
        if (serverName != null && serverName.equalsIgnoreCase(plugin.getStorageServerName())) {
            ClaimedResidence local = plugin.getResidenceManager().getByName(resName);
            if (local == null) {
                lm.Invalid_Residence.sendMessage(player);
                return false;
            }
            if (!resadmin && !local.isOwner(player)) {
                lm.Residence_NotOwner.sendMessage(player);
                return false;
            }
            return true;
        }
        if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            List<RemoteResidenceEntry> matches = plugin.getCrossServerManager().findRemoteMatches(serverName + ":" + resName);
            if (matches.size() == 1) {
                if (!resadmin && !matches.get(0).isOwner(player.getUniqueId())) {
                    lm.Residence_NotOwner.sendMessage(player);
                    return false;
                }
                return true;
            }
        }
        lm.Invalid_Residence.sendMessage(player);
        return false;
    }

    private String formatOwnerLoreLine(String serverName, String resName, UUID resUuid, String ownerName) {
        if (plugin.getCleanupReclaimManager() != null) {
            String archived = plugin.getCleanupReclaimManager().formatArchivedInfoLine(serverName, resName, resUuid, ownerName);
            if (archived != null)
                return archived;
        }
        return lm.Gui_Entry_Owner.getMessage(ownerName);
    }

    private String formatLastSeenLoreLine(RemoteResidenceEntry entry, boolean includeTime) {
        if (entry == null || plugin.getCrossServerManager() == null)
            return null;
        CleanupReclaimManager reclaim = plugin.getCleanupReclaimManager();
        if (reclaim != null && reclaim.isArchivedHolder(entry.getOwnerName())) {
            ReclaimEntry arc = reclaim.getArchivedEntry(entry.getServerName(), entry.getResName(), entry.getResUuid());
            if (arc != null && arc.getOriginalOwnerUuid() != null) {
                return lm.Gui_Entry_OriginalLastSeen.getMessage(
                        plugin.getCrossServerManager().formatLastSeenValue(arc.getOriginalOwnerUuid(), includeTime));
            }
            return lm.Gui_Entry_OriginalLastSeen.getMessage(lm.Gui_Manage_OriginalOwnerUnset.getMessage());
        }
        if (entry.getOwnerUuid() != null
                && !entry.getOwnerUuid().equals(plugin.getServerUUID())
                && !entry.getOwnerName().equalsIgnoreCase("Server Land")
                && !entry.getOwnerName().equalsIgnoreCase(plugin.getServerLandName())) {
            return lm.Gui_Entry_LastSeen.getMessage(
                    plugin.getCrossServerManager().formatLastSeenValue(entry.getOwnerUuid(), includeTime));
        }
        return null;
    }

    private List<String> buildResidenceInfoLore(RemoteResidenceEntry entry, ClaimedResidence localRes) {
        List<String> lore = new ArrayList<>();
        lore.add(formatOwnerLoreLine(entry.getServerName(), entry.getResName(), entry.getResUuid(), entry.getOwnerName()));
        String seenLine = formatLastSeenLoreLine(entry, true);
        if (seenLine != null) {
            lore.add(seenLine);
        }
        lore.add(lm.Gui_Entry_World.getMessage(
                entry.getWorldName(),
                (int) Math.floor(entry.getTpX()),
                (int) Math.floor(entry.getTpY()),
                (int) Math.floor(entry.getTpZ())));
        if (localRes != null) {
            lore.add(lm.Gui_Manage_InfoAreas.getMessage(localRes.getAreaMap().size(), localRes.getTotalSize()));
            lore.add(lm.Gui_Manage_InfoSubzones.getMessage(localRes.getSubzonesAmount(true)));
            int memberCount = 0;
            for (Map<String, Boolean> map : localRes.getPermissions().getPlayerFlags().values()) {
                if (map != null && !map.isEmpty())
                    memberCount++;
            }
            lore.add(lm.Gui_Manage_InfoMembers.getMessage(memberCount));
            if (localRes.getCreateTime() > 0) {
                String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(localRes.getCreateTime()));
                lore.add(lm.Gui_Manage_InfoCreated.getMessage(dateStr));
            }
        }
        lore.add("");
        lore.add(lm.Gui_Manage_InfoClick.getMessage());
        return lore;
    }

    public List<RemoteResidenceEntry> collectFilteredEntries(Player player, FilterMode filter, SortMode sort, String serverFilter, boolean resadmin) {
        return collectFilteredEntries(player, filter, sort, serverFilter, null, false, resadmin);
    }

    public List<RemoteResidenceEntry> collectFilteredEntries(Player player, FilterMode filter, SortMode sort, String serverFilter, UUID ownerFilter, boolean resadmin) {
        return collectFilteredEntries(player, filter, sort, serverFilter, ownerFilter, false, resadmin);
    }

    public List<RemoteResidenceEntry> collectFilteredEntries(
            Player player, FilterMode filter, SortMode sort, String serverFilter, UUID ownerFilter, boolean archivedHolderOnly, boolean resadmin) {
        CrossServerManager csm = plugin.getCrossServerManager();
        ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
        CleanupReclaimManager reclaim = plugin.getCleanupReclaimManager();
        UUID viewerUuid = player.getUniqueId();
        boolean includeCross = csm != null && csm.isEnabled() && plugin.getConfigManager().isResidenceGuiShowCrossServer();

        List<RemoteResidenceEntry> raw = includeCross
                ? csm.getAllResidencesUnified()
                : (csm != null ? csm.buildLocalEntriesSnapshot() : new ArrayList<>());

        List<RemoteResidenceEntry> filtered = new ArrayList<>();
        for (RemoteResidenceEntry entry : raw) {
            if (serverFilter != null && !serverFilter.equalsIgnoreCase(entry.getServerName()))
                continue;

            boolean isOwner = entry.isOwner(viewerUuid);
            boolean isPinned = metaMgr != null && metaMgr.isPinned(viewerUuid, entry.getServerName(), entry.getResName());
            boolean isEntryArchived = reclaim != null && reclaim.isArchivedHolder(entry.getOwnerName());

            if (archivedHolderOnly) {
                if (!isEntryArchived)
                    continue;
                if (!resadmin && (entry.isHidden() || !entry.isListed()))
                    continue;
                filtered.add(entry);
                continue;
            }

            if (ownerFilter != null) {
                boolean matchesOwner = entry.isOwner(ownerFilter);
                boolean matchesArchivedOrig = false;
                if (!matchesOwner && isEntryArchived) {
                    ReclaimEntry arc = reclaim.getArchivedEntry(entry.getServerName(), entry.getResName(), entry.getResUuid());
                    matchesArchivedOrig = arc != null && ownerFilter.equals(arc.getOriginalOwnerUuid());
                }
                if (!matchesOwner && !matchesArchivedOrig)
                    continue;
                boolean viewerIsTarget = viewerUuid.equals(ownerFilter);
                if (!resadmin && !isOwner && !viewerIsTarget && (entry.isHidden() || !entry.isListed()))
                    continue;
                filtered.add(entry);
                continue;
            }

            switch (filter) {
            case MINE:
                if (!isOwner) {
                    if (isEntryArchived) {
                        ReclaimEntry arc = reclaim.getArchivedEntry(entry.getServerName(), entry.getResName(), entry.getResUuid());
                        if (arc == null || !viewerUuid.equals(arc.getOriginalOwnerUuid()))
                            continue;
                    } else {
                        continue;
                    }
                }
                break;
            case PINNED:
                if (!isPinned)
                    continue;
                if (!resadmin && !isOwner && (entry.isHidden() || !entry.isListed()))
                    continue;
                break;
            case ALL:
            default:
                if (!resadmin && (!entry.isListed() || entry.isHidden())) {
                    if (!(isOwner && plugin.getConfigManager().isResidenceGuiShowHiddenToOwner()))
                        continue;
                }
                break;
            }
            filtered.add(entry);
        }

        Comparator<RemoteResidenceEntry> comparator;
        switch (sort) {
        case LIKES:
            comparator = Comparator.<RemoteResidenceEntry>comparingInt(e -> effectiveLikes(metaMgr, e)).reversed()
                    .thenComparing(RemoteResidenceEntry::getResName, String.CASE_INSENSITIVE_ORDER);
            break;
        case NAME:
            comparator = Comparator.comparing(RemoteResidenceEntry::getResName, String.CASE_INSENSITIVE_ORDER);
            break;
        case NEWEST:
            comparator = Comparator.comparingLong(RemoteResidenceEntry::getCreateTime).reversed()
                    .thenComparing(RemoteResidenceEntry::getResName, String.CASE_INSENSITIVE_ORDER);
            break;
        case DEFAULT:
        default:
            comparator = Comparator.<RemoteResidenceEntry>comparingInt(e -> (metaMgr != null && metaMgr.isPinned(viewerUuid, e.getServerName(), e.getResName())) ? 0 : 1)
                    .thenComparing(Comparator.<RemoteResidenceEntry>comparingInt(e -> effectiveLikes(metaMgr, e)).reversed())
                    .thenComparing(RemoteResidenceEntry::getResName, String.CASE_INSENSITIVE_ORDER);
            break;
        }
        filtered.sort(comparator);
        return filtered;
    }

    private static int effectiveLikes(ResidenceGuiMetaManager metaMgr, RemoteResidenceEntry entry) {
        if (metaMgr == null)
            return entry.getLikes();
        if (metaMgr.hasLikeData(entry.getServerName(), entry.getResName()) || entry.isLocal()
                || (Residence.getInstance() != null && Residence.getInstance().getDatabaseStorageManager() != null
                        && Residence.getInstance().getDatabaseStorageManager().isInitialized())) {
            return metaMgr.getLikes(entry.getServerName(), entry.getResName());
        }
        return entry.getLikes();
    }

    private ItemStack buildEntryItem(Player viewer, RemoteResidenceEntry entry, boolean resadmin) {
        ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
        boolean pinned = metaMgr != null && metaMgr.isPinned(viewer.getUniqueId(), entry.getServerName(), entry.getResName());
        boolean liked = metaMgr != null && metaMgr.isLiked(viewer.getUniqueId(), entry.getServerName(), entry.getResName());
        int likes = effectiveLikes(metaMgr, entry);
        boolean canManage = resadmin || entry.isOwner(viewer.getUniqueId());
        boolean showCross = plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled();

        CMIMaterial iconMat = resolveIconMaterial(entry.getIcon());
        ItemStack stack = iconMat.newItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String namePrefix = pinned ? "&6★ " : "&e";
            String serverLabel = showCross ? displayServerName(entry.getServerName()) : "";
            String serverSuffix = showCross ? " &8[&b" + serverLabel + "&8]" : "";
            meta.setDisplayName(CMIChatColor.translate(namePrefix + entry.getDisplayName() + serverSuffix));

            List<String> lore = new ArrayList<>();
            if (entry.getAlias() != null && !entry.getAlias().equalsIgnoreCase(entry.getResName())) {
                lore.add(lm.Gui_Entry_Id.getMessage(entry.getResName()));
            }
            lore.add(formatOwnerLoreLine(entry.getServerName(), entry.getResName(), entry.getResUuid(), entry.getOwnerName()));
            String seenLine = formatLastSeenLoreLine(entry, false);
            if (seenLine != null) {
                lore.add(seenLine);
            }
            if (showCross) {
                String tag = entry.isLocal() ? lm.Gui_Server_Current.getMessage() : lm.Gui_Server_Remote.getMessage();
                lore.add(lm.Gui_Entry_Server.getMessage(serverLabel, tag));
            }
            lore.add(lm.Gui_Entry_World.getMessage(
                    entry.getWorldName(),
                    (int) Math.floor(entry.getTpX()),
                    (int) Math.floor(entry.getTpY()),
                    (int) Math.floor(entry.getTpZ())));

            if (entry.getDescription() != null && !entry.getDescription().isEmpty()) {
                lore.add("");
                lore.add(CMIChatColor.translate("&f" + entry.getDescription()));
            }

            lore.add("");
            if (plugin.getConfigManager().isResidenceGuiAllowLike()) {
                lore.add(lm.Gui_Entry_Likes.getMessage(liked ? "&d♥ " : "&7♡ ", likes));
            }
            if (canManage) {
                String statusStr = (entry.isListed() && !entry.isHidden())
                        ? lm.Gui_Entry_StatusPublic.getMessage()
                        : lm.Gui_Entry_StatusHidden.getMessage();
                lore.add(lm.Gui_Entry_Status.getMessage(statusStr));
            }
            String tpStr = (entry.isTpPublic() || canManage)
                    ? lm.Gui_Entry_TeleportAvailable.getMessage()
                    : lm.Gui_Entry_TeleportRestricted.getMessage();
            lore.add(lm.Gui_Entry_Teleport.getMessage(tpStr));

            lore.add("");
            lore.add(lm.Gui_Entry_ClickTeleport.getMessage());
            if (plugin.getConfigManager().isResidenceGuiAllowPin()) {
                lore.add(pinned ? lm.Gui_Entry_ClickUnpin.getMessage() : lm.Gui_Entry_ClickPin.getMessage());
            }
            if (canManage) {
                lore.add(lm.Gui_Entry_ClickManage.getMessage());
                if (plugin.getConfigManager().isResidenceGuiAllowLike()) {
                    lore.add(liked ? lm.Gui_Entry_ClickShiftUnlike.getMessage() : lm.Gui_Entry_ClickShiftLike.getMessage());
                }
            } else if (plugin.getConfigManager().isResidenceGuiAllowLike()) {
                lore.add(liked ? lm.Gui_Entry_ClickUnlike.getMessage() : lm.Gui_Entry_ClickLike.getMessage());
            }

            meta.setLore(lore);
            if (pinned && CMIEnchantEnum.UNBREAKING.getEnchantment() != null) {
                meta.addEnchant(CMIEnchantEnum.UNBREAKING.getEnchantment(), 1, true);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildBatchEntryItem(Player viewer, RemoteResidenceEntry entry, boolean isSelected) {
        boolean showCross = plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled();
        CMIMaterial iconMat = resolveIconMaterial(entry.getIcon());
        ItemStack stack = iconMat.newItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String prefix = isSelected ? lm.Gui_Batch_SelectedPrefix.getMessage() : "&e";
            String serverLabel = showCross ? displayServerName(entry.getServerName()) : "";
            String serverSuffix = showCross ? " &8[&b" + serverLabel + "&8]" : "";
            meta.setDisplayName(CMIChatColor.translate(prefix + entry.getDisplayName() + serverSuffix));

            List<String> lore = new ArrayList<>();
            if (entry.getAlias() != null && !entry.getAlias().equalsIgnoreCase(entry.getResName())) {
                lore.add(lm.Gui_Entry_Id.getMessage(entry.getResName()));
            }
            lore.add(formatOwnerLoreLine(entry.getServerName(), entry.getResName(), entry.getResUuid(), entry.getOwnerName()));
            String seenLine = formatLastSeenLoreLine(entry, false);
            if (seenLine != null) {
                lore.add(seenLine);
            }
            lore.add(lm.Gui_Entry_World.getMessage(
                    entry.getWorldName(),
                    (int) Math.floor(entry.getTpX()),
                    (int) Math.floor(entry.getTpY()),
                    (int) Math.floor(entry.getTpZ())));

            ClaimedResidence localRes = entry.isLocal() ? plugin.getResidenceManager().getByName(entry.getResName()) : null;
            String hiddenVal;
            if (localRes != null) {
                Boolean rawHidden = localRes.getPermissions().getFlags().get(Flags.hidden.toString());
                hiddenVal = rawHidden == null ? lm.General_Removed.getMessage()
                        : (rawHidden ? lm.General_True.getMessage() : lm.General_False.getMessage());
            } else {
                hiddenVal = entry.isHidden() ? lm.General_True.getMessage() : lm.General_False.getMessage();
            }
            lore.add("");
            lore.add(lm.Gui_Entry_FlagHiddenState.getMessage(hiddenVal));
            String statusStr = (entry.isListed() && !entry.isHidden())
                    ? lm.Gui_Entry_StatusPublic.getMessage()
                    : lm.Gui_Entry_StatusHidden.getMessage();
            lore.add(lm.Gui_Entry_Status.getMessage(statusStr));

            lore.add("");
            if (entry.isLocal()) {
                lore.add(lm.Gui_Batch_CardLeftSelect.getMessage());
                lore.add(lm.Gui_Batch_CardRightTp.getMessage());
                lore.add(lm.Gui_Batch_CardShiftManage.getMessage());
            } else {
                lore.add(lm.Gui_Batch_CardRemoteNotice.getMessage(displayServerName(entry.getServerName())));
                lore.add(lm.Gui_Batch_CardRightTp.getMessage());
            }

            List<String> colored = new ArrayList<>(lore.size());
            for (String line : lore) {
                colored.add(CMIChatColor.translate(line));
            }
            meta.setLore(colored);
            if (isSelected && CMIEnchantEnum.UNBREAKING.getEnchantment() != null) {
                meta.addEnchant(CMIEnchantEnum.UNBREAKING.getEnchantment(), 1, true);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public void openBatchFlagPickerGui(Player player, int page, Runnable backAction) {
        if (player == null || !player.isOnline() || !canUseAdminBatch(player, true))
            return;

        Set<String> selectedKeys = getOrCreateBatchSelection(player.getUniqueId());
        List<ClaimedResidence> selectedResidences = resolveSelectedLocalResidences(selectedKeys);
        if (selectedResidences.isEmpty()) {
            lm.Gui_Batch_Msg_NoneSelected.sendMessage(player);
            if (backAction != null)
                backAction.run();
            return;
        }

        List<String> flagNames = new ArrayList<>();
        Map<String, Boolean> globalFlags = plugin.getPermissionManager().getAllFlags().getFlags();
        for (String fname : globalFlags.keySet()) {
            if ("admin".equalsIgnoreCase(fname))
                continue;
            Flags f = Flags.getFlag(fname);
            if (f != null && !f.isGlobalyEnabled())
                continue;
            flagNames.add(fname);
        }
        flagNames.sort(String.CASE_INSENSITIVE_ORDER);

        int maxPages = Math.max(1, (flagNames.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, flagNames.size(), safePage);

        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openBatchFlagPickerGui(player, newPage, backAction);
            }
        };
        gui.setTitle(lm.Gui_Batch_Picker_Title.getMessage(selectedResidences.size(), pi.getCurrentPage(), Math.max(1, pi.getTotalPages())));
        gui.setInvSize(GUIRows.r6);

        int slot = 0;
        for (String flagKey : flagNames) {
            if (pi.isContinue())
                continue;
            if (pi.isBreak())
                break;

            final String targetFlag = flagKey;
            ItemStack flagItem = buildBatchFlagPickerItem(targetFlag, selectedResidences);
            CMIGuiButton btn = new CMIGuiButton(slot++, flagItem) {
                @Override
                public void click(GUIClickType type) {
                    FlagState targetState;
                    if (type == GUIClickType.Left) {
                        targetState = FlagState.TRUE;
                    } else if (type == GUIClickType.Right) {
                        targetState = FlagState.FALSE;
                    } else {
                        targetState = FlagState.NEITHER;
                    }
                    applyBatchFlagOnMain(player, selectedKeys, targetFlag, targetState);
                    openBatchFlagPickerGui(player, pi.getCurrentPage(), backAction);
                }
            };
            btn.hideItemFlags();
            gui.addButton(btn);
        }

        // Slot 46: Back to Batch Browser
        gui.addButton(new CMIGuiButton(46, buildControlItem(
                CMIMaterial.ARROW,
                lm.Gui_Button_Back_Name.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null)
                    backAction.run();
                else
                    player.closeInventory();
            }
        });

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    private ItemStack buildBatchFlagPickerItem(String flagKey, List<ClaimedResidence> selectedResidences) {
        int trueCount = 0;
        int falseCount = 0;
        int unsetCount = 0;
        for (ClaimedResidence res : selectedResidences) {
            Boolean v = res.getPermissions().getFlags().get(flagKey);
            if (v == null)
                unsetCount++;
            else if (v)
                trueCount++;
            else
                falseCount++;
        }

        FlagState dominant = FlagState.NEITHER;
        if (!selectedResidences.isEmpty() && trueCount == selectedResidences.size()) {
            dominant = FlagState.TRUE;
        } else if (!selectedResidences.isEmpty() && falseCount == selectedResidences.size()) {
            dominant = FlagState.FALSE;
        }

        ItemStack stack = plugin.getConfigManager().getGuiBottonStates(dominant).clone();
        FlagData flagData = plugin.getFlagUtilManager().getFlagData();
        if (flagData != null && flagData.contains(flagKey)) {
            stack = flagData.getItem(flagKey).clone();
        }

        Flags flagEnum = Flags.getFlag(flagKey);
        String displayKey = flagEnum != null ? flagEnum.getName() : flagKey;
        ConfigurationSection customLocale = flagEnum == null ? plugin.getLocaleManager().getFlagLocale(displayKey) : null;

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String translated = customLocale == null ? null : customLocale.getString("Translated");
            if (translated != null) {
                meta.setDisplayName(CMIChatColor.translate(lm.Gui_Flag_NameColor.getMessage() + translated));
            } else {
                meta.setDisplayName(CMIChatColor.translate(lm.Gui_Flag_NameColor.getMessage() + CMIText.firstToUpperCase(displayKey)));
            }

            List<String> lore = new ArrayList<>();
            lore.add(CMIChatColor.translate(lm.Gui_Batch_Picker_Stats.getMessage(trueCount, falseCount, unsetCount)));
            if (flagEnum != null && flagEnum.getDesc() != null && !flagEnum.getDesc().isEmpty()) {
                int words = 0;
                StringBuilder line = new StringBuilder();
                for (String w : flagEnum.getDesc().split(" ")) {
                    line.append(w).append(" ");
                    if (++words > 5) {
                        lore.add(ChatColor.YELLOW + line.toString().trim());
                        line.setLength(0);
                        words = 0;
                    }
                }
                if (line.length() > 0) {
                    lore.add(ChatColor.YELLOW + line.toString().trim());
                }
            } else if (customLocale != null && customLocale.getString("Description") != null) {
                lore.add(CMIChatColor.translate("&e" + customLocale.getString("Description")));
            }
            lore.add("");
            for (String act : lm.Gui_Actions.getMessageList()) {
                lore.add(CMIChatColor.translate(act));
            }
            meta.setLore(lore);
            if (dominant == FlagState.TRUE && CMIEnchantEnum.UNBREAKING.getEnchantment() != null) {
                meta.addEnchant(CMIEnchantEnum.UNBREAKING.getEnchantment(), 1, true);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<ClaimedResidence> resolveSelectedLocalResidences(Set<String> selectedKeys) {
        if (selectedKeys == null || selectedKeys.isEmpty())
            return Collections.emptyList();
        List<ClaimedResidence> out = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String key : new ArrayList<>(selectedKeys)) {
            ClaimedResidence res = plugin.getResidenceManager().getByName(key);
            if (res == null) {
                stale.add(key);
            } else {
                out.add(res);
            }
        }
        if (!stale.isEmpty()) {
            selectedKeys.removeAll(stale);
        }
        return out;
    }

    private void applyBatchFlagOnMain(Player admin, Set<String> selectedKeys, String flagName, FlagState state) {
        if (!canUseAdminBatch(admin, true)) {
            lm.General_AdminOnly.sendMessage(admin);
            return;
        }
        List<ClaimedResidence> targets = resolveSelectedLocalResidences(selectedKeys);
        if (targets.isEmpty()) {
            lm.Gui_Batch_Msg_NoneSelected.sendMessage(admin);
            return;
        }

        int applied = 0;
        int skippedFrozen = 0;
        for (ClaimedResidence res : targets) {
            if (plugin.getAuctionManager() != null && plugin.getAuctionManager().isResidenceInAuction(res)) {
                skippedFrozen++;
                continue;
            }
            if (res.getPermissions().setFlag(admin, flagName, state, true, false)) {
                applied++;
            }
        }

        if (applied > 0) {
            plugin.saveResidenceDataNow();
            if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                plugin.getCrossServerManager().triggerAsyncSync();
            }
            if (plugin.getConfigManager().isConsoleLogsShowFlagChanges()) {
                lm.consoleMessage(admin.getName() + " batch-applied flag " + flagName + "=" + state.getName()
                        + " to " + applied + " residence(s).");
            }
        }

        String stateLabel = state == FlagState.TRUE ? lm.General_True.getMessage()
                : (state == FlagState.FALSE ? lm.General_False.getMessage() : lm.General_Removed.getMessage());
        String skippedSuffix = skippedFrozen > 0 ? lm.Gui_Batch_Msg_SkippedFrozen.getMessage(skippedFrozen) : "";
        lm.Gui_Batch_Msg_Applied.sendMessage(admin, flagName, stateLabel, applied, skippedSuffix);
    }

    private RemoteResidenceEntry refreshEntry(RemoteResidenceEntry old) {
        if (old.isLocal()) {
            ClaimedResidence local = plugin.getResidenceManager().getByName(old.getResName());
            if (local != null) {
                return CrossServerManager.toEntry(local, old.getServerName(), plugin.getGuiMetaManager());
            }
        }
        ResidenceGuiMetadata meta = plugin.getGuiMetaManager().getMetadata(old.getServerName(), old.getResName());
        return new RemoteResidenceEntry(
                old.getServerName(), old.getResKey(), old.getResName(), old.getResUuid(),
                old.getWorldName(), old.getOwnerUuid(), old.getOwnerName(),
                old.getTpX(), old.getTpY(), old.getTpZ(), old.getTpYaw(), old.getTpPitch(),
                old.isHidden(), old.isTpPublic(), meta.isListed(), meta.getAlias(), meta.getDescription(), meta.getIcon(),
                effectiveLikes(plugin.getGuiMetaManager(), old), old.getCreateTime(), old.isLocal());
    }

    private CMIMaterial resolveIconMaterial(String iconName) {
        if (iconName != null && !iconName.isEmpty()) {
            CMIMaterial m = CMIMaterial.get(iconName);
            if (m != null && !m.isNone() && !m.isAir())
                return m;
        }
        CMIMaterial def = CMIMaterial.get(plugin.getConfigManager().getResidenceGuiDefaultIcon());
        if (def != null && !def.isNone() && !def.isAir())
            return def;
        return CMIMaterial.OAK_SIGN;
    }

    private static ItemStack buildPlayerHeadItem(UUID uuid, String name, List<String> loreLines) {
        ItemStack stack = CMIMaterial.PLAYER_HEAD.newItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta instanceof SkullMeta && uuid != null) {
                try {
                    OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                    if (off != null) {
                        ((SkullMeta) meta).setOwningPlayer(off);
                    }
                } catch (Throwable ignored) {
                }
            }
            meta.setDisplayName(CMIChatColor.translate(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(CMIChatColor.translate(line));
                }
                meta.setLore(lore);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack buildControlItem(CMIMaterial material, String name, String... loreLines) {
        return buildControlItem(material, name, loreLines == null ? null : Arrays.asList(loreLines));
    }

    private static ItemStack buildControlItem(CMIMaterial material, String name, List<String> loreLines) {
        ItemStack stack = material.newItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(CMIChatColor.translate(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(CMIChatColor.translate(line));
                }
                meta.setLore(lore);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String displayServerName(String serverName) {
        return plugin.getConfigManager().getCrossServerDisplayName(serverName);
    }

    private String buildBrowserTitle(FilterMode filter, String serverFilter, String ownerFilterName, PageInfo pi) {
        int totalPages = Math.max(1, pi.getTotalPages());
        if (ownerFilterName != null && !ownerFilterName.isEmpty()) {
            return lm.Gui_Browser_TitlePlayer.getMessage(ownerFilterName, pi.getCurrentPage(), totalPages);
        }
        String srv = serverFilter == null ? "" : " [" + displayServerName(serverFilter) + "]";
        return lm.Gui_Browser_Title.getMessage(filterLabel(filter), srv, pi.getCurrentPage(), totalPages);
    }

    private static String filterLabel(FilterMode filter) {
        switch (filter) {
        case MINE:
            return lm.Gui_Filter_Mine.getMessage();
        case PINNED:
            return lm.Gui_Filter_Pinned.getMessage();
        case ALL:
        default:
            return lm.Gui_Filter_All.getMessage();
        }
    }

    private static String sortLabel(SortMode sort) {
        switch (sort) {
        case LIKES:
            return lm.Gui_Sort_Likes.getMessage();
        case NAME:
            return lm.Gui_Sort_Name.getMessage();
        case NEWEST:
            return lm.Gui_Sort_Newest.getMessage();
        case DEFAULT:
        default:
            return lm.Gui_Sort_Default.getMessage();
        }
    }

    private static String resolveDisplayPlayerName(UUID uuid) {
        if (uuid == null)
            return "Unknown";
        String name = ResidencePlayer.getName(uuid);
        if (name != null && !name.isEmpty())
            return name;
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isEmpty())
            return off.getName();
        return uuid.toString().substring(0, 8);
    }

    private static String nextServerFilter(String current, List<String> servers) {
        if (servers.isEmpty())
            return null;
        if (current == null)
            return servers.get(0);
        int idx = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(current)) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx + 1 >= servers.size())
            return null;
        return servers.get(idx + 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatEdit(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingEdit edit = pendingEdits.remove(player.getUniqueId());
        if (edit == null)
            return;

        if (System.currentTimeMillis() > edit.expiresAt) {
            return;
        }

        event.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            if ("cancel".equalsIgnoreCase(msg)) {
                lm.Gui_Msg_EditCancelled.sendMessage(player);
                return;
            }

            if (edit.field == EditField.ORIGINAL_OWNER) {
                if (!canUseAdminBatch(player, edit.resadmin)) {
                    lm.General_AdminOnly.sendMessage(player);
                    return;
                }
                cleanup.handleSetOriginalOwner(plugin, player, edit.resName, msg, null, false, edit.reopenCallback);
                return;
            }

            if (edit.field == EditField.ADD_MEMBER) {
                ClaimedResidence liveRes = plugin.getResidenceManager().getByName(edit.resName);
                if (liveRes == null) {
                    lm.Invalid_Residence.sendMessage(player);
                    return;
                }
                if (!edit.resadmin && !liveRes.getPermissions().hasResidencePermission(player, false)) {
                    lm.General_NoPermission.sendMessage(player);
                    return;
                }
                UUID targetUuid = resolvePlayerUuidByName(msg);
                if (targetUuid == null) {
                    lm.Invalid_Player.sendMessage(player);
                    return;
                }
                plugin.getFlagUtilManager().openPsetFlagGui(player, targetUuid, liveRes, edit.resadmin, 1);
                return;
            }

            if (!verifyCanStillManage(player, edit.serverName, edit.resName, edit.resadmin)) {
                return;
            }
            String value = "clear".equalsIgnoreCase(msg) ? null : msg;
            if (value != null) {
                int limit = edit.field == EditField.ALIAS ? MAX_ALIAS_LENGTH : MAX_DESCRIPTION_LENGTH;
                if (value.length() > limit)
                    value = value.substring(0, limit);
            }
            ResidenceGuiMetadata meta = plugin.getGuiMetaManager().getOrCreateMetadata(edit.serverName, edit.resName);
            if (edit.field == EditField.ALIAS) {
                meta.setAlias(value);
                if (value == null)
                    lm.Gui_Msg_AliasCleared.sendMessage(player, edit.resName);
                else
                    lm.Gui_Msg_AliasUpdated.sendMessage(player, edit.resName, value);
            } else {
                meta.setDescription(value);
                if (value == null)
                    lm.Gui_Msg_DescCleared.sendMessage(player, edit.resName);
                else
                    lm.Gui_Msg_DescUpdated.sendMessage(player, edit.resName, value);
            }
            plugin.getGuiMetaManager().updateMetadata(edit.serverName, edit.resName, meta);
            if (edit.reopenCallback != null && player.isOnline()) {
                edit.reopenCallback.run();
            }
        }, null);
    }

    private static UUID resolvePlayerUuidByName(String name) {
        if (name == null || name.trim().isEmpty())
            return null;
        String trimmed = name.trim();
        Player online = Bukkit.getPlayerExact(trimmed);
        if (online != null)
            return online.getUniqueId();
        ResidencePlayer rp = ResidencePlayer.get(trimmed);
        if (rp != null && rp.getUniqueId() != null)
            return rp.getUniqueId();
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(trimmed);
        if (off != null && (off.hasPlayedBefore() || off.isOnline()))
            return off.getUniqueId();
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingEdits.remove(event.getPlayer().getUniqueId());
        batchSelections.remove(event.getPlayer().getUniqueId());
    }
}
