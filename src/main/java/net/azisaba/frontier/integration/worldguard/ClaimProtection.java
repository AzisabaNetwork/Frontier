package net.azisaba.frontier.integration.worldguard;

import net.azisaba.frontier.domain.ClaimRecord;

import java.util.List;
import java.util.UUID;

public interface ClaimProtection {
    void createClaimRegion(ClaimRecord claim);

    void releaseClaimRegion(ClaimRecord claim);

    void freezeClaimRegion(ClaimRecord claim);

    boolean hasConflictingRegion(String worldName, int chunkX, int chunkZ);

    boolean isOwner(ClaimRecord claim, UUID playerId);

    boolean canEdit(ClaimRecord claim, UUID playerId);

    boolean supportsSharing();

    List<String> owners(ClaimRecord claim);

    List<String> members(ClaimRecord claim);

    void addOwner(ClaimRecord claim, UUID playerId, String lastKnownName);

    void removeOwner(ClaimRecord claim, UUID playerId);

    void addMember(ClaimRecord claim, UUID playerId, String lastKnownName);

    void removeMember(ClaimRecord claim, UUID playerId);

    String mode();
}
