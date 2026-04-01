package net.azisaba.frontier.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.azisaba.frontier.FrontierPlugin;
import net.azisaba.frontier.domain.ClaimRecord;
import net.azisaba.frontier.domain.MissionRecord;
import net.azisaba.frontier.domain.PlayerProfileRecord;
import net.azisaba.frontier.domain.SeasonRecord;
import net.azisaba.frontier.service.FrontierService;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public final class FrontierPlaceholderExpansion extends PlaceholderExpansion {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private final FrontierPlugin plugin;
    private final FrontierService service;

    public FrontierPlaceholderExpansion(FrontierPlugin plugin, FrontierService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "frontier";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Azisaba Network ft. Codex";
    }

    @Override
    public @NotNull String getVersion() {
        return this.plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        SeasonRecord season = this.service.getActiveSeason().orElse(null);
        if (season == null) {
            return "";
        }
        if (params.equalsIgnoreCase("season_name")) {
            return season.displayName();
        }
        if (params.equalsIgnoreCase("season_phase")) {
            return season.phase().name();
        }
        if (player == null || player.getUniqueId() == null) {
            return "";
        }
        PlayerProfileRecord profile = this.service.getProfile(player.getUniqueId());
        if (params.equalsIgnoreCase("player_coins")) {
            return Long.toString(this.service.coinBalance(player.getUniqueId()));
        }
        if (params.equalsIgnoreCase("player_sp")) {
            return Long.toString(profile.seasonPoints());
        }
        if (params.equalsIgnoreCase("claim_count")) {
            return Integer.toString(this.service.getClaims(player.getUniqueId()).size());
        }
        if (params.equalsIgnoreCase("claim_limit")) {
            return Integer.toString(this.service.claimLimit(player.getUniqueId()));
        }
        if (params.equalsIgnoreCase("claim_next_expiry")) {
            return this.service.getClaims(player.getUniqueId()).stream()
                    .map(ClaimRecord::expiresAt)
                    .min(Comparator.naturalOrder())
                    .map(DATE_TIME::format)
                    .orElse("-");
        }
        if (params.equalsIgnoreCase("weekly_1_title")) {
            return firstWeeklyMission().map(MissionRecord::title).orElse("");
        }
        if (params.equalsIgnoreCase("weekly_1_progress")) {
            return firstWeeklyMission()
                    .map(mission -> this.service.getMissionProgress(player.getUniqueId(), mission.id()).progress() + "/" + mission.targetValue())
                    .orElse("");
        }
        return null;
    }

    private java.util.Optional<MissionRecord> firstWeeklyMission() {
        List<MissionRecord> missions = this.service.getActiveMissions().stream()
                .filter(mission -> mission.scope().name().equals("WEEKLY"))
                .toList();
        return missions.stream().findFirst();
    }
}
