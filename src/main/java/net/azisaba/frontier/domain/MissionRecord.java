package net.azisaba.frontier.domain;

public record MissionRecord(
        long id,
        long seasonId,
        MissionScope scope,
        String title,
        String description,
        String targetKey,
        long targetValue,
        long rewardCoins,
        long rewardPoints,
        boolean active
) {
}
