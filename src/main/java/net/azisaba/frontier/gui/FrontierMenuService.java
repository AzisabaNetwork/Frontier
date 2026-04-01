package net.azisaba.frontier.gui;

import net.azisaba.frontier.domain.ClaimRecord;
import net.azisaba.frontier.domain.MissionRecord;
import net.azisaba.frontier.domain.OrderRecord;
import net.azisaba.frontier.domain.OrderStatus;
import net.azisaba.frontier.domain.OrderType;
import net.azisaba.frontier.domain.PlayerProfileRecord;
import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.service.FrontierService;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

public final class FrontierMenuService {
    public static final String MAIN_TITLE = "メニュー";
    public static final String SEASON_TITLE = "シーズン";
    public static final String CLAIMS_TITLE = "保護の管理";
    public static final String MISSIONS_TITLE = "ミッション";
    public static final String ORDERS_TITLE = "注文ボード";
    public static final String ORDER_CREATE_TITLE = "注文作成";
    public static final String NEWCOMER_TITLE = "新規プレイヤー";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));
    private static final long ORDER_ICON_TOGGLE_MILLIS = 3_000L;

    private final FrontierService service;
    private final MessageService messages;
    private final Map<UUID, OrderDraft> drafts = new HashMap<>();

    public FrontierMenuService(FrontierService service, MessageService messages) {
        this.service = service;
        this.messages = messages;
    }

    public void openMain(Player player) {
        PlayerProfileRecord profile = this.service.getProfile(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(player, 27, MAIN_TITLE);
        inventory.setItem(10, item(Material.CLOCK, "&eシーズン情報", List.of("&7現在のシーズン状態を確認します", "&8フェーズ・期間・ワールドを表示")));
        inventory.setItem(11, item(Material.MAP, "&bミッション", List.of("&7進行中のミッション一覧です", "&8進捗と報酬を確認")));
        inventory.setItem(12, item(Material.GRASS_BLOCK, "&a保護の管理", List.of("&7自分の保護を管理します", "&8更新・解放・上限確認")));
        inventory.setItem(14, item(Material.BARREL, "&6注文ボード", List.of("&7プレイヤー注文を確認します", "&8予約・納品・新規作成")));
        inventory.setItem(15, item(Material.EMERALD, "&2所持ポイント", List.of("&fCoins: &6" + profile.coins(), "&fSP: &b" + profile.seasonPoints())));
        inventory.setItem(16, item(Material.SUNFLOWER, "&d新規プレイヤー支援", List.of("&7" + this.service.newcomerStatus(player), "&8スターター受取・支援実行")));
        ItemStack phaseBanner = phaseBanner();
        if (phaseBanner != null) {
            inventory.setItem(4, phaseBanner);
        }
        player.openInventory(inventory);
    }

    public void openClaims(Player player) {
        List<ClaimRecord> claims = this.service.getClaims(player.getUniqueId());
        int claimLimit = this.service.claimLimit(player.getUniqueId());
        Long nextUnlock = this.service.nextClaimUnlockSp(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(player, 54, CLAIMS_TITLE);
        int slot = 0;
        for (ClaimRecord claim : claims.stream().limit(45).toList()) {
            inventory.setItem(slot++, item(Material.GRASS_BLOCK, "&a#" + claim.id() + " &f" + displayClaimState(claim.state()), List.of(
                    "&7場所: &f" + claim.world() + " &7(" + claim.chunkX() + ", " + claim.chunkZ() + ")",
                    "&7期限: &f" + DATE_TIME.format(claim.expiresAt()),
                    "&8左クリック: 更新",
                    "&8右クリック: 解放"
            )));
        }
        inventory.setItem(45, item(Material.LECTERN, "&e保護上限", List.of(
                "&7所持数: &f" + claims.size() + " &7/ &f" + claimLimit,
                nextUnlock == null ? "&7次の解放: &f最大まで到達済み" : "&7次の解放: &b累計 " + nextUnlock + " SP"
        )));
        inventory.setItem(49, item(Material.ARROW, "&7戻る", List.of("&8メインメニューへ戻ります")));
        player.openInventory(inventory);
    }

    public void openSeason(Player player) {
        var season = this.service.getActiveSeason().orElseThrow(() -> new net.azisaba.frontier.util.UserMessageException("error.no_active_season"));
        int claimLimit = this.service.claimLimit(player.getUniqueId());
        Long nextUnlock = this.service.nextClaimUnlockSp(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(player, 27, SEASON_TITLE);
        inventory.setItem(11, item(Material.CLOCK, "&e" + season.displayName(), List.of(
                "&7キー: &f" + season.key(),
                "&7フェーズ: &f" + displaySeasonPhase(season.phase()),
                "&7ワールド: &f" + season.worldName()
        )));
        inventory.setItem(15, item(Material.PAPER, "&bスケジュール", List.of(
                "&7作成: &f" + formatInstant(season.createdAt()),
                "&7開始: &f" + formatInstant(season.startAt()),
                "&7終了: &f" + formatInstant(season.endAt()),
                "&7アーカイブ: &f" + formatInstant(season.archiveAt())
        )));
        inventory.setItem(13, item(Material.GRASS_BLOCK, "&a保護拡張", List.of(
                "&7現在上限: &f" + claimLimit,
                nextUnlock == null ? "&7次の解放: &f最大まで到達済み" : "&7次の解放: &b累計 " + nextUnlock + " SP"
        )));
        inventory.setItem(22, item(Material.ARROW, "&7戻る", List.of("&8メインメニューへ戻ります")));
        player.openInventory(inventory);
    }

    public void openMissions(Player player) {
        List<MissionRecord> missions = this.service.getActiveMissions();
        Inventory inventory = Bukkit.createInventory(player, 54, MISSIONS_TITLE);
        int slot = 0;
        for (MissionRecord mission : missions.stream().limit(45).toList()) {
            long progress = this.service.getMissionProgress(player.getUniqueId(), mission.id()).progress();
            inventory.setItem(slot++, item(Material.FILLED_MAP, "&f" + mission.title(), List.of(
                    mission.scope() == net.azisaba.frontier.domain.MissionScope.DAILY ? "&eデイリー" : "&dウィークリー",
                    "&7進捗: &f" + progress + " / " + mission.targetValue(),
                    "&7内容: &f" + mission.description(),
                    "&7報酬: &6" + mission.rewardCoins() + " Coins &7/ &b" + mission.rewardPoints() + " SP"
            )));
        }
        inventory.setItem(49, item(Material.ARROW, "&7戻る", List.of("&8メインメニューへ戻ります")));
        player.openInventory(inventory);
    }

    public void openOrders(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 54, ORDERS_TITLE);
        this.renderOrdersInventory(player, inventory);
        player.openInventory(inventory);
    }

    public void refreshOpenOrderMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top == null || !ORDERS_TITLE.equals(player.getOpenInventory().getTitle())) {
                continue;
            }
            this.renderOrdersInventory(player, top);
            player.updateInventory();
        }
    }

    public void openOrderCreate(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            this.messages.send(player, "error.menu_order_item_required", java.util.Map.of("prefix", this.messages.get("prefix")));
            return;
        }
        OrderDraft draft = this.drafts.compute(player.getUniqueId(), (uuid, existing) -> {
            String itemKey = held.getType().getKey().asString();
            if (existing != null && existing.itemKey.equals(itemKey)) {
                return existing;
            }
            return new OrderDraft(OrderType.SELL_ITEM, itemKey, Math.max(1, held.getAmount()), 10L, 24);
        });
        Inventory inventory = Bukkit.createInventory(player, 45, ORDER_CREATE_TITLE);
        inventory.setItem(10, item(Material.COMPASS, "&e種別: &f" + displayOrderType(draft.type), List.of("&8クリック: 買取 / 販売 を切替")));
        inventory.setItem(13, item(held.getType(), "&bアイテム: &f" + draft.itemKey, List.of("&7メインハンドの内容を使います")));
        inventory.setItem(20, item(Material.CHEST, "&a数量: &f" + draft.amount, List.of("&8左: +1  Shift左: +16", "&8右: -1  Shift右: -16")));
        inventory.setItem(22, item(Material.GOLD_INGOT, "&6単価: &f" + draft.unitPrice + "コイン", List.of("&8左: +10  Shift左: +100", "&8右: -10  Shift右: -100")));
        inventory.setItem(24, item(Material.CLOCK, "&d時間: &f" + draft.hours + "時間", List.of("&8左: +1時間  Shift左: +24時間", "&8右: -1時間  Shift右: -24時間")));
        inventory.setItem(31, item(Material.LIME_CONCRETE, "&aこの内容で作成", List.of("&7現在の設定で注文を作成します")));
        inventory.setItem(40, item(Material.BARRIER, "&cキャンセル", List.of("&8注文一覧へ戻ります")));
        player.openInventory(inventory);
    }

    public void openNewcomer(Player player) {
        PlayerProfileRecord profile = this.service.getProfile(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(player, 54, NEWCOMER_TITLE);
        inventory.setItem(10, item(Material.CHEST, "&eスターター特典", List.of(
                "&7" + this.service.newcomerStatus(player),
                profile.starterClaimed() ? "&8受取済み" : "&8クリック: 受け取る"
        )));
        int slot = 19;
        for (Player target : Bukkit.getOnlinePlayers().stream().filter(other -> !other.getUniqueId().equals(player.getUniqueId())).limit(21).toList()) {
            inventory.setItem(slot++, item(Material.PLAYER_HEAD, "&d支援: &f" + target.getName(), List.of(
                    "&7" + this.service.newcomerStatus(target),
                    "&8対象ならクリックで支援"
            )));
        }
        inventory.setItem(49, item(Material.ARROW, "&7戻る", List.of("&8メインメニューへ戻ります")));
        player.openInventory(inventory);
    }

    public void handleMenuClick(Player player, String title, int slot, ItemStack clicked, ClickType click) {
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        switch (title) {
            case MAIN_TITLE -> {
                if (slot == 10) {
                    this.openSeason(player);
                } else if (slot == 11) {
                    this.openMissions(player);
                } else if (slot == 12) {
                    this.openClaims(player);
                } else if (slot == 14) {
                    this.openOrders(player);
                } else if (slot == 16) {
                    this.openNewcomer(player);
                }
            }
            case CLAIMS_TITLE -> {
                if (slot == 49) {
                    this.openMain(player);
                    return;
                }
                long claimId = parsePrefixedId(clicked, "#");
                if (claimId <= 0) {
                    return;
                }
                if (click.isRightClick()) {
                    this.service.releaseClaim(player, claimId);
                    this.messages.send(player, "claim.released", java.util.Map.of("prefix", this.messages.get("prefix"), "id", Long.toString(claimId)));
                } else {
                    ClaimRecord claim = this.service.renewClaim(player, claimId);
                    this.messages.send(player, "claim.renewed", java.util.Map.of(
                            "prefix", this.messages.get("prefix"),
                            "id", Long.toString(claim.id()),
                            "expires", DATE_TIME.format(claim.expiresAt())
                    ));
                }
                this.openClaims(player);
            }
            case SEASON_TITLE -> {
                if (slot == 22) {
                    this.openMain(player);
                }
            }
            case MISSIONS_TITLE -> {
                if (slot == 49) {
                    this.openMain(player);
                }
            }
            case ORDERS_TITLE -> {
                if (slot == 49) {
                    this.openMain(player);
                    return;
                }
                if (slot == 53) {
                    this.openOrderCreate(player);
                    return;
                }
                long orderId = parsePrefixedId(clicked, "#");
                if (orderId <= 0) {
                    return;
                }
                OrderRecord order = this.service.listOrders().stream().filter(it -> it.id() == orderId).findFirst().orElse(null);
                if (order == null) {
                    this.messages.send(player, "error.order_not_found_or_closed", java.util.Map.of("prefix", this.messages.get("prefix")));
                    return;
                }
                if (order.status() == OrderStatus.OPEN) {
                    this.service.reserveOrder(player, orderId);
                    this.messages.send(player, "order.reserved", java.util.Map.of("prefix", this.messages.get("prefix"), "id", Long.toString(orderId)));
                } else if (order.status() == OrderStatus.RESERVED && player.getUniqueId().equals(order.reservedByUuid())) {
                    this.service.deliverOrder(player, orderId);
                    this.messages.send(player, "order.completed", java.util.Map.of("prefix", this.messages.get("prefix"), "id", Long.toString(orderId)));
                }
                this.openOrders(player);
            }
            case ORDER_CREATE_TITLE -> {
                if (slot == 40) {
                    this.drafts.remove(player.getUniqueId());
                    this.openOrders(player);
                    return;
                }
                OrderDraft draft = this.drafts.get(player.getUniqueId());
                if (draft == null) {
                    this.openOrderCreate(player);
                    return;
                }
                OrderDraft updated = switch (slot) {
                    case 10 -> draft.withType(draft.type == OrderType.BUY_ITEM ? OrderType.SELL_ITEM : OrderType.BUY_ITEM);
                    case 20 -> draft.withAmount(adjustLong(click, draft.amount, 1L, 16L, 1L, 4096L));
                    case 22 -> draft.withUnitPrice(adjustLong(click, draft.unitPrice, 10L, 100L, 1L, 1_000_000_000L));
                    case 24 -> draft.withHours((int) adjustLong(click, draft.hours, 1L, 24L, 1L, 168L));
                    case 31 -> {
                        OrderRecord order = this.service.createPlayerOrder(player, draft.type, draft.itemKey, draft.amount, draft.unitPrice, draft.hours);
                        this.messages.send(player, "order.created", java.util.Map.of("prefix", this.messages.get("prefix"), "id", Long.toString(order.id()), "fee", Long.toString(order.fee())));
                        this.drafts.remove(player.getUniqueId());
                        this.openOrders(player);
                        yield null;
                    }
                    default -> draft;
                };
                if (updated != null) {
                    this.drafts.put(player.getUniqueId(), updated);
                    this.openOrderCreate(player);
                }
            }
            case NEWCOMER_TITLE -> {
                if (slot == 49) {
                    this.openMain(player);
                    return;
                }
                if (slot == 10) {
                    this.service.claimStarter(player);
                    this.messages.send(player, "newcomer.starter_claimed", java.util.Map.of("prefix", this.messages.get("prefix")));
                    this.openNewcomer(player);
                    return;
                }
                String name = parseSupportTarget(clicked);
                if (name == null) {
                    return;
                }
                Player target = Bukkit.getPlayerExact(name);
                if (target == null) {
                    this.messages.send(player, "error.player_must_be_online", java.util.Map.of("prefix", this.messages.get("prefix")));
                    this.openNewcomer(player);
                    return;
                }
                this.service.supportNewcomer(player, target);
                this.messages.send(player, "newcomer.supported", java.util.Map.of("prefix", this.messages.get("prefix"), "player", target.getName()));
                this.openNewcomer(player);
            }
            default -> {
            }
        }
    }

    public boolean isMenuTitle(String title) {
        return MAIN_TITLE.equals(title)
                || SEASON_TITLE.equals(title)
                || CLAIMS_TITLE.equals(title)
                || MISSIONS_TITLE.equals(title)
                || ORDERS_TITLE.equals(title)
                || ORDER_CREATE_TITLE.equals(title)
                || NEWCOMER_TITLE.equals(title);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(FrontierMenuService::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack phaseBanner() {
        return switch (this.service.currentSeasonPhase()) {
            case PRESEASON -> item(Material.YELLOW_STAINED_GLASS_PANE, "&e準備中", List.of("&7現在はプレシーズンです", "&8通常プレイ系の機能は停止中"));
            case OPENING -> item(Material.LIME_STAINED_GLASS_PANE, "&a開幕期間", List.of("&7現在はオープニングです", "&8一部機能に制限があります"));
            case ACTIVE -> null;
            case FINALE -> item(Material.ORANGE_STAINED_GLASS_PANE, "&6終盤期間", List.of("&7現在はフィナーレです", "&8一部の新規要素は制限されています"));
            case ARCHIVED -> item(Material.GRAY_STAINED_GLASS_PANE, "&7アーカイブ済み", List.of("&7このシーズンは終了しています", "&8進捗系の機能は停止中"));
        };
    }

    private void renderOrdersInventory(Player player, Inventory inventory) {
        inventory.clear();
        boolean showOrderItem = this.showOrderItemIcon();
        int slot = 0;
        for (OrderRecord order : this.service.listOrders().stream().limit(45).toList()) {
            Material icon = showOrderItem ? resolveOrderItem(order.itemKey()) : orderStatusIcon(order.status());
            List<String> lore = new ArrayList<>();
            lore.add("&7種別: &f" + displayOrderType(order.orderType()));
            lore.add("&7アイテム: &f" + order.itemKey());
            lore.add("&7数量: &f" + order.amount() + "   &7単価: &6" + order.unitPrice() + "コイン");
            lore.add("&7所有者: &f" + order.ownerName());
            lore.add("&7状態: &f" + displayOrderStatus(order.status()));
            lore.add(showOrderItem ? "&8表示: 注文アイテム" : "&8表示: 注文状態");
            if (order.reservedByName() != null) {
                lore.add("&7予約者: &f" + order.reservedByName());
            }
            if (order.status() == OrderStatus.OPEN) {
                lore.add("&8クリック: 予約");
            } else if (order.status() == OrderStatus.RESERVED && player.getUniqueId().equals(order.reservedByUuid())) {
                lore.add("&8クリック: 納品");
            }
            inventory.setItem(slot++, item(icon, "&6#" + order.id(), lore));
        }
        inventory.setItem(53, item(Material.WRITABLE_BOOK, "&a注文を作成", List.of(
                "&7メインハンドのアイテムを使います",
                "&8種別・数量・価格・時間を設定"
        )));
        inventory.setItem(49, item(Material.ARROW, "&7戻る", List.of("&8メインメニューへ戻ります")));
    }

    private boolean showOrderItemIcon() {
        return (System.currentTimeMillis() / ORDER_ICON_TOGGLE_MILLIS) % 2L == 1L;
    }

    private static Material orderStatusIcon(OrderStatus status) {
        return switch (status) {
            case OPEN -> Material.BARREL;
            case RESERVED -> Material.CHEST_MINECART;
            case COMPLETED -> Material.MINECART;
            case EXPIRED -> Material.HOPPER_MINECART;
            case CANCELLED -> Material.TNT_MINECART;
        };
    }

    private static Material resolveOrderItem(String itemKey) {
        Material material = Material.matchMaterial(itemKey);
        if (material == null && itemKey.startsWith("minecraft:")) {
            material = Material.matchMaterial(itemKey.substring("minecraft:".length()));
        }
        if (material == null) {
            String enumName = itemKey.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace(':', '_');
            try {
                material = Material.valueOf(enumName);
            } catch (IllegalArgumentException ignored) {
                material = null;
            }
        }
        return material == null || material.isAir() ? Material.PAPER : material;
    }

    private static long parsePrefixedId(ItemStack clicked, String prefix) {
        if (clicked.getItemMeta() == null || clicked.getItemMeta().getDisplayName() == null) {
            return -1L;
        }
        String raw = clicked.getItemMeta().getDisplayName();
        if (!raw.startsWith(prefix)) {
            return -1L;
        }
        int end = raw.indexOf(' ');
        String number = end == -1 ? raw.substring(prefix.length()) : raw.substring(prefix.length(), end);
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String parseSupportTarget(ItemStack clicked) {
        if (clicked.getItemMeta() == null || clicked.getItemMeta().getDisplayName() == null) {
            return null;
        }
        String raw = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (raw.startsWith("支援: ")) {
            return raw.substring("支援: ".length());
        }
        return null;
    }

    private static String displayClaimState(net.azisaba.frontier.domain.ClaimState state) {
        return switch (state) {
            case ACTIVE -> "有効";
            case WARNING -> "警告";
            case EXPIRED -> "期限切れ";
            case ABANDONED -> "放棄";
            case FROZEN -> "凍結";
        };
    }

    private static String displayOrderType(OrderType type) {
        return switch (type) {
            case BUY_ITEM -> "買取";
            case SELL_ITEM -> "販売";
        };
    }

    private static String displayOrderStatus(OrderStatus status) {
        return switch (status) {
            case OPEN -> "受付中";
            case RESERVED -> "予約中";
            case COMPLETED -> "完了";
            case EXPIRED -> "期限切れ";
            case CANCELLED -> "取消";
        };
    }

    private static String displaySeasonPhase(net.azisaba.frontier.domain.SeasonPhase phase) {
        return switch (phase) {
            case PRESEASON -> "プレシーズン";
            case OPENING -> "オープニング";
            case ACTIVE -> "進行中";
            case FINALE -> "フィナーレ";
            case ARCHIVED -> "アーカイブ";
        };
    }

    private static String formatInstant(java.time.Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant);
    }

    private static long adjustLong(ClickType click, long current, long step, long shiftStep, long min, long max) {
        long delta;
        if (click.isRightClick()) {
            delta = click.isShiftClick() ? -shiftStep : -step;
        } else {
            delta = click.isShiftClick() ? shiftStep : step;
        }
        long next = current + delta;
        if (next < min) {
            return min;
        }
        return Math.min(next, max);
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private record OrderDraft(OrderType type, String itemKey, long amount, long unitPrice, int hours) {
        private OrderDraft withType(OrderType newType) {
            return new OrderDraft(newType, this.itemKey, this.amount, this.unitPrice, this.hours);
        }

        private OrderDraft withAmount(long newAmount) {
            return new OrderDraft(this.type, this.itemKey, newAmount, this.unitPrice, this.hours);
        }

        private OrderDraft withUnitPrice(long newUnitPrice) {
            return new OrderDraft(this.type, this.itemKey, this.amount, newUnitPrice, this.hours);
        }

        private OrderDraft withHours(int newHours) {
            return new OrderDraft(this.type, this.itemKey, this.amount, this.unitPrice, newHours);
        }
    }
}
