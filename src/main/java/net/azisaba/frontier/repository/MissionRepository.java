package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.MissionProgressRecord;
import net.azisaba.frontier.domain.MissionRecord;

import java.util.Collection;

public interface MissionRepository {
    MissionRecord findMission(long missionId);

    Collection<MissionRecord> missions();

    void saveMission(MissionRecord mission);

    MissionProgressRecord findMissionProgress(String key);

    Collection<MissionProgressRecord> missionProgress();

    void saveMissionProgress(String key, MissionProgressRecord progress);
}
