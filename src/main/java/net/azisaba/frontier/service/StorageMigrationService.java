package net.azisaba.frontier.service;

import net.azisaba.frontier.audit.AuditService;
import net.azisaba.frontier.repository.FrontierRepositories;
import net.azisaba.frontier.repository.MySqlFrontierRepositories;
import net.azisaba.frontier.storage.DatabaseSettings;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class StorageMigrationService {
    private final JavaPlugin plugin;
    private final AuditService auditService;

    public StorageMigrationService(JavaPlugin plugin, AuditService auditService) {
        this.plugin = plugin;
        this.auditService = auditService;
    }

    public MigrationResult migrateYamlToMySql(FrontierRepositories source, DatabaseSettings settings) {
        try (MySqlFrontierRepositories target = new MySqlFrontierRepositories(this.plugin, settings.asMySql())) {
            copyAll(source, target);
            target.flush();
            this.auditService.log("storage_migrated", "system", java.util.Map.of(
                    "source", "yaml",
                    "target", "mysql",
                    "seasons", source.seasons().size(),
                    "profiles", source.profiles().size(),
                    "claims", source.claims().size(),
                    "missions", source.missions().size(),
                    "progress", source.missionProgress().size(),
                    "orders", source.orders().size(),
                    "likes", source.likes().size()
            ));
            return new MigrationResult(
                    source.seasons().size(),
                    source.profiles().size(),
                    source.claims().size(),
                    source.missions().size(),
                    source.missionProgress().size(),
                    source.orders().size(),
                    source.likes().size(),
                    source.claimNotificationTimes().size()
            );
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL migration failed", e);
        }
    }

    private static void copyAll(FrontierRepositories source, FrontierRepositories target) {
        target.setCurrentSequence("season", source.currentSequence("season"));
        target.setCurrentSequence("claim", source.currentSequence("claim"));
        target.setCurrentSequence("mission", source.currentSequence("mission"));
        target.setCurrentSequence("order", source.currentSequence("order"));
        target.setLastDailyRotationDate(source.lastDailyRotationDate());
        target.setLastWeeklyRotationDate(source.lastWeeklyRotationDate());
        source.seasons().forEach(target::saveSeason);
        source.profiles().forEach(profile -> target.saveProfile(profile.seasonId() + ":" + profile.playerId(), profile));
        source.claims().forEach(target::saveClaim);
        source.missions().forEach(target::saveMission);
        source.missionProgress().forEach(progress -> target.saveMissionProgress(progress.missionId() + ":" + progress.playerId(), progress));
        source.orders().forEach(target::saveOrder);
        source.likes().forEach(like -> target.saveLike(like.claimId() + ":" + like.voterUuid() + ":" + like.likedOn(), like));
        source.claimNotificationTimes().forEach(target::setClaimNotificationAt);
    }

    public record MigrationResult(
            int seasons,
            int profiles,
            int claims,
            int missions,
            int missionProgress,
            int orders,
            int likes,
            int claimNotifications
    ) {
    }
}
