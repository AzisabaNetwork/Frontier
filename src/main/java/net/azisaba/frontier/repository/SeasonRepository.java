package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.SeasonRecord;

import java.util.Collection;
import java.util.Optional;

public interface SeasonRepository {
    Optional<SeasonRecord> activeSeason();

    Collection<SeasonRecord> seasons();

    void saveSeason(SeasonRecord season);
}
