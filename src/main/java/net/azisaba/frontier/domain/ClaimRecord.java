package net.azisaba.frontier.domain;

import java.time.Instant;
import java.util.UUID;

public record ClaimRecord(
        long id,
        long seasonId,
        String world,
        UUID ownerUuid,
        String ownerName,
        String regionId,
        int chunkX,
        int chunkZ,
        ClaimState state,
        Instant createdAt,
        Instant expiresAt,
        Instant warningAt,
        Instant abandonedAt
) {
    public ClaimRecord withState(ClaimState newState) {
        Instant now = Instant.now();
        return new ClaimRecord(
                this.id,
                this.seasonId,
                this.world,
                this.ownerUuid,
                this.ownerName,
                this.regionId,
                this.chunkX,
                this.chunkZ,
                newState,
                this.createdAt,
                this.expiresAt,
                newState == ClaimState.WARNING ? now : this.warningAt,
                newState == ClaimState.ABANDONED ? now : this.abandonedAt
        );
    }

    public ClaimRecord renew(Instant newExpiry) {
        return new ClaimRecord(
                this.id,
                this.seasonId,
                this.world,
                this.ownerUuid,
                this.ownerName,
                this.regionId,
                this.chunkX,
                this.chunkZ,
                ClaimState.ACTIVE,
                this.createdAt,
                newExpiry,
                null,
                this.abandonedAt
        );
    }
}
