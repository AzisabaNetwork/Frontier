package net.azisaba.frontier.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FrontierState {
    public int schemaVersion = 1;
    public long seasonSequence = 0L;
    public long claimSequence = 0L;
    public long missionSequence = 0L;
    public long orderSequence = 0L;
    public String lastDailyRotationDate;
    public String lastWeeklyRotationDate;
    public final Map<Long, SeasonRecord> seasons = new LinkedHashMap<>();
    public final Map<String, PlayerProfileRecord> profiles = new LinkedHashMap<>();
    public final Map<Long, ClaimRecord> claims = new LinkedHashMap<>();
    public final Map<Long, MissionRecord> missions = new LinkedHashMap<>();
    public final Map<String, MissionProgressRecord> missionProgress = new LinkedHashMap<>();
    public final Map<Long, OrderRecord> orders = new LinkedHashMap<>();
    public final Map<String, LikeRecord> likes = new LinkedHashMap<>();
    public final Map<String, String> claimNotificationTimes = new LinkedHashMap<>();
}
