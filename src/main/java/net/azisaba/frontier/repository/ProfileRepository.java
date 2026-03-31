package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.PlayerProfileRecord;

import java.util.Collection;

public interface ProfileRepository {
    PlayerProfileRecord findProfile(String key);

    Collection<PlayerProfileRecord> profiles();

    void saveProfile(String key, PlayerProfileRecord profile);
}
