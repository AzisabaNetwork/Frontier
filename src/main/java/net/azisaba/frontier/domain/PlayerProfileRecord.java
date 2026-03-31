package net.azisaba.frontier.domain;

import java.time.Instant;
import java.util.UUID;

public record PlayerProfileRecord(
        UUID playerId,
        String lastKnownName,
        long seasonId,
        long coins,
        long seasonPoints,
        int totalMissionCompleted,
        int totalLikesReceived,
        boolean starterClaimed,
        Instant lastSupportedAt,
        Instant lastSupportReceivedAt,
        Instant joinAt,
        Instant lastActiveAt
) {
    public PlayerProfileRecord withBalances(long newCoins, long newSeasonPoints) {
        return new PlayerProfileRecord(
                this.playerId,
                this.lastKnownName,
                this.seasonId,
                newCoins,
                newSeasonPoints,
                this.totalMissionCompleted,
                this.totalLikesReceived,
                this.starterClaimed,
                this.lastSupportedAt,
                this.lastSupportReceivedAt,
                this.joinAt,
                Instant.now()
        );
    }

    public PlayerProfileRecord withMissionCompleted() {
        return new PlayerProfileRecord(
                this.playerId,
                this.lastKnownName,
                this.seasonId,
                this.coins,
                this.seasonPoints,
                this.totalMissionCompleted + 1,
                this.totalLikesReceived,
                this.starterClaimed,
                this.lastSupportedAt,
                this.lastSupportReceivedAt,
                this.joinAt,
                Instant.now()
        );
    }

    public PlayerProfileRecord withLikeReceived() {
        return new PlayerProfileRecord(
                this.playerId,
                this.lastKnownName,
                this.seasonId,
                this.coins,
                this.seasonPoints,
                this.totalMissionCompleted,
                this.totalLikesReceived + 1,
                this.starterClaimed,
                this.lastSupportedAt,
                this.lastSupportReceivedAt,
                this.joinAt,
                Instant.now()
        );
    }

    public PlayerProfileRecord withStarterClaimed() {
        return new PlayerProfileRecord(
                this.playerId,
                this.lastKnownName,
                this.seasonId,
                this.coins,
                this.seasonPoints,
                this.totalMissionCompleted,
                this.totalLikesReceived,
                true,
                this.lastSupportedAt,
                this.lastSupportReceivedAt,
                this.joinAt,
                Instant.now()
        );
    }

    public PlayerProfileRecord withSupportedAt(Instant supportedAt) {
        return new PlayerProfileRecord(
                this.playerId,
                this.lastKnownName,
                this.seasonId,
                this.coins,
                this.seasonPoints,
                this.totalMissionCompleted,
                this.totalLikesReceived,
                this.starterClaimed,
                supportedAt,
                this.lastSupportReceivedAt,
                this.joinAt,
                Instant.now()
        );
    }

    public PlayerProfileRecord withSupportReceivedAt(Instant supportedAt) {
        return new PlayerProfileRecord(
                this.playerId,
                this.lastKnownName,
                this.seasonId,
                this.coins,
                this.seasonPoints,
                this.totalMissionCompleted,
                this.totalLikesReceived,
                this.starterClaimed,
                this.lastSupportedAt,
                supportedAt,
                this.joinAt,
                Instant.now()
        );
    }

    public PlayerProfileRecord touch(String playerName) {
        return new PlayerProfileRecord(
                this.playerId,
                playerName,
                this.seasonId,
                this.coins,
                this.seasonPoints,
                this.totalMissionCompleted,
                this.totalLikesReceived,
                this.starterClaimed,
                this.lastSupportedAt,
                this.lastSupportReceivedAt,
                this.joinAt,
                Instant.now()
        );
    }
}
