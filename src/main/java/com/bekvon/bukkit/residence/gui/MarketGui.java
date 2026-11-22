package com.bekvon.bukkit.residence.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.economy.rent.RentableLand;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
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

public class MarketGui implements Listener {

    public enum MarketFilter {
        ALL("全部市場"),
        SELL("出售中"),
        RENT("可租賃"),
        MY_RENTED("我的租約");

        private final String label;

        MarketFilter(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public MarketFilter next() {
            MarketFilter[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static MarketFilter parse(String raw) {
            if (raw == null)
                return ALL;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sell":
            case "sale":
            case "buy":
                return SELL;
            case "rent":
            case "rentable":
                return RENT;
            case "my":
            case "myrent":
            case "rented":
                return MY_RENTED;
            default:
                return ALL;
            }
        }
    }

    public enum MarketSort {
        PRICE_ASC("價格低→高"),
        PRICE_DESC("價格高→低"),
        SIZE_DESC("面積大→小"),
        NAME("名稱排序");

        private final String label;

        MarketSort(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public MarketSort next() {
            MarketSort[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    private enum PromptAction {
        PUT_FOR_SALE,
        PUT_FOR_RENT
    }

    private static final class PendingMarketPrompt {
        final String resName;
        final PromptAction action;
        final boolean resadmin;
        final long expiresAt;

        PendingMarketPrompt(String resName, PromptAction action, boolean resadmin) {
            this.resName = resName;
            this.action = action;
            this.resadmin = resadmin;
            this.expiresAt = System.currentTimeMillis() + 60_000L;
        }
    }

    private static final class MarketItem {
        final ClaimedResidence res;
        final boolean forSale;
        final int salePrice;
        final boolean forRent;
        final boolean rented;
        final int rentCost;
        final int rentDays;

        MarketItem(ClaimedResidence res, boolean forSale, int salePrice, boolean forRent, boolean rented, int rentCost, int rentDays) {
            this.res = res;
            this.forSale = forSale;
            this.salePrice = salePrice;
            this.forRent = forRent;
            this.rented = rented;
            this.rentCost = rentCost;
            this.rentDays = rentDays;
        }

        int primaryPrice() {
            if (forSale && forRent)
                return Math.min(salePrice, rentCost);
            if (forSale)
                return salePrice;
            return rentCost;
        }
    }

    private final Residence plugin;
    private final Map<UUID, PendingMarketPrompt> pendingPrompts = new ConcurrentHashMap<>();

    public MarketGui(Residence plugin) {
        this.plugin = plugin;
    }

    public void openMarketBrowser(Player player, MarketFilter filter, MarketSort sort, boolean resadmin, int page) {
        if (player == null || !player.isOnline())
            return;
        if (!plugin.getConfigManager().enableEconomy()) {
            lm.Economy_MarketDisabled.sendMessage(player);
            return;
        }
        if (!plugin.getConfigManager().isResidenceGuiEnabled()) {
            lm.Gui_Disabled.sendMessage(player);
            return;
        }

        MarketFilter effectiveFilter = filter == null ? MarketFilter.ALL : filter;
        MarketSort effectiveSort = sort == null ? MarketSort.PRICE_ASC : sort;

        List<MarketItem> items = collectMarketItems(player, effectiveFilter, effectiveSort);
        int maxPages = Math.max(1, (items.size() + 44) / 45);
        int safePage = Math.max(1, Math.min(page, maxPages));
        PageInfo pi = new PageInfo(45, items.size(), safePage);

        String title = CMIChatColor.translate("&2領地市場 - " + effectiveFilter.getLabel() + " &8(" + pi.getCurrentPage() + "/" + Math.max(1, pi.getTotalPages()) + ")");
        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int newPage) {
                openMarketBrowser(player, effectiveFilter, effectiveSort, resadmin, newPage);
            }
        };
        gui.setTitle(title);
        gui.setInvSize(GUIRows.r6);

        if (items.isEmpty()) {
            gui.addButton(new CMIGuiButton(22, buildItem(
                    CMIMaterial.PAPER,
                    "&e目前沒有符合條件的領地",
                    Arrays.asList(
                            "&7切換下方分類或等待其他玩家上架",
                            "&7領主可在 &e/res gui &7管理領地中直接上架出售或出租"))));
        } else {
            int slot = 0;
            for (MarketItem mi : items) {
                if (pi.isContinue())
                    continue;
                if (pi.isBreak())
                    break;

                final MarketItem target = mi;
                Runnable reopen = () -> openMarketBrowser(player, effectiveFilter, effectiveSort, resadmin, pi.getCurrentPage());
                CMIGuiButton btn = new CMIGuiButton(slot++, buildMarketCard(player, target, resadmin)) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence live = plugin.getResidenceManager().getByName(target.res.getName());
                        if (live == null) {
                            lm.Invalid_Residence.sendMessage(player);
                            reopen.run();
                            return;
                        }
                        if (type == GUIClickType.Right) {
                            player.closeInventory();
                            live.tpToResidence(player, player, resadmin);
                            return;
                        }
                        if (resadmin || live.isOwner(player)) {
                            openOwnerMarketGui(player, live, resadmin, reopen);
                        } else {
                            openMarketActionGui(player, live, resadmin, reopen);
                        }
                    }
                };
                btn.hideItemFlags();
                gui.addButton(btn);
            }
        }

        // Slot 46: Filter toggle
        gui.addButton(new CMIGuiButton(46, buildItem(
                CMIMaterial.HOPPER,
                "&e分類篩選：&6" + effectiveFilter.getLabel(),
                Arrays.asList(
                        "&7點擊切換顯示分類",
                        "&8(全部市場 / 出售中 / 可租賃 / 我的租約)"))) {
            @Override
            public void click(GUIClickType type) {
                openMarketBrowser(player, effectiveFilter.next(), effectiveSort, resadmin, 1);
            }
        });

        // Slot 48: Sort toggle
        gui.addButton(new CMIGuiButton(48, buildItem(
                CMIMaterial.COMPARATOR,
                "&e排序方式：&6" + effectiveSort.getLabel(),
                Arrays.asList(
                        "&7點擊切換排序方式",
                        "&8(價格低→高 / 價格高→低 / 面積大→小 / 名稱排序)"))) {
            @Override
            public void click(GUIClickType type) {
                openMarketBrowser(player, effectiveFilter, effectiveSort.next(), resadmin, 1);
            }
        });

        // Slot 50: Back to Residence List GUI
        gui.addButton(new CMIGuiButton(50, buildItem(
                CMIMaterial.OAK_SIGN,
                "&a前往領地瀏覽介面",
                Arrays.asList("&7點擊切換回 /res gui 領地清單"))) {
            @Override
            public void click(GUIClickType type) {
                plugin.getResidenceListGui().openBrowser(player, ResidenceListGui.FilterMode.ALL, ResidenceListGui.SortMode.DEFAULT, null, resadmin, 1);
            }
        });

        // Slot 51: Refresh
        gui.addButton(new CMIGuiButton(51, buildItem(
                CMIMaterial.SUNFLOWER,
                "&a重新整理市場",
                Arrays.asList("&7點擊重新載入最新出售與出租列表"))) {
            @Override
            public void click(GUIClickType type) {
                openMarketBrowser(player, effectiveFilter, effectiveSort, resadmin, pi.getCurrentPage());
            }
        });

        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    public void openMarketActionGui(Player player, ClaimedResidence res, boolean resadmin, Runnable backAction) {
        if (player == null || !player.isOnline() || res == null)
            return;

        boolean forSale = plugin.getTransactionManager().isForSale(res);
        boolean forRent = plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isForRent(res);
        boolean rented = forRent && res.isRented();
        boolean isRenter = rented && res.getRentedLand() != null && res.getRentedLand().isRenter(player);

        CMIGui gui = new CMIGui(player);
        gui.setTitle(CMIChatColor.translate("&2市場操作：&f" + res.getName()));
        gui.setInvSize(GUIRows.r3);

        if (isRenter) {
            RentedLand rl = res.getRentedLand();
            int cost = plugin.getRentManager().getCostOfRent(res);
            int days = plugin.getRentManager().getRentDays(res);
            boolean autoPay = rl.AutoPay;

            // Slot 10: Pay / Renew Rent
            gui.addButton(new CMIGuiButton(10, buildItem(
                    CMIMaterial.EMERALD,
                    "&a立即續租（支付租金）",
                    Arrays.asList(
                            "&7費用：&6" + formatMoney(cost) + " &7/ &f" + days + " 天",
                            "&7目前到期：&f" + formatDate(rl.endTime),
                            "",
                            "&e點擊立即支付並延長租期"))) {
                @Override
                public void click(GUIClickType type) {
                    player.closeInventory();
                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                    if (live != null) {
                        plugin.getRentManager().payRent(player, live, resadmin);
                    }
                }
            });

            // Slot 12: Toggle AutoPay
            gui.addButton(new CMIGuiButton(12, buildItem(
                    autoPay ? CMIMaterial.LIME_DYE : CMIMaterial.GRAY_DYE,
                    "&e自動續租：" + (autoPay ? "&a開啟" : "&c關閉"),
                    Arrays.asList(
                            "&7到期時是否自動從餘額扣款續租",
                            "&e點擊切換開關"))) {
                @Override
                public void click(GUIClickType type) {
                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                    if (live != null) {
                        plugin.getRentManager().setRentedRepeatable(player, live.getName(), !autoPay, resadmin);
                        openMarketActionGui(player, live, resadmin, backAction);
                    }
                }
            });

            // Slot 14: Teleport
            gui.addButton(new CMIGuiButton(14, buildItem(
                    CMIMaterial.COMPASS,
                    "&b傳送到領地",
                    Arrays.asList("&7點擊傳送到 &f" + res.getName()))) {
                @Override
                public void click(GUIClickType type) {
                    player.closeInventory();
                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                    if (live != null)
                        live.tpToResidence(player, player, resadmin);
                }
            });

            // Slot 16: Unrent (with confirm)
            gui.addButton(new CMIGuiButton(16, buildItem(
                    CMIMaterial.BARRIER,
                    "&c退租此領地",
                    Arrays.asList(
                            "&7放棄此領地的租賃權",
                            "&c點擊前往確認視窗"))) {
                @Override
                public void click(GUIClickType type) {
                    openConfirmGui(player, "&c確認退租：&f" + res.getName(),
                            "&c確認退租",
                            Arrays.asList("&7點擊確認退租 &e" + res.getName(), "&c退租後剩餘租金不會退還"),
                            () -> {
                                ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                                if (live != null) {
                                    plugin.getRentManager().unrent(player, live, resadmin);
                                }
                                if (backAction != null) backAction.run();
                            },
                            () -> openMarketActionGui(player, res, resadmin, backAction));
                }
            });
        } else {
            // Slot 11: Buy (if forSale) or Rent (if available forRent)
            if (forSale) {
                int price = plugin.getTransactionManager().getSaleAmount(res);
                gui.addButton(new CMIGuiButton(11, buildItem(
                        CMIMaterial.LIME_CONCRETE,
                        "&a購買此領地",
                        Arrays.asList(
                                "&7領地：&f" + res.getName(),
                                "&7擁有者：&f" + res.getOwner(),
                                "&7售價：&6" + formatMoney(price),
                                "&7你的餘額：&f" + formatPlayerBalance(player),
                                "",
                                "&e點擊進入購買確認"))) {
                    @Override
                    public void click(GUIClickType type) {
                        openConfirmGui(player, "&2確認購買：&f" + res.getName(),
                                "&a確認花費 " + formatMoney(price) + " 購買",
                                Arrays.asList("&7領地：&e" + res.getName(), "&7面積：&f" + res.getTotalSize() + " 格"),
                                () -> {
                                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                                    if (live != null && plugin.getTransactionManager().isForSale(live)) {
                                        plugin.getTransactionManager().buyPlot(live, player, resadmin);
                                    } else {
                                        lm.Residence_NotForRentOrSell.sendMessage(player);
                                    }
                                },
                                () -> openMarketActionGui(player, res, resadmin, backAction));
                    }
                });
            }

            if (forRent && !rented) {
                int rentSlot = forSale ? 12 : 11;
                int cost = plugin.getRentManager().getCostOfRent(res);
                int days = plugin.getRentManager().getRentDays(res);
                boolean defaultAutoPay = plugin.getConfigManager().isRentPlayerAutoPay();
                gui.addButton(new CMIGuiButton(rentSlot, buildItem(
                        CMIMaterial.GOLD_BLOCK,
                        "&e租賃此領地",
                        Arrays.asList(
                                "&7租金：&6" + formatMoney(cost) + " &7/ &f" + days + " 天",
                                "&7你的餘額：&f" + formatPlayerBalance(player),
                                "&7預設自動續租：&f" + (defaultAutoPay ? "開啟" : "關閉"),
                                "",
                                "&e點擊進入租賃確認"))) {
                    @Override
                    public void click(GUIClickType type) {
                        openConfirmGui(player, "&6確認租賃：&f" + res.getName(),
                                "&a確認支付 " + formatMoney(cost) + " 租賃 " + days + " 天",
                                Arrays.asList("&7領地：&e" + res.getName(), "&7可在「我的租約」調整自動續租"),
                                () -> {
                                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                                    if (live != null && plugin.getRentManager().isForRent(live)) {
                                        plugin.getRentManager().rent(player, live, defaultAutoPay, resadmin);
                                    } else {
                                        lm.Residence_NotForRentOrSell.sendMessage(player);
                                    }
                                },
                                () -> openMarketActionGui(player, res, resadmin, backAction));
                    }
                });
            } else if (forRent && rented) {
                RentedLand rl = res.getRentedLand();
                gui.addButton(new CMIGuiButton(11, buildItem(
                        CMIMaterial.CLOCK,
                        "&7此領地目前已被租出",
                        Arrays.asList(
                                "&7目前租客：&f" + plugin.getRentManager().getRentingPlayer(res),
                                "&7到期時間：&f" + (rl != null ? formatDate(rl.endTime) : "未知")))));
            }

            // Slot 14: Teleport to inspect
            gui.addButton(new CMIGuiButton(14, buildItem(
                    CMIMaterial.COMPASS,
                    "&b傳送查看領地",
                    Arrays.asList("&7先傳送過去看看環境再決定是否購買／租賃"))) {
                @Override
                public void click(GUIClickType type) {
                    player.closeInventory();
                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                    if (live != null)
                        live.tpToResidence(player, player, resadmin);
                }
            });

            // Slot 16: Cancel / Back
            gui.addButton(new CMIGuiButton(16, buildItem(
                    CMIMaterial.RED_CONCRETE,
                    "&c返回市場")) {
                @Override
                public void click(GUIClickType type) {
                    if (backAction != null) backAction.run();
                    else player.closeInventory();
                }
            });
        }

        // Slot 22: Back arrow
        gui.addButton(new CMIGuiButton(22, buildItem(
                CMIMaterial.ARROW,
                "&7返回上一頁")) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) backAction.run();
                else player.closeInventory();
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    public void openOwnerMarketGui(Player player, ClaimedResidence res, boolean resadmin, Runnable backAction) {
        if (player == null || !player.isOnline() || res == null)
            return;
        if (!plugin.getConfigManager().enableEconomy()) {
            lm.Economy_MarketDisabled.sendMessage(player);
            return;
        }
        if (!resadmin && !res.isOwner(player)) {
            lm.Residence_NotOwner.sendMessage(player);
            return;
        }

        boolean forSale = plugin.getTransactionManager().isForSale(res);
        boolean forRent = plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isForRent(res);
        boolean rented = forRent && res.isRented();

        CMIGui gui = new CMIGui(player);
        gui.setTitle(CMIChatColor.translate("&2領地市場管理：&f" + res.getName()));
        gui.setInvSize(GUIRows.r3);

        // Slot 11: Sell / Unsell
        if (forSale) {
            int price = plugin.getTransactionManager().getSaleAmount(res);
            gui.addButton(new CMIGuiButton(11, buildItem(
                    CMIMaterial.REDSTONE_BLOCK,
                    "&c取消出售領地",
                    Arrays.asList(
                            "&7目前狀態：&a出售中",
                            "&7目前售價：&6" + formatMoney(price),
                            "",
                            "&e點擊下架取消出售"))) {
                @Override
                public void click(GUIClickType type) {
                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                    if (live != null) {
                        plugin.getTransactionManager().removeFromSale(player, live.getName(), resadmin);
                        openOwnerMarketGui(player, live, resadmin, backAction);
                    }
                }
            });
        } else {
            gui.addButton(new CMIGuiButton(11, buildItem(
                    CMIMaterial.EMERALD,
                    "&a上架出售領地",
                    Arrays.asList(
                            "&7目前狀態：&7未出售",
                            "",
                            "&e點擊後在聊天欄輸入售價（正整數）",
                            "&8輸入 cancel 可取消"))) {
                @Override
                public void click(GUIClickType type) {
                    player.closeInventory();
                    pendingPrompts.put(player.getUniqueId(), new PendingMarketPrompt(res.getName(), PromptAction.PUT_FOR_SALE, resadmin));
                    player.sendMessage(CMIChatColor.translate(
                            "&a[Residence] &e請在聊天欄輸入領地 &f" + res.getName() + " &e的出售價格（正整數），或輸入 &ccancel &e取消（60秒內有效）。"));
                }
            });
        }

        // Slot 13: Rentable / Unrent / Evict
        if (plugin.getConfigManager().enabledRentSystem()) {
            if (!forRent) {
                gui.addButton(new CMIGuiButton(13, buildItem(
                        CMIMaterial.GOLD_INGOT,
                        "&e上架出租領地",
                        Arrays.asList(
                                "&7目前狀態：&7未出租",
                                "",
                                "&e點擊後在聊天欄輸入「租金 天數」",
                                "&8範例：1000 7（每 7 天 1000 元）",
                                "&8輸入 cancel 可取消"))) {
                    @Override
                    public void click(GUIClickType type) {
                        player.closeInventory();
                        pendingPrompts.put(player.getUniqueId(), new PendingMarketPrompt(res.getName(), PromptAction.PUT_FOR_RENT, resadmin));
                        player.sendMessage(CMIChatColor.translate(
                                "&a[Residence] &e請在聊天欄輸入 &f<租金> <天數> &e（例如 &f1000 7&e）以上架出租領地 &f" + res.getName() + "&e，或輸入 &ccancel &e取消。"));
                    }
                });
            } else if (!rented) {
                int cost = plugin.getRentManager().getCostOfRent(res);
                int days = plugin.getRentManager().getRentDays(res);
                gui.addButton(new CMIGuiButton(13, buildItem(
                        CMIMaterial.ORANGE_CONCRETE,
                        "&c下架出租（取消出租）",
                        Arrays.asList(
                                "&7目前狀態：&b待租中",
                                "&7租金設定：&6" + formatMoney(cost) + " &7/ &f" + days + " 天",
                                "",
                                "&e點擊取消出租上架"))) {
                    @Override
                    public void click(GUIClickType type) {
                        ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                        if (live != null) {
                            plugin.getRentManager().removeFromForRent(player, live, resadmin);
                            openOwnerMarketGui(player, live, resadmin, backAction);
                        }
                    }
                });
            } else {
                RentedLand rl = res.getRentedLand();
                boolean canEvict = resadmin || ResAdmin.isResAdmin(player) || ResPerm.market_evict.hasPermission(player);
                List<String> lore = new ArrayList<>();
                lore.add("&7目前狀態：&e已租出");
                lore.add("&7租客：&f" + plugin.getRentManager().getRentingPlayer(res));
                if (rl != null) {
                    lore.add("&7到期時間：&f" + formatDate(rl.endTime));
                }
                if (canEvict) {
                    lore.add("");
                    lore.add("&c點擊驅逐目前租客（需確認）");
                }
                gui.addButton(new CMIGuiButton(13, buildItem(CMIMaterial.CLOCK, "&e目前出租中", lore)) {
                    @Override
                    public void click(GUIClickType type) {
                        if (!canEvict)
                            return;
                        openConfirmGui(player, "&c確認驅逐租客：&f" + res.getName(),
                                "&c確認解除目前租約",
                                Arrays.asList("&7目標領地：&e" + res.getName()),
                                () -> {
                                    ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                                    if (live != null) {
                                        plugin.getRentManager().unrent(player, live, resadmin);
                                    }
                                    if (backAction != null) backAction.run();
                                },
                                () -> openOwnerMarketGui(player, res, resadmin, backAction));
                    }
                });
            }
        }

        // Slot 15: Teleport
        gui.addButton(new CMIGuiButton(15, buildItem(
                CMIMaterial.COMPASS,
                "&b傳送到領地",
                Arrays.asList("&7點擊傳送到 &f" + res.getName()))) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                ClaimedResidence live = plugin.getResidenceManager().getByName(res.getName());
                if (live != null)
                    live.tpToResidence(player, player, resadmin);
            }
        });

        // Slot 22: Back
        gui.addButton(new CMIGuiButton(22, buildItem(
                CMIMaterial.ARROW,
                "&7返回上一頁")) {
            @Override
            public void click(GUIClickType type) {
                if (backAction != null) backAction.run();
                else player.closeInventory();
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    private void openConfirmGui(Player player, String title, String confirmName, List<String> confirmLore, Runnable onConfirm, Runnable onCancel) {
        CMIGui gui = new CMIGui(player);
        gui.setTitle(CMIChatColor.translate(title));
        gui.setInvSize(GUIRows.r3);

        gui.addButton(new CMIGuiButton(11, buildItem(CMIMaterial.LIME_CONCRETE, confirmName, confirmLore)) {
            @Override
            public void click(GUIClickType type) {
                player.closeInventory();
                if (onConfirm != null)
                    onConfirm.run();
            }
        });

        gui.addButton(new CMIGuiButton(15, buildItem(CMIMaterial.RED_CONCRETE, "&c取消")) {
            @Override
            public void click(GUIClickType type) {
                if (onCancel != null)
                    onCancel.run();
                else
                    player.closeInventory();
            }
        });

        gui.fillEmptyButtons();
        gui.open();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatPrompt(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingMarketPrompt prompt = pendingPrompts.get(player.getUniqueId());
        if (prompt == null)
            return;
        if (System.currentTimeMillis() > prompt.expiresAt) {
            pendingPrompts.remove(player.getUniqueId());
            return;
        }

        event.setCancelled(true);
        pendingPrompts.remove(player.getUniqueId());
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline())
                return;
            if (raw.equalsIgnoreCase("cancel") || raw.equals("取消")) {
                player.sendMessage(CMIChatColor.translate("&7[Residence] 已取消市場上架操作。"));
                return;
            }
            ClaimedResidence res = plugin.getResidenceManager().getByName(prompt.resName);
            if (res == null) {
                lm.Invalid_Residence.sendMessage(player);
                return;
            }
            if (!prompt.resadmin && !res.isOwner(player)) {
                lm.Residence_NotOwner.sendMessage(player);
                return;
            }

            if (prompt.action == PromptAction.PUT_FOR_SALE) {
                int price;
                try {
                    price = Integer.parseInt(raw.replace(",", ""));
                } catch (NumberFormatException ex) {
                    lm.Invalid_Amount.sendMessage(player);
                    return;
                }
                if (price <= 0) {
                    lm.Invalid_Amount.sendMessage(player);
                    return;
                }
                plugin.getTransactionManager().putForSale(res.getName(), player, price, prompt.resadmin);
            } else if (prompt.action == PromptAction.PUT_FOR_RENT) {
                String[] parts = raw.split("\\s+");
                if (parts.length < 2) {
                    player.sendMessage(CMIChatColor.translate("&c[Residence] 格式錯誤！請輸入「租金 天數」，例如：&e1000 7"));
                    return;
                }
                int cost;
                int days;
                try {
                    cost = Integer.parseInt(parts[0].replace(",", ""));
                    days = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    lm.Invalid_Amount.sendMessage(player);
                    return;
                }
                if (cost <= 0) {
                    lm.Invalid_Cost.sendMessage(player);
                    return;
                }
                if (days <= 0) {
                    lm.Invalid_Days.sendMessage(player);
                    return;
                }
                plugin.getRentManager().setForRent(
                        player,
                        res,
                        cost,
                        days,
                        plugin.getConfigManager().isRentAllowRenewing(),
                        plugin.getConfigManager().isRentStayInMarket(),
                        plugin.getConfigManager().isRentAllowAutoPay(),
                        prompt.resadmin);
            }
        }, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingPrompts.remove(event.getPlayer().getUniqueId());
    }

    private List<MarketItem> collectMarketItems(Player player, MarketFilter filter, MarketSort sort) {
        Map<String, ClaimedResidence> candidates = new LinkedHashMap<>();

        if (filter == MarketFilter.ALL || filter == MarketFilter.SELL) {
            for (String name : plugin.getTransactionManager().getBuyableResidences().keySet()) {
                ClaimedResidence r = plugin.getResidenceManager().getByName(name);
                if (r != null) {
                    candidates.put(r.getName().toLowerCase(Locale.ROOT), r);
                }
            }
        }
        if (plugin.getConfigManager().enabledRentSystem()
                && (filter == MarketFilter.ALL || filter == MarketFilter.RENT || filter == MarketFilter.MY_RENTED)) {
            for (ClaimedResidence r : plugin.getRentManager().getRentableResidences()) {
                if (r != null) {
                    candidates.put(r.getName().toLowerCase(Locale.ROOT), r);
                }
            }
        }

        List<MarketItem> list = new ArrayList<>();
        for (ClaimedResidence res : candidates.values()) {
            boolean forSale = plugin.getTransactionManager().isForSale(res);
            int salePrice = forSale ? plugin.getTransactionManager().getSaleAmount(res) : 0;
            boolean forRent = plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isForRent(res);
            boolean rented = forRent && res.isRented();
            int rentCost = forRent ? plugin.getRentManager().getCostOfRent(res) : 0;
            int rentDays = forRent ? plugin.getRentManager().getRentDays(res) : 0;

            if (filter == MarketFilter.SELL && !forSale)
                continue;
            if (filter == MarketFilter.RENT && (!forRent || rented))
                continue;
            if (filter == MarketFilter.MY_RENTED) {
                if (!rented || res.getRentedLand() == null || !res.getRentedLand().isRenter(player))
                    continue;
            }
            if (!forSale && !forRent)
                continue;

            list.add(new MarketItem(res, forSale, salePrice, forRent, rented, rentCost, rentDays));
        }

        Comparator<MarketItem> cmp;
        switch (sort) {
        case PRICE_DESC:
            cmp = Comparator.comparingInt(MarketItem::primaryPrice).reversed()
                    .thenComparing(m -> m.res.getName(), String.CASE_INSENSITIVE_ORDER);
            break;
        case SIZE_DESC:
            cmp = Comparator.comparingLong((MarketItem m) -> m.res.getTotalSize()).reversed()
                    .thenComparing(m -> m.res.getName(), String.CASE_INSENSITIVE_ORDER);
            break;
        case NAME:
            cmp = Comparator.comparing(m -> m.res.getName(), String.CASE_INSENSITIVE_ORDER);
            break;
        case PRICE_ASC:
        default:
            cmp = Comparator.comparingInt((MarketItem m) -> m.rented && !m.forSale ? 1 : 0)
                    .thenComparingInt(MarketItem::primaryPrice)
                    .thenComparing(m -> m.res.getName(), String.CASE_INSENSITIVE_ORDER);
            break;
        }
        list.sort(cmp);
        return list;
    }

    private ItemStack buildMarketCard(Player player, MarketItem mi, boolean resadmin) {
        ClaimedResidence res = mi.res;
        ResidenceGuiMetadata meta = plugin.getGuiMetaManager() != null
                ? plugin.getGuiMetaManager().getMetadata(plugin.getStorageServerName(), res.getName())
                : null;

        CMIMaterial icon = null;
        if (meta != null && meta.getIcon() != null) {
            icon = CMIMaterial.get(meta.getIcon());
            if (icon == CMIMaterial.NONE || icon == CMIMaterial.AIR)
                icon = null;
        }
        if (icon == null) {
            if (mi.forSale)
                icon = CMIMaterial.EMERALD;
            else if (!mi.rented)
                icon = CMIMaterial.GOLD_INGOT;
            else
                icon = CMIMaterial.CLOCK;
        }

        String badge;
        if (mi.forSale && mi.forRent && !mi.rented)
            badge = " &a[出售/可租]";
        else if (mi.forSale)
            badge = " &a[出售中]";
        else if (!mi.rented)
            badge = " &b[可租賃]";
        else
            badge = " &7[已租出]";

        String displayName = (meta != null && meta.getAlias() != null && !meta.getAlias().trim().isEmpty())
                ? meta.getAlias()
                : res.getName();

        List<String> lore = new ArrayList<>();
        if (!displayName.equals(res.getName())) {
            lore.add("&8領地代號：&7" + res.getName());
        }
        String archivedOwner = plugin.getCleanupReclaimManager() != null
                ? plugin.getCleanupReclaimManager().formatArchivedInfoLine(res)
                : null;
        lore.add(archivedOwner != null ? archivedOwner : "&7擁有者：&f" + res.getOwner());
        lore.add("&7世界：&f" + res.getWorldName() + " &8(面積 " + res.getTotalSize() + " 格)");
        if (mi.forSale) {
            lore.add("&a售價：&6" + formatMoney(mi.salePrice));
        }
        if (mi.forRent) {
            lore.add("&b租金：&6" + formatMoney(mi.rentCost) + " &7/ &f" + mi.rentDays + " 天");
            if (mi.rented && res.getRentedLand() != null) {
                lore.add("&7目前租客：&f" + plugin.getRentManager().getRentingPlayer(res));
                lore.add("&7到期時間：&f" + formatDate(res.getRentedLand().endTime));
            }
        }
        lore.add("");
        if (resadmin || res.isOwner(player)) {
            lore.add("&e左鍵點擊：&6管理出售／出租設定");
        } else if (mi.rented && res.getRentedLand() != null && res.getRentedLand().isRenter(player)) {
            lore.add("&e左鍵點擊：&a續租／自動扣款／退租");
        } else if (mi.forSale || (mi.forRent && !mi.rented)) {
            lore.add("&e左鍵點擊：&a購買／租賃此領地");
        } else {
            lore.add("&e左鍵點擊：&7查看租約詳情");
        }
        lore.add("&e右鍵點擊：&f傳送到領地查看");

        return buildItem(icon, "&6" + displayName + badge, lore);
    }

    private String formatMoney(double amount) {
        if (plugin.getEconomyManager() != null) {
            try {
                return plugin.getEconomyManager().format(amount);
            } catch (Exception ignored) {
            }
        }
        return "$" + (long) amount;
    }

    private String formatPlayerBalance(Player player) {
        if (plugin.getEconomyManager() != null) {
            try {
                return plugin.getEconomyManager().format(plugin.getEconomyManager().getBalance(player.getUniqueId()));
            } catch (Exception ignored) {
            }
        }
        return "0";
    }

    private String formatDate(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm");
        return sdf.format(new Date(epochMs));
    }

    private static ItemStack buildItem(CMIMaterial mat, String name) {
        return buildItem(mat, name, null);
    }

    private static ItemStack buildItem(CMIMaterial mat, String name, List<String> lore) {
        CMIMaterial safe = (mat == null || mat == CMIMaterial.NONE || mat == CMIMaterial.AIR) ? CMIMaterial.PAPER : mat;
        ItemStack item = safe.newItemStack();
        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(Material.PAPER);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(CMIChatColor.translate(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> colored = new ArrayList<>(lore.size());
                for (String line : lore) {
                    colored.add(CMIChatColor.translate(line));
                }
                meta.setLore(colored);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }
}
