package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.*;
import net.azisaba.frontier.storage.FrontierDataStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Optional;

public final class YamlFrontierRepositories implements FrontierRepositories {
    private final FrontierDataStore dataStore;
    private final FrontierState state;

    public YamlFrontierRepositories(FrontierDataStore dataStore) {
        this.dataStore = dataStore;
        this.state = dataStore.load();
    }

    @Override
    public long nextSeasonId() {
        return ++this.state.seasonSequence;
    }

    @Override
    public long nextClaimId() {
        return ++this.state.claimSequence;
    }

    @Override
    public long nextMissionId() {
        return ++this.state.missionSequence;
    }

    @Override
    public long nextOrderId() {
        return ++this.state.orderSequence;
    }

    @Override
    public long currentSequence(String key) {
        return switch (key) {
            case "season" -> this.state.seasonSequence;
            case "claim" -> this.state.claimSequence;
            case "mission" -> this.state.missionSequence;
            case "order" -> this.state.orderSequence;
            default -> 0L;
        };
    }

    @Override
    public void setCurrentSequence(String key, long value) {
        switch (key) {
            case "season" -> this.state.seasonSequence = value;
            case "claim" -> this.state.claimSequence = value;
            case "mission" -> this.state.missionSequence = value;
            case "order" -> this.state.orderSequence = value;
            default -> throw new IllegalArgumentException("Unsupported sequence key: " + key);
        }
    }

    @Override
    public String lastDailyRotationDate() {
        return this.state.lastDailyRotationDate;
    }

    @Override
    public void setLastDailyRotationDate(String value) {
        this.state.lastDailyRotationDate = value;
    }

    @Override
    public String lastWeeklyRotationDate() {
        return this.state.lastWeeklyRotationDate;
    }

    @Override
    public void setLastWeeklyRotationDate(String value) {
        this.state.lastWeeklyRotationDate = value;
    }

    @Override
    public Optional<SeasonRecord> activeSeason() {
        return this.state.seasons.values().stream().filter(SeasonRecord::active).findFirst();
    }

    @Override
    public Collection<SeasonRecord> seasons() {
        return this.state.seasons.values();
    }

    @Override
    public void saveSeason(SeasonRecord season) {
        this.state.seasons.put(season.id(), season);
    }

    @Override
    public PlayerProfileRecord findProfile(String key) {
        return this.state.profiles.get(key);
    }

    @Override
    public Collection<PlayerProfileRecord> profiles() {
        return this.state.profiles.values();
    }

    @Override
    public void saveProfile(String key, PlayerProfileRecord profile) {
        this.state.profiles.put(key, profile);
    }

    @Override
    public ClaimRecord findClaim(long claimId) {
        return this.state.claims.get(claimId);
    }

    @Override
    public Collection<ClaimRecord> claims() {
        return this.state.claims.values();
    }

    @Override
    public void saveClaim(ClaimRecord claim) {
        this.state.claims.put(claim.id(), claim);
    }

    @Override
    public MissionRecord findMission(long missionId) {
        return this.state.missions.get(missionId);
    }

    @Override
    public Collection<MissionRecord> missions() {
        return this.state.missions.values();
    }

    @Override
    public void saveMission(MissionRecord mission) {
        this.state.missions.put(mission.id(), mission);
    }

    @Override
    public MissionProgressRecord findMissionProgress(String key) {
        return this.state.missionProgress.get(key);
    }

    @Override
    public Collection<MissionProgressRecord> missionProgress() {
        return this.state.missionProgress.values();
    }

    @Override
    public void saveMissionProgress(String key, MissionProgressRecord progress) {
        this.state.missionProgress.put(key, progress);
    }

    @Override
    public OrderRecord findOrder(long orderId) {
        return this.state.orders.get(orderId);
    }

    @Override
    public Collection<OrderRecord> orders() {
        return this.state.orders.values();
    }

    @Override
    public void saveOrder(OrderRecord order) {
        this.state.orders.put(order.id(), order);
    }

    @Override
    public boolean hasLike(String key) {
        return this.state.likes.containsKey(key);
    }

    @Override
    public Collection<LikeRecord> likes() {
        return this.state.likes.values();
    }

    @Override
    public void saveLike(String key, LikeRecord like) {
        this.state.likes.put(key, like);
    }

    @Override
    public Instant claimNotificationAt(String key) {
        String value = this.state.claimNotificationTimes.get(key);
        return value == null ? null : Instant.parse(value);
    }

    @Override
    public Map<String, Instant> claimNotificationTimes() {
        Map<String, Instant> values = new LinkedHashMap<>();
        for (var entry : this.state.claimNotificationTimes.entrySet()) {
            values.put(entry.getKey(), Instant.parse(entry.getValue()));
        }
        return values;
    }

    @Override
    public void setClaimNotificationAt(String key, Instant instant) {
        this.state.claimNotificationTimes.put(key, instant.toString());
    }

    @Override
    public void flush() {
        this.dataStore.save(this.state);
    }
}
