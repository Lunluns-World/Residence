package com.bekvon.bukkit.residence.auction;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.util.Vector;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.auction.core.ClearMode;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;

/**
 * 保管／管理員拍賣成交後的分批安全清空工作（相容 Paper 與 Folia RegionScheduler）。
 * 僅在拍賣確定成交並脫離 ACTIVE 狀態後執行：
 * - ITEMS_ONLY：先正規移除領地內 QuickShop 商店，再清空所有容器、展示框、盔甲座、掉落物與容器載具。
 * - ALL：執行 ITEMS_ONLY 並分區塊清除範圍內非基岩建築方塊與裝飾實體。
 */
public final class AuctionClearTask {

    private AuctionClearTask() {
    }

    public static void execute(Residence plugin, ClaimedResidence res, ClearMode mode, Runnable onComplete) {
        if (plugin == null || res == null || mode == null || mode == ClearMode.NONE) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // 第一步：先正規移除領地內的 QuickShop 商店，避免商店箱子或懸浮物殘留
        removeQuickShopsInside(plugin, res);

        List<CuboidArea> areas = new ArrayList<>(res.getAreaMap().values());
        if (areas.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        World world = res.getPermissions().getBukkitWorld();
        if (world == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // 收集所有涉及的區塊座標（避免重複處理同一區塊）
        Set<Long> chunkKeys = new LinkedHashSet<>();
        for (CuboidArea area : areas) {
            int minCx = area.getLowVector().getBlockX() >> 4;
            int maxCx = area.getHighVector().getBlockX() >> 4;
            int minCz = area.getLowVector().getBlockZ() >> 4;
            int maxCz = area.getHighVector().getBlockZ() >> 4;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    chunkKeys.add((((long) cx) << 32) | (cz & 0xffffffffL));
                }
            }
        }

        if (chunkKeys.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        AtomicInteger remaining = new AtomicInteger(chunkKeys.size());
        long delayTicks = 1L;
        for (long key : chunkKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            long scheduledDelay = delayTicks;
            delayTicks++;

            Bukkit.getRegionScheduler().runDelayed(plugin, world, cx, cz, task -> {
                try {
                    clearChunk(world, cx, cz, areas, mode);
                } catch (Throwable ex) {
                    plugin.getLogger().log(Level.WARNING, "[Auction] 清空領地 " + res.getName() + " 區塊 (" + cx + "," + cz + ") 時發生例外", ex);
                } finally {
                    if (remaining.decrementAndGet() == 0 && onComplete != null) {
                        Bukkit.getGlobalRegionScheduler().execute(plugin, onComplete);
                    }
                }
            }, scheduledDelay);
        }
    }

    private static void clearChunk(World world, int cx, int cz, List<CuboidArea> areas, ClearMode mode) {
        Chunk chunk = world.getChunkAt(cx, cz);

        // 1. 清空區塊內的容器 TileEntity
        for (BlockState state : chunk.getTileEntities()) {
            Location loc = state.getLocation();
            if (!isInsideAnyArea(loc, areas)) {
                continue;
            }
            if (state instanceof InventoryHolder) {
                ((InventoryHolder) state).getInventory().clear();
            } else if (state instanceof Jukebox) {
                ((Jukebox) state).setRecord(null);
                state.update(true, false);
            } else if (state instanceof Lectern) {
                ((Lectern) state).getInventory().clear();
            } else if (state instanceof ChiseledBookshelf) {
                ((ChiseledBookshelf) state).getInventory().clear();
            }
        }

        // 2. 清理區塊內的掉落物、展示框、盔甲座與儲物載具
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (!isInsideAnyArea(entity.getLocation(), areas)) {
                continue;
            }
            if (entity instanceof Item) {
                entity.remove();
            } else if (entity instanceof ItemFrame) {
                if (mode == ClearMode.ALL) {
                    entity.remove();
                } else {
                    ((ItemFrame) entity).setItem(null);
                }
            } else if (entity instanceof ArmorStand) {
                ArmorStand stand = (ArmorStand) entity;
                if (stand.getEquipment() != null) {
                    stand.getEquipment().clear();
                }
                if (mode == ClearMode.ALL) {
                    stand.remove();
                }
            } else if (entity instanceof InventoryHolder) {
                ((InventoryHolder) entity).getInventory().clear();
                if (mode == ClearMode.ALL && entity instanceof Vehicle) {
                    entity.remove();
                }
            } else if (mode == ClearMode.ALL && entity instanceof Painting) {
                entity.remove();
            }
        }

        // 3. 若為 ALL 模式，清空範圍內所有非基岩方塊（不觸發物理更新避免卡頓）
        if (mode == ClearMode.ALL) {
            int chunkMinX = cx << 4;
            int chunkMaxX = chunkMinX + 15;
            int chunkMinZ = cz << 4;
            int chunkMaxZ = chunkMinZ + 15;

            for (CuboidArea area : areas) {
                Vector low = area.getLowVector();
                Vector high = area.getHighVector();
                int x1 = Math.max(chunkMinX, low.getBlockX());
                int x2 = Math.min(chunkMaxX, high.getBlockX());
                int z1 = Math.max(chunkMinZ, low.getBlockZ());
                int z2 = Math.min(chunkMaxZ, high.getBlockZ());
                int y1 = Math.max(world.getMinHeight(), low.getBlockY());
                int y2 = Math.min(world.getMaxHeight() - 1, high.getBlockY());

                for (int x = x1; x <= x2; x++) {
                    for (int z = z1; z <= z2; z++) {
                        for (int y = y2; y >= y1; y--) {
                            Block b = world.getBlockAt(x, y, z);
                            Material mat = b.getType();
                            if (mat.isAir() || mat == Material.BEDROCK || mat == Material.END_PORTAL_FRAME) {
                                continue;
                            }
                            b.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
    }

    private static boolean isInsideAnyArea(Location loc, List<CuboidArea> areas) {
        if (loc == null) {
            return false;
        }
        for (CuboidArea area : areas) {
            if (area.containsLoc(loc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 透過反射呼叫 QuickShop-Hikari API 正規移除領地內所有商店（避免直接破壞箱子造成孤兒商店）。
     */
    private static void removeQuickShopsInside(Residence plugin, ClaimedResidence res) {
        if (!Bukkit.getPluginManager().isPluginEnabled("QuickShop-Hikari")) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return;
            }
            Object shopManager = api.getClass().getMethod("getShopManager").invoke(api);
            if (shopManager == null) {
                return;
            }
            Collection<?> shops = null;
            try {
                Method byWorld = shopManager.getClass().getMethod("getAllShops", String.class);
                Object raw = byWorld.invoke(shopManager, res.getWorldName());
                if (raw instanceof Collection<?>) {
                    shops = new ArrayList<>((Collection<?>) raw);
                }
            } catch (NoSuchMethodException ignored) {
                Method all = shopManager.getClass().getMethod("getAllShops");
                Object raw = all.invoke(shopManager);
                if (raw instanceof Collection<?>) {
                    shops = new ArrayList<>((Collection<?>) raw);
                }
            }
            if (shops == null || shops.isEmpty()) {
                return;
            }

            Method deleteShopMethod = null;
            for (Method m : shopManager.getClass().getMethods()) {
                if ("deleteShop".equals(m.getName()) && m.getParameterCount() == 1) {
                    deleteShopMethod = m;
                    break;
                }
            }

            int removed = 0;
            for (Object shop : shops) {
                if (shop == null) {
                    continue;
                }
                Object locObj = shop.getClass().getMethod("getLocation").invoke(shop);
                if (!(locObj instanceof Location)) {
                    continue;
                }
                Location loc = (Location) locObj;
                if (loc.getWorld() == null || !loc.getWorld().getName().equals(res.getWorldName())) {
                    continue;
                }
                if (!res.containsLoc(loc)) {
                    continue;
                }
                if (deleteShopMethod != null) {
                    deleteShopMethod.invoke(shopManager, shop);
                } else {
                    shop.getClass().getMethod("delete").invoke(shop);
                }
                removed++;
            }
            if (removed > 0) {
                plugin.getLogger().info("[Auction] 成交清空領地 " + res.getName() + " 前已移除 " + removed + " 個 QuickShop 商店。");
            }
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "[Auction] 移除領地內 QuickShop 商店時發生例外", ex);
        }
    }
}
