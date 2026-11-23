# 保管領地與玩家領地拍賣功能規格（Residence Fork v2 最終定案版）

> 本規格已依現有 Residence Fork（Paper 1.21.8 Leaves / Folia、Velocity、MariaDB 11、XConomy、DiscordSRV、QuickShop-Hikari）架構定案，並補齊防漏與回退機制。

## 背景

- 伺服器：多個 Paper 分流（分流-1、分流-2、副本分流），前端為 Velocity，資料庫 MariaDB 11，可選 Redis（停用或斷線時自動回退 MariaDB 輪詢）
- 領地插件：自有 Fork 的 Residence
- 玩家超過 365 日未上線，其領地轉移給系統帳號 `Server_Unclaimed`（保管狀態），保留原權限設定
- 玩家重新登入任一分流時，保管中的領地自動歸還
- Residence 原有的出售功能只能固定價格，本次新增「喊價拍賣」：
  - **玩家拍賣（`PLAYER`）**：玩家拍賣自己的領地（一律現況交付）
  - **保管／管理員拍賣（`ARCHIVED` / `ADMIN`）**：管理人員拍賣 `Server_Unclaimed` 保管中領地或違規沒收領地
- 遊戲幣：XConomy（透過 Vault API，跨服同步）

## 共用跨服即時匯流排（Redis + MariaDB 自動回退）

- **MariaDB 永遠為唯一真實資料來源（Source of Truth）與交易鎖**，確保重啟與異常時資料零遺失。
- **Redis Pub/Sub（`RedisSyncBus`）** 作為選配跨服即時事件層，啟用時同步加速全系統：
  - 跨服領地索引與 `/res gui` 資訊即時更新
  - `/resadmin limit` 跨服額度即時同步
  - 過期領地跨服自動歸還即時觸發
  - 拍賣上架／喊價／取消／中止／結標即時全服公告與即時刷新開啟中的拍賣 GUI
- **自動回退機制**：`Redis.Enabled: false`、開機連不上、或運行中斷線時，靜默背景重連（不洗頻報錯）並無縫回退 MariaDB 輪詢，所有功能照常運作。

## 領地狀態與前置檢查

| 狀態 | 說明 |
|---|---|
| 一般 | 玩家持有 |
| 保管中 | 轉移給 `Server_Unclaimed`，原領地主授權的玩家仍可依原權限使用 |
| 拍賣中 | 玩家或管理人員開啟拍賣，領地與內部 QuickShop 商店全面凍結 |
| 已成交 | 立即移轉給得標者（含屬於原領主／賣方的子領地） |

開拍前置檢查：
- 同一塊領地（以領地 UUID 判斷）同時只能有一場進行中拍賣。
- 主領地與所有子領地皆不得處於出租中、待租中或固定價出售中，且不得包含屬於第三方的子領地。
- **重上架冷卻**：同一塊領地（以領地 UUID 判斷，改名無效）取消或流標後，預設需等待 **24 小時**（`Auction.RelistCooldownHours: 24`）才可重新上架（管理員可繞過）。

## 開啟拍賣（強制條款同意頁 + 全 GUI）

- **開拍前強制「條款同意頁」GUI**：
  - 必須點擊「我已知曉並同意上述條款」物品後，才能進入開拍設定 GUI。
  - 條款清楚告知：拍賣期間領地與內部 QuickShop 商店將全面凍結（不可破壞／放置／開箱／抽貨／交易／刪除商店／刪改縮放領地）、有人喊價後賣方不得取消、成交扣除手續費並清除領地與子領地權限、取消後重上架需冷卻 24 小時。
