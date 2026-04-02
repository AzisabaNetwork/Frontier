package net.azisaba.frontier.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.azisaba.frontier.domain.*;
import net.azisaba.frontier.storage.DatabaseSettings;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MySqlFrontierRepositories implements FrontierRepositories, AutoCloseable {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    private final HikariDataSource dataSource;

    public MySqlFrontierRepositories(JavaPlugin plugin, DatabaseSettings settings) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("FrontierMySql");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        this.dataSource = new HikariDataSource(config);
        this.initializeSchema();
        this.migrateSchema();
        plugin.getLogger().info("Frontier storage: mysql");
    }

    @Override
    public long nextSeasonId() {
        return this.nextSequence("season");
    }

    @Override
    public long nextClaimId() {
        return this.nextSequence("claim");
    }

    @Override
    public long nextMissionId() {
        return this.nextSequence("mission");
    }

    @Override
    public long nextOrderId() {
        return this.nextSequence("order");
    }

    @Override
    public long currentSequence(String key) {
        return this.queryOne("SELECT next_value FROM frontier_sequences WHERE sequence_key = ?", rs -> rs.getLong(1), ps -> ps.setString(1, key)).orElse(0L);
    }

    @Override
    public void setCurrentSequence(String key, long value) {
        this.update("""
                INSERT INTO frontier_sequences(sequence_key, next_value)
                VALUES(?, ?)
                ON DUPLICATE KEY UPDATE next_value = VALUES(next_value)
                """, ps -> {
            ps.setString(1, key);
            ps.setLong(2, value);
        });
    }

    @Override
    public String lastDailyRotationDate() {
        return this.getSetting("last_daily_rotation_date");
    }

    @Override
    public void setLastDailyRotationDate(String value) {
        this.setSetting("last_daily_rotation_date", value);
    }

    @Override
    public String lastWeeklyRotationDate() {
        return this.getSetting("last_weekly_rotation_date");
    }

    @Override
    public void setLastWeeklyRotationDate(String value) {
        this.setSetting("last_weekly_rotation_date", value);
    }

    @Override
    public Optional<SeasonRecord> activeSeason() {
        return this.queryOne("SELECT * FROM frontier_seasons WHERE active = TRUE LIMIT 1", rs -> season(rs));
    }

    @Override
    public Collection<SeasonRecord> seasons() {
        return this.queryMany("SELECT * FROM frontier_seasons", rs -> season(rs));
    }

    @Override
    public void saveSeason(SeasonRecord season) {
        this.update("""
                INSERT INTO frontier_seasons(id, season_key, display_name, world_name, phase, created_at, start_at, end_at, archive_at, active)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                season_key = VALUES(season_key),
                display_name = VALUES(display_name),
                world_name = VALUES(world_name),
                phase = VALUES(phase),
                created_at = VALUES(created_at),
                start_at = VALUES(start_at),
                end_at = VALUES(end_at),
                archive_at = VALUES(archive_at),
                active = VALUES(active)
                """, ps -> {
            ps.setLong(1, season.id());
            ps.setString(2, season.key());
            ps.setString(3, season.displayName());
            ps.setString(4, season.worldName());
            ps.setString(5, season.phase().name());
            setInstant(ps, 6, season.createdAt());
            setInstant(ps, 7, season.startAt());
            setInstant(ps, 8, season.endAt());
            setInstant(ps, 9, season.archiveAt());
            ps.setBoolean(10, season.active());
        });
    }

    @Override
    public PlayerProfileRecord findProfile(String key) {
        String[] parts = key.split(":", 2);
        long seasonId = Long.parseLong(parts[0]);
        String playerId = parts[1];
        return this.queryOne("SELECT * FROM frontier_player_profiles WHERE season_id = ? AND player_uuid = ?", rs -> profile(rs), ps -> {
            ps.setLong(1, seasonId);
            ps.setString(2, playerId);
        }).orElse(null);
    }

    @Override
    public Collection<PlayerProfileRecord> profiles() {
        return this.queryMany("SELECT * FROM frontier_player_profiles", rs -> profile(rs));
    }

    @Override
    public void saveProfile(String key, PlayerProfileRecord profile) {
        this.update("""
                INSERT INTO frontier_player_profiles(player_uuid, last_known_name, season_id, coins, season_points, total_mission_completed, total_likes_received, starter_claimed, tutorial_step, tutorial_completed, last_supported_at, last_support_received_at, join_at, last_active_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                last_known_name = VALUES(last_known_name),
                coins = VALUES(coins),
                season_points = VALUES(season_points),
                total_mission_completed = VALUES(total_mission_completed),
                total_likes_received = VALUES(total_likes_received),
                starter_claimed = VALUES(starter_claimed),
                tutorial_step = VALUES(tutorial_step),
                tutorial_completed = VALUES(tutorial_completed),
                last_supported_at = VALUES(last_supported_at),
                last_support_received_at = VALUES(last_support_received_at),
                join_at = VALUES(join_at),
                last_active_at = VALUES(last_active_at)
                """, ps -> {
            ps.setString(1, profile.playerId().toString());
            ps.setString(2, profile.lastKnownName());
            ps.setLong(3, profile.seasonId());
            ps.setLong(4, profile.coins());
            ps.setLong(5, profile.seasonPoints());
            ps.setInt(6, profile.totalMissionCompleted());
            ps.setInt(7, profile.totalLikesReceived());
            ps.setBoolean(8, profile.starterClaimed());
            ps.setInt(9, profile.tutorialStep());
            ps.setBoolean(10, profile.tutorialCompleted());
            setInstant(ps, 11, profile.lastSupportedAt());
            setInstant(ps, 12, profile.lastSupportReceivedAt());
            setInstant(ps, 13, profile.joinAt());
            setInstant(ps, 14, profile.lastActiveAt());
        });
    }

    @Override
    public ClaimRecord findClaim(long claimId) {
        return this.queryOne("SELECT * FROM frontier_claims WHERE id = ?", rs -> claim(rs), ps -> ps.setLong(1, claimId)).orElse(null);
    }

    @Override
    public Collection<ClaimRecord> claims() {
        return this.queryMany("SELECT * FROM frontier_claims", rs -> claim(rs));
    }

    @Override
    public void saveClaim(ClaimRecord claim) {
        this.update("""
                INSERT INTO frontier_claims(id, season_id, world, owner_uuid, owner_name, region_id, chunk_x, chunk_z, state, created_at, expires_at, warning_at, abandoned_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                season_id = VALUES(season_id),
                world = VALUES(world),
                owner_uuid = VALUES(owner_uuid),
                owner_name = VALUES(owner_name),
                region_id = VALUES(region_id),
                chunk_x = VALUES(chunk_x),
                chunk_z = VALUES(chunk_z),
                state = VALUES(state),
                created_at = VALUES(created_at),
                expires_at = VALUES(expires_at),
                warning_at = VALUES(warning_at),
                abandoned_at = VALUES(abandoned_at)
                """, ps -> {
            ps.setLong(1, claim.id());
            ps.setLong(2, claim.seasonId());
            ps.setString(3, claim.world());
            ps.setString(4, claim.ownerUuid().toString());
            ps.setString(5, claim.ownerName());
            ps.setString(6, claim.regionId());
            ps.setInt(7, claim.chunkX());
            ps.setInt(8, claim.chunkZ());
            ps.setString(9, claim.state().name());
            setInstant(ps, 10, claim.createdAt());
            setInstant(ps, 11, claim.expiresAt());
            setInstant(ps, 12, claim.warningAt());
            setInstant(ps, 13, claim.abandonedAt());
        });
    }

    @Override
    public MissionRecord findMission(long missionId) {
        return this.queryOne("SELECT * FROM frontier_missions WHERE id = ?", rs -> mission(rs), ps -> ps.setLong(1, missionId)).orElse(null);
    }

    @Override
    public Collection<MissionRecord> missions() {
        return this.queryMany("SELECT * FROM frontier_missions", rs -> mission(rs));
    }

    @Override
    public void saveMission(MissionRecord mission) {
        this.update("""
                INSERT INTO frontier_missions(id, season_id, scope, title, description, target_key, target_value, reward_coins, reward_points, active)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                season_id = VALUES(season_id),
                scope = VALUES(scope),
                title = VALUES(title),
                description = VALUES(description),
                target_key = VALUES(target_key),
                target_value = VALUES(target_value),
                reward_coins = VALUES(reward_coins),
                reward_points = VALUES(reward_points),
                active = VALUES(active)
                """, ps -> {
            ps.setLong(1, mission.id());
            ps.setLong(2, mission.seasonId());
            ps.setString(3, mission.scope().name());
            ps.setString(4, mission.title());
            ps.setString(5, mission.description());
            ps.setString(6, mission.targetKey());
            ps.setLong(7, mission.targetValue());
            ps.setLong(8, mission.rewardCoins());
            ps.setLong(9, mission.rewardPoints());
            ps.setBoolean(10, mission.active());
        });
    }

    @Override
    public MissionProgressRecord findMissionProgress(String key) {
        String[] parts = key.split(":", 2);
        long missionId = Long.parseLong(parts[0]);
        String playerId = parts[1];
        return this.queryOne("SELECT * FROM frontier_mission_progress WHERE mission_id = ? AND player_uuid = ?", rs -> missionProgress(rs), ps -> {
            ps.setLong(1, missionId);
            ps.setString(2, playerId);
        }).orElse(null);
    }

    @Override
    public Collection<MissionProgressRecord> missionProgress() {
        return this.queryMany("SELECT * FROM frontier_mission_progress", rs -> missionProgress(rs));
    }

    @Override
    public void saveMissionProgress(String key, MissionProgressRecord progress) {
        this.update("""
                INSERT INTO frontier_mission_progress(mission_id, player_uuid, progress, completed, completed_at)
                VALUES(?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                progress = VALUES(progress),
                completed = VALUES(completed),
                completed_at = VALUES(completed_at)
                """, ps -> {
            ps.setLong(1, progress.missionId());
            ps.setString(2, progress.playerId().toString());
            ps.setLong(3, progress.progress());
            ps.setBoolean(4, progress.completed());
            setInstant(ps, 5, progress.completedAt());
        });
    }

    @Override
    public OrderRecord findOrder(long orderId) {
        return this.queryOne("SELECT * FROM frontier_orders WHERE id = ?", rs -> order(rs), ps -> ps.setLong(1, orderId)).orElse(null);
    }

    @Override
    public Collection<OrderRecord> orders() {
        return this.queryMany("SELECT * FROM frontier_orders", rs -> order(rs));
    }

    @Override
    public void saveOrder(OrderRecord order) {
        this.update("""
                INSERT INTO frontier_orders(id, season_id, owner_uuid, owner_name, order_type, item_key, amount, unit_price, fee, status, reserved_by_uuid, reserved_by_name, reserved_at, created_at, expires_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                season_id = VALUES(season_id),
                owner_uuid = VALUES(owner_uuid),
                owner_name = VALUES(owner_name),
                order_type = VALUES(order_type),
                item_key = VALUES(item_key),
                amount = VALUES(amount),
                unit_price = VALUES(unit_price),
                fee = VALUES(fee),
                status = VALUES(status),
                reserved_by_uuid = VALUES(reserved_by_uuid),
                reserved_by_name = VALUES(reserved_by_name),
                reserved_at = VALUES(reserved_at),
                created_at = VALUES(created_at),
                expires_at = VALUES(expires_at)
                """, ps -> {
            ps.setLong(1, order.id());
            ps.setLong(2, order.seasonId());
            if (order.ownerUuid() == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, order.ownerUuid().toString());
            }
            ps.setString(4, order.ownerName());
            ps.setString(5, order.orderType().name());
            ps.setString(6, order.itemKey());
            ps.setLong(7, order.amount());
            ps.setLong(8, order.unitPrice());
            ps.setLong(9, order.fee());
            ps.setString(10, order.status().name());
            if (order.reservedByUuid() == null) {
                ps.setNull(11, Types.VARCHAR);
            } else {
                ps.setString(11, order.reservedByUuid().toString());
            }
            ps.setString(12, order.reservedByName());
            setInstant(ps, 13, order.reservedAt());
            setInstant(ps, 14, order.createdAt());
            setInstant(ps, 15, order.expiresAt());
        });
    }

    @Override
    public boolean hasLike(String key) {
        String[] parts = key.split(":");
        return this.queryOne("SELECT 1 FROM frontier_likes WHERE claim_id = ? AND voter_uuid = ? AND liked_on = ? LIMIT 1", rs -> Boolean.TRUE, ps -> {
            ps.setLong(1, Long.parseLong(parts[0]));
            ps.setString(2, parts[1]);
            ps.setDate(3, Date.valueOf(parts[2]));
        }).orElse(Boolean.FALSE);
    }

    @Override
    public Collection<LikeRecord> likes() {
        return this.queryMany("SELECT * FROM frontier_likes", rs -> like(rs));
    }

    @Override
    public void saveLike(String key, LikeRecord like) {
        this.update("""
                INSERT INTO frontier_likes(season_id, claim_id, target_owner_uuid, voter_uuid, liked_on)
                VALUES(?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                target_owner_uuid = VALUES(target_owner_uuid)
                """, ps -> {
            ps.setLong(1, like.seasonId());
            ps.setLong(2, like.claimId());
            ps.setString(3, like.targetOwnerUuid().toString());
            ps.setString(4, like.voterUuid().toString());
            ps.setDate(5, Date.valueOf(like.likedOn()));
        });
    }

    @Override
    public Instant claimNotificationAt(String key) {
        return this.queryOne("SELECT notified_at FROM frontier_claim_notifications WHERE notification_key = ?", rs -> instant(rs, "notified_at"), ps -> ps.setString(1, key)).orElse(null);
    }

    @Override
    public Map<String, Instant> claimNotificationTimes() {
        Map<String, Instant> values = new LinkedHashMap<>();
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT notification_key, notified_at FROM frontier_claim_notifications");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                values.put(rs.getString("notification_key"), instant(rs, "notified_at"));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL query failed", e);
        }
    }

    @Override
    public void setClaimNotificationAt(String key, Instant instant) {
        this.update("""
                INSERT INTO frontier_claim_notifications(notification_key, notified_at)
                VALUES(?, ?)
                ON DUPLICATE KEY UPDATE notified_at = VALUES(notified_at)
                """, ps -> {
            ps.setString(1, key);
            setInstant(ps, 2, instant);
        });
    }

    @Override
    public void flush() {
    }

    public void close() {
        this.dataSource.close();
    }

    private long nextSequence(String key) {
        this.update("""
                INSERT INTO frontier_sequences(sequence_key, next_value)
                VALUES(?, 1)
                ON DUPLICATE KEY UPDATE next_value = next_value + 1
                """, ps -> ps.setString(1, key));
        return this.queryOne("SELECT next_value FROM frontier_sequences WHERE sequence_key = ?", rs -> rs.getLong(1), ps -> ps.setString(1, key))
                .orElseThrow(() -> new IllegalStateException("Missing sequence: " + key));
    }

    private String getSetting(String key) {
        return this.queryOne("SELECT setting_value FROM frontier_settings WHERE setting_key = ?", rs -> rs.getString(1), ps -> ps.setString(1, key)).orElse(null);
    }

    private void setSetting(String key, String value) {
        this.update("""
                INSERT INTO frontier_settings(setting_key, setting_value)
                VALUES(?, ?)
                ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
                """, ps -> {
            ps.setString(1, key);
            ps.setString(2, value);
        });
    }

    private void initializeSchema() throws SQLException {
        try (Connection ignored = this.dataSource.getConnection()) {
            // Connectivity probe and lazy schema bootstrap through versioned migrations.
        }
    }

    private void migrateSchema() {
        if (!this.tableExists("frontier_settings")) {
            this.applyMigration0To1();
            this.setSetting("schema_version", "1");
        }
        String version = this.getSetting("schema_version");
        if (version == null) {
            this.applyMigration0To1();
            version = "1";
            this.setSetting("schema_version", version);
        }
        int existing = Integer.parseInt(version);
        if (existing > CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported database schema version: " + existing);
        }
        while (existing < CURRENT_SCHEMA_VERSION) {
            switch (existing) {
                case 0 -> this.applyMigration0To1();
                case 1 -> this.applyMigration1To2();
                case 2 -> this.applyMigration2To3();
                default -> throw new IllegalStateException("Unsupported database schema version: " + existing);
            }
            existing++;
            this.setSetting("schema_version", Integer.toString(existing));
        }
        if (existing >= 2) {
            // Self-heal installs that were incorrectly marked as schema v2 before the v1->v2 ALTERs ran.
            this.applyMigration1To2();
        }
        if (existing >= 3) {
            this.applyMigration2To3();
        }
    }

    private boolean tableExists(String tableName) {
        try (Connection connection = this.dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL query failed", e);
        }
    }

    private void applyMigration0To1() {
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_sequences (sequence_key VARCHAR(64) PRIMARY KEY, next_value BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_settings (setting_key VARCHAR(64) PRIMARY KEY, setting_value VARCHAR(255) NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_seasons (id BIGINT PRIMARY KEY, season_key VARCHAR(64) UNIQUE NOT NULL, display_name VARCHAR(128) NOT NULL, world_name VARCHAR(128) NOT NULL, phase VARCHAR(32) NOT NULL, created_at TIMESTAMP NULL, start_at TIMESTAMP NULL, end_at TIMESTAMP NULL, archive_at TIMESTAMP NULL, active BOOLEAN NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_player_profiles (player_uuid CHAR(36) NOT NULL, season_id BIGINT NOT NULL, last_known_name VARCHAR(64) NULL, coins BIGINT NOT NULL, season_points BIGINT NOT NULL, total_mission_completed INT NOT NULL, total_likes_received INT NOT NULL, starter_claimed BOOLEAN NOT NULL DEFAULT FALSE, tutorial_step INT NOT NULL DEFAULT 0, tutorial_completed BOOLEAN NOT NULL DEFAULT FALSE, last_supported_at TIMESTAMP NULL, last_support_received_at TIMESTAMP NULL, join_at TIMESTAMP NULL, last_active_at TIMESTAMP NULL, PRIMARY KEY(player_uuid, season_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_claims (id BIGINT PRIMARY KEY, season_id BIGINT NOT NULL, world VARCHAR(64) NOT NULL, owner_uuid CHAR(36) NOT NULL, owner_name VARCHAR(64) NOT NULL, region_id VARCHAR(128) NOT NULL, chunk_x INT NOT NULL, chunk_z INT NOT NULL, state VARCHAR(32) NOT NULL, created_at TIMESTAMP NULL, expires_at TIMESTAMP NULL, warning_at TIMESTAMP NULL, abandoned_at TIMESTAMP NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_missions (id BIGINT PRIMARY KEY, season_id BIGINT NOT NULL, scope VARCHAR(32) NOT NULL, title VARCHAR(128) NOT NULL, description TEXT NOT NULL, target_key VARCHAR(128) NOT NULL, target_value BIGINT NOT NULL, reward_coins BIGINT NOT NULL, reward_points BIGINT NOT NULL, active BOOLEAN NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_mission_progress (mission_id BIGINT NOT NULL, player_uuid CHAR(36) NOT NULL, progress BIGINT NOT NULL, completed BOOLEAN NOT NULL, completed_at TIMESTAMP NULL, PRIMARY KEY(mission_id, player_uuid))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_orders (id BIGINT PRIMARY KEY, season_id BIGINT NOT NULL, owner_uuid CHAR(36) NULL, owner_name VARCHAR(64) NOT NULL, order_type VARCHAR(32) NOT NULL, item_key VARCHAR(128) NOT NULL, amount BIGINT NOT NULL, unit_price BIGINT NOT NULL, fee BIGINT NOT NULL, status VARCHAR(32) NOT NULL, reserved_by_uuid CHAR(36) NULL, reserved_by_name VARCHAR(64) NULL, reserved_at TIMESTAMP NULL, created_at TIMESTAMP NULL, expires_at TIMESTAMP NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_likes (claim_id BIGINT NOT NULL, voter_uuid CHAR(36) NOT NULL, season_id BIGINT NOT NULL, target_owner_uuid CHAR(36) NOT NULL, liked_on DATE NOT NULL, PRIMARY KEY(claim_id, voter_uuid, liked_on))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS frontier_claim_notifications (notification_key VARCHAR(128) PRIMARY KEY, notified_at TIMESTAMP NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL migration failed", e);
        }
    }

    private void applyMigration1To2() {
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_player_profiles ADD COLUMN starter_claimed BOOLEAN NOT NULL DEFAULT FALSE");
        } catch (SQLException ignored) {
        }
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_player_profiles ADD COLUMN last_supported_at TIMESTAMP NULL");
        } catch (SQLException ignored) {
        }
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_player_profiles ADD COLUMN last_support_received_at TIMESTAMP NULL");
        } catch (SQLException ignored) {
        }
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_orders ADD COLUMN reserved_by_uuid CHAR(36) NULL");
        } catch (SQLException ignored) {
        }
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_orders ADD COLUMN reserved_by_name VARCHAR(64) NULL");
        } catch (SQLException ignored) {
        }
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_orders ADD COLUMN reserved_at TIMESTAMP NULL");
        } catch (SQLException ignored) {
        }
    }

    private void applyMigration2To3() {
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_player_profiles ADD COLUMN tutorial_step INT NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE frontier_player_profiles ADD COLUMN tutorial_completed BOOLEAN NOT NULL DEFAULT FALSE");
        } catch (SQLException ignored) {
        }
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper) {
        return this.queryOne(sql, mapper, ps -> {});
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, StatementFiller filler) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            filler.fill(statement);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapper.map(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL query failed", e);
        }
    }

    private <T> Collection<T> queryMany(String sql, RowMapper<T> mapper) {
        List<T> rows = new ArrayList<>();
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL query failed", e);
        }
    }

    private void update(String sql, StatementFiller filler) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            filler.fill(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL update failed", e);
        }
    }

    private static SeasonRecord season(ResultSet rs) throws SQLException {
        return new SeasonRecord(
                rs.getLong("id"),
                rs.getString("season_key"),
                rs.getString("display_name"),
                rs.getString("world_name"),
                SeasonPhase.valueOf(rs.getString("phase")),
                instant(rs, "created_at"),
                instant(rs, "start_at"),
                instant(rs, "end_at"),
                instant(rs, "archive_at"),
                rs.getBoolean("active")
        );
    }

    private static PlayerProfileRecord profile(ResultSet rs) throws SQLException {
        return new PlayerProfileRecord(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("last_known_name"),
                rs.getLong("season_id"),
                rs.getLong("coins"),
                rs.getLong("season_points"),
                rs.getInt("total_mission_completed"),
                rs.getInt("total_likes_received"),
                rs.getBoolean("starter_claimed"),
                rs.getInt("tutorial_step"),
                rs.getBoolean("tutorial_completed"),
                instant(rs, "last_supported_at"),
                instant(rs, "last_support_received_at"),
                instant(rs, "join_at"),
                instant(rs, "last_active_at")
        );
    }

    private static ClaimRecord claim(ResultSet rs) throws SQLException {
        return new ClaimRecord(
                rs.getLong("id"),
                rs.getLong("season_id"),
                rs.getString("world"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                rs.getString("region_id"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                ClaimState.valueOf(rs.getString("state")),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "warning_at"),
                instant(rs, "abandoned_at")
        );
    }

    private static MissionRecord mission(ResultSet rs) throws SQLException {
        return new MissionRecord(
                rs.getLong("id"),
                rs.getLong("season_id"),
                MissionScope.valueOf(rs.getString("scope")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("target_key"),
                rs.getLong("target_value"),
                rs.getLong("reward_coins"),
                rs.getLong("reward_points"),
                rs.getBoolean("active")
        );
    }

    private static MissionProgressRecord missionProgress(ResultSet rs) throws SQLException {
        return new MissionProgressRecord(
                rs.getLong("mission_id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("progress"),
                rs.getBoolean("completed"),
                instant(rs, "completed_at")
        );
    }

    private static OrderRecord order(ResultSet rs) throws SQLException {
        String ownerUuid = rs.getString("owner_uuid");
        String reservedByUuid = rs.getString("reserved_by_uuid");
        return new OrderRecord(
                rs.getLong("id"),
                rs.getLong("season_id"),
                ownerUuid == null ? null : UUID.fromString(ownerUuid),
                rs.getString("owner_name"),
                OrderType.valueOf(rs.getString("order_type")),
                rs.getString("item_key"),
                rs.getLong("amount"),
                rs.getLong("unit_price"),
                rs.getLong("fee"),
                OrderStatus.valueOf(rs.getString("status")),
                reservedByUuid == null ? null : UUID.fromString(reservedByUuid),
                rs.getString("reserved_by_name"),
                instant(rs, "reserved_at"),
                instant(rs, "created_at"),
                instant(rs, "expires_at")
        );
    }

    private static LikeRecord like(ResultSet rs) throws SQLException {
        return new LikeRecord(
                rs.getLong("season_id"),
                rs.getLong("claim_id"),
                UUID.fromString(rs.getString("target_owner_uuid")),
                UUID.fromString(rs.getString("voter_uuid")),
                rs.getDate("liked_on").toLocalDate()
        );
    }

    private static void setInstant(PreparedStatement ps, int index, Instant instant) throws SQLException {
        if (instant == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementFiller {
        void fill(PreparedStatement ps) throws SQLException;
    }
}
