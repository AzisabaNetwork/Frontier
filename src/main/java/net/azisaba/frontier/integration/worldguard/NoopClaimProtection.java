package net.azisaba.frontier.integration.worldguard;

import net.azisaba.frontier.domain.ClaimRecord;

import java.util.List;
import java.util.UUID;

public final class NoopClaimProtection implements ClaimProtection {
    @Override
    public void createClaimRegion(ClaimRecord claim) {
    }

    @Override
    public void releaseClaimRegion(ClaimRecord claim) {
    }

    @Override
    public void freezeClaimRegion(ClaimRecord claim) {
    }

    @Override
    public boolean hasConflictingRegion(String worldName, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean isOwner(ClaimRecord claim, UUID playerId) {
        return claim.ownerUuid().equals(playerId);
    }

    @Override
    public boolean canEdit(ClaimRecord claim, UUID playerId) {
        return this.isOwner(claim, playerId);
    }

    @Override
    public boolean supportsSharing() {
        return false;
    }

    @Override
    public List<String> owners(ClaimRecord claim) {
        return List.of(claim.ownerName());
    }

    @Override
    public List<String> members(ClaimRecord claim) {
        return List.of();
    }

    @Override
    public void addOwner(ClaimRecord claim, UUID playerId, String lastKnownName) {
    }

    @Override
    public void removeOwner(ClaimRecord claim, UUID playerId) {
    }

    @Override
    public void addMember(ClaimRecord claim, UUID playerId, String lastKnownName) {
    }

    @Override
    public void removeMember(ClaimRecord claim, UUID playerId) {
    }

    @Override
    public String mode() {
        return "internal";
    }
}
