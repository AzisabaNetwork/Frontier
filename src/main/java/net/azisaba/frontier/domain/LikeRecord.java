package net.azisaba.frontier.domain;

import java.time.LocalDate;
import java.util.UUID;

public record LikeRecord(
        long seasonId,
        long claimId,
        UUID targetOwnerUuid,
        UUID voterUuid,
        LocalDate likedOn
) {
}