- **開拍參數設定**：
  - 起標價（須 ≥ `Auction.MinStartPrice`）
  - 每次最低加價金額（預設 `Auction.DefaultMinIncrement`，拍賣期間可由開啟者修改，只套用到之後的喊價）
  - 拍賣時長（介於 `MinDurationMinutes` ～ `MaxDurationHours`）
  - 大致說明文字
  - **清空模式（僅限管理員開拍設定；玩家拍賣固定 `NONE` 現況交付）**：
    - `NONE`：不清除（現況交付）
    - `ITEMS_ONLY`：僅清除物品（保留建築，清空所有容器物品、展示框／盔甲座物品與 QuickShop 商店）
    - `ALL`：連同建築與物品一起清除（先刪 QuickShop 商店，再分批清空容器、實體與非基岩建築方塊）
  - **清空執行時機**：統一在**確定成交移轉給得標者時**分批安全執行，確保流標或原領主中途回歸時建築與物品完好無損。
- **拍賣期間全面凍結（玩家拍賣與管理員拍賣皆適用）**：
  - 完整**備份原權限設定**（含所有子領地），供流標／取消／原領主回歸時還原。
  - 暫停所有玩家權限與區域建構／容器／紅石活塞／爆炸／流體／撿物／傷害旗標，僅開放所有玩家「進入（`move`）」與「傳送（`tp`）」看房。
  - 攔截跨邊界漏斗／漏斗礦車抽物、活塞推拉、領地刪除／改名／縮放／轉讓／上架買賣與出租。
  - 透過 `Compat-Residence` 鎖定領地內所有 QuickShop 商店：**禁止購買／收購、禁止新建、禁止刪除（含原店主）、禁止改價／換貨／抽庫存**。
- **角色限制**：
  - 玩家拍賣：只能拍賣自己擁有的領地；已有喊價後賣方不得取消；賣方不得對自己喊價；限制每人同時拍賣場次上限（`Auction.MaxActivePerPlayer`）。
  - 保管領地拍賣：由管理人員開啟，記錄原領地主 UUID；原領地主本人與管理人員（權限節點 `residence.auction.staff` / `residence.admin`）不得喊價。

## 喊價與金流（悲觀鎖 + Outbox 冪等退款）

- 支援指令與 GUI，送出前顯示**確認畫面**，明確列出：
  - 喊價金額、目前最高價、成交清空模式說明
  - 「成交後所有權限將清除，子領地保留但權限清除，請自行重新設定」
  - 「領地現況交付，不保證內容」（若設為 `ITEMS_ONLY` 或 `ALL` 則明確標示清除範圍）
- 檢查：
  - 拍賣狀態為 `ACTIVE` 且未過期
  - 首次喊價 ≥ 起標價；後續喊價 ≥ 目前最高價 + 最低加價金額
  - 喊價者餘額足夠、不可重複自我加價（已是最高出價者不可再喊）、非賣方（保管領地亦非原領地主與管理人員）
- **防尾盤狙擊**：最後 `Auction.AntiSnipeSeconds` 秒內（預設 60 秒）有人出價，自動延長結束時間 `Auction.AntiSnipeExtendSeconds` 秒（上限 `MaxAntiSnipeExtensions` 次防無限拖延）。
- **交易與 Outbox 保護**：
  1. 以 MariaDB `SELECT ... FOR UPDATE` 鎖定拍賣列並檢查狀態與金額。
  2. 先扣除新喊價者 Vault/XConomy 餘額（失敗立即回滾鎖）。
  3. 同一個 DB 交易內更新最高出價者／最高價、寫入 `res_auction_bids` 與 `res_auction_tx_log`，並將前一位最高價者的退款寫入 `res_auction_payouts`（`PENDING`）後 `COMMIT`。
  4. `COMMIT` 後立即嘗試退款並以條件更新 `WHERE status='PENDING'` 標記 `COMPLETED`；若瞬間失敗或關服，背景重試自動補發，保證不吞錢、不重複退款。

## 拍賣中止（保管領地原領主回歸）

