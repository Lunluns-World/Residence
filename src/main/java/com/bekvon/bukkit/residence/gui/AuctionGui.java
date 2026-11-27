package com.bekvon.bukkit.residence.gui;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.BidEntry;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.NotificationRecord;
import com.bekvon.bukkit.residence.auction.AuctionManager;
import com.bekvon.bukkit.residence.auction.core.AuctionCore;
import com.bekvon.bukkit.residence.auction.core.AuctionPolicy;
import com.bekvon.bukkit.residence.auction.core.AuctionRecord;
import com.bekvon.bukkit.residence.auction.core.AuctionType;
import com.bekvon.bukkit.residence.auction.core.ClearMode;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.crossserver.CrossServerManager;
import com.bekvon.bukkit.residence.crossserver.RemoteResidenceEntry;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Container.PageInfo;
import net.Zrips.CMILib.GUI.CMIGui;
import net.Zrips.CMILib.GUI.CMIGuiButton;
import net.Zrips.CMILib.GUI.GUIManager.GUIClickType;
import net.Zrips.CMILib.GUI.GUIManager.GUIRows;
import net.Zrips.CMILib.Items.CMIMaterial;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 領地拍賣系統互動式 GUI（與 /res gui、/res market 統一風格）。
 * 包含：拍賣瀏覽頁、強制條款同意頁、開拍設定頁、拍賣詳情與出價頁、出價二次確認頁、以及「我知道了」待確認通知頁。
 */
public class AuctionGui implements Listener {

    public enum AuctionFilter {
        ALL,
        ARCHIVED,
        PLAYER,
        PARTICIPATED;

        public String getLabel() {
            switch (this) {
            case ARCHIVED:
                return lm.Gui_Auction_Filter_Archived.getMessage();
            case PLAYER:
                return lm.Gui_Auction_Filter_Player.getMessage();
            case PARTICIPATED:
                return lm.Gui_Auction_Filter_Participated.getMessage();
            case ALL:
            default:
                return lm.Gui_Auction_Filter_All.getMessage();
            }
        }

