package net.azisaba.frontier.domain;

import java.time.Instant;
import java.util.UUID;

public record MissionProgressRecord(
        long missionId,
        UUID playerId,
        long progress,
        boolean completed,
        Instant completedAt
) {
    public MissionProgressRecord increment(long amount, long targetValue) {
        long next = Math.min(targetValue, this.progress + amount);
        boolean done = next >= targetValue;
        return new MissionProgressRecord(this.missionId, this.playerId, next, done, done ? Instant.now() : this.completedAt);
    }
}
