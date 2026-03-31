package net.azisaba.frontier.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.azisaba.frontier.FrontierPlugin;
import net.azisaba.frontier.audit.AuditService;
import net.azisaba.frontier.domain.ClaimRecord;
import net.azisaba.frontier.domain.MissionRecord;
import net.azisaba.frontier.domain.OrderRecord;
import net.azisaba.frontier.domain.OrderType;
import net.azisaba.frontier.domain.PlayerProfileRecord;
import net.azisaba.frontier.domain.SeasonPhase;
import net.azisaba.frontier.domain.SeasonRecord;
import net.azisaba.frontier.domain.OrderStatus;
import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.service.FrontierService;
import net.azisaba.frontier.service.StorageMigrationService;
import net.azisaba.frontier.util.UserMessageException;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class FrontierCommand implements BasicCommand {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private final FrontierPlugin plugin;
    private final FrontierService service;
    private final MessageService messages;

    public FrontierCommand(FrontierPlugin plugin, FrontierService service, MessageService messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        try {
            if (args.length == 0 || equals(args[0], "help")) {
                this.sendHelp(sender);
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "season" -> this.handleSeason(sender, tail(args));
                case "points", "coins" -> this.handleBalances(sender);
                case "missions" -> this.handleMissions(sender);
                case "claims" -> this.handleClaims(sender);
                case "claim" -> this.handleClaim(sender, tail(args));
                case "orders", "board" -> this.handleOrders(sender, tail(args));
                case "menu" -> this.handleMenu(sender);
                case "newcomer" -> this.handleNewcomer(sender, tail(args));
                case "like" -> this.handleLike(sender);
                case "admin" -> this.handleAdmin(sender, tail(args));
                default -> this.sendHelp(sender);
            }
        } catch (UserMessageException e) {
            this.messages.send(sender, e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sender.sendMessage(this.messages.get("error.internal", map("prefix", this.messages.get("prefix"), "message", e.getMessage())));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
        CommandSender sender = commandSourceStack.getSender();
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(args[0], List.of("help", "season", "points", "coins", "missions", "claims", "claim", "orders", "menu", "newcomer", "like", "admin"));
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "claim" -> this.suggestClaim(sender, args);
            case "orders", "board" -> this.suggestOrders(args);
            case "newcomer" -> this.suggestNewcomer(args);
            case "admin" -> this.suggestAdmin(sender, args);
            default -> List.of();
        };
    }

    @Override
    public String permission() {
        return "frontier.user";
    }

    private void handleSeason(CommandSender sender, String[] args) {
        SeasonRecord season = this.service.getActiveSeason().orElseThrow(() -> new UserMessageException("error.no_active_season", map("prefix", this.messages.get("prefix"))));
        this.messages.send(sender, "season.status", map("prefix", this.messages.get("prefix"), "display", season.displayName(), "key", season.key()));
        this.messages.send(sender, "season.phase", map("phase", displaySeasonPhase(season.phase())));
        this.messages.send(sender, "season.world", map("world", season.worldName()));
    }

    private void handleBalances(CommandSender sender) {
        Player player = requirePlayer(sender);
        PlayerProfileRecord profile = this.service.getProfile(player.getUniqueId());
        this.messages.send(sender, "balance.coins", map("prefix", this.messages.get("prefix"), "amount", Long.toString(profile.coins())));
        this.messages.send(sender, "balance.sp", map("prefix", this.messages.get("prefix"), "amount", Long.toString(profile.seasonPoints())));
    }

    private void handleMissions(CommandSender sender) {
        Player player = requirePlayer(sender);
        List<MissionRecord> missions = this.service.getActiveMissions();
        this.messages.send(sender, "mission.header", map("prefix", this.messages.get("prefix")));
        for (MissionRecord mission : missions) {
            long progress = this.service.getMissionProgress(player.getUniqueId(), mission.id()).progress();
            this.messages.send(sender, "mission.entry", map(
                    "scope", mission.scope().name(),
                    "title", mission.title(),
                    "progress", Long.toString(progress),
                    "target", Long.toString(mission.targetValue()),
                    "coins", Long.toString(mission.rewardCoins()),
                    "sp", Long.toString(mission.rewardPoints())
            ));
        }
    }

    private void handleClaims(CommandSender sender) {
        Player player = requirePlayer(sender);
        List<ClaimRecord> claims = this.service.getClaims(player.getUniqueId());
        if (claims.isEmpty()) {
            this.messages.send(sender, "claim.none_owned", map("prefix", this.messages.get("prefix")));
            return;
        }
        this.messages.send(sender, "claim.header", map("prefix", this.messages.get("prefix")));
        for (ClaimRecord claim : claims) {
            this.messages.send(sender, "claim.entry", map(
                    "prefix", this.messages.get("prefix"),
                    "id", Long.toString(claim.id()),
                    "world", claim.world(),
                    "x", Integer.toString(claim.chunkX()),
                    "z", Integer.toString(claim.chunkZ()),
                    "state", displayClaimState(claim.state()),
                    "expires", DATE_TIME.format(claim.expiresAt())
            ));
        }
    }

    private void handleClaim(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (args.length == 0) {
            this.messages.send(sender, "help.claim");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                ClaimRecord claim = this.service.createClaim(player);
                this.messages.send(sender, "claim.created", map("prefix", this.messages.get("prefix"), "id", Long.toString(claim.id()), "x", Integer.toString(claim.chunkX()), "z", Integer.toString(claim.chunkZ())));
            }
            case "info" -> this.service.getClaimAt(player.getLocation().getChunk())
                    .ifPresentOrElse(
                            claim -> this.messages.send(sender, "claim.info", map("prefix", this.messages.get("prefix"), "id", Long.toString(claim.id()), "owner", claim.ownerName(), "state", displayClaimState(claim.state()), "expires", DATE_TIME.format(claim.expiresAt()))),
                            () -> this.messages.send(sender, "claim.not_claimed", map("prefix", this.messages.get("prefix")))
                    );
            case "renew" -> {
                long claimId = claimIdFromContext(player, args, 1, this.service);
                ClaimRecord claim = this.service.renewClaim(player, claimId);
                this.messages.send(sender, "claim.renewed", map("prefix", this.messages.get("prefix"), "id", Long.toString(claim.id()), "expires", DATE_TIME.format(claim.expiresAt())));
            }
            case "release" -> {
                long claimId = claimIdFromContext(player, args, 1, this.service);
                ClaimRecord claim = this.service.releaseClaim(player, claimId);
                this.messages.send(sender, "claim.released", map("prefix", this.messages.get("prefix"), "id", Long.toString(claim.id())));
            }
            case "members", "shared", "list" -> {
                long claimId = claimIdFromContext(player, args, 1, this.service);
                List<String> owners = this.service.claimOwners(player, claimId);
                List<String> members = this.service.claimMembers(player, claimId);
                this.messages.send(sender, "claim.share_header", map("prefix", this.messages.get("prefix"), "id", Long.toString(claimId)));
                this.messages.send(sender, "claim.owners", map("owners", joinNames(owners)));
                this.messages.send(sender, "claim.members", map("members", joinNames(members)));
            }
            case "trust" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.claim_trust", map("prefix", this.messages.get("prefix")));
                }
                long claimId = claimIdFromContext(player, args, 2, this.service);
                this.service.trustClaimMember(player, claimId, args[1]);
                this.messages.send(sender, "claim.trusted", map("prefix", this.messages.get("prefix"), "player", args[1], "id", Long.toString(claimId)));
            }
            case "untrust" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.claim_untrust", map("prefix", this.messages.get("prefix")));
                }
                long claimId = claimIdFromContext(player, args, 2, this.service);
                this.service.untrustClaimMember(player, claimId, args[1]);
                this.messages.send(sender, "claim.untrusted", map("prefix", this.messages.get("prefix"), "player", args[1], "id", Long.toString(claimId)));
            }
            case "owner" -> {
                if (args.length < 3) {
                    throw new UserMessageException("usage.claim_owner", map("prefix", this.messages.get("prefix")));
                }
                long claimId = claimIdFromContext(player, args, 3, this.service);
                if (equals(args[1], "add")) {
                    this.service.addClaimOwner(player, claimId, args[2]);
                    this.messages.send(sender, "claim.owner_added", map("prefix", this.messages.get("prefix"), "player", args[2], "id", Long.toString(claimId)));
                } else if (equals(args[1], "remove")) {
                    this.service.removeClaimOwner(player, claimId, args[2]);
                    this.messages.send(sender, "claim.owner_removed", map("prefix", this.messages.get("prefix"), "player", args[2], "id", Long.toString(claimId)));
                } else {
                    throw new UserMessageException("usage.claim_owner", map("prefix", this.messages.get("prefix")));
                }
            }
            default -> this.messages.send(sender, "help.claim");
        }
    }

    private void handleOrders(CommandSender sender, String[] args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        if (args.length == 0 || equals(args[0], "list")) {
            List<OrderRecord> orders = this.service.listOrders();
            this.messages.send(sender, "order.header", map("prefix", this.messages.get("prefix")));
            for (OrderRecord order : orders) {
                this.messages.send(sender, "order.entry", map(
                        "id", Long.toString(order.id()),
                        "status", displayOrderStatus(order.status()),
                        "type", displayOrderType(order.orderType()),
                        "item", order.itemKey(),
                        "amount", Long.toString(order.amount()),
                        "price", Long.toString(order.unitPrice()),
                        "owner", order.ownerName(),
                        "reservedBy", order.reservedByName() == null ? "-" : order.reservedByName()
                ));
            }
            return;
        }
        if (player == null) {
            throw new UserMessageException("error.player_only", map("prefix", this.messages.get("prefix")));
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 5) {
                    throw new UserMessageException("usage.orders_create", map("prefix", this.messages.get("prefix")));
                }
                OrderType type = parseOrderType(args[1]);
                String item = normalizeItemKey(args[2]);
                long amount = parsePositiveLong(args[3], "amount");
                long unitPrice = parsePositiveLong(args[4], "unit price");
                int hours = args.length >= 6 ? parseBoundedInt(args[5], "hours", 1, 168) : 24;
                OrderRecord order = this.service.createPlayerOrder(player, type, item, amount, unitPrice, hours);
                this.messages.send(sender, "order.created", map("prefix", this.messages.get("prefix"), "id", Long.toString(order.id()), "fee", Long.toString(order.fee())));
            }
            case "fill" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.orders_fill", map("prefix", this.messages.get("prefix")));
                }
                OrderRecord order = this.service.fillOrder(player, parsePositiveLong(args[1], "order id"));
                this.messages.send(sender, "order.completed", map("prefix", this.messages.get("prefix"), "id", Long.toString(order.id())));
            }
            case "accept" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.orders_accept", map("prefix", this.messages.get("prefix")));
                }
                OrderRecord order = this.service.reserveOrder(player, parsePositiveLong(args[1], "order id"));
                this.messages.send(sender, "order.reserved", map("prefix", this.messages.get("prefix"), "id", Long.toString(order.id())));
            }
            case "deliver", "submit" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.orders_deliver", map("prefix", this.messages.get("prefix")));
                }
                OrderRecord order = this.service.deliverOrder(player, parsePositiveLong(args[1], "order id"));
                this.messages.send(sender, "order.completed", map("prefix", this.messages.get("prefix"), "id", Long.toString(order.id())));
            }
            default -> this.messages.send(sender, "help.orders");
        }
    }

    private void handleMenu(CommandSender sender) {
        Player player = requirePlayer(sender);
        this.plugin.menus().openMain(player);
    }

    private void handleNewcomer(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        this.handleNewcomer(player, args);
    }

    private void handleNewcomer(Player player, String[] args) {
        if (args.length == 0 || equals(args[0], "status")) {
            this.messages.send(player, "newcomer.status", map("prefix", this.messages.get("prefix"), "status", this.service.newcomerStatus(player)));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "starter" -> {
                this.service.claimStarter(player);
                this.messages.send(player, "newcomer.starter_claimed", map("prefix", this.messages.get("prefix")));
            }
            case "support" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.newcomer_support", map("prefix", this.messages.get("prefix")));
                }
                Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    throw new UserMessageException("error.player_must_be_online", map("prefix", this.messages.get("prefix")));
                }
                this.service.supportNewcomer(player, target);
                this.messages.send(player, "newcomer.supported", map("prefix", this.messages.get("prefix"), "player", target.getName()));
            }
            default -> this.messages.send(player, "help.newcomer");
        }
    }

    private void handleLike(CommandSender sender) {
        Player player = requirePlayer(sender);
        ClaimRecord claim = this.service.likeCurrentClaim(player);
        this.messages.send(sender, "like.added", map("prefix", this.messages.get("prefix"), "owner", claim.ownerName()));
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("frontier.admin")) {
            this.messages.send(sender, "error.no_permission", map("prefix", this.messages.get("prefix")));
            return;
        }
        if (args.length == 0) {
            this.messages.send(sender, "help.admin_season");
            this.messages.send(sender, "help.admin_economy");
            this.messages.send(sender, "help.admin_order");
            this.messages.send(sender, "help.admin_archive");
            this.messages.send(sender, "help.admin_migrate");
            this.messages.send(sender, "help.admin_debug");
            this.messages.send(sender, "admin.integrations", map("prefix", this.messages.get("prefix"), "economy", this.service.economyMode(), "claims", this.service.claimProtectionMode()));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                this.plugin.reloadConfig();
                this.messages.reload();
                this.service.attachIntegrations(
                        net.azisaba.frontier.integration.economy.EconomyProvider.create(this.plugin, this.service),
                        net.azisaba.frontier.integration.worldguard.ClaimProtectionProvider.create(),
                        new AuditService(this.plugin)
                );
                this.logAdmin(sender, "reload", map());
                this.messages.send(sender, "admin.reload", map("prefix", this.messages.get("prefix")));
            }
            case "season" -> this.handleAdminSeason(sender, tail(args));
            case "economy" -> this.handleAdminEconomy(sender, tail(args));
            case "order" -> this.handleAdminOrder(sender, tail(args));
            case "archive" -> this.handleAdminArchive(sender, tail(args));
            case "migrate" -> this.handleAdminMigrate(sender, tail(args));
            case "debug" -> this.handleAdminDebug(sender, tail(args));
            default -> this.messages.send(sender, "help.admin_season");
        }
    }

    private void handleAdminDebug(CommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new UserMessageException("usage.admin_debug", map("prefix", this.messages.get("prefix")));
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "player" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.admin_debug_player", map("prefix", this.messages.get("prefix")));
                }
                Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    throw new UserMessageException("error.player_must_be_online", map("prefix", this.messages.get("prefix")));
                }
                PlayerProfileRecord profile = this.service.getProfile(target.getUniqueId());
                this.messages.send(sender, "debug.player", map(
                        "prefix", this.messages.get("prefix"),
                        "player", target.getName(),
                        "coins", Long.toString(profile.coins()),
                        "sp", Long.toString(profile.seasonPoints()),
                        "missions", Integer.toString(profile.totalMissionCompleted()),
                        "likes", Integer.toString(profile.totalLikesReceived()),
                        "starter", Boolean.toString(profile.starterClaimed())
                ));
            }
            case "claim" -> {
                ClaimRecord claim;
                if (args.length >= 2) {
                    claim = this.plugin.repositories().findClaim(parsePositiveLong(args[1], "claim id"));
                } else {
                    Player player = requirePlayer(sender);
                    claim = this.service.getClaimAt(player.getLocation().getChunk()).orElse(null);
                }
                if (claim == null) {
                    throw new UserMessageException("error.claim_not_found", map("prefix", this.messages.get("prefix")));
                }
                this.messages.send(sender, "debug.claim", map(
                        "prefix", this.messages.get("prefix"),
                        "id", Long.toString(claim.id()),
                        "owner", claim.ownerName(),
                        "world", claim.world(),
                        "x", Integer.toString(claim.chunkX()),
                        "z", Integer.toString(claim.chunkZ()),
                        "state", displayClaimState(claim.state()),
                        "expires", DATE_TIME.format(claim.expiresAt())
                ));
            }
            case "order" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.admin_debug_order", map("prefix", this.messages.get("prefix")));
                }
                OrderRecord order = this.plugin.repositories().findOrder(parsePositiveLong(args[1], "order id"));
                if (order == null) {
                    throw new UserMessageException("error.order_not_found_or_closed", map("prefix", this.messages.get("prefix")));
                }
                this.messages.send(sender, "debug.order", map(
                        "prefix", this.messages.get("prefix"),
                        "id", Long.toString(order.id()),
                        "owner", order.ownerName(),
                        "status", displayOrderStatus(order.status()),
                        "item", order.itemKey(),
                        "amount", Long.toString(order.amount()),
                        "price", Long.toString(order.unitPrice()),
                        "reservedBy", order.reservedByName() == null ? "-" : order.reservedByName()
                ));
            }
            case "suspicious" -> {
                long suspiciousOrders = this.plugin.repositories().orders().stream().filter(order -> order.status() == OrderStatus.RESERVED).count();
                long suspiciousLikes = this.plugin.repositories().likes().size();
                this.messages.send(sender, "debug.suspicious", map(
                        "prefix", this.messages.get("prefix"),
                        "reservedOrders", Long.toString(suspiciousOrders),
                        "likes", Long.toString(suspiciousLikes)
                ));
            }
            default -> throw new UserMessageException("usage.admin_debug", map("prefix", this.messages.get("prefix")));
        }
    }

    private void handleAdminSeason(CommandSender sender, String[] args) {
        if (args.length == 0) {
            this.messages.send(sender, "help.admin_season");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 3) {
                    throw new UserMessageException("usage.admin_season_create", map("prefix", this.messages.get("prefix")));
                }
                String key = args[1];
                String displayName = String.join(" ", List.of(args).subList(2, args.length));
                String world = this.service.getDefaultSeasonWorld();
                SeasonRecord season = this.service.createSeason(key, displayName, world);
                this.logAdmin(sender, "season_create", map("season", season.key()));
                this.messages.send(sender, "season.created", map("prefix", this.messages.get("prefix"), "display", season.displayName()));
            }
            case "setphase" -> {
                if (args.length < 2) {
                    throw new UserMessageException("usage.admin_season_setphase", map("prefix", this.messages.get("prefix")));
                }
                SeasonRecord season = this.service.setPhase(SeasonPhase.valueOf(args[1].toUpperCase(Locale.ROOT)));
                this.logAdmin(sender, "season_setphase", map("phase", season.phase().name()));
                this.messages.send(sender, "season.phase_changed", map("prefix", this.messages.get("prefix"), "phase", displaySeasonPhase(season.phase())));
            }
            case "start" -> {
                SeasonRecord current = this.service.getActiveSeason().orElseThrow(() -> new UserMessageException("error.no_active_season", map("prefix", this.messages.get("prefix"))));
                SeasonPhase next = switch (current.phase()) {
                    case PRESEASON -> SeasonPhase.OPENING;
                    case OPENING -> SeasonPhase.ACTIVE;
                    default -> throw new UserMessageException("error.invalid_start_phase", map("prefix", this.messages.get("prefix")));
                };
                this.logAdmin(sender, "season_start", map("next", next.name()));
                this.messages.send(sender, "season.phase_changed", map("prefix", this.messages.get("prefix"), "phase", displaySeasonPhase(this.service.setPhase(next).phase())));
            }
            case "finale" -> {
                this.logAdmin(sender, "season_finale", map());
                this.messages.send(sender, "season.phase_changed", map("prefix", this.messages.get("prefix"), "phase", displaySeasonPhase(this.service.setPhase(SeasonPhase.FINALE).phase())));
            }
            case "archive" -> {
                this.logAdmin(sender, "season_archive", map());
                this.messages.send(sender, "season.phase_changed", map("prefix", this.messages.get("prefix"), "phase", displaySeasonPhase(this.service.setPhase(SeasonPhase.ARCHIVED).phase())));
            }
            default -> this.messages.send(sender, "help.admin_season");
        }
    }

    private void handleAdminEconomy(CommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new UserMessageException("usage.admin_economy", map("prefix", this.messages.get("prefix")));
        }
        Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            throw new UserMessageException("error.player_must_be_online", map("prefix", this.messages.get("prefix")));
        }
        long amount = parseNonZeroBoundedLong(args[2], "amount", 1_000_000_000L);
        if (equals(args[0], "coins")) {
            this.service.addCoins(target.getUniqueId(), amount, "admin_command");
        } else if (equals(args[0], "sp")) {
            this.service.addSeasonPoints(target.getUniqueId(), amount, "admin_command");
        } else {
            throw new UserMessageException("usage.admin_economy", map("prefix", this.messages.get("prefix")));
        }
        this.logAdmin(sender, "economy_adjust", map("target", target.getName(), "amount", Long.toString(amount), "kind", args[0]));
        this.service.save();
        this.messages.send(sender, "admin.economy_updated", map("prefix", this.messages.get("prefix"), "player", target.getName()));
    }

    private void handleAdminOrder(CommandSender sender, String[] args) {
        if (args.length < 5 || !equals(args[0], "server")) {
            throw new UserMessageException("usage.admin_order_server", map("prefix", this.messages.get("prefix")));
        }
        String item = normalizeItemKey(args[1]);
        long amount = parsePositiveLong(args[2], "amount");
        long unitPrice = parsePositiveLong(args[3], "unit price");
        int hours = parseBoundedInt(args[4], "hours", 1, 168);
        OrderRecord order = this.service.createServerOrder(item, amount, unitPrice, hours);
        this.logAdmin(sender, "server_order_create", map("orderId", Long.toString(order.id())));
        this.service.save();
        this.messages.send(sender, "order.server_created", map("prefix", this.messages.get("prefix"), "id", Long.toString(order.id())));
    }

    private void handleAdminArchive(CommandSender sender, String[] args) {
        if (args.length == 0 || equals(args[0], "export")) {
            File file = this.service.exportArchive();
            this.logAdmin(sender, "archive_export", map("file", file.getName()));
            this.messages.send(sender, "archive.exported", map("prefix", this.messages.get("prefix"), "file", file.getName()));
            return;
        }
        throw new UserMessageException("usage.admin_archive_export", map("prefix", this.messages.get("prefix")));
    }

    private void handleAdminMigrate(CommandSender sender, String[] args) {
        if (args.length < 1 || !equals(args[0], "mysql")) {
            throw new UserMessageException("usage.admin_migrate_mysql", map("prefix", this.messages.get("prefix")));
        }
        if (this.plugin.databaseSettings().useMySql()) {
            throw new UserMessageException("error.migration_already_mysql", map("prefix", this.messages.get("prefix")));
        }
        StorageMigrationService.MigrationResult result = new StorageMigrationService(this.plugin, this.plugin.auditService())
                .migrateYamlToMySql(this.plugin.repositories(), this.plugin.databaseSettings());
        this.logAdmin(sender, "storage_migrate_mysql", map(
                "seasons", Integer.toString(result.seasons()),
                "profiles", Integer.toString(result.profiles()),
                "claims", Integer.toString(result.claims()),
                "missions", Integer.toString(result.missions()),
                "orders", Integer.toString(result.orders())
        ));
        this.messages.send(sender, "admin.migrate_mysql_completed", map(
                "prefix", this.messages.get("prefix"),
                "seasons", Integer.toString(result.seasons()),
                "profiles", Integer.toString(result.profiles()),
                "claims", Integer.toString(result.claims()),
                "missions", Integer.toString(result.missions()),
                "orders", Integer.toString(result.orders()),
                "likes", Integer.toString(result.likes()),
                "notifications", Integer.toString(result.claimNotifications())
        ));
    }

    private void sendHelp(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("help.header");
        lines.add("help.season");
        lines.add("help.points");
        lines.add("help.missions");
        lines.add("help.claim");
        lines.add("help.orders");
        lines.add("help.menu");
        lines.add("help.newcomer");
        lines.add("help.like");
        if (sender.hasPermission("frontier.admin")) {
            lines.add("help.admin_season");
            lines.add("help.admin_economy");
            lines.add("help.admin_order");
            lines.add("help.admin_archive");
            lines.add("help.admin_migrate");
            lines.add("help.admin_debug");
        }
        lines.forEach(key -> this.messages.send(sender, key, map("prefix", this.messages.get("prefix"))));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new UserMessageException("error.player_only", map("prefix", this.messages.get("prefix")));
    }

    private static String[] tail(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] tail = new String[args.length - 1];
        System.arraycopy(args, 1, tail, 0, tail.length);
        return tail;
    }

    private static boolean equals(String left, String right) {
        return left.equalsIgnoreCase(right);
    }

    private Collection<String> suggestClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 2) {
            return filterPrefix(args[1], List.of("create", "info", "renew", "release", "members", "trust", "untrust", "owner"));
        }
        if (equals(args[1], "renew") || equals(args[1], "release") || equals(args[1], "members")) {
            if (args.length == 3) {
                return filterPrefix(args[2], ownedClaimIds(player));
            }
            return List.of();
        }
        if (equals(args[1], "trust") || equals(args[1], "untrust")) {
            if (args.length == 3) {
                return equals(args[1], "trust")
                        ? filterPrefix(args[2], trustablePlayerNames(player))
                        : filterPrefix(args[2], claimMemberNames(player, args, 3));
            }
            if (args.length == 4) {
                return filterPrefix(args[3], ownedClaimIds(player));
            }
            return List.of();
        }
        if (equals(args[1], "owner")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("add", "remove"));
            }
            if (args.length == 4) {
                return equals(args[2], "add")
                        ? filterPrefix(args[3], ownerAddablePlayerNames(player, args))
                        : filterPrefix(args[3], claimOwnerNames(player, args, 4));
            }
            if (args.length == 5) {
                return filterPrefix(args[4], ownedClaimIds(player));
            }
        }
        return List.of();
    }

    private Collection<String> suggestOrders(String[] args) {
        if (args.length == 2) {
            return filterPrefix(args[1], List.of("list", "create", "accept", "deliver", "fill"));
        }
        if (equals(args[1], "create")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("buy", "sell"));
            }
            if (args.length == 4) {
                return filterPrefix(args[3], List.of("minecraft:stone", "minecraft:torch", "minecraft:wheat", "minecraft:cod"));
            }
        }
        return List.of();
    }

    private Collection<String> suggestNewcomer(String[] args) {
        if (args.length == 2) {
            return filterPrefix(args[1], List.of("status", "starter", "support"));
        }
        if (equals(args[1], "support") && args.length == 3) {
            return filterPrefix(args[2], onlinePlayerNames());
        }
        return List.of();
    }

    private Collection<String> suggestAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("frontier.admin")) {
            return List.of();
        }
        if (args.length == 2) {
            return filterPrefix(args[1], List.of("reload", "season", "economy", "order", "archive", "migrate", "debug"));
        }
        if (equals(args[1], "season")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("create", "setphase", "start", "finale", "archive"));
            }
            if (equals(args[2], "setphase") && args.length == 4) {
                return filterPrefix(args[3], Stream.of(SeasonPhase.values()).map(Enum::name).toList());
            }
            return List.of();
        }
        if (equals(args[1], "economy")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("coins", "sp"));
            }
            if (args.length == 4) {
                return filterPrefix(args[3], onlinePlayerNames());
            }
            return List.of();
        }
        if (equals(args[1], "order")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("server"));
            }
            return List.of();
        }
        if (equals(args[1], "archive")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("export"));
            }
            return List.of();
        }
        if (equals(args[1], "migrate")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("mysql"));
            }
            return List.of();
        }
        if (equals(args[1], "debug")) {
            if (args.length == 3) {
                return filterPrefix(args[2], List.of("player", "claim", "order", "suspicious"));
            }
            if (equals(args[2], "player") && args.length == 4) {
                return filterPrefix(args[3], onlinePlayerNames());
            }
        }
        return List.of();
    }

    private List<String> ownedClaimIds(Player player) {
        return this.service.getClaims(player.getUniqueId()).stream()
                .map(claim -> Long.toString(claim.id()))
                .toList();
    }

    private List<String> trustablePlayerNames(Player player) {
        long claimId = currentClaimId(player);
        List<String> members = claimId > 0 ? this.safeClaimMembers(player, claimId) : List.of();
        List<String> owners = claimId > 0 ? this.safeClaimOwners(player, claimId) : List.of();
        return onlinePlayerNames().stream()
                .filter(name -> !name.equalsIgnoreCase(player.getName()))
                .filter(name -> members.stream().noneMatch(existing -> existing.equalsIgnoreCase(name)))
                .filter(name -> owners.stream().noneMatch(existing -> existing.equalsIgnoreCase(name)))
                .toList();
    }

    private List<String> ownerAddablePlayerNames(Player player, String[] args) {
        long claimId = args.length >= 5 ? parseClaimIdForSuggestion(args[4]) : currentClaimId(player);
        List<String> owners = claimId > 0 ? this.safeClaimOwners(player, claimId) : List.of();
        return onlinePlayerNames().stream()
                .filter(name -> owners.stream().noneMatch(existing -> existing.equalsIgnoreCase(name)))
                .toList();
    }

    private List<String> claimMemberNames(Player player, String[] args, int claimIdIndex) {
        long claimId = args.length > claimIdIndex ? parseClaimIdForSuggestion(args[claimIdIndex]) : currentClaimId(player);
        return claimId > 0 ? this.safeClaimMembers(player, claimId) : List.of();
    }

    private List<String> claimOwnerNames(Player player, String[] args, int claimIdIndex) {
        long claimId = args.length > claimIdIndex ? parseClaimIdForSuggestion(args[claimIdIndex]) : currentClaimId(player);
        return claimId > 0 ? this.safeClaimOwners(player, claimId) : List.of();
    }

    private long currentClaimId(Player player) {
        return this.service.getClaimAt(player.getLocation().getChunk())
                .map(ClaimRecord::id)
                .orElse(-1L);
    }

    private List<String> safeClaimMembers(Player player, long claimId) {
        try {
            return this.service.claimMembers(player, claimId);
        } catch (UserMessageException ignored) {
            return List.of();
        }
    }

    private List<String> safeClaimOwners(Player player, long claimId) {
        try {
            return this.service.claimOwners(player, claimId);
        } catch (UserMessageException ignored) {
            return List.of();
        }
    }

    private static long parseClaimIdForSuggestion(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static List<String> filterPrefix(String input, Collection<String> values) {
        String needle = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(needle))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UserMessageException("error.invalid_number", map("label", label, "value", value));
        }
    }

    private static long parsePositiveLong(String value, String label) {
        long parsed = parseLong(value, label);
        if (parsed <= 0) {
            throw new UserMessageException("error.invalid_positive_number", map("label", label));
        }
        return parsed;
    }

    private static long parseNonZeroBoundedLong(String value, String label, long maxAbs) {
        long parsed = parseLong(value, label);
        if (parsed == 0) {
            throw new UserMessageException("error.invalid_nonzero_number", map("label", label));
        }
        if (Math.abs(parsed) > maxAbs) {
            throw new UserMessageException("error.invalid_range_number", map("label", label, "min", Long.toString(-maxAbs), "max", Long.toString(maxAbs)));
        }
        return parsed;
    }

    private static int parseBoundedInt(String value, String label, int min, int max) {
        long parsed = parseLong(value, label);
        if (parsed < min || parsed > max) {
            throw new UserMessageException("error.invalid_range_number", map("label", label, "min", Integer.toString(min), "max", Integer.toString(max)));
        }
        return (int) parsed;
    }

    private static long claimIdFromContext(Player player, String[] args, int index, FrontierService service) {
        if (args.length > index) {
            return parsePositiveLong(args[index], "claim id");
        }
        return service.getClaimAt(player.getLocation().getChunk())
                .map(ClaimRecord::id)
                .orElseThrow(() -> new UserMessageException("error.claim_context_required"));
    }

    private static String joinNames(List<String> names) {
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private static OrderType parseOrderType(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "buy" -> OrderType.BUY_ITEM;
            case "sell" -> OrderType.SELL_ITEM;
            default -> throw new UserMessageException("error.order_type_invalid");
        };
    }

    private static String normalizeItemKey(String raw) {
        return raw.startsWith("minecraft:") ? raw : "minecraft:" + raw.toLowerCase(Locale.ROOT);
    }

    private static String displaySeasonPhase(SeasonPhase phase) {
        return switch (phase) {
            case PRESEASON -> "プレシーズン";
            case OPENING -> "オープニング";
            case ACTIVE -> "進行中";
            case FINALE -> "フィナーレ";
            case ARCHIVED -> "アーカイブ";
        };
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

    private static String displayOrderStatus(OrderStatus status) {
        return switch (status) {
            case OPEN -> "受付中";
            case RESERVED -> "予約中";
            case COMPLETED -> "完了";
            case EXPIRED -> "期限切れ";
            case CANCELLED -> "取消";
        };
    }

    private static String displayOrderType(OrderType type) {
        return switch (type) {
            case BUY_ITEM -> "買取";
            case SELL_ITEM -> "販売";
        };
    }

    private static java.util.Map<String, String> map(String... values) {
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            placeholders.put(values[i], values[i + 1]);
        }
        return placeholders;
    }

    private void logAdmin(CommandSender sender, String action, java.util.Map<String, String> details) {
        java.util.Map<String, String> payload = new java.util.LinkedHashMap<>(details);
        payload.put("action", action);
        new AuditService(this.plugin).log("admin_command", sender.getName(), payload);
    }
}
