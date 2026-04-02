package net.azisaba.frontier.tab;

import net.azisaba.frontier.service.FrontierService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public final class FrontierTabListService {
    private static final String OBJECTIVE_NAME = "frontier_sp";

    private final JavaPlugin plugin;
    private final FrontierService service;

    public FrontierTabListService(JavaPlugin plugin, FrontierService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("tab_list_sp.enabled", false);
    }

    public void refreshAll() {
        if (!this.isEnabled()) {
            this.clear();
            return;
        }
        Objective objective = this.ensureObjective();
        if (objective == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            objective.getScore(player.getName()).setScore(clampScore(this.service.getProfile(player.getUniqueId()).seasonPoints()));
        }
    }

    public void refreshPlayer(Player player) {
        if (!this.isEnabled()) {
            this.clear();
            return;
        }
        Objective objective = this.ensureObjective();
        if (objective == null) {
            return;
        }
        objective.getScore(player.getName()).setScore(clampScore(this.service.getProfile(player.getUniqueId()).seasonPoints()));
    }

    public void removePlayer(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        if (scoreboard != null) {
            scoreboard.resetScores(player.getName());
        }
    }

    public void clear() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        if (scoreboard == null) {
            return;
        }
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective != null) {
            objective.unregister();
        }
    }

    private Objective ensureObjective() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        if (scoreboard == null) {
            return null;
        }
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, this.plugin.getConfig().getString("tab_list_sp.title", "SP"));
        }
        objective.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(this.plugin.getConfig().getString("tab_list_sp.title", "&bSP")));
        objective.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        return objective;
    }

    private static int clampScore(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
