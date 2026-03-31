package net.azisaba.frontier.service;

import net.azisaba.frontier.domain.ClaimRecord;
import net.azisaba.frontier.domain.ClaimState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontierServiceTest {
    @Test
    void claimStateAdvancesAcrossConfiguredThresholds() {
        Instant abandonAt = Instant.parse("2026-04-14T00:00:00Z");
        ClaimRecord claim = new ClaimRecord(1L, 1L, "world", UUID.randomUUID(), "tester", "region", 0, 0, ClaimState.ACTIVE, Instant.parse("2026-03-31T00:00:00Z"), abandonAt, null, null);

        assertEquals(ClaimState.ACTIVE, FrontierService.nextClaimState(claim, Instant.parse("2026-04-06T23:00:00Z"), 7, 10, 14));
        assertEquals(ClaimState.WARNING, FrontierService.nextClaimState(claim, Instant.parse("2026-04-07T00:00:00Z"), 7, 10, 14));
        assertEquals(ClaimState.EXPIRED, FrontierService.nextClaimState(claim, Instant.parse("2026-04-10T00:00:00Z"), 7, 10, 14));
        assertEquals(ClaimState.ABANDONED, FrontierService.nextClaimState(claim, Instant.parse("2026-04-14T00:00:00Z"), 7, 10, 14));
    }
}