- 原領主登入**任一分流**時：
  - 登入分流立即在 MariaDB 交易中檢查並將該場拍賣原子標記為 `ABORT_PENDING`（瞬間阻擋任何分流的新喊價與結標），並透過 Redis（或 MariaDB 輪詢）通知領地所屬分流（`origin_server`）。
  - `origin_server` 接手完成：寫入目前最高價者退款單並立即退款、還原開拍前備份的所有領地與子領地權限、歸還領地給原領主、標記拍賣為 `ABORTED`、全服公告與通知所有曾喊價的玩家。
- `CleanupReclaimManager` 與拍賣中止共用同一資料庫狀態檢查，避免競態重複歸還。

## 拍賣結束（直接轉讓＋直接入帳＋確認通知）

- **時間到且有人喊價（由 `origin_server` 鎖定 `SETTLING` 後執行）**：
  - **直接移轉領地與所有子領地給得標者**（不受 `MaxRes` 上限卡死），清除領地與子領地所有權限。
  - 若管理員設定 `ITEMS_ONLY` 或 `ALL`，於脫離 `ACTIVE` 狀態後先正規移除領地內 QuickShop 商店，再分批安全清理容器物品／實體／建築方塊。
  - 保管金額扣除手續費（`Auction.FeePercent`，預設 10%）後，透過 Outbox **直接入帳**給賣方（玩家拍賣）或原領地主（保管領地拍賣），並寫入 `res_auction_tx_log`。
  - 寫入待確認通知（`res_auction_notifications`）給得標者與原領主／賣方（含領地名稱、座標、成交金額、手續費、實收金額）。在線立即提示，離線於下次登入任一分流提醒；點擊 **「我知道了」** 記錄 `ack_at` 後不再重複提醒。
- **時間到且無人喊價（流標）**：
  - 還原開拍前備份的所有領地與子領地權限。
  - 保管領地回到「保管中」；玩家拍賣回到「一般」狀態，並通知賣方。

## 全服公告與 DiscordSRV 通知

- 上架、有人喊價、取消／中止、結標時，透過 `RedisSyncBus`（或 MariaDB 輪詢）向所有分流廣播全服公告。
- 透過 optional `DiscordSRV` 整合：**事件發生在哪一台分流，就由該分流的 DiscordSRV 機器人發送 Embed 到設定頻道**（`Auction.Discord.Channel`）。

## QuickShop 整合（`Compat-Residence`）

- 新增 `quickshop-remove` 領地權限旗標：擁有該旗標的玩家僅可**移除**領地內的 QuickShop 商店（對應 `BuiltInShopPermission.DELETE`），不可修改價格、商品或存取庫存。
- 領地處於 `ACTIVE` 拍賣中時全面鎖定內部 QuickShop 商店（禁止購買／收購、禁止新建、禁止刪除、禁止修改與存取庫存）。

## 設定項目（PascalCase，統一 Residence 風格）

```yaml
Redis:
  Enabled: false
  Host: localhost
  Port: 6379
  Password: ''
  Database: 0
  ChannelPrefix: 'residence:'
  TimeoutMs: 3000

Auction:
  Enabled: true
  DefaultMinIncrement: 100
  MinStartPrice: 100
  FeePercent: 10
  MinDurationMinutes: 60
  MaxDurationHours: 336
  AntiSnipeSeconds: 60
  AntiSnipeExtendSeconds: 60
  MaxAntiSnipeExtensions: 30
  RelistCooldownHours: 24
  MaxActivePerPlayer: 3
  AnnounceBiddersOnCancel: true
  BroadcastEnabled: true
  StaffBypassPermission: 'residence.auction.staff'
  Discord:
    Enabled: true
    Channel: 'auction'
```

## 開發規範

- 程式碼註解與 commit 訊息使用繁體中文
- Git commit 一律使用 `TW527E <github@tw527e.zip>`
- 喊價檢查、狀態轉換、金額計算、冷卻與防狙擊等核心邏輯與 Bukkit API 分離，並撰寫 JUnit 單元測試
