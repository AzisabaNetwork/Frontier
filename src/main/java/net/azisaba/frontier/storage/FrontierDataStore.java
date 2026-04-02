package net.azisaba.frontier.storage;

import net.azisaba.frontier.domain.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class FrontierDataStore {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    private final JavaPlugin plugin;
    private final File file;

    public FrontierDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public FrontierState load() {
        FrontierState state = new FrontierState();
        if (!this.file.exists()) {
            return state;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
        int schemaVersion = yaml.getInt("meta.schemaVersion", 0);
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported data schema version: " + schemaVersion);
        }
        state.schemaVersion = schemaVersion == 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        state.seasonSequence = yaml.getLong("sequences.season");
        state.claimSequence = yaml.getLong("sequences.claim");
        state.missionSequence = yaml.getLong("sequences.mission");
        state.orderSequence = yaml.getLong("sequences.order");
        state.lastDailyRotationDate = yaml.getString("sequences.lastDailyRotationDate");
        state.lastWeeklyRotationDate = yaml.getString("sequences.lastWeeklyRotationDate");

        ConfigurationSection seasons = yaml.getConfigurationSection("seasons");
        if (seasons != null) {
            for (String key : seasons.getKeys(false)) {
                ConfigurationSection section = seasons.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                long id = Long.parseLong(key);
                state.seasons.put(id, new SeasonRecord(
                        id,
                        section.getString("key", "season-" + id),
                        section.getString("displayName", "Season " + id),
                        section.getString("worldName", "world"),
                        SeasonPhase.valueOf(section.getString("phase", SeasonPhase.PRESEASON.name())),
                        parseInstant(section.getString("createdAt")),
                        parseInstant(section.getString("startAt")),
                        parseInstant(section.getString("endAt")),
                        parseInstant(section.getString("archiveAt")),
                        section.getBoolean("active", false)
                ));
            }
        }

        ConfigurationSection profiles = yaml.getConfigurationSection("profiles");
        if (profiles != null) {
            for (String key : profiles.getKeys(false)) {
                ConfigurationSection section = profiles.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                state.profiles.put(key, new PlayerProfileRecord(
                        UUID.fromString(section.getString("playerId")),
                        section.getString("lastKnownName", "unknown"),
                        section.getLong("seasonId"),
                        section.getLong("coins"),
                        section.getLong("seasonPoints"),
                        section.getInt("totalMissionCompleted"),
                        section.getInt("totalLikesReceived"),
                        section.getBoolean("starterClaimed", false),
                        section.getInt("tutorialStep", 0),
                        section.getBoolean("tutorialCompleted", false),
                        parseInstant(section.getString("lastSupportedAt")),
                        parseInstant(section.getString("lastSupportReceivedAt")),
                        parseInstant(section.getString("joinAt")),
                        parseInstant(section.getString("lastActiveAt"))
                ));
            }
        }

        ConfigurationSection claims = yaml.getConfigurationSection("claims");
        if (claims != null) {
            for (String key : claims.getKeys(false)) {
                ConfigurationSection section = claims.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                long id = Long.parseLong(key);
                state.claims.put(id, new ClaimRecord(
                        id,
                        section.getLong("seasonId"),
                        section.getString("world"),
                        UUID.fromString(section.getString("ownerUuid")),
                        section.getString("ownerName", "unknown"),
                        section.getString("regionId"),
                        section.getInt("chunkX"),
                        section.getInt("chunkZ"),
                        ClaimState.valueOf(section.getString("state", ClaimState.ACTIVE.name())),
                        parseInstant(section.getString("createdAt")),
                        parseInstant(section.getString("expiresAt")),
                        parseInstant(section.getString("warningAt")),
                        parseInstant(section.getString("abandonedAt"))
                ));
            }
        }

        ConfigurationSection missions = yaml.getConfigurationSection("missions");
        if (missions != null) {
            for (String key : missions.getKeys(false)) {
                ConfigurationSection section = missions.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                long id = Long.parseLong(key);
                state.missions.put(id, new MissionRecord(
                        id,
                        section.getLong("seasonId"),
                        MissionScope.valueOf(section.getString("scope", MissionScope.DAILY.name())),
                        section.getString("title", "Mission"),
                        section.getString("description", ""),
                        section.getString("targetKey", "minecraft:stone"),
                        section.getLong("targetValue", 1L),
                        section.getLong("rewardCoins", 0L),
                        section.getLong("rewardPoints", 0L),
                        section.getBoolean("active", true)
                ));
            }
        }

        ConfigurationSection progress = yaml.getConfigurationSection("missionProgress");
        if (progress != null) {
            for (String key : progress.getKeys(false)) {
                ConfigurationSection section = progress.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                state.missionProgress.put(key, new MissionProgressRecord(
                        section.getLong("missionId"),
                        UUID.fromString(section.getString("playerId")),
                        section.getLong("progress"),
                        section.getBoolean("completed"),
                        parseInstant(section.getString("completedAt"))
                ));
            }
        }

        ConfigurationSection orders = yaml.getConfigurationSection("orders");
        if (orders != null) {
            for (String key : orders.getKeys(false)) {
                ConfigurationSection section = orders.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                long id = Long.parseLong(key);
                String ownerUuid = section.getString("ownerUuid");
                String reservedByUuid = section.getString("reservedByUuid");
                state.orders.put(id, new OrderRecord(
                        id,
                        section.getLong("seasonId"),
                        ownerUuid == null || ownerUuid.isBlank() ? null : UUID.fromString(ownerUuid),
                        section.getString("ownerName", "unknown"),
                        OrderType.valueOf(section.getString("orderType", OrderType.BUY_ITEM.name())),
                        section.getString("itemKey", "minecraft:stone"),
                        section.getLong("amount"),
                        section.getLong("unitPrice"),
                        section.getLong("fee"),
                        OrderStatus.valueOf(section.getString("status", OrderStatus.OPEN.name())),
                        reservedByUuid == null || reservedByUuid.isBlank() ? null : UUID.fromString(reservedByUuid),
                        section.getString("reservedByName"),
                        parseInstant(section.getString("reservedAt")),
                        parseInstant(section.getString("createdAt")),
                        parseInstant(section.getString("expiresAt"))
                ));
            }
        }

        ConfigurationSection likes = yaml.getConfigurationSection("likes");
        if (likes != null) {
            for (String key : likes.getKeys(false)) {
                ConfigurationSection section = likes.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                state.likes.put(key, new LikeRecord(
                        section.getLong("seasonId"),
                        section.getLong("claimId"),
                        UUID.fromString(section.getString("targetOwnerUuid")),
                        UUID.fromString(section.getString("voterUuid")),
                        LocalDate.parse(section.getString("likedOn"))
                ));
            }
        }

        ConfigurationSection notifications = yaml.getConfigurationSection("claimNotifications");
        if (notifications != null) {
            for (String key : notifications.getKeys(false)) {
                state.claimNotificationTimes.put(key, notifications.getString(key));
            }
        }
        return state;
    }

    public void save(FrontierState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schemaVersion", CURRENT_SCHEMA_VERSION);
        yaml.set("sequences.season", state.seasonSequence);
        yaml.set("sequences.claim", state.claimSequence);
        yaml.set("sequences.mission", state.missionSequence);
        yaml.set("sequences.order", state.orderSequence);
        yaml.set("sequences.lastDailyRotationDate", state.lastDailyRotationDate);
        yaml.set("sequences.lastWeeklyRotationDate", state.lastWeeklyRotationDate);
        for (SeasonRecord season : state.seasons.values()) {
            String path = "seasons." + season.id();
            yaml.set(path + ".key", season.key());
            yaml.set(path + ".displayName", season.displayName());
            yaml.set(path + ".worldName", season.worldName());
            yaml.set(path + ".phase", season.phase().name());
            yaml.set(path + ".createdAt", stringify(season.createdAt()));
            yaml.set(path + ".startAt", stringify(season.startAt()));
            yaml.set(path + ".endAt", stringify(season.endAt()));
            yaml.set(path + ".archiveAt", stringify(season.archiveAt()));
            yaml.set(path + ".active", season.active());
        }
        for (var entry : state.profiles.entrySet()) {
            PlayerProfileRecord profile = entry.getValue();
            String path = "profiles." + entry.getKey();
            yaml.set(path + ".playerId", profile.playerId().toString());
            yaml.set(path + ".lastKnownName", profile.lastKnownName());
            yaml.set(path + ".seasonId", profile.seasonId());
            yaml.set(path + ".coins", profile.coins());
            yaml.set(path + ".seasonPoints", profile.seasonPoints());
            yaml.set(path + ".totalMissionCompleted", profile.totalMissionCompleted());
            yaml.set(path + ".totalLikesReceived", profile.totalLikesReceived());
            yaml.set(path + ".starterClaimed", profile.starterClaimed());
            yaml.set(path + ".tutorialStep", profile.tutorialStep());
            yaml.set(path + ".tutorialCompleted", profile.tutorialCompleted());
            yaml.set(path + ".lastSupportedAt", stringify(profile.lastSupportedAt()));
            yaml.set(path + ".lastSupportReceivedAt", stringify(profile.lastSupportReceivedAt()));
            yaml.set(path + ".joinAt", stringify(profile.joinAt()));
            yaml.set(path + ".lastActiveAt", stringify(profile.lastActiveAt()));
        }
        for (ClaimRecord claim : state.claims.values()) {
            String path = "claims." + claim.id();
            yaml.set(path + ".seasonId", claim.seasonId());
            yaml.set(path + ".world", claim.world());
            yaml.set(path + ".ownerUuid", claim.ownerUuid().toString());
            yaml.set(path + ".ownerName", claim.ownerName());
            yaml.set(path + ".regionId", claim.regionId());
            yaml.set(path + ".chunkX", claim.chunkX());
            yaml.set(path + ".chunkZ", claim.chunkZ());
            yaml.set(path + ".state", claim.state().name());
            yaml.set(path + ".createdAt", stringify(claim.createdAt()));
            yaml.set(path + ".expiresAt", stringify(claim.expiresAt()));
            yaml.set(path + ".warningAt", stringify(claim.warningAt()));
            yaml.set(path + ".abandonedAt", stringify(claim.abandonedAt()));
        }
        for (MissionRecord mission : state.missions.values()) {
            String path = "missions." + mission.id();
            yaml.set(path + ".seasonId", mission.seasonId());
            yaml.set(path + ".scope", mission.scope().name());
            yaml.set(path + ".title", mission.title());
            yaml.set(path + ".description", mission.description());
            yaml.set(path + ".targetKey", mission.targetKey());
            yaml.set(path + ".targetValue", mission.targetValue());
            yaml.set(path + ".rewardCoins", mission.rewardCoins());
            yaml.set(path + ".rewardPoints", mission.rewardPoints());
            yaml.set(path + ".active", mission.active());
        }
        for (var entry : state.missionProgress.entrySet()) {
            MissionProgressRecord missionProgress = entry.getValue();
            String path = "missionProgress." + entry.getKey();
            yaml.set(path + ".missionId", missionProgress.missionId());
            yaml.set(path + ".playerId", missionProgress.playerId().toString());
            yaml.set(path + ".progress", missionProgress.progress());
            yaml.set(path + ".completed", missionProgress.completed());
            yaml.set(path + ".completedAt", stringify(missionProgress.completedAt()));
        }
        for (OrderRecord order : state.orders.values()) {
            String path = "orders." + order.id();
            yaml.set(path + ".seasonId", order.seasonId());
            yaml.set(path + ".ownerUuid", order.ownerUuid() == null ? null : order.ownerUuid().toString());
            yaml.set(path + ".ownerName", order.ownerName());
            yaml.set(path + ".orderType", order.orderType().name());
            yaml.set(path + ".itemKey", order.itemKey());
            yaml.set(path + ".amount", order.amount());
            yaml.set(path + ".unitPrice", order.unitPrice());
            yaml.set(path + ".fee", order.fee());
            yaml.set(path + ".status", order.status().name());
            yaml.set(path + ".reservedByUuid", order.reservedByUuid() == null ? null : order.reservedByUuid().toString());
            yaml.set(path + ".reservedByName", order.reservedByName());
            yaml.set(path + ".reservedAt", stringify(order.reservedAt()));
            yaml.set(path + ".createdAt", stringify(order.createdAt()));
            yaml.set(path + ".expiresAt", stringify(order.expiresAt()));
        }
        for (var entry : state.likes.entrySet()) {
            LikeRecord like = entry.getValue();
            String path = "likes." + entry.getKey();
            yaml.set(path + ".seasonId", like.seasonId());
            yaml.set(path + ".claimId", like.claimId());
            yaml.set(path + ".targetOwnerUuid", like.targetOwnerUuid().toString());
            yaml.set(path + ".voterUuid", like.voterUuid().toString());
            yaml.set(path + ".likedOn", like.likedOn().toString());
        }
        for (var entry : state.claimNotificationTimes.entrySet()) {
            yaml.set("claimNotifications." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(this.file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save Frontier data", e);
        }
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String stringify(Instant value) {
        return value == null ? null : value.toString();
    }
}
