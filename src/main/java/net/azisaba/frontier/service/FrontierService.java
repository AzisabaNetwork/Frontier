package net.azisaba.frontier.service;

import net.azisaba.frontier.audit.AuditService;
import net.azisaba.frontier.domain.*;
import net.azisaba.frontier.integration.economy.FrontierEconomy;
import net.azisaba.frontier.integration.worldguard.ClaimProtection;
import net.azisaba.frontier.repository.FrontierRepositories;
import net.azisaba.frontier.storage.FrontierDataStore;
import net.azisaba.frontier.util.UserMessageException;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public final class FrontierService {
    private final JavaPlugin plugin;
    private final FrontierRepositories repositories;
    private final Clock clock;
    private final List<ClaimNotification> pendingClaimNotifications = new ArrayList<>();
    private Optional<SeasonRecord> cachedActiveSeason = Optional.empty();
    private boolean activeSeasonCacheLoaded;
    private final Map<String, ClaimRecord> claimByChunkCache = new HashMap<>();
    private boolean claimChunkCacheLoaded;
    private FrontierEconomy economy;
    private ClaimProtection claimProtection;
    private AuditService auditService;

    public FrontierService(JavaPlugin plugin, FrontierDataStore dataStore) {
        this(plugin, new net.azisaba.frontier.repository.YamlFrontierRepositories(dataStore), Clock.system(ZoneId.of(plugin.getConfig().getString("plugin.timezone", "Asia/Tokyo"))));
    }

    public FrontierService(JavaPlugin plugin, FrontierRepositories repositories, Clock clock) {
        this.plugin = plugin;
        this.repositories = repositories;
        this.clock = clock;
    }

    public void attachIntegrations(FrontierEconomy economy, ClaimProtection claimProtection, AuditService auditService) {
        this.economy = economy;
        this.claimProtection = claimProtection;
        this.auditService = auditService;
    }

    public long coinBalance(UUID playerId) {
        return this.economy == null ? this.getProfile(playerId).coins() : this.economy.balance(playerId);
    }

    public String economyMode() {
        return this.economy == null ? "unconfigured" : this.economy.mode();
    }

    public String claimProtectionMode() {
        return this.claimProtection == null ? "unconfigured" : this.claimProtection.mode();
    }

    public boolean hasPhaseOverride(Player player) {
        return player.hasPermission("frontier.bypass") || player.hasPermission("frontier.admin");
    }

    public SeasonPhase currentSeasonPhase() {
        return this.getActiveSeason().map(SeasonRecord::phase).orElse(SeasonPhase.PRESEASON);
    }

    public boolean isGameplayEnabled() {
        return this.getActiveSeason().map(this::isGameplayEnabled).orElse(false);
    }

    public void save() {
        this.repositories.flush();
    }

    public Optional<SeasonRecord> getActiveSeason() {
        if (!this.activeSeasonCacheLoaded) {
            this.cachedActiveSeason = this.repositories.activeSeason();
            this.activeSeasonCacheLoaded = true;
        }
        return this.cachedActiveSeason;
    }

    public List<SeasonRecord> getSeasons() {
        return new ArrayList<>(this.repositories.seasons());
    }

    public String getDefaultSeasonWorld() {
        return this.plugin.getConfig().getString("season.active_world", "world");
    }

    public SeasonRecord createSeason(String key, String displayName, String worldName) {
        if (this.repositories.seasons().stream().anyMatch(season -> season.key().equalsIgnoreCase(key))) {
            throw fail("error.season_key_exists", "key", key);
        }
        if (this.getActiveSeason().isPresent()) {
            throw fail("error.active_season_exists");
        }
        long id = this.repositories.nextSeasonId();
        SeasonRecord season = new SeasonRecord(id, key, displayName, worldName, SeasonPhase.PRESEASON, this.now(), null, null, null, true);
        this.saveSeasonRecord(season);
        this.rotateMissions(season, MissionScope.DAILY);
        this.rotateMissions(season, MissionScope.WEEKLY);
        this.repositories.setLastDailyRotationDate(LocalDate.now(this.clock).toString());
        this.repositories.setLastWeeklyRotationDate(LocalDate.now(this.clock).with(DayOfWeek.MONDAY).toString());
        this.audit("season_created", "system", Map.of("seasonId", id, "key", key, "display", displayName, "world", worldName));
        this.save();
        return season;
    }

    public SeasonRecord setPhase(SeasonPhase target) {
        SeasonRecord season = this.getRequiredSeason();
        if (season.phase() == target) {
            return season;
        }
        if (!season.phase().canTransitionTo(target)) {
            throw fail("error.invalid_phase_transition", "from", season.phase().name(), "to", target.name());
        }
        SeasonRecord updated = season.withPhase(target);
        this.saveSeasonRecord(updated);
        if (target == SeasonPhase.ARCHIVED) {
            this.freezeClaims(updated.id());
        }
        this.audit("season_phase_changed", "system", Map.of("seasonId", updated.id(), "phase", updated.phase().name()));
        this.save();
        return updated;
    }

    public PlayerProfileRecord touchProfile(Player player) {
        SeasonRecord season = this.getRequiredSeason();
        String key = profileKey(player.getUniqueId(), season.id());
        PlayerProfileRecord current = this.repositories.findProfile(key);
        if (current == null) {
            current = new PlayerProfileRecord(player.getUniqueId(), player.getName(), season.id(), 0L, 0L, 0, 0, false, null, null, this.now(), this.now());
        } else {
            current = current.touch(player.getName());
        }
        this.repositories.saveProfile(key, current);
        this.extendClaimsForOwner(player.getUniqueId());
        this.save();
        return current;
    }

    public PlayerProfileRecord getProfile(UUID playerId) {
        SeasonRecord season = this.getRequiredSeason();
        String key = profileKey(playerId, season.id());
        PlayerProfileRecord profile = this.repositories.findProfile(key);
        if (profile != null) {
            return profile;
        }
        return new PlayerProfileRecord(playerId, Bukkit.getOfflinePlayer(playerId).getName(), season.id(), 0L, 0L, 0, 0, false, null, null, this.now(), this.now());
    }

    public void addCoins(UUID playerId, long amount) {
        this.addCoins(playerId, amount, "unspecified");
    }

    public void addCoins(UUID playerId, long amount, String reason) {
        if (Math.abs(amount) > 1_000_000_000L) {
            throw fail("error.invalid_range_number", "label", "amount", "min", "-1000000000", "max", "1000000000");
        }
        if (amount > 0) {
            this.economy.deposit(playerId, amount);
        } else if (amount < 0) {
            this.economy.withdraw(playerId, -amount);
        }
        this.audit("coins", playerId.toString(), Map.of("delta", amount, "reason", reason));
    }

    public void addSeasonPoints(UUID playerId, long amount) {
        this.addSeasonPoints(playerId, amount, "unspecified");
    }

    public void addSeasonPoints(UUID playerId, long amount, String reason) {
        this.updateProfile(playerId, profile -> profile.withBalances(profile.coins(), profile.seasonPoints() + amount));
        this.audit("season_points", playerId.toString(), Map.of("delta", amount, "reason", reason));
    }

    public void setCoins(UUID playerId, long amount) {
        this.updateProfile(playerId, profile -> profile.withBalances(amount, profile.seasonPoints()));
    }

    public List<ClaimRecord> getClaims(UUID ownerUuid) {
        return this.repositories.claims().stream()
                .filter(claim -> claim.ownerUuid().equals(ownerUuid) && claim.state() != ClaimState.ABANDONED)
                .sorted(Comparator.comparing(ClaimRecord::createdAt))
                .toList();
    }

    public List<ClaimRecord> allClaims() {
        return this.repositories.claims().stream()
                .filter(claim -> claim.state() != ClaimState.ABANDONED)
                .sorted(Comparator.comparing(ClaimRecord::createdAt))
                .toList();
    }

    public Optional<ClaimRecord> getClaimAt(Chunk chunk) {
        this.ensureClaimChunkCacheLoaded();
        ClaimRecord claim = this.claimByChunkCache.get(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (claim == null || claim.state() == ClaimState.ABANDONED) {
            return Optional.empty();
        }
        return Optional.of(claim);
    }

    public ClaimRecord createClaim(Player player) {
        SeasonRecord season = this.getRequiredSeason();
        this.assertSeasonEditable(season);
        this.assertClaimCreateAllowed(season, player);
        Chunk chunk = player.getLocation().getChunk();
        if (!this.isClaimsWorldEnabled(chunk.getWorld())) {
            throw fail("error.claims_disabled_world", "world", chunk.getWorld().getName());
        }
        if (this.getClaimAt(chunk).isPresent()) {
            throw fail("error.chunk_already_claimed");
        }
        if (this.claimProtection.hasConflictingRegion(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())) {
            throw fail("error.chunk_conflicts_worldguard");
        }
        List<ClaimRecord> owned = this.getClaims(player.getUniqueId());
        int limit = this.claimLimit(player.getUniqueId());
        if (owned.size() >= limit) {
            throw fail("error.claim_limit_reached", "limit", Integer.toString(limit));
        }
        if (this.plugin.getConfig().getBoolean("claims.adjacency_required", true) && !owned.isEmpty()) {
            boolean adjacent = owned.stream().anyMatch(claim -> Math.abs(claim.chunkX() - chunk.getX()) + Math.abs(claim.chunkZ() - chunk.getZ()) == 1);
            if (!adjacent) {
                throw fail("error.claim_adjacency_required");
            }
        }
        long id = this.repositories.nextClaimId();
        ClaimRecord claim = new ClaimRecord(
                id,
                season.id(),
                chunk.getWorld().getName(),
                player.getUniqueId(),
                player.getName(),
                "frontier_%d_%s_%d_%d".formatted(season.id(), shortName(player.getName()), chunk.getX(), chunk.getZ()),
                chunk.getX(),
                chunk.getZ(),
                ClaimState.ACTIVE,
                this.now(),
                this.claimExpiryFrom(this.now()),
                null,
                null
        );
        this.saveClaimRecord(claim);
        this.claimProtection.createClaimRegion(claim);
        this.audit("claim_created", player.getName(), Map.of("claimId", claim.id(), "world", claim.world(), "chunkX", claim.chunkX(), "chunkZ", claim.chunkZ()));
        this.save();
        return claim;
    }

    public ClaimRecord renewClaim(Player player, long claimId) {
        SeasonRecord season = this.getRequiredSeason();
        this.assertSeasonEditable(season);
        this.assertClaimRenewAllowed(season, player);
        ClaimRecord claim = this.getManageableClaim(player.getUniqueId(), claimId);
        long cost = this.plugin.getConfig().getLong("claims.renew.manual_extend_cost", 500L);
        if (this.coinBalance(player.getUniqueId()) < cost) {
            throw fail("error.not_enough_coins", "required", Long.toString(cost));
        }
        this.addCoins(player.getUniqueId(), -cost, "claim_renew");
        ClaimRecord renewed = claim.renew(this.claimExpiryFrom(this.now()));
        this.saveClaimRecord(renewed);
        this.audit("claim_renewed", player.getName(), Map.of("claimId", renewed.id(), "expiresAt", renewed.expiresAt().toString(), "cost", cost));
        this.save();
        return renewed;
    }

    public ClaimRecord releaseClaim(Player player, long claimId) {
        this.assertGameplayActionAllowed(this.getRequiredSeason(), "保護の解放", player);
        ClaimRecord claim = this.getManageableClaim(player.getUniqueId(), claimId).withState(ClaimState.ABANDONED);
        this.saveClaimRecord(claim);
        this.claimProtection.releaseClaimRegion(claim);
        this.audit("claim_released", player.getName(), Map.of("claimId", claim.id()));
        this.save();
        return claim;
    }

    public List<String> claimOwners(Player player, long claimId) {
        ClaimRecord claim = this.getAccessibleClaim(player.getUniqueId(), claimId);
        return this.claimProtection.owners(claim);
    }

    public List<String> claimMembers(Player player, long claimId) {
        ClaimRecord claim = this.getAccessibleClaim(player.getUniqueId(), claimId);
        return this.claimProtection.members(claim);
    }

    public List<String> claimOwners(ClaimRecord claim) {
        return this.claimProtection.owners(claim);
    }

    public List<String> claimMembers(ClaimRecord claim) {
        return this.claimProtection.members(claim);
    }

    public ClaimRecord trustClaimMember(Player player, long claimId, String targetName) {
        this.assertGameplayActionAllowed(this.getRequiredSeason(), "保護の共有設定", player);
        ClaimRecord claim = this.getManageableClaim(player.getUniqueId(), claimId);
        this.assertClaimSharingSupported();
        ResolvedPlayer target = this.resolvePlayer(targetName);
        this.claimProtection.addMember(claim, target.playerId(), target.lastKnownName());
        this.audit("claim_member_added", player.getName(), Map.of("claimId", claim.id(), "target", target.lastKnownName()));
        this.save();
        return claim;
    }

    public ClaimRecord untrustClaimMember(Player player, long claimId, String targetName) {
        this.assertGameplayActionAllowed(this.getRequiredSeason(), "保護の共有設定", player);
        ClaimRecord claim = this.getManageableClaim(player.getUniqueId(), claimId);
        this.assertClaimSharingSupported();
        ResolvedPlayer target = this.resolvePlayer(targetName);
        this.claimProtection.removeMember(claim, target.playerId());
        this.audit("claim_member_removed", player.getName(), Map.of("claimId", claim.id(), "target", target.lastKnownName()));
        this.save();
        return claim;
    }

    public ClaimRecord addClaimOwner(Player player, long claimId, String targetName) {
        this.assertGameplayActionAllowed(this.getRequiredSeason(), "保護の共有設定", player);
        ClaimRecord claim = this.getManageableClaim(player.getUniqueId(), claimId);
        this.assertClaimSharingSupported();
        ResolvedPlayer target = this.resolvePlayer(targetName);
        this.claimProtection.addOwner(claim, target.playerId(), target.lastKnownName());
        this.audit("claim_owner_added", player.getName(), Map.of("claimId", claim.id(), "target", target.lastKnownName()));
        this.save();
        return claim;
    }

    public ClaimRecord removeClaimOwner(Player player, long claimId, String targetName) {
        this.assertGameplayActionAllowed(this.getRequiredSeason(), "保護の共有設定", player);
        ClaimRecord claim = this.getManageableClaim(player.getUniqueId(), claimId);
        this.assertClaimSharingSupported();
        ResolvedPlayer target = this.resolvePlayer(targetName);
        List<String> owners = this.claimProtection.owners(claim);
        boolean listed = owners.stream().anyMatch(owner -> owner.equalsIgnoreCase(target.lastKnownName()));
        if (listed && owners.size() <= 1) {
            throw fail("error.claim_last_owner");
        }
        if (claim.ownerUuid().equals(target.playerId())) {
            throw fail("error.claim_primary_owner_remove");
        }
        this.claimProtection.removeOwner(claim, target.playerId());
        this.audit("claim_owner_removed", player.getName(), Map.of("claimId", claim.id(), "target", target.lastKnownName()));
        this.save();
        return claim;
    }

    public List<MissionRecord> getActiveMissions() {
        SeasonRecord season = this.getRequiredSeason();
        return this.repositories.missions().stream()
                .filter(mission -> mission.seasonId() == season.id() && mission.active())
                .sorted(Comparator.comparing(MissionRecord::scope).thenComparing(MissionRecord::id))
                .toList();
    }

    public MissionProgressRecord getMissionProgress(UUID playerId, long missionId) {
        MissionProgressRecord progress = this.repositories.findMissionProgress(progressKey(playerId, missionId));
        return progress != null ? progress : new MissionProgressRecord(missionId, playerId, 0L, false, null);
    }

    public List<String> recordGatherProgress(Player player, Material material, long amount) {
        return this.recordMissionProgress(player, List.of(toItemKey(material), "action:gather"), amount);
    }

    public List<String> recordCraftProgress(Player player, Material material, long amount) {
        return this.recordMissionProgress(player, List.of(toItemKey(material), "action:craft"), amount);
    }

    public List<String> recordFishProgress(Player player, Material material, long amount) {
        return this.recordMissionProgress(player, List.of(toItemKey(material), "action:fish"), amount);
    }

    public List<String> recordTradeProgress(Player player, long amount) {
        return this.recordMissionProgress(player, List.of("action:trade"), amount);
    }

    public List<String> recordSocialProgress(Player player, String actionKey, long amount) {
        return this.recordMissionProgress(player, List.of(actionKey), amount);
    }

    private List<String> recordMissionProgress(Player player, List<String> targetKeys, long amount) {
        SeasonRecord season = this.getRequiredSeason();
        if (!this.isMissionProgressEnabled(season)) {
            return List.of();
        }
        List<String> completed = new ArrayList<>();
        for (MissionRecord mission : this.getActiveMissions()) {
            if (!targetKeys.contains(mission.targetKey())) {
                continue;
            }
            String progressKey = progressKey(player.getUniqueId(), mission.id());
            MissionProgressRecord next = this.getMissionProgress(player.getUniqueId(), mission.id()).increment(amount, mission.targetValue());
            boolean wasCompleted = this.getMissionProgress(player.getUniqueId(), mission.id()).completed();
            this.repositories.saveMissionProgress(progressKey, next);
            if (!wasCompleted && next.completed()) {
                this.addCoins(player.getUniqueId(), mission.rewardCoins(), "mission_complete:" + mission.id());
                this.addSeasonPoints(player.getUniqueId(), mission.rewardPoints(), "mission_complete:" + mission.id());
                this.updateProfile(player.getUniqueId(), PlayerProfileRecord::withMissionCompleted);
                this.audit("mission_completed", player.getName(), Map.of("missionId", mission.id(), "title", mission.title(), "scope", mission.scope().name()));
                completed.add(mission.title());
            }
        }
        if (!completed.isEmpty()) {
            this.save();
        }
        return completed;
    }

    public void ensureMissions(SeasonRecord season) {
        if (this.isMissionRotationEnabled(season)) {
            this.reconcileMissionRotations(season);
        }
    }

    public OrderRecord createServerOrder(String itemKey, long amount, long unitPrice, int hours) {
        SeasonRecord season = this.getRequiredSeason();
        this.assertSeasonEditable(season);
        this.validateOrderParameters(amount, unitPrice, hours);
        return this.addOrder(null, "SERVER", OrderType.BUY_ITEM, itemKey, amount, unitPrice, 0L, hours);
    }

    public OrderRecord createPlayerOrder(Player player, OrderType type, String itemKey, long amount, long unitPrice, int hours) {
        SeasonRecord season = this.getRequiredSeason();
        this.assertSeasonEditable(season);
        this.assertPlayerOrderCreateAllowed(season, amount, unitPrice, player);
        this.validateOrderParameters(amount, unitPrice, hours);
        long fee = this.orderFee(player.getUniqueId());
        long total = amount * unitPrice;
        Material material = parseMaterial(itemKey);
        long coins = this.coinBalance(player.getUniqueId());
        if (type == OrderType.BUY_ITEM && coins < total + fee) {
            throw fail("error.not_enough_coins", "required", Long.toString(total + fee));
        }
        if (type == OrderType.SELL_ITEM && coins < fee) {
            throw fail("error.not_enough_coins_fee", "required", Long.toString(fee));
        }
        if (type == OrderType.SELL_ITEM && this.countInventory(player, material) < amount) {
            throw fail("error.not_enough_items");
        }
        if (type == OrderType.BUY_ITEM) {
            this.addCoins(player.getUniqueId(), -(total + fee), "order_create_buy");
        } else {
            this.removeItems(player, material, amount);
            if (fee > 0) {
                this.addCoins(player.getUniqueId(), -fee, "order_fee_sell");
            }
        }
        OrderRecord order = this.addOrder(player.getUniqueId(), player.getName(), type, itemKey, amount, unitPrice, fee, hours);
        this.audit("order_created", player.getName(), Map.of("orderId", order.id(), "type", order.orderType().name(), "item", order.itemKey(), "amount", order.amount(), "unitPrice", order.unitPrice()));
        this.save();
        return order;
    }

    public OrderRecord reserveOrder(Player player, long orderId) {
        this.assertOrderFulfillmentAllowed(this.getRequiredSeason(), player);
        OrderRecord order = this.getReservableOrder(orderId, player.getUniqueId());
        if (Objects.equals(order.ownerUuid(), player.getUniqueId())) {
            throw fail("error.cannot_fill_own_order");
        }
        if (order.status() == OrderStatus.RESERVED && player.getUniqueId().equals(order.reservedByUuid())) {
            return order;
        }
        OrderRecord reserved = order.reserve(player.getUniqueId(), player.getName(), this.now());
        this.repositories.saveOrder(reserved);
        this.audit("order_reserved", player.getName(), Map.of("orderId", reserved.id(), "type", reserved.orderType().name()));
        this.save();
        return reserved;
    }

    public OrderRecord deliverOrder(Player player, long orderId) {
        this.assertOrderFulfillmentAllowed(this.getRequiredSeason(), player);
        OrderRecord order = this.getDeliverableOrder(orderId, player.getUniqueId());
        Material material = parseMaterial(order.itemKey());
        if (order.orderType() == OrderType.BUY_ITEM) {
            Player recipient = order.ownerUuid() == null ? null : Bukkit.getPlayer(order.ownerUuid());
            if (order.ownerUuid() != null && recipient == null) {
                throw fail("error.order_owner_must_be_online");
            }
            if (recipient != null && !this.canReceiveItems(recipient, material, order.amount())) {
                throw fail("error.order_owner_inventory_full");
            }
            if (this.countInventory(player, material) < order.amount()) {
                throw fail("error.not_enough_items");
            }
            this.removeItems(player, material, order.amount());
            if (recipient != null) {
                this.giveItems(recipient, material, order.amount());
            }
            this.addCoins(player.getUniqueId(), order.totalPrice(), "order_fill_buy");
        } else {
            if (this.coinBalance(player.getUniqueId()) < order.totalPrice()) {
                throw fail("error.not_enough_coins", "required", Long.toString(order.totalPrice()));
            }
            this.addCoins(player.getUniqueId(), -order.totalPrice(), "order_fill_sell");
            if (!this.canReceiveItems(player, material, order.amount())) {
                throw fail("error.inventory_full");
            }
            this.giveItems(player, material, order.amount());
            if (order.ownerUuid() != null) {
                this.addCoins(order.ownerUuid(), order.totalPrice(), "order_completed_sell");
            }
        }
        this.recordTradeProgress(player, 1L);
        OrderRecord completed = order.clearReservation(OrderStatus.COMPLETED);
        this.repositories.saveOrder(completed);
        this.audit("order_completed", player.getName(), Map.of("orderId", completed.id(), "type", completed.orderType().name(), "owner", completed.ownerName()));
        this.save();
        return completed;
    }

    public OrderRecord fillOrder(Player player, long orderId) {
        this.reserveOrder(player, orderId);
        return this.deliverOrder(player, orderId);
    }

    public List<OrderRecord> listOrders() {
        Instant now = this.now();
        List<OrderRecord> orders = this.repositories.orders().stream()
                .map(order -> order.expiresAt().isBefore(now) && (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.RESERVED) ? order.clearReservation(OrderStatus.EXPIRED) : order)
                .peek(this.repositories::saveOrder)
                .sorted(Comparator.comparing(OrderRecord::createdAt).reversed())
                .toList();
        this.save();
        return orders;
    }

    public String newcomerStatus(Player player) {
        PlayerProfileRecord profile = this.getProfile(player.getUniqueId());
        Instant newcomerUntil = this.newcomerUntil(profile);
        boolean newcomer = this.isNewcomer(profile);
        return newcomer
                ? "新規プレイヤー対象: " + newcomerUntil.atZone(this.clock.getZone()).toLocalDate() + "まで / スターター特典=" + (profile.starterClaimed() ? "受取済み" : "未受取")
                : "通常プレイヤー";
    }

    public PlayerProfileRecord claimStarter(Player player) {
        this.assertNewcomerSystemsAllowed(this.getRequiredSeason(), "スターター特典の受け取り", player);
        PlayerProfileRecord profile = this.getProfile(player.getUniqueId());
        if (!this.isNewcomer(profile)) {
            throw fail("error.newcomer_only");
        }
        if (profile.starterClaimed()) {
            throw fail("error.newcomer_starter_already_claimed");
        }
        long coins = this.plugin.getConfig().getLong("player.starter.coins", 250L);
        long points = this.plugin.getConfig().getLong("player.starter.sp", 15L);
        this.addCoins(player.getUniqueId(), coins, "newcomer_starter");
        this.addSeasonPoints(player.getUniqueId(), points, "newcomer_starter");
        PlayerProfileRecord updated = profile.withStarterClaimed();
        this.repositories.saveProfile(profileKey(player.getUniqueId(), profile.seasonId()), updated);
        this.audit("newcomer_starter_claimed", player.getName(), Map.of("coins", coins, "sp", points));
        this.save();
        return updated;
    }

    public void supportNewcomer(Player helper, Player target) {
        this.assertNewcomerSystemsAllowed(this.getRequiredSeason(), "新規プレイヤー支援", helper);
        if (helper.getUniqueId().equals(target.getUniqueId())) {
            throw fail("error.newcomer_support_self");
        }
        PlayerProfileRecord helperProfile = this.getProfile(helper.getUniqueId());
        PlayerProfileRecord targetProfile = this.getProfile(target.getUniqueId());
        if (!this.isNewcomer(targetProfile)) {
            throw fail("error.newcomer_target_not_eligible");
        }
        if (!this.plugin.getConfig().getBoolean("player.helper.allow_newcomer_helpers", false) && this.isNewcomer(helperProfile)) {
            throw fail("error.helper_must_be_regular");
        }
        Instant now = this.now();
        long helperCooldownHours = this.plugin.getConfig().getLong("player.helper.cooldown_hours", 24L);
        long targetCooldownHours = this.plugin.getConfig().getLong("player.helper.target_cooldown_hours", 24L);
        if (helperProfile.lastSupportedAt() != null && helperProfile.lastSupportedAt().plus(helperCooldownHours, ChronoUnit.HOURS).isAfter(now)) {
            throw fail("error.helper_cooldown_active");
        }
        if (targetProfile.lastSupportReceivedAt() != null && targetProfile.lastSupportReceivedAt().plus(targetCooldownHours, ChronoUnit.HOURS).isAfter(now)) {
            throw fail("error.newcomer_recently_supported");
        }
        long helperCoins = this.plugin.getConfig().getLong("player.helper.reward_coins", 150L);
        long helperSp = this.plugin.getConfig().getLong("player.helper.reward_sp", 10L);
        long targetCoins = this.plugin.getConfig().getLong("player.helper.target_bonus_coins", 100L);
        long targetSp = this.plugin.getConfig().getLong("player.helper.target_bonus_sp", 5L);
        this.addCoins(helper.getUniqueId(), helperCoins, "newcomer_support_helper");
        this.addSeasonPoints(helper.getUniqueId(), helperSp, "newcomer_support_helper");
        this.addCoins(target.getUniqueId(), targetCoins, "newcomer_support_target");
        this.addSeasonPoints(target.getUniqueId(), targetSp, "newcomer_support_target");
        this.repositories.saveProfile(profileKey(helper.getUniqueId(), helperProfile.seasonId()), helperProfile.withSupportedAt(now));
        this.repositories.saveProfile(profileKey(target.getUniqueId(), targetProfile.seasonId()), targetProfile.withSupportReceivedAt(now));
        this.recordSocialProgress(helper, "action:support_newcomer", 1L);
        this.audit("newcomer_supported", helper.getName(), Map.of("target", target.getName(), "helperCoins", helperCoins, "targetCoins", targetCoins));
        this.save();
    }

    public ClaimRecord likeCurrentClaim(Player player) {
        this.assertLikeAllowed(this.getRequiredSeason(), player);
        ClaimRecord claim = this.getClaimAt(player.getLocation().getChunk()).orElseThrow(() -> fail("error.like_no_claim"));
        if (claim.ownerUuid().equals(player.getUniqueId())) {
            throw fail("error.like_own_claim");
        }
        PlayerProfileRecord voterProfile = this.getProfile(player.getUniqueId());
        long minimumPlaytimeHours = this.plugin.getConfig().getLong("player.likes.minimum_playtime_hours", 2L);
        if (voterProfile.joinAt().plus(minimumPlaytimeHours, ChronoUnit.HOURS).isAfter(this.now())) {
            throw fail("error.like_minimum_playtime");
        }
        LocalDate today = LocalDate.now(this.clock);
        long dailyLikes = this.repositories.likes().stream()
                .filter(like -> like.voterUuid().equals(player.getUniqueId()) && like.likedOn().equals(today))
                .count();
        long dailyCap = this.plugin.getConfig().getLong("player.likes.daily_cap", 3L);
        if (dailyLikes >= dailyCap) {
            throw fail("error.like_daily_cap_reached");
        }
        String likeKey = claim.id() + ":" + player.getUniqueId() + ":" + today;
        if (this.repositories.hasLike(likeKey)) {
            throw fail("error.like_already_today");
        }
        this.repositories.saveLike(likeKey, new LikeRecord(claim.seasonId(), claim.id(), claim.ownerUuid(), player.getUniqueId(), today));
        this.addSeasonPoints(claim.ownerUuid(), 5L, "claim_like");
        this.updateProfile(claim.ownerUuid(), PlayerProfileRecord::withLikeReceived);
        this.recordSocialProgress(player, "action:social_like", 1L);
        this.audit("claim_liked", player.getName(), Map.of("claimId", claim.id(), "owner", claim.ownerName()));
        this.auditSuspiciousLikePattern(player, claim);
        this.save();
        return claim;
    }

    public File exportArchive() {
        SeasonRecord season = this.getRequiredSeason();
        File archiveDir = new File(this.plugin.getDataFolder(), "archive/" + season.key());
        archiveDir.mkdirs();
        File out = new File(archiveDir, "summary.json");
        try {
            Files.writeString(out.toPath(), this.buildArchiveJson(season), StandardCharsets.UTF_8);
            Files.writeString(new File(archiveDir, "leaderboard.csv").toPath(), this.buildLeaderboardCsv(season), StandardCharsets.UTF_8);
            Files.writeString(new File(archiveDir, "claims.csv").toPath(), this.buildClaimsCsv(season), StandardCharsets.UTF_8);
            Files.writeString(new File(archiveDir, "orders.csv").toPath(), this.buildOrdersCsv(season), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw fail("error.archive_write_failed");
        }
        this.audit("archive_exported", "system", Map.of("seasonId", season.id(), "file", out.getName()));
        return archiveDir;
    }

    public void tickAutomation() {
        this.reconcileSeasonSchedule();
        this.getActiveSeason().ifPresent(season -> {
            if (this.isMissionRotationEnabled(season)) {
                this.reconcileMissionRotations(season);
            }
            if (this.isClaimExpiryEnabled(season)) {
                this.tickClaims();
            }
        });
    }

    public void tickClaims() {
        SeasonRecord season = this.getRequiredSeason();
        if (!this.isClaimExpiryEnabled(season)) {
            return;
        }
        Instant now = this.now();
        boolean changed = false;
        for (ClaimRecord claim : new ArrayList<>(this.repositories.claims())) {
            ClaimState next = nextClaimState(claim, now, this.warningDays(), this.expireDays(), this.abandonDays());
            if (next != claim.state()) {
                this.saveClaimRecord(claim.withState(next));
                this.audit("claim_state_changed", "system", Map.of("claimId", claim.id(), "from", claim.state().name(), "to", next.name()));
                this.queueClaimNotificationIfDue(claim.ownerUuid(), claim.id(), next, claim.expiresAt(), now);
                changed = true;
            } else if (next == ClaimState.WARNING || next == ClaimState.EXPIRED) {
                this.queueClaimNotificationIfDue(claim.ownerUuid(), claim.id(), next, claim.expiresAt(), now);
            }
        }
        if (changed) {
            this.save();
        }
    }

    public List<ClaimNotification> drainClaimNotifications() {
        List<ClaimNotification> notifications = new ArrayList<>(this.pendingClaimNotifications);
        this.pendingClaimNotifications.clear();
        return notifications;
    }

    public boolean canEdit(Player player, Chunk chunk) {
        if (this.hasPhaseOverride(player)) {
            return true;
        }
        if (!this.isGameplayEnabled()) {
            return false;
        }
        Optional<ClaimRecord> claim = this.getClaimAt(chunk);
        return claim.isEmpty()
                || !claim.get().state().protects()
                || this.claimProtection.canEdit(claim.get(), player.getUniqueId());
    }

    public boolean isClaimOwner(Player player, ClaimRecord claim) {
        return claim.ownerUuid().equals(player.getUniqueId()) || this.claimProtection.isOwner(claim, player.getUniqueId());
    }

    public boolean isSharedClaimMember(Player player, ClaimRecord claim) {
        return !this.isClaimOwner(player, claim) && this.claimProtection.canEdit(claim, player.getUniqueId());
    }

    static ClaimState nextClaimState(ClaimRecord claim, Instant now, long warningDays, long expireDays, long abandonDays) {
        if (claim.state() == ClaimState.FROZEN || claim.state() == ClaimState.ABANDONED) {
            return claim.state();
        }
        Instant warningAt = claim.expiresAt().minus(abandonDays - warningDays, ChronoUnit.DAYS);
        Instant expireAt = claim.expiresAt().minus(abandonDays - expireDays, ChronoUnit.DAYS);
        if (!now.isBefore(claim.expiresAt())) {
            return ClaimState.ABANDONED;
        }
        if (!now.isBefore(expireAt)) {
            return ClaimState.EXPIRED;
        }
        if (!now.isBefore(warningAt)) {
            return ClaimState.WARNING;
        }
        return ClaimState.ACTIVE;
    }

    private OrderRecord addOrder(UUID ownerUuid, String ownerName, OrderType type, String itemKey, long amount, long unitPrice, long fee, int hours) {
        SeasonRecord season = this.getRequiredSeason();
        long id = this.repositories.nextOrderId();
        OrderRecord order = new OrderRecord(
                id,
                season.id(),
                ownerUuid,
                ownerName,
                type,
                itemKey,
                amount,
                unitPrice,
                fee,
                OrderStatus.OPEN,
                null,
                null,
                null,
                this.now(),
                this.now().plus(hours, ChronoUnit.HOURS)
        );
        this.repositories.saveOrder(order);
        return order;
    }

    private void createMission(long seasonId, MissionScope scope, String title, String description, String itemKey, long target, long rewardCoins, long rewardPoints) {
        long id = this.repositories.nextMissionId();
        this.repositories.saveMission(new MissionRecord(id, seasonId, scope, title, description, itemKey, target, rewardCoins, rewardPoints, true));
    }

    private ClaimRecord getAccessibleClaim(UUID playerUuid, long claimId) {
        ClaimRecord claim = this.repositories.findClaim(claimId);
        if (claim == null || claim.state() == ClaimState.ABANDONED) {
            throw fail("error.claim_not_found");
        }
        if (claim.ownerUuid().equals(playerUuid) || this.claimProtection.canEdit(claim, playerUuid)) {
            return claim;
        }
        throw fail("error.claim_not_found");
    }

    private ClaimRecord getManageableClaim(UUID playerUuid, long claimId) {
        ClaimRecord claim = this.repositories.findClaim(claimId);
        if (claim == null || claim.state() == ClaimState.ABANDONED) {
            throw fail("error.claim_not_found");
        }
        if (claim.ownerUuid().equals(playerUuid) || this.claimProtection.isOwner(claim, playerUuid)) {
            return claim;
        }
        throw fail("error.claim_not_found");
    }

    private void assertClaimSharingSupported() {
        if (!this.claimProtection.supportsSharing()) {
            throw fail("error.claim_sharing_requires_worldguard");
        }
    }

    private ResolvedPlayer resolvePlayer(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return new ResolvedPlayer(online.getUniqueId(), online.getName());
        }
        var offline = Bukkit.getOfflinePlayer(playerName);
        String resolvedName = offline.getName();
        if (offline.getUniqueId() == null || (!offline.hasPlayedBefore() && !offline.isOnline()) || resolvedName == null) {
            throw fail("error.player_not_found", "player", playerName);
        }
        return new ResolvedPlayer(offline.getUniqueId(), resolvedName);
    }

    private record ResolvedPlayer(UUID playerId, String lastKnownName) {
    }

    private OrderRecord getOpenOrder(long orderId) {
        OrderRecord order = this.repositories.findOrder(orderId);
        if (order == null || order.status() != OrderStatus.OPEN || order.expiresAt().isBefore(this.now())) {
            throw fail("error.order_not_found_or_closed");
        }
        return order;
    }

    private OrderRecord getReservableOrder(long orderId, UUID playerId) {
        OrderRecord order = this.repositories.findOrder(orderId);
        if (order == null || order.expiresAt().isBefore(this.now())) {
            throw fail("error.order_not_found_or_closed");
        }
        if (order.status() == OrderStatus.OPEN) {
            return order;
        }
        if (order.status() == OrderStatus.RESERVED && playerId.equals(order.reservedByUuid())) {
            return order;
        }
        throw fail("error.order_not_found_or_closed");
    }

    private OrderRecord getDeliverableOrder(long orderId, UUID playerId) {
        OrderRecord order = this.repositories.findOrder(orderId);
        if (order == null || order.expiresAt().isBefore(this.now())) {
            throw fail("error.order_not_found_or_closed");
        }
        if (order.status() != OrderStatus.RESERVED || !playerId.equals(order.reservedByUuid())) {
            throw fail("error.order_not_reserved_for_you");
        }
        return order;
    }

    private void extendClaimsForOwner(UUID ownerUuid) {
        if (!this.plugin.getConfig().getBoolean("claims.renew.login_auto_extend", true)) {
            return;
        }
        SeasonRecord season = this.getRequiredSeason();
        if (!this.isClaimExpiryEnabled(season)) {
            return;
        }
        if (season.phase() == SeasonPhase.FINALE && this.plugin.getConfig().getBoolean("season.finale.block_claim_auto_renew", true)) {
            return;
        }
        boolean changed = false;
        for (ClaimRecord claim : this.getClaims(ownerUuid)) {
            ClaimRecord renewed = claim.renew(this.claimExpiryFrom(this.now()));
            this.saveClaimRecord(renewed);
            changed = true;
        }
        if (changed) {
            this.save();
        }
    }

    private void freezeClaims(long seasonId) {
        for (ClaimRecord claim : new ArrayList<>(this.repositories.claims())) {
            if (claim.seasonId() == seasonId && claim.state() != ClaimState.ABANDONED) {
                ClaimRecord frozen = claim.withState(ClaimState.FROZEN);
                this.saveClaimRecord(frozen);
                this.claimProtection.freezeClaimRegion(frozen);
            }
        }
    }

    private String buildArchiveJson(SeasonRecord season) {
        List<PlayerProfileRecord> profiles = this.repositories.profiles().stream()
                .filter(profile -> profile.seasonId() == season.id())
                .sorted(Comparator.comparing(PlayerProfileRecord::seasonPoints).reversed())
                .toList();
        List<ClaimRecord> claims = this.repositories.claims().stream().filter(claim -> claim.seasonId() == season.id()).toList();
        List<OrderRecord> orders = this.repositories.orders().stream().filter(order -> order.seasonId() == season.id()).toList();
        List<PlayerProfileRecord> topCoins = this.repositories.profiles().stream()
                .filter(profile -> profile.seasonId() == season.id())
                .sorted(Comparator.comparing(PlayerProfileRecord::coins).reversed())
                .limit(10)
                .toList();
        List<PlayerProfileRecord> topLikes = this.repositories.profiles().stream()
                .filter(profile -> profile.seasonId() == season.id())
                .sorted(Comparator.comparing(PlayerProfileRecord::totalLikesReceived).reversed())
                .limit(10)
                .toList();
        return "{\n" +
                "  \"season\": {\n" +
                "    \"key\": \"" + escape(season.key()) + "\",\n" +
                "    \"displayName\": \"" + escape(season.displayName()) + "\",\n" +
                "    \"phase\": \"" + season.phase().name() + "\"\n" +
                "  },\n" +
                "  \"topSeasonPoints\": [\n" +
                profiles.stream().limit(10).map(profile -> "    {\"player\":\"" + escape(profile.lastKnownName()) + "\",\"coins\":" + profile.coins() + ",\"seasonPoints\":" + profile.seasonPoints() + "}").collect(Collectors.joining(",\n")) +
                "\n  ],\n" +
                "  \"topCoins\": [\n" +
                topCoins.stream().map(profile -> "    {\"player\":\"" + escape(profile.lastKnownName()) + "\",\"coins\":" + profile.coins() + "}").collect(Collectors.joining(",\n")) +
                "\n  ],\n" +
                "  \"topLikes\": [\n" +
                topLikes.stream().map(profile -> "    {\"player\":\"" + escape(profile.lastKnownName()) + "\",\"likes\":" + profile.totalLikesReceived() + "}").collect(Collectors.joining(",\n")) +
                "\n  ],\n" +
                "  \"claims\": " + claims.size() + ",\n" +
                "  \"orders\": " + orders.size() + ",\n" +
                "  \"likes\": " + this.repositories.likes().stream().filter(like -> like.seasonId() == season.id()).count() + "\n" +
                "}\n";
    }

    private String buildLeaderboardCsv(SeasonRecord season) {
        List<PlayerProfileRecord> profiles = this.repositories.profiles().stream()
                .filter(profile -> profile.seasonId() == season.id())
                .sorted(Comparator.comparing(PlayerProfileRecord::seasonPoints).reversed())
                .toList();
        StringBuilder builder = new StringBuilder("rank,player,coins,season_points,total_likes_received,total_missions_completed\n");
        for (int i = 0; i < profiles.size(); i++) {
            PlayerProfileRecord profile = profiles.get(i);
            builder.append(i + 1).append(',')
                    .append(csv(profile.lastKnownName())).append(',')
                    .append(profile.coins()).append(',')
                    .append(profile.seasonPoints()).append(',')
                    .append(profile.totalLikesReceived()).append(',')
                    .append(profile.totalMissionCompleted()).append('\n');
        }
        return builder.toString();
    }

    private String buildClaimsCsv(SeasonRecord season) {
        List<ClaimRecord> claims = this.repositories.claims().stream()
                .filter(claim -> claim.seasonId() == season.id())
                .sorted(Comparator.comparing(ClaimRecord::id))
                .toList();
        StringBuilder builder = new StringBuilder("id,owner,world,chunk_x,chunk_z,state,created_at,expires_at\n");
        for (ClaimRecord claim : claims) {
            builder.append(claim.id()).append(',')
                    .append(csv(claim.ownerName())).append(',')
                    .append(csv(claim.world())).append(',')
                    .append(claim.chunkX()).append(',')
                    .append(claim.chunkZ()).append(',')
                    .append(claim.state().name()).append(',')
                    .append(claim.createdAt()).append(',')
                    .append(claim.expiresAt()).append('\n');
        }
        return builder.toString();
    }

    private String buildOrdersCsv(SeasonRecord season) {
        List<OrderRecord> orders = this.repositories.orders().stream()
                .filter(order -> order.seasonId() == season.id())
                .sorted(Comparator.comparing(OrderRecord::id))
                .toList();
        StringBuilder builder = new StringBuilder("id,owner,type,item,amount,unit_price,fee,status,reserved_by,created_at,expires_at\n");
        for (OrderRecord order : orders) {
            builder.append(order.id()).append(',')
                    .append(csv(order.ownerName())).append(',')
                    .append(order.orderType().name()).append(',')
                    .append(csv(order.itemKey())).append(',')
                    .append(order.amount()).append(',')
                    .append(order.unitPrice()).append(',')
                    .append(order.fee()).append(',')
                    .append(order.status().name()).append(',')
                    .append(csv(order.reservedByName() == null ? "" : order.reservedByName())).append(',')
                    .append(order.createdAt()).append(',')
                    .append(order.expiresAt()).append('\n');
        }
        return builder.toString();
    }

    private void updateProfile(UUID playerId, java.util.function.UnaryOperator<PlayerProfileRecord> updater) {
        PlayerProfileRecord current = this.getProfile(playerId);
        this.repositories.saveProfile(profileKey(playerId, current.seasonId()), updater.apply(current));
    }

    private void saveSeasonRecord(SeasonRecord season) {
        this.repositories.saveSeason(season);
        this.activeSeasonCacheLoaded = false;
    }

    private void saveClaimRecord(ClaimRecord claim) {
        this.repositories.saveClaim(claim);
        if (this.claimChunkCacheLoaded) {
            this.cacheClaim(claim);
        }
    }

    private long countInventory(Player player, Material material) {
        long found = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                found += stack.getAmount();
            }
        }
        return found;
    }

    private boolean canReceiveItems(Player player, Material material, long amount) {
        long remaining = amount;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null) {
                remaining -= material.getMaxStackSize();
            } else if (stack.getType() == material) {
                remaining -= material.getMaxStackSize() - stack.getAmount();
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private void giveItems(Player player, Material material, long amount) {
        long remaining = amount;
        while (remaining > 0) {
            int stackSize = (int) Math.min(remaining, material.getMaxStackSize());
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, stackSize));
            if (!leftovers.isEmpty()) {
                throw fail("error.inventory_full");
            }
            remaining -= stackSize;
        }
    }

    private void removeItems(Player player, Material material, long amount) {
        long left = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int remove = (int) Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - remove);
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
            left -= remove;
        }
        player.getInventory().setContents(contents);
        if (left > 0) {
            throw fail("error.inventory_mutation_failed");
        }
    }

    private Material parseMaterial(String itemKey) {
        String materialKey = itemKey.startsWith("minecraft:") ? itemKey.substring("minecraft:".length()) : itemKey;
        Material material = Material.matchMaterial(materialKey, false);
        if (material == null || material.isAir()) {
            throw fail("error.unknown_item", "item", itemKey);
        }
        return material;
    }

    private Instant claimExpiryFrom(Instant base) {
        return base.plus(this.abandonDays(), ChronoUnit.DAYS);
    }

    public int claimLimit(UUID playerId) {
        PlayerProfileRecord profile = this.getProfile(playerId);
        int limit = this.plugin.getConfig().getInt("claims.initial_limit", 2);
        for (long threshold : this.plugin.getConfig().getLongList("claims.sp_unlock_thresholds")) {
            if (profile.seasonPoints() >= threshold) {
                limit++;
            }
        }
        if (this.currentSeasonPhase() == SeasonPhase.OPENING) {
            int cap = this.plugin.getConfig().getInt("season.opening.claim_limit_cap", limit);
            limit = Math.min(limit, Math.max(1, cap));
        }
        return limit;
    }

    public Long nextClaimUnlockSp(UUID playerId) {
        PlayerProfileRecord profile = this.getProfile(playerId);
        for (long threshold : this.plugin.getConfig().getLongList("claims.sp_unlock_thresholds")) {
            if (profile.seasonPoints() < threshold) {
                return threshold;
            }
        }
        return null;
    }

    private long orderFee(UUID playerId) {
        PlayerProfileRecord profile = this.getProfile(playerId);
        boolean freeFirstOrder = this.plugin.getConfig().getBoolean("player.newcomer.first_order_fee_exempt", true) && this.isNewcomer(profile);
        return freeFirstOrder && this.repositories.orders().stream().noneMatch(order -> playerId.equals(order.ownerUuid())) ? 0L : 25L;
    }

    private boolean isNewcomer(PlayerProfileRecord profile) {
        return this.now().isBefore(this.newcomerUntil(profile)) && profile.seasonPoints() < this.plugin.getConfig().getLong("player.newcomer_sp_limit", 100L);
    }

    private Instant newcomerUntil(PlayerProfileRecord profile) {
        long newcomerDays = this.plugin.getConfig().getLong("player.newcomer_days", 7L);
        return profile.joinAt().plus(newcomerDays, ChronoUnit.DAYS);
    }

    private boolean isClaimsWorldEnabled(World world) {
        return this.plugin.getConfig().getStringList("claims.enabled_worlds").contains(world.getName());
    }

    private long warningDays() {
        return this.plugin.getConfig().getLong("claims.durations.warning_days", 7L);
    }

    private long expireDays() {
        return this.plugin.getConfig().getLong("claims.durations.expire_days", 10L);
    }

    private long abandonDays() {
        return this.plugin.getConfig().getLong("claims.durations.abandon_days", 14L);
    }

    private SeasonRecord getRequiredSeason() {
        return this.getActiveSeason().orElseThrow(() -> fail("error.no_active_season"));
    }

    private void assertSeasonEditable(SeasonRecord season) {
        if (season.phase() == SeasonPhase.ARCHIVED) {
            throw fail("error.season_archived");
        }
    }

    private boolean isGameplayEnabled(SeasonRecord season) {
        return switch (season.phase()) {
            case OPENING, ACTIVE, FINALE -> true;
            case PRESEASON, ARCHIVED -> false;
        };
    }

    private boolean isMissionProgressEnabled(SeasonRecord season) {
        return this.isGameplayEnabled(season);
    }

    private boolean isMissionRotationEnabled(SeasonRecord season) {
        return season.phase() != SeasonPhase.PRESEASON && season.phase() != SeasonPhase.ARCHIVED;
    }

    private boolean isClaimExpiryEnabled(SeasonRecord season) {
        return season.phase() != SeasonPhase.PRESEASON && season.phase() != SeasonPhase.ARCHIVED;
    }

    private void assertGameplayActionAllowed(SeasonRecord season, String action, Player actor) {
        if (!this.isGameplayEnabled(season) && !this.hasPhaseOverride(actor)) {
            throw fail("error.phase_action_blocked", "phase", this.displaySeasonPhase(season.phase()), "action", action);
        }
    }

    private void assertClaimCreateAllowed(SeasonRecord season, Player actor) {
        if (season.phase() == SeasonPhase.PRESEASON || season.phase() == SeasonPhase.ARCHIVED) {
            this.assertGameplayActionAllowed(season, "保護の作成", actor);
        }
        if (season.phase() == SeasonPhase.FINALE && this.plugin.getConfig().getBoolean("season.finale.block_new_claims", true) && !this.hasPhaseOverride(actor)) {
            throw fail("error.claim_create_blocked_finale");
        }
    }

    private void assertClaimRenewAllowed(SeasonRecord season, Player actor) {
        if (season.phase() == SeasonPhase.PRESEASON || season.phase() == SeasonPhase.ARCHIVED) {
            this.assertGameplayActionAllowed(season, "保護の更新", actor);
        }
        if (season.phase() == SeasonPhase.FINALE && this.plugin.getConfig().getBoolean("season.finale.block_claim_manual_renew", true) && !this.hasPhaseOverride(actor)) {
            throw fail("error.claim_renew_blocked_finale");
        }
    }

    private void assertOrderFulfillmentAllowed(SeasonRecord season, Player actor) {
        this.assertGameplayActionAllowed(season, "注文の処理", actor);
    }

    private void assertPlayerOrderCreateAllowed(SeasonRecord season, long amount, long unitPrice, Player actor) {
        this.assertGameplayActionAllowed(season, "注文の作成", actor);
        if (this.hasPhaseOverride(actor)) {
            return;
        }
        if (season.phase() == SeasonPhase.FINALE && this.plugin.getConfig().getBoolean("season.finale.block_new_player_orders", true)) {
            throw fail("error.orders_disabled_finale");
        }
        if (season.phase() == SeasonPhase.OPENING) {
            long openOrders = this.repositories.orders().stream()
                    .filter(order -> actor.getUniqueId().equals(order.ownerUuid()))
                    .filter(order -> order.status() == OrderStatus.OPEN || order.status() == OrderStatus.RESERVED)
                    .count();
            long maxOpen = this.plugin.getConfig().getLong("season.opening.max_open_player_orders", 2L);
            if (openOrders >= maxOpen) {
                throw fail("error.orders_disabled_opening_limit");
            }
            long maxAmount = this.plugin.getConfig().getLong("season.opening.max_order_amount", 256L);
            if (amount > maxAmount) {
                throw fail("error.orders_disabled_opening_amount", "max", Long.toString(maxAmount));
            }
            long maxUnitPrice = this.plugin.getConfig().getLong("season.opening.max_unit_price", 10000L);
            if (unitPrice > maxUnitPrice) {
                throw fail("error.orders_disabled_opening_price", "max", Long.toString(maxUnitPrice));
            }
        }
    }

    private void assertNewcomerSystemsAllowed(SeasonRecord season, String action, Player actor) {
        this.assertGameplayActionAllowed(season, action, actor);
    }

    private void assertLikeAllowed(SeasonRecord season, Player actor) {
        this.assertGameplayActionAllowed(season, "高評価", actor);
    }

    private String displaySeasonPhase(SeasonPhase phase) {
        return switch (phase) {
            case PRESEASON -> "プレシーズン";
            case OPENING -> "オープニング";
            case ACTIVE -> "進行中";
            case FINALE -> "フィナーレ";
            case ARCHIVED -> "アーカイブ";
        };
    }

    private Instant now() {
        return Instant.now(this.clock);
    }

    private static String profileKey(UUID playerId, long seasonId) {
        return seasonId + ":" + playerId;
    }

    private static String progressKey(UUID playerId, long missionId) {
        return missionId + ":" + playerId;
    }

    private void ensureClaimChunkCacheLoaded() {
        if (this.claimChunkCacheLoaded) {
            return;
        }
        this.claimByChunkCache.clear();
        for (ClaimRecord claim : this.repositories.claims()) {
            this.cacheClaim(claim);
        }
        this.claimChunkCacheLoaded = true;
    }

    private void cacheClaim(ClaimRecord claim) {
        String key = chunkKey(claim.world(), claim.chunkX(), claim.chunkZ());
        if (claim.state() == ClaimState.ABANDONED) {
            this.claimByChunkCache.remove(key);
            return;
        }
        this.claimByChunkCache.put(key, claim);
    }

    private static String toItemKey(Material material) {
        return material.getKey().asString();
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    private static String shortName(String playerName) {
        String compact = playerName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return compact.length() > 8 ? compact.substring(0, 8) : compact;
    }

    private void reconcileMissionRotations(SeasonRecord season) {
        LocalDate today = LocalDate.now(this.clock);
        if (this.plugin.getConfig().getBoolean("season.auto_rotate_daily", true)
                && !today.toString().equals(this.repositories.lastDailyRotationDate())
                && !today.atTime(this.dailyRotationTime()).isAfter(LocalDateTime.now(this.clock))) {
            this.rotateMissions(season, MissionScope.DAILY);
            this.repositories.setLastDailyRotationDate(today.toString());
            this.audit("mission_rotation", "system", Map.of("scope", "DAILY", "date", today.toString()));
            this.save();
        }
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        if (this.plugin.getConfig().getBoolean("season.auto_rotate_weekly", true)
                && !monday.toString().equals(this.repositories.lastWeeklyRotationDate())
                && !monday.atTime(this.weeklyRotationTime()).isAfter(LocalDateTime.now(this.clock))) {
            this.rotateMissions(season, MissionScope.WEEKLY);
            this.repositories.setLastWeeklyRotationDate(monday.toString());
            this.audit("mission_rotation", "system", Map.of("scope", "WEEKLY", "date", monday.toString()));
            this.save();
        }
    }

    private void rotateMissions(SeasonRecord season, MissionScope scope) {
        for (MissionRecord mission : new ArrayList<>(this.repositories.missions())) {
            if (mission.seasonId() == season.id() && mission.scope() == scope) {
                this.repositories.saveMission(new MissionRecord(
                        mission.id(),
                        mission.seasonId(),
                        mission.scope(),
                        mission.title(),
                        mission.description(),
                        mission.targetKey(),
                        mission.targetValue(),
                        mission.rewardCoins(),
                        mission.rewardPoints(),
                        false
                ));
            }
        }
        if (scope == MissionScope.DAILY) {
            this.createMission(season.id(), MissionScope.DAILY, "デイリー採掘", "石を64個壊す", "minecraft:stone", 64, 150, 10);
            this.createMission(season.id(), MissionScope.DAILY, "デイリー作業台", "松明を16個作る", "minecraft:torch", 16, 150, 10);
            this.createMission(season.id(), MissionScope.DAILY, "デイリー交流", "建築を1回高評価する", "action:social_like", 1, 120, 8);
        } else {
            this.createMission(season.id(), MissionScope.WEEKLY, "ウィークリー農業", "小麦を128個収穫する", "minecraft:wheat", 128, 500, 50);
            this.createMission(season.id(), MissionScope.WEEKLY, "ウィークリー釣り", "タラを24匹釣る", "minecraft:cod", 24, 500, 50);
            this.createMission(season.id(), MissionScope.WEEKLY, "ウィークリー取引", "取引を3回完了する", "action:trade", 3, 700, 70);
        }
    }

    private void reconcileSeasonSchedule() {
        SeasonRecord season = this.getActiveSeason().orElse(null);
        if (season == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(this.clock);
        this.applyScheduledPhase(season, now, "season.schedule.opening_at", SeasonPhase.OPENING);
        season = this.getRequiredSeason();
        this.applyScheduledPhase(season, now, "season.schedule.active_at", SeasonPhase.ACTIVE);
        season = this.getRequiredSeason();
        this.applyScheduledPhase(season, now, "season.schedule.finale_at", SeasonPhase.FINALE);
        season = this.getRequiredSeason();
        this.applyScheduledPhase(season, now, "season.schedule.archive_at", SeasonPhase.ARCHIVED);
    }

    private void applyScheduledPhase(SeasonRecord season, LocalDateTime now, String configPath, SeasonPhase target) {
        String configured = this.plugin.getConfig().getString(configPath, "").trim();
        if (configured.isEmpty() || season.phase() == target || !season.phase().canTransitionTo(target)) {
            return;
        }
        LocalDateTime threshold = LocalDateTime.parse(configured);
        if (!threshold.isAfter(now)) {
            this.setPhase(target);
            this.audit("season_phase_reconciled", "system", Map.of("seasonId", season.id(), "phase", target.name(), "path", configPath));
        }
    }

    private LocalTime dailyRotationTime() {
        return LocalTime.parse(this.plugin.getConfig().getString("season.daily_rotation_time", "00:00"));
    }

    private LocalTime weeklyRotationTime() {
        return LocalTime.parse(this.plugin.getConfig().getString("season.weekly_rotation_time", "00:00"));
    }

    private void audit(String type, String actor, Map<String, ?> details) {
        if (this.auditService != null) {
            this.auditService.log(type, actor, details);
        }
    }

    private void queueClaimNotificationIfDue(UUID ownerUuid, long claimId, ClaimState state, Instant expiresAt, Instant now) {
        if (state != ClaimState.WARNING && state != ClaimState.EXPIRED && state != ClaimState.ABANDONED) {
            return;
        }
        String key = claimNotificationKey(claimId, state);
        Instant lastSentAt = this.repositories.claimNotificationAt(key);
        long intervalMinutes = switch (state) {
            case WARNING -> this.plugin.getConfig().getLong("claims.warnings.repeat.warning_minutes", 180L);
            case EXPIRED -> this.plugin.getConfig().getLong("claims.warnings.repeat.expired_minutes", 180L);
            case ABANDONED -> this.plugin.getConfig().getLong("claims.warnings.repeat.abandoned_minutes", 1440L);
            default -> 0L;
        };
        if (lastSentAt != null && lastSentAt.plus(intervalMinutes, ChronoUnit.MINUTES).isAfter(now)) {
            return;
        }
        this.pendingClaimNotifications.add(new ClaimNotification(ownerUuid, claimId, state, expiresAt));
        this.repositories.setClaimNotificationAt(key, now);
    }

    private static String claimNotificationKey(long claimId, ClaimState state) {
        return claimId + ":" + state.name();
    }

    private static UserMessageException fail(String key, String... values) {
        Map<String, String> placeholders = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            placeholders.put(values[i], values[i + 1]);
        }
        return new UserMessageException(key, placeholders);
    }

    private void validateOrderParameters(long amount, long unitPrice, int hours) {
        if (amount <= 0) {
            throw fail("error.invalid_positive_number", "label", "amount");
        }
        if (amount > Integer.MAX_VALUE) {
            throw fail("error.invalid_range_number", "label", "amount", "min", "1", "max", Integer.toString(Integer.MAX_VALUE));
        }
        if (unitPrice <= 0) {
            throw fail("error.invalid_positive_number", "label", "unit price");
        }
        if (unitPrice > 1_000_000_000L) {
            throw fail("error.invalid_range_number", "label", "unit price", "min", "1", "max", "1000000000");
        }
        if (hours < 1 || hours > 168) {
            throw fail("error.invalid_range_number", "label", "hours", "min", "1", "max", "168");
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void auditSuspiciousLikePattern(Player player, ClaimRecord claim) {
        LocalDate since = LocalDate.now(this.clock).minusDays(this.plugin.getConfig().getLong("player.likes.suspicious_same_owner_window_days", 7L) - 1);
        long threshold = this.plugin.getConfig().getLong("player.likes.suspicious_same_owner_threshold", 3L);
        long sameOwnerLikes = this.repositories.likes().stream()
                .filter(like -> like.voterUuid().equals(player.getUniqueId()))
                .filter(like -> like.targetOwnerUuid().equals(claim.ownerUuid()))
                .filter(like -> !like.likedOn().isBefore(since))
                .count();
        if (sameOwnerLikes >= threshold) {
            this.audit("like_suspicious_pattern", player.getName(), Map.of(
                    "targetOwner", claim.ownerName(),
                    "likesInWindow", sameOwnerLikes,
                    "windowDays", this.plugin.getConfig().getLong("player.likes.suspicious_same_owner_window_days", 7L)
            ));
        }
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
