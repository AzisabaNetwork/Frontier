package net.azisaba.frontier.domain;

import java.time.Instant;

public record SeasonRecord(
        long id,
        String key,
        String displayName,
        String worldName,
        SeasonPhase phase,
        Instant createdAt,
        Instant startAt,
        Instant endAt,
        Instant archiveAt,
        boolean active
) {
    public SeasonRecord withPhase(SeasonPhase newPhase) {
        Instant now = Instant.now();
        return new SeasonRecord(
                this.id,
                this.key,
                this.displayName,
                this.worldName,
                newPhase,
                this.createdAt,
                newPhase == SeasonPhase.ACTIVE && this.startAt == null ? now : this.startAt,
                newPhase == SeasonPhase.FINALE ? now : this.endAt,
                newPhase == SeasonPhase.ARCHIVED ? now : this.archiveAt,
                newPhase != SeasonPhase.ARCHIVED
        );
    }
}
