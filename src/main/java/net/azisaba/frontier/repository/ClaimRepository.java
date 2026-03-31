package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.ClaimRecord;

import java.util.Collection;

public interface ClaimRepository {
    ClaimRecord findClaim(long claimId);

    Collection<ClaimRecord> claims();

    void saveClaim(ClaimRecord claim);
}
