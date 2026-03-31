package net.azisaba.frontier.domain;

import java.time.Instant;
import java.util.UUID;

public record ClaimNotification(
        UUID ownerUuid,
        long claimId,
        ClaimState state,
        Instant expiresAt
) {
}
