package net.azisaba.frontier;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.azisaba.frontier.audit.AuditService;
import net.azisaba.frontier.command.AliasedFrontierCommand;
import net.azisaba.frontier.command.FrontierCommand;
import net.azisaba.frontier.domain.ClaimNotification;
import net.azisaba.frontier.gui.FrontierMenuService;
import net.azisaba.frontier.integration.bluemap.BlueMapClaimVisualizer;
import net.azisaba.frontier.integration.economy.EconomyProvider;
import net.azisaba.frontier.integration.placeholder.FrontierPlaceholderExpansion;
import net.azisaba.frontier.integration.worldguard.ClaimProtectionProvider;
import net.azisaba.frontier.listener.FrontierListener;
import net.azisaba.frontier.listener.FrontierMenuListener;
import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.repository.FrontierRepositories;
import net.azisaba.frontier.repository.MySqlFrontierRepositories;
import net.azisaba.frontier.repository.RepositoryFactory;
import net.azisaba.frontier.service.FrontierService;
import net.azisaba.frontier.storage.DatabaseSettings;
import net.azisaba.frontier.tab.FrontierTabListService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;

public class FrontierPlugin extends JavaPlugin {
    private FrontierService frontierService;
    private MessageService messageService;
    private AuditService auditService;
    private FrontierRepositories repositories;
    private DatabaseSettings databaseSettings;
    private FrontierMenuService frontierMenuService;
    private BlueMapClaimVisualizer blueMapClaimVisualizer;
    private FrontierTabListService tabListService;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.messageService = new MessageService(this);
        this.auditService = new AuditService(this);
        this.databaseSettings = DatabaseSettings.load(this);
        try {
            this.repositories = RepositoryFactory.create(this, this.databaseSettings);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize Frontier storage", e);
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage());
            throw e;
        }
        this.frontierService = new FrontierService(
                this,
                this.repositories,
                Clock.system(ZoneId.of(this.getConfig().getString("plugin.timezone", "Asia/Tokyo")))
        );
        this.frontierService.attachIntegrations(
                EconomyProvider.create(this, this.frontierService),
                ClaimProtectionProvider.create(),
                this.auditService
        );
        this.tabListService = new FrontierTabListService(this, this.frontierService);
        this.frontierMenuService = new FrontierMenuService(this.frontierService, this.messageService);
        if (Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
            this.blueMapClaimVisualizer = new BlueMapClaimVisualizer(this, this.frontierService);
            this.blueMapClaimVisualizer.start();
        }
        Bukkit.getPluginManager().registerEvents(new FrontierListener(this.frontierService, this.messageService, this.tabListService), this);
        Bukkit.getPluginManager().registerEvents(new FrontierMenuListener(this.frontierMenuService, this.messageService), this);
        this.tickAutomationAndNotify();
        Bukkit.getScheduler().runTaskTimer(this, this::tickAutomationAndNotify, 20L * 60L, 20L * 60L);
        Bukkit.getScheduler().runTaskTimer(this, () -> this.frontierMenuService.refreshOpenOrderMenus(), 60L, 60L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (this.tabListService != null) {
                this.tabListService.refreshAll();
            }
        }, 20L, 20L * 10L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (this.blueMapClaimVisualizer != null) {
                this.blueMapClaimVisualizer.refresh();
            }
        }, 20L * 10L, 20L * 10L);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            FrontierCommand frontierCommand = new FrontierCommand(this, this.frontierService, this.messageService);
            commands.registrar().register("frontier", "Frontier main command", java.util.List.of("fr"), frontierCommand);
            commands.registrar().register("season", "Alias for /frontier season", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "season"));
            commands.registrar().register("points", "Alias for /frontier points", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "points"));
            commands.registrar().register("coins", "Alias for /frontier coins", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "coins"));
            commands.registrar().register("missions", "Alias for /frontier missions", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "missions"));
            commands.registrar().register("claims", "Alias for /frontier claims", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "claims"));
            commands.registrar().register("claim", "Alias for /frontier claim", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "claim"));
            commands.registrar().register("orders", "Alias for /frontier orders", java.util.List.of("board"), new AliasedFrontierCommand(frontierCommand, "orders"));
            commands.registrar().register("menu", "Alias for /frontier menu", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "menu"));
            commands.registrar().register("newcomer", "Alias for /frontier newcomer", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "newcomer"));
            commands.registrar().register("like", "Alias for /frontier like", java.util.List.of(), new AliasedFrontierCommand(frontierCommand, "like"));
        });
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new FrontierPlaceholderExpansion(this, this.frontierService).register();
        }
        getLogger().info("Frontier integrations: economy=" + this.frontierService.economyMode() + ", claims=" + this.frontierService.claimProtectionMode() + ", bluemap=" + (this.blueMapClaimVisualizer == null ? "disabled" : "enabled"));
    }

    @Override
    public void onDisable() {
        if (this.frontierService != null) {
            this.frontierService.save();
        }
        if (this.tabListService != null) {
            this.tabListService.clear();
        }
        if (this.blueMapClaimVisualizer != null) {
            this.blueMapClaimVisualizer.stop();
        }
        if (this.repositories instanceof MySqlFrontierRepositories mySqlFrontierRepositories) {
            mySqlFrontierRepositories.close();
        }
    }

    private void tickAutomationAndNotify() {
        this.frontierService.tickAutomation();
        for (ClaimNotification notification : this.frontierService.drainClaimNotifications()) {
            Player player = Bukkit.getPlayer(notification.ownerUuid());
            if (player == null) {
                continue;
            }
            java.util.Map<String, String> placeholders = java.util.Map.of(
                    "prefix", this.messageService.get("prefix"),
                    "id", Long.toString(notification.claimId())
            );
            if (this.getConfig().getBoolean("claims.warnings.notify_chat", true)) {
                String key = switch (notification.state()) {
                    case WARNING -> "claim.warning";
                    case EXPIRED -> "claim.expired";
                    case ABANDONED -> "claim.abandoned";
                    default -> null;
                };
                if (key != null) {
                    this.messageService.send(player, key, placeholders);
                }
            }
            if (this.getConfig().getBoolean("claims.warnings.notify_actionbar", true)) {
                String text = switch (notification.state()) {
                    case WARNING -> this.messageService.get("claim.warning_actionbar", placeholders);
                    case EXPIRED -> this.messageService.get("claim.expired_actionbar", placeholders);
                    case ABANDONED -> this.messageService.get("claim.abandoned_actionbar", placeholders);
                    default -> null;
                };
                if (text != null) {
                    player.sendActionBar(text);
                }
            }
            if (this.getConfig().getBoolean("claims.warnings.notify_title", false)) {
                switch (notification.state()) {
                    case WARNING -> player.sendTitle(
                            this.messageService.get("claim.warning_title", placeholders),
                            this.messageService.get("claim.warning_subtitle", placeholders),
                            10, 60, 10
                    );
                    case EXPIRED -> player.sendTitle(
                            this.messageService.get("claim.expired_title", placeholders),
                            this.messageService.get("claim.expired_subtitle", placeholders),
                            10, 60, 10
                    );
                    case ABANDONED -> player.sendTitle(
                            this.messageService.get("claim.abandoned_title", placeholders),
                            this.messageService.get("claim.abandoned_subtitle", placeholders),
                            10, 60, 10
                    );
                    default -> {
                    }
                }
            }
        }
    }

    public FrontierRepositories repositories() {
        return this.repositories;
    }

    public AuditService auditService() {
        return this.auditService;
    }

    public DatabaseSettings databaseSettings() {
        return this.databaseSettings;
    }

    public FrontierMenuService menus() {
        return this.frontierMenuService;
    }
}
