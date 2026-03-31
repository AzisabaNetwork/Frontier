package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.LikeRecord;

import java.util.Collection;

public interface LikeRepository {
    boolean hasLike(String key);

    Collection<LikeRecord> likes();

    void saveLike(String key, LikeRecord like);
}