        public AuctionFilter next() {
            AuctionFilter[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    public enum AuctionSort {
        ENDING_SOON,
        PRICE_ASC,
        PRICE_DESC;

        public String getLabel() {
            switch (this) {
            case PRICE_ASC:
                return lm.Gui_Auction_Sort_PriceAsc.getMessage();
            case PRICE_DESC:
                return lm.Gui_Auction_Sort_PriceDesc.getMessage();
            case ENDING_SOON:
            default:
                return lm.Gui_Auction_Sort_EndingSoon.getMessage();
            }
        }

        public AuctionSort next() {
            AuctionSort[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    public static final class DraftConfig {
        public long startPrice;
        public long minIncrement;
        public long durationMinutes;
        public String description = "";
        public ClearMode clearMode = ClearMode.NONE;

        public DraftConfig(AuctionPolicy policy) {
            this.startPrice = Math.max(100L, policy.getMinStartPrice());
            this.minIncrement = Math.max(1L, policy.getDefaultMinIncrement());
            this.durationMinutes = Math.max(policy.getMinDurationMinutes(), 24L * 60L);
            long maxMinutes = policy.getMaxDurationHours() * 60L;
            if (this.durationMinutes > maxMinutes) {
                this.durationMinutes = maxMinutes;
            }
        }
    }

    private enum PromptType {
        DRAFT_START_PRICE,
        DRAFT_MIN_INCREMENT,
        DRAFT_DURATION_HOURS,
        DRAFT_DESCRIPTION,
        CUSTOM_BID,
        UPDATE_MIN_INCREMENT
    }

    private static final class PendingPrompt {
        final PromptType type;
        final String resName;
        final DraftConfig draft;
        final long auctionId;
        final boolean adminMode;
        final Runnable backAction;
        final long expiresAt;

        PendingPrompt(PromptType type, String resName, DraftConfig draft, long auctionId, boolean adminMode, Runnable backAction) {
            this.type = type;
            this.resName = resName;
            this.draft = draft;
            this.auctionId = auctionId;
            this.adminMode = adminMode;
            this.backAction = backAction;
            this.expiresAt = System.currentTimeMillis() + 60_000L;
        }
    }

    private static final class OpenViewState {
        final AuctionFilter filter;
        final AuctionSort sort;
        final boolean resadmin;
        final int page;

        OpenViewState(AuctionFilter filter, AuctionSort sort, boolean resadmin, int page) {
            this.filter = filter;
            this.sort = sort;
            this.resadmin = resadmin;
            this.page = page;
        }
    }

    private static final long[] DURATION_PRESET_MINUTES = new long[] { 120L, 360L, 720L, 1440L, 4320L, 10080L };

    private final Residence plugin;
    private final Map<UUID, PendingPrompt> pendingPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, OpenViewState> openBrowsers = new ConcurrentHashMap<>();

    public AuctionGui(Residence plugin) {
        this.plugin = plugin;
    }

    /**
     * 收到 Redis 拍賣異動事件時，即時刷新所有正在瀏覽拍賣清單的玩家介面。
     */
    public void refreshOpenViewersAsync() {
        if (openBrowsers.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, OpenViewState> e : openBrowsers.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                openBrowsers.remove(e.getKey());
                continue;
            }
            OpenViewState st = e.getValue();
            p.getScheduler().run(plugin, t -> {
                if (p.isOnline() && openBrowsers.containsKey(p.getUniqueId())) {
                    openBrowser(p, st.filter, st.sort, st.resadmin, st.page);
                }
            }, null);
        }
    }

    /**
     * 開啟拍賣瀏覽主介面（/res auction）。
     */
    public void openBrowser(Player player, AuctionFilter filter, AuctionSort sort, boolean resadmin, int page) {
        if (player == null || !player.isOnline()) {
            return;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        if (mgr == null || !mgr.isEnabled()) {
            lm.Auction_Disabled.sendMessage(player);
            return;
        }
        if (!plugin.getConfigManager().isResidenceGuiEnabled()) {
            lm.Gui_Disabled.sendMessage(player);
            return;
        }

        AuctionFilter effFilter = filter == null ? AuctionFilter.ALL : filter;
        AuctionSort effSort = sort == null ? AuctionSort.ENDING_SOON : sort;
        UUID playerUuid = player.getUniqueId();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Set<Long> participatedIds = Collections.emptySet();
            int unackedCount = 0;
            try {
                if (effFilter == AuctionFilter.PARTICIPATED && mgr.getDatabase() != null) {
                    participatedIds = mgr.getDatabase().loadParticipantAuctionIds(playerUuid);
                }
                if (mgr.getDatabase() != null) {
                    unackedCount = mgr.getDatabase().countUnackedNotifications(playerUuid);
                }
            } catch (SQLException ignored) {
            }

            List<AuctionRecord> items = filterAndSort(mgr.getCachedActiveAuctions(), effFilter, effSort, participatedIds);
            final int unread = unackedCount;

            player.getScheduler().run(plugin, t -> {
                if (!player.isOnline()) {
                    return;
                }
                renderBrowserOnPlayerThread(player, items, effFilter, effSort, resadmin, page, unread);
            }, null);
        });
    }

    private void renderBrowserOnPlayerThread(
            Player player, List<AuctionRecord> items, AuctionFilter filter, AuctionSort sort,
            boolean resadmin, int page, int unreadNotifications) {
        int maxPages = Math.max(1, (items.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, items.size(), safePage);

        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openBrowser(player, filter, sort, resadmin, newPage);
            }
        };
        gui.setTitle(lm.Gui_Auction_BrowserTitle.getMessage(filter.getLabel(), pi.getCurrentPage(), Math.max(1, pi.getTotalPages())));
        gui.setInvSize(GUIRows.r6);

        AuctionManager mgr = plugin.getAuctionManager();
        Runnable reopen = () -> openBrowser(player, filter, sort, resadmin, pi.getCurrentPage());

        if (items.isEmpty()) {
            gui.addButton(new CMIGuiButton(22, buildItem(
                    CMIMaterial.PAPER,
                    lm.Gui_Auction_EmptyName.getMessage(),
                    lm.Gui_Auction_EmptyLore.getMessageList())));
        } else {
            int slot = 0;
            for (AuctionRecord rec : items) {
                if (pi.isContinue()) {
                    continue;
                }
                if (pi.isBreak()) {
                    break;
                }
                final AuctionRecord target = rec;
                CMIGuiButton btn = new CMIGuiButton(slot++, buildAuctionCard(target, mgr)) {
                    @Override
                    public void click(GUIClickType type) {
                        openBrowsers.remove(player.getUniqueId());
                        if (type == GUIClickType.Right) {
                            player.closeInventory();
                            teleportToAuctionResidence(player, target, resadmin);
                            return;
                        }
                        openDetailGui(player, target.getId(), resadmin, reopen);
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
            }
        }

        // Slot 46: 分類篩選
        gui.addButton(new CMIGuiButton(46, buildItem(
                CMIMaterial.HOPPER,
                lm.Gui_Auction_Btn_FilterName.getMessage(filter.getLabel()),
                lm.Gui_Auction_Btn_FilterLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBrowser(player, filter.next(), sort, resadmin, 1);
            }
        });

        // Slot 48: 排序方式
        gui.addButton(new CMIGuiButton(48, buildItem(
                CMIMaterial.COMPARATOR,
                lm.Gui_Auction_Btn_SortName.getMessage(sort.getLabel()),
                lm.Gui_Auction_Btn_SortLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBrowser(player, filter, sort.next(), resadmin, 1);
            }
        });

        // Slot 47: 待確認通知（「我知道了」，避開 Slot 49 分頁顯示）
        gui.addButton(new CMIGuiButton(47, buildItem(
                unreadNotifications > 0 ? CMIMaterial.BELL : CMIMaterial.PAPER,
                lm.Gui_Auction_Btn_NotifyName.getMessage(unreadNotifications),
                lm.Gui_Auction_Btn_NotifyLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBrowsers.remove(player.getUniqueId());
                openNotificationsGui(player, reopen);
            }
        });

        // Slot 50: 返回領地列表 GUI
        gui.addButton(new CMIGuiButton(50, buildItem(
                CMIMaterial.OAK_SIGN,
                lm.Gui_Auction_Btn_BackResGuiName.getMessage(),
                lm.Gui_Auction_Btn_BackResGuiLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBrowsers.remove(player.getUniqueId());
                plugin.getResidenceListGui().openBrowser(player, ResidenceListGui.FilterMode.ALL, ResidenceListGui.SortMode.DEFAULT, null, resadmin, 1);
            }
        });

        // Slot 51: 重新整理
        gui.addButton(new CMIGuiButton(51, buildItem(
                CMIMaterial.SUNFLOWER,
                lm.Gui_Auction_Btn_RefreshName.getMessage(),
                lm.Gui_Auction_Btn_RefreshLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                Bukkit.getAsyncScheduler().runNow(plugin, t -> {
                    mgr.refreshActiveCacheNow();
                    player.getScheduler().run(plugin, t2 -> openBrowser(player, filter, sort, resadmin, pi.getCurrentPage()), null);
                });
            }
        });

        // Slot 52: 前往領地市場 GUI
        if (plugin.getConfigManager().enableEconomy()) {
            gui.addButton(new CMIGuiButton(52, buildItem(
                    CMIMaterial.EMERALD,
                    lm.Gui_Auction_Btn_MarketName.getMessage(),
                    lm.Gui_Auction_Btn_MarketLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    openBrowsers.remove(player.getUniqueId());
                    plugin.getMarketGui().openMarketBrowser(player, MarketGui.MarketFilter.ALL, MarketGui.MarketSort.PRICE_ASC, resadmin, 1);
                }
            });
        }

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        openBrowsers.put(player.getUniqueId(), new OpenViewState(filter, sort, resadmin, pi.getCurrentPage()));
        gui.open();
    }

    /**
     * 開拍前強制條款同意頁：必須點擊「我已知曉並同意上述條款」才能進入開拍設定介面。
     */
    public void openTermsAgreementGui(Player player, ClaimedResidence res, boolean adminMode, Runnable backAction) {
        if (player == null || !player.isOnline()) {
            return;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        String preErr = mgr == null ? lm.Auction_Disabled.getMessage() : mgr.checkPreconditionsForCreate(res, player, adminMode);
        if (preErr != null) {
            player.sendMessage(CMIChatColor.translate(preErr));
            return;
        }

        AuctionPolicy policy = plugin.getConfigManager().getAuctionPolicy();
        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Auction_Terms_Title.getMessage(res.getName()));
        gui.setInvSize(GUIRows.r3);

        // Slot 13: 條款說明書
        List<String> termsLore = new ArrayList<>();
        termsLore.add(lm.Gui_Auction_Terms_RuleFreeze.getMessage());
        termsLore.add(lm.Gui_Auction_Terms_RuleQuickShop.getMessage());
        termsLore.add(lm.Gui_Auction_Terms_RuleNoCancel.getMessage());
        termsLore.add(lm.Gui_Auction_Terms_RuleFeeAndFlags.getMessage(policy.getFeePercent()));
        termsLore.add(lm.Gui_Auction_Terms_RuleCooldown.getMessage(policy.getRelistCooldownHours()));
        if (!adminMode) {
            termsLore.add(lm.Gui_Auction_Terms_RuleAsIs.getMessage());
        } else {
            termsLore.add(lm.Gui_Auction_Terms_RuleAdminClear.getMessage());
        }
        gui.addButton(new CMIGuiButton(13, buildItem(
                CMIMaterial.BOOK,
                lm.Gui_Auction_Terms_InfoName.getMessage(res.getName()),
                termsLore)));

        // Slot 11: 我已知曉並同意上述條款
        gui.addButton(new CMIGuiButton(11, buildItem(
                CMIMaterial.EMERALD_BLOCK,
                lm.Gui_Auction_Terms_AgreeName.getMessage(),
                lm.Gui_Auction_Terms_AgreeLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                if (live == null) {
                    lm.Invalid_Residence.sendMessage(player);
                    player.closeInventory();
                    return;
                }
                openCreateSetupGui(player, live, new DraftConfig(policy), adminMode, backAction);
            }
        });

        // Slot 15: 取消返回
        gui.addButton(new CMIGuiButton(15, buildItem(
                CMIMaterial.BARRIER,
                lm.Gui_Auction_Terms_CancelName.getMessage(),
                lm.Gui_Auction_Terms_CancelLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) {
                    backAction.run();
                } else {
                    player.closeInventory();
                }
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    /**
     * 開拍參數設定頁（僅可從條款同意頁點擊同意後進入）。
     */
    public void openCreateSetupGui(Player player, ClaimedResidence res, DraftConfig draft, boolean adminMode, Runnable backAction) {
        if (player == null || !player.isOnline() || res == null) {
            return;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        AuctionPolicy policy = plugin.getConfigManager().getAuctionPolicy();
        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Auction_Create_Title.getMessage(res.getName()));
        gui.setInvSize(GUIRows.r4);

        // Slot 10: 起標價
        gui.addButton(new CMIGuiButton(10, buildItem(
                CMIMaterial.GOLD_INGOT,
                lm.Gui_Auction_Create_StartPriceName.getMessage(mgr.formatMoney(draft.startPrice)),
                Arrays.asList(
                        lm.Gui_Auction_Create_MinAllowed.getMessage(mgr.formatMoney(policy.getMinStartPrice())),
                        lm.Gui_Auction_Create_ClickChatInput.getMessage(),
                        lm.Gui_Auction_Create_RightClickStep.getMessage("+1,000")))) {
            @Override
            public void click(GUIClickType type) {
                if (type == GUIClickType.Right) {
                    draft.startPrice += 1000L;
                    openCreateSetupGui(player, res, draft, adminMode, backAction);
                } else {
                    startPrompt(player, PromptType.DRAFT_START_PRICE, res.getName(), draft, 0L, adminMode, backAction,
                            lm.Auction_Prompt_StartPrice.getMessage(mgr.formatMoney(policy.getMinStartPrice())));
                }
            }
        });

        // Slot 11: 最低加價
        gui.addButton(new CMIGuiButton(11, buildItem(
                CMIMaterial.GOLD_NUGGET,
                lm.Gui_Auction_Create_MinIncName.getMessage(mgr.formatMoney(draft.minIncrement)),
                Arrays.asList(
                        lm.Gui_Auction_Create_ClickChatInput.getMessage(),
                        lm.Gui_Auction_Create_RightClickStep.getMessage("+100")))) {
            @Override
            public void click(GUIClickType type) {
                if (type == GUIClickType.Right) {
                    draft.minIncrement += 100L;
                    openCreateSetupGui(player, res, draft, adminMode, backAction);
                } else {
                    startPrompt(player, PromptType.DRAFT_MIN_INCREMENT, res.getName(), draft, 0L, adminMode, backAction,
                            lm.Auction_Prompt_MinIncrement.getMessage());
                }
            }
        });

        // Slot 12: 拍賣時長
        gui.addButton(new CMIGuiButton(12, buildItem(
                CMIMaterial.CLOCK,
                lm.Gui_Auction_Create_DurationName.getMessage(mgr.formatDurationShort(draft.durationMinutes * 60_000L)),
                Arrays.asList(
                        lm.Gui_Auction_Create_DurationRange.getMessage(policy.getMinDurationMinutes(), policy.getMaxDurationHours()),
                        lm.Gui_Auction_Create_DurationLeftPreset.getMessage(),
                        lm.Gui_Auction_Create_DurationRightChat.getMessage()))) {
            @Override
            public void click(GUIClickType type) {
                if (type == GUIClickType.Right) {
                    startPrompt(player, PromptType.DRAFT_DURATION_HOURS, res.getName(), draft, 0L, adminMode, backAction,
                            lm.Auction_Prompt_DurationHours.getMessage(policy.getMinDurationMinutes(), policy.getMaxDurationHours()));
                } else {
                    draft.durationMinutes = nextDurationPreset(draft.durationMinutes, policy);
                    openCreateSetupGui(player, res, draft, adminMode, backAction);
                }
            }
        });

        // Slot 14: 說明文字
        String descDisplay = draft.description.isEmpty() ? lm.Gui_Manage_None.getMessage() : draft.description;
        gui.addButton(new CMIGuiButton(14, buildItem(
                CMIMaterial.WRITABLE_BOOK,
                lm.Gui_Auction_Create_DescName.getMessage(),
                Arrays.asList(
                        lm.Gui_Manage_Current.getMessage(descDisplay),
                        lm.Gui_Auction_Create_DescLeft.getMessage(),
                        lm.Gui_Auction_Create_DescRight.getMessage()))) {
            @Override
            public void click(GUIClickType type) {
                if (type == GUIClickType.Right) {
                    draft.description = "";
                    openCreateSetupGui(player, res, draft, adminMode, backAction);
                } else {
                    startPrompt(player, PromptType.DRAFT_DESCRIPTION, res.getName(), draft, 0L, adminMode, backAction,
                            lm.Auction_Prompt_Description.getMessage());
                }
            }
        });

        // Slot 16: 清空模式（僅限管理員拍賣顯示可切換；玩家拍賣固定為現況交付）
        if (adminMode) {
            CMIMaterial modeIcon = draft.clearMode == ClearMode.ALL ? CMIMaterial.TNT
                    : (draft.clearMode == ClearMode.ITEMS_ONLY ? CMIMaterial.CHEST : CMIMaterial.GRASS_BLOCK);
            gui.addButton(new CMIGuiButton(16, buildItem(
                    modeIcon,
                    lm.Gui_Auction_Create_ClearModeName.getMessage(mgr.formatClearModeLabel(draft.clearMode)),
                    lm.Gui_Auction_Create_ClearModeLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    draft.clearMode = draft.clearMode.next();
                    openCreateSetupGui(player, res, draft, true, backAction);
                }
            });
        } else {
            draft.clearMode = ClearMode.NONE;
            gui.addButton(new CMIGuiButton(16, buildItem(
                    CMIMaterial.GRASS_BLOCK,
                    lm.Gui_Auction_Create_ClearModeName.getMessage(mgr.formatClearModeLabel(ClearMode.NONE)),
                    lm.Gui_Auction_Create_PlayerAsIsLore.getMessageList())));
        }

        // Slot 29: 確認上架開始拍賣
        gui.addButton(new CMIGuiButton(29, buildItem(
                CMIMaterial.LIME_CONCRETE,
                lm.Gui_Auction_Create_ConfirmName.getMessage(),
                Arrays.asList(
                        lm.Gui_Auction_Create_SummaryPrice.getMessage(mgr.formatMoney(draft.startPrice), mgr.formatMoney(draft.minIncrement)),
                        lm.Gui_Auction_Create_SummaryDuration.getMessage(mgr.formatDurationShort(draft.durationMinutes * 60_000L)),
                        lm.Gui_Auction_Create_SummaryClearMode.getMessage(mgr.formatClearModeLabel(draft.clearMode))))) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                if (live == null) {
                    lm.Invalid_Residence.sendMessage(player);
                    return;
                }
                mgr.createAuction(player, live, draft.startPrice, draft.minIncrement, draft.durationMinutes,
                        draft.description, adminMode ? draft.clearMode : ClearMode.NONE, adminMode, outcome -> {
                            player.sendMessage(CMIChatColor.translate(outcome.message));
                            if (outcome.ok && outcome.auction != null) {
                                openDetailGui(player, outcome.auction.getId(), adminMode, () -> openBrowser(player, AuctionFilter.ALL, AuctionSort.ENDING_SOON, adminMode, 1));
                            }
                        });
            }
        });

        // Slot 33: 返回
        gui.addButton(new CMIGuiButton(33, buildItem(
                CMIMaterial.ARROW,
                lm.Gui_Button_Back_Name.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) {
                    backAction.run();
                } else {
                    player.closeInventory();
                }
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    /**
     * 拍賣詳情與喊價介面。
     */
    public void openDetailGui(Player player, long auctionId, boolean resadmin, Runnable backAction) {
        if (player == null || !player.isOnline()) {
            return;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        if (mgr == null || !mgr.isEnabled()) {
            lm.Auction_Disabled.sendMessage(player);
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            AuctionRecord rec;
            List<BidEntry> recentBids;
            try {
                rec = mgr.getDatabase().loadAuctionById(auctionId);
                recentBids = rec != null ? mgr.loadBidHistory(auctionId, 5) : Collections.emptyList();
            } catch (SQLException ex) {
                player.getScheduler().run(plugin, t -> lm.Auction_DbError.sendMessage(player), null);
                return;
            }
            if (rec == null) {
                player.getScheduler().run(plugin, t -> {
                    lm.Auction_NotFound.sendMessage(player, auctionId);
                    if (backAction != null) {
                        backAction.run();
                    }
                }, null);
                return;
            }
            final AuctionRecord target = rec;
            final List<BidEntry> bids = recentBids;
            player.getScheduler().run(plugin, t -> {
                if (player.isOnline()) {
                    renderDetailGui(player, target, bids, resadmin, backAction);
                }
            }, null);
        });
    }

    private void renderDetailGui(Player player, AuctionRecord rec, List<BidEntry> recentBids, boolean resadmin, Runnable backAction) {
        AuctionManager mgr = plugin.getAuctionManager();
        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Auction_Detail_Title.getMessage(rec.getId(), rec.getResName()));
        gui.setInvSize(GUIRows.r4);

        // Slot 4: 拍賣詳情卡（含最近出價紀錄）
        List<String> infoLore = new ArrayList<>(buildCardLore(rec, mgr));
        if (!recentBids.isEmpty()) {
            infoLore.add("");
            infoLore.add(lm.Gui_Auction_Detail_RecentBidsHeader.getMessage());
            for (BidEntry b : recentBids) {
                infoLore.add(lm.Gui_Auction_Detail_RecentBidLine.getMessage(b.bidderName, mgr.formatMoney(b.amount)));
            }
        }
        gui.addButton(new CMIGuiButton(4, buildItem(
                resolveTypeMaterial(rec.getType()),
                "&6#" + rec.getId() + " &f" + rec.getResName(),
                infoLore)));

        long minBid = AuctionCore.calculateMinRequiredBid(rec);
        long inc = Math.max(1L, rec.getMinIncrement());
        Runnable reopenDetail = () -> openDetailGui(player, rec.getId(), resadmin, backAction);

        // 快速喊價按鈕（點擊一律先進入二次確認頁）
        gui.addButton(new CMIGuiButton(10, buildItem(
                CMIMaterial.GOLD_NUGGET,
                lm.Gui_Auction_Detail_BidMinName.getMessage(mgr.formatMoney(minBid)),
                lm.Gui_Auction_Detail_BidClickLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBidConfirmGui(player, rec, minBid, resadmin, reopenDetail);
            }
        });

        long bidPlus5 = minBid + (inc * 4L);
        gui.addButton(new CMIGuiButton(11, buildItem(
                CMIMaterial.GOLD_INGOT,
                lm.Gui_Auction_Detail_BidQuickName.getMessage(mgr.formatMoney(bidPlus5)),
                lm.Gui_Auction_Detail_BidClickLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBidConfirmGui(player, rec, bidPlus5, resadmin, reopenDetail);
            }
        });

        long bidPlus10 = minBid + (inc * 9L);
        gui.addButton(new CMIGuiButton(12, buildItem(
                CMIMaterial.GOLD_BLOCK,
                lm.Gui_Auction_Detail_BidQuickName.getMessage(mgr.formatMoney(bidPlus10)),
                lm.Gui_Auction_Detail_BidClickLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                openBidConfirmGui(player, rec, bidPlus10, resadmin, reopenDetail);
            }
        });

        // Slot 13: 自訂喊價金額（聊天輸入 -> 進入確認頁）
        gui.addButton(new CMIGuiButton(13, buildItem(
                CMIMaterial.NAME_TAG,
                lm.Gui_Auction_Detail_CustomBidName.getMessage(),
                Arrays.asList(
                        lm.Gui_Auction_Detail_CustomBidMin.getMessage(mgr.formatMoney(minBid)),
                        lm.Gui_Auction_Detail_CustomBidLore.getMessage()))) {
            @Override
            public void click(GUIClickType type) {
                startPrompt(player, PromptType.CUSTOM_BID, rec.getResName(), null, rec.getId(), resadmin, reopenDetail,
                        lm.Auction_Prompt_CustomBid.getMessage(mgr.formatMoney(minBid)));
            }
        });

        // Slot 15: 傳送看房
        gui.addButton(new CMIGuiButton(15, buildItem(
                CMIMaterial.ENDER_PEARL,
                lm.Gui_Auction_Detail_TpName.getMessage(),
                lm.Gui_Auction_Detail_TpLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                teleportToAuctionResidence(player, rec, resadmin);
            }
        });

        // Slot 20 & 22: 賣方或管理員專屬管理（調整最低加價／取消拍賣）
        boolean isAdmin = resadmin || plugin.getPermissionManager().isResidenceAdmin(player);
        boolean isCreator = player.getUniqueId().equals(rec.getCreatorUuid());
        if (isAdmin || isCreator) {
            gui.addButton(new CMIGuiButton(20, buildItem(
                    CMIMaterial.ANVIL,
                    lm.Gui_Auction_Detail_SetIncName.getMessage(mgr.formatMoney(rec.getMinIncrement())),
                    lm.Gui_Auction_Detail_SetIncLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    startPrompt(player, PromptType.UPDATE_MIN_INCREMENT, rec.getResName(), null, rec.getId(), isAdmin, reopenDetail,
                            lm.Auction_Prompt_MinIncrement.getMessage());
                }
            });

            gui.addButton(new CMIGuiButton(22, buildItem(
                    CMIMaterial.REDSTONE_BLOCK,
                    lm.Gui_Auction_Detail_CancelName.getMessage(),
                    isAdmin ? lm.Gui_Auction_Detail_CancelAdminLore.getMessageList() : lm.Gui_Auction_Detail_CancelOwnerLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    player.closeInventory();
                    mgr.cancelAuction(player, rec.getId(), isAdmin, "GUI cancel", outcome -> {
                        player.sendMessage(CMIChatColor.translate(outcome.message));
                        if (backAction != null) {
                            backAction.run();
                        }
                    });
                }
            });
        }

        // Slot 31: 返回
        gui.addButton(new CMIGuiButton(31, buildItem(
                CMIMaterial.ARROW,
                lm.Gui_Auction_Btn_BackBrowserName.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) {
                    backAction.run();
                } else {
                    openBrowser(player, AuctionFilter.ALL, AuctionSort.ENDING_SOON, resadmin, 1);
                }
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    /**
     * 喊價送出前強制確認畫面（列出金額、清權限提醒、現況交付／管理員清空模式說明）。
     */
    public void openBidConfirmGui(Player player, AuctionRecord rec, long bidAmount, boolean resadmin, Runnable backAction) {
        if (player == null || !player.isOnline() || rec == null) {
            return;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Auction_BidConfirm_Title.getMessage(rec.getResName()));
        gui.setInvSize(GUIRows.r3);

        List<String> warnLore = new ArrayList<>();
        warnLore.add(lm.Gui_Auction_BidConfirm_YourBid.getMessage(mgr.formatMoney(bidAmount)));
        warnLore.add(lm.Gui_Auction_BidConfirm_CurrentBid.getMessage(
                rec.hasBids() ? mgr.formatMoney(rec.getCurrentBid()) : mgr.formatMoney(rec.getStartPrice())));
        warnLore.add(lm.Gui_Auction_BidConfirm_ClearMode.getMessage(mgr.formatClearModeLabel(rec.getClearMode())));
        warnLore.add("");
        warnLore.add(lm.Gui_Auction_BidConfirm_WarnFlags.getMessage());
        warnLore.add(rec.getClearMode() == ClearMode.NONE
                ? lm.Gui_Auction_BidConfirm_WarnAsIs.getMessage()
                : lm.Gui_Auction_BidConfirm_WarnWillClear.getMessage(mgr.formatClearModeLabel(rec.getClearMode())));

        gui.addButton(new CMIGuiButton(13, buildItem(
                CMIMaterial.PAPER,
                lm.Gui_Auction_BidConfirm_SummaryName.getMessage(rec.getId(), rec.getResName()),
                warnLore)));

        gui.addButton(new CMIGuiButton(11, buildItem(
                CMIMaterial.EMERALD_BLOCK,
                lm.Gui_Auction_BidConfirm_ConfirmName.getMessage(mgr.formatMoney(bidAmount)),
                lm.Gui_Auction_BidConfirm_ConfirmLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                mgr.placeBid(player, rec.getId(), bidAmount, outcome -> {
                    player.sendMessage(CMIChatColor.translate(outcome.message));
                    if (outcome.ok) {
                        openDetailGui(player, rec.getId(), resadmin, () -> openBrowser(player, AuctionFilter.ALL, AuctionSort.ENDING_SOON, resadmin, 1));
                    } else if (backAction != null) {
                        backAction.run();
                    }
                });
            }
        });

        gui.addButton(new CMIGuiButton(15, buildItem(
                CMIMaterial.BARRIER,
                lm.Gui_Auction_BidConfirm_CancelName.getMessage(),
                lm.Gui_Auction_BidConfirm_CancelLore.getMessageList())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) {
                    backAction.run();
                } else {
                    player.closeInventory();
                }
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    /**
     * 待確認通知介面（點擊每一筆通知即標記「我知道了」並記錄 ack_at）。
     */
    public void openNotificationsGui(Player player, Runnable backAction) {
        if (player == null || !player.isOnline()) {
            return;
        }
        AuctionManager mgr = plugin.getAuctionManager();
        if (mgr == null || !mgr.isEnabled()) {
            lm.Auction_Disabled.sendMessage(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<NotificationRecord> list;
            try {
                list = mgr.loadUnackedNotifications(uuid);
            } catch (SQLException ex) {
                player.getScheduler().run(plugin, t -> lm.Auction_DbError.sendMessage(player), null);
                return;
            }
            player.getScheduler().run(plugin, t -> {
                if (player.isOnline()) {
                    renderNotificationsGui(player, list, backAction);
                }
            }, null);
        });
    }

    private void renderNotificationsGui(Player player, List<NotificationRecord> list, Runnable backAction) {
        AuctionManager mgr = plugin.getAuctionManager();
        CMIGui gui = new CMIGui(player);
        gui.setTitle(lm.Gui_Auction_Notify_Title.getMessage(list.size()));
        gui.setInvSize(GUIRows.r6);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        if (list.isEmpty()) {
            gui.addButton(new CMIGuiButton(22, buildItem(
                    CMIMaterial.PAPER,
                    lm.Gui_Auction_Notify_EmptyName.getMessage(),
                    lm.Gui_Auction_Notify_EmptyLore.getMessageList())));
        } else {
            int slot = 0;
            for (NotificationRecord n : list) {
                if (slot >= 45) {
                    break;
                }
                List<String> lore = new ArrayList<>();
                lore.add("&8" + sdf.format(new Date(n.createdAt)));
                lore.add("");
                for (String line : wrapLines(n.message, 36)) {
                    lore.add("&f" + line);
                }
                lore.add("");
                lore.add(lm.Gui_Auction_Notify_ClickAck.getMessage());

                final long notifId = n.id;
                CMIGuiButton btn = new CMIGuiButton(slot++, buildItem(CMIMaterial.WRITTEN_BOOK, "&e" + n.title, lore)) {
                    @Override
                    public void click(GUIClickType type) {
                        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
                            try {
                                mgr.acknowledgeNotification(player.getUniqueId(), notifId);
                            } catch (SQLException ignored) {
                            }
                            player.getScheduler().run(plugin, t2 -> openNotificationsGui(player, backAction), null);
                        });
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
            }
        }

        if (!list.isEmpty()) {
            gui.addButton(new CMIGuiButton(49, buildItem(
                    CMIMaterial.LIME_DYE,
                    lm.Gui_Auction_Notify_AckAllName.getMessage(),
                    lm.Gui_Auction_Notify_AckAllLore.getMessageList())) {
                @Override
                public void click(GUIClickType type) {
                    Bukkit.getAsyncScheduler().runNow(plugin, t -> {
                        try {
                            mgr.acknowledgeAllNotifications(player.getUniqueId());
                        } catch (SQLException ignored) {
                        }
                        player.getScheduler().run(plugin, t2 -> {
                            lm.Auction_Notify_AckAllDone.sendMessage(player);
                            openNotificationsGui(player, backAction);
                        }, null);
                    });
                }
            });
        }

        gui.addButton(new CMIGuiButton(46, buildItem(
                CMIMaterial.ARROW,
                lm.Gui_Auction_Btn_BackBrowserName.getMessage())) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) {
                    backAction.run();
                } else {
                    openBrowser(player, AuctionFilter.ALL, AuctionSort.ENDING_SOON, false, 1);
                }
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    // ==================== 聊天輸入監聽（自訂金額／時長／說明） ====================

    private void startPrompt(
            Player player, PromptType type, String resName, DraftConfig draft, long auctionId,
            boolean adminMode, Runnable backAction, String promptMessage) {
        pendingPrompts.put(player.getUniqueId(), new PendingPrompt(type, resName, draft, auctionId, adminMode, backAction));
        player.closeInventory();
        player.sendMessage(CMIChatColor.translate(promptMessage));
        lm.Auction_Prompt_CancelHint.sendMessage(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingPrompt prompt = pendingPrompts.get(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        if (System.currentTimeMillis() > prompt.expiresAt) {
            pendingPrompts.remove(player.getUniqueId());
            return;
        }
        event.setCancelled(true);
        pendingPrompts.remove(player.getUniqueId());

        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        player.getScheduler().run(plugin, t -> handlePromptInput(player, prompt, text), null);
    }

    private void handlePromptInput(Player player, PendingPrompt prompt, String input) {
        if (!player.isOnline()) {
            return;
        }
        if (input.equalsIgnoreCase("cancel") || input.equals("取消")) {
            lm.Auction_Prompt_Cancelled.sendMessage(player);
            reopenAfterPrompt(player, prompt);
            return;
        }

        AuctionPolicy policy = plugin.getConfigManager().getAuctionPolicy();
        AuctionManager mgr = plugin.getAuctionManager();

        switch (prompt.type) {
        case DRAFT_START_PRICE: {
            Long val = parsePositiveLong(input);
            if (val == null || val < policy.getMinStartPrice()) {
                player.sendMessage(CMIChatColor.translate(lm.Auction_Err_StartPriceTooLow.getMessage(mgr.formatMoney(policy.getMinStartPrice()))));
            } else {
                prompt.draft.startPrice = val;
            }
            reopenAfterPrompt(player, prompt);
            break;
        }
        case DRAFT_MIN_INCREMENT: {
            Long val = parsePositiveLong(input);
            if (val == null || val < 1L) {
                lm.Auction_Err_InvalidIncrement.sendMessage(player);
            } else {
                prompt.draft.minIncrement = val;
            }
            reopenAfterPrompt(player, prompt);
            break;
        }
        case DRAFT_DURATION_HOURS: {
            Long hours = parsePositiveLong(input);
            if (hours == null || hours * 60L < policy.getMinDurationMinutes() || hours > policy.getMaxDurationHours()) {
                player.sendMessage(CMIChatColor.translate(lm.Gui_Auction_Create_DurationRange.getMessage(
                        policy.getMinDurationMinutes(), policy.getMaxDurationHours())));
            } else {
                prompt.draft.durationMinutes = hours * 60L;
            }
            reopenAfterPrompt(player, prompt);
            break;
        }
        case DRAFT_DESCRIPTION: {
            prompt.draft.description = input.length() > 200 ? input.substring(0, 200) : input;
            reopenAfterPrompt(player, prompt);
            break;
        }
        case CUSTOM_BID: {
            Long amount = parsePositiveLong(input);
            if (amount == null || amount < 1L) {
                lm.Invalid_Amount.sendMessage(player);
                reopenAfterPrompt(player, prompt);
                return;
            }
            AuctionRecord cached = mgr.getCachedActiveAuction(prompt.auctionId);
            if (cached == null) {
                lm.Auction_Err_NotActive.sendMessage(player);
                return;
            }
            openBidConfirmGui(player, cached, amount, prompt.adminMode, prompt.backAction);
            break;
        }
        case UPDATE_MIN_INCREMENT: {
            Long val = parsePositiveLong(input);
            if (val == null || val < 1L) {
                lm.Auction_Err_InvalidIncrement.sendMessage(player);
                reopenAfterPrompt(player, prompt);
                return;
            }
            mgr.updateMinIncrement(player, prompt.auctionId, prompt.adminMode, val, outcome -> {
                player.sendMessage(CMIChatColor.translate(outcome.message));
                reopenAfterPrompt(player, prompt);
            });
            break;
        }
        }
    }

    private void reopenAfterPrompt(Player player, PendingPrompt prompt) {
        if (prompt.draft != null && prompt.resName != null) {
            ClaimedResidence live = plugin.getResidenceManager().getByName(prompt.resName);
            if (live != null) {
                openCreateSetupGui(player, live, prompt.draft, prompt.adminMode, prompt.backAction);
                return;
            }
        }
        if (prompt.backAction != null) {
            prompt.backAction.run();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() != null) {
            openBrowsers.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingPrompts.remove(uuid);
        openBrowsers.remove(uuid);
    }

    // ==================== 輔助方法 ====================

    private void teleportToAuctionResidence(Player player, AuctionRecord rec, boolean resadmin) {
        if (rec.getOriginServer().equalsIgnoreCase(plugin.getStorageServerName())) {
            ClaimedResidence local = rec.getResUuid() != null ? plugin.getResidenceManager().getByUUID(rec.getResUuid()) : null;
            if (local == null) {
                local = plugin.getResidenceManager().getByName(rec.getResName());
            }
            if (local == null) {
                lm.Invalid_Residence.sendMessage(player);
                return;
            }
            local.tpToResidence(player, player, resadmin);
            return;
        }
        CrossServerManager csm = plugin.getCrossServerManager();
        if (csm != null && csm.isEnabled()) {
            RemoteResidenceEntry remote = new RemoteResidenceEntry(
                    rec.getOriginServer(), rec.getResKey(), rec.getResName(), rec.getResUuid(),
                    rec.getWorldName(), rec.getCreatorUuid(), rec.getCreatorName(),
                    rec.getTpX(), rec.getTpY(), rec.getTpZ(), 0F, 0F,
                    false, true, true, null, null, null, 0, rec.getCreatedAt(), false);
            csm.teleportToRemoteEntry(player, remote, resadmin);
        } else {
            lm.Invalid_Residence.sendMessage(player);
        }
    }

    private List<AuctionRecord> filterAndSort(
            List<AuctionRecord> all, AuctionFilter filter, AuctionSort sort, Set<Long> participatedIds) {
        List<AuctionRecord> out = new ArrayList<>();
        for (AuctionRecord r : all) {
            switch (filter) {
            case ARCHIVED:
                if (r.getType() != AuctionType.ARCHIVED && r.getType() != AuctionType.ADMIN) {
                    continue;
                }
                break;
            case PLAYER:
                if (r.getType() != AuctionType.PLAYER) {
                    continue;
                }
                break;
            case PARTICIPATED:
                if (!participatedIds.contains(r.getId())) {
                    continue;
                }
                break;
            case ALL:
            default:
                break;
            }
            out.add(r);
        }
        Comparator<AuctionRecord> cmp;
        switch (sort) {
        case PRICE_ASC:
            cmp = Comparator.comparingLong(r -> r.hasBids() ? r.getCurrentBid() : r.getStartPrice());
            break;
        case PRICE_DESC:
            cmp = Comparator.comparingLong((AuctionRecord r) -> r.hasBids() ? r.getCurrentBid() : r.getStartPrice()).reversed();
            break;
        case ENDING_SOON:
        default:
            cmp = Comparator.comparingLong(AuctionRecord::getEndsAt);
            break;
        }
        out.sort(cmp.thenComparingLong(AuctionRecord::getId));
        return out;
    }

    private ItemStack buildAuctionCard(AuctionRecord rec, AuctionManager mgr) {
        List<String> lore = buildCardLore(rec, mgr);
        lore.add("");
        lore.add(lm.Gui_Auction_Card_LeftBid.getMessage());
        lore.add(lm.Gui_Auction_Card_RightTp.getMessage());
        return buildItem(resolveTypeMaterial(rec.getType()), "&6#" + rec.getId() + " &f" + rec.getResName(), lore);
    }

    private List<String> buildCardLore(AuctionRecord rec, AuctionManager mgr) {
        List<String> lore = new ArrayList<>();
        String srvDisplay = plugin.getConfigManager().getCrossServerDisplayName(rec.getOriginServer());
        lore.add(lm.Gui_Auction_Card_Type.getMessage(mgr.formatTypeLabel(rec.getType())));
        lore.add(lm.Gui_Auction_Card_Server.getMessage(srvDisplay, rec.getWorldName()));
        if (rec.getType() == AuctionType.ARCHIVED && !rec.getOriginalOwnerName().isEmpty()) {
            lore.add(lm.Gui_Auction_Card_OriginalOwner.getMessage(rec.getOriginalOwnerName()));
        } else {
            lore.add(lm.Gui_Auction_Card_Seller.getMessage(rec.getCreatorName()));
        }
        if (rec.hasBids()) {
            lore.add(lm.Gui_Auction_Card_CurrentBid.getMessage(mgr.formatMoney(rec.getCurrentBid()), rec.getHighestBidderName()));
        } else {
            lore.add(lm.Gui_Auction_Card_StartPrice.getMessage(mgr.formatMoney(rec.getStartPrice())));
        }
        lore.add(lm.Gui_Auction_Card_MinIncrement.getMessage(mgr.formatMoney(rec.getMinIncrement()), rec.getBidCount()));
        lore.add(lm.Gui_Auction_Card_Remaining.getMessage(mgr.formatDurationShort(rec.getEndsAt() - System.currentTimeMillis())));
        lore.add(lm.Gui_Auction_Card_ClearMode.getMessage(mgr.formatClearModeLabel(rec.getClearMode())));
        if (!rec.getDescription().isEmpty()) {
            lore.add("");
            for (String line : wrapLines(rec.getDescription(), 32)) {
                lore.add("&7" + line);
            }
        }
        return lore;
    }

    private CMIMaterial resolveTypeMaterial(AuctionType type) {
        if (type == AuctionType.ARCHIVED) {
            return CMIMaterial.ENDER_CHEST;
        }
        if (type == AuctionType.ADMIN) {
            return CMIMaterial.BEACON;
        }
        return CMIMaterial.GOLD_BLOCK;
    }

    private long nextDurationPreset(long currentMinutes, AuctionPolicy policy) {
        long minM = policy.getMinDurationMinutes();
        long maxM = policy.getMaxDurationHours() * 60L;
        for (long preset : DURATION_PRESET_MINUTES) {
            if (preset > currentMinutes && preset >= minM && preset <= maxM) {
                return preset;
            }
        }
        for (long preset : DURATION_PRESET_MINUTES) {
            if (preset >= minM && preset <= maxM) {
                return preset;
            }
        }
        return minM;
    }

    private static Long parsePositiveLong(String raw) {
        try {
            String cleaned = raw.replace(",", "").trim();
            long val = Long.parseLong(cleaned);
            return val > 0L ? val : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> wrapLines(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        int idx = 0;
        while (idx < text.length()) {
            int end = Math.min(text.length(), idx + maxChars);
            lines.add(text.substring(idx, end));
            idx = end;
        }
        return lines;
    }

    private static ItemStack buildItem(CMIMaterial mat, String name) {
        return buildItem(mat, name, Collections.emptyList());
    }

    private static ItemStack buildItem(CMIMaterial mat, String name, List<String> lore) {
        ItemStack stack = (mat != null ? mat : CMIMaterial.PAPER).newItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(CMIChatColor.translate(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> colored = new ArrayList<>(lore.size());
                for (String line : lore) {
                    colored.add(CMIChatColor.translate(line));
                }
                meta.setLore(colored);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
