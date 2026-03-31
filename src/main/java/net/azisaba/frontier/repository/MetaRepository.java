package net.azisaba.frontier.repository;

import java.time.Instant;
import java.util.Map;

public interface MetaRepository {
    long nextSeasonId();

    long nextClaimId();

    long nextMissionId();

    long nextOrderId();

    long currentSequence(String key);

    void setCurrentSequence(String key, long value);

    String lastDailyRotationDate();

    void setLastDailyRotationDate(String value);

    String lastWeeklyRotationDate();

    void setLastWeeklyRotationDate(String value);

    Instant claimNotificationAt(String key);

    Map<String, Instant> claimNotificationTimes();

    void setClaimNotificationAt(String key, Instant instant);

    void flush();
}
