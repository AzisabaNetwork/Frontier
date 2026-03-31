package net.azisaba.frontier.repository;

import net.azisaba.frontier.storage.DatabaseSettings;
import net.azisaba.frontier.storage.FrontierDataStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class RepositoryFactory {
    private RepositoryFactory() {
    }

    public static FrontierRepositories create(JavaPlugin plugin, DatabaseSettings settings) throws SQLException {
        if (settings.useMySql()) {
            return new MySqlFrontierRepositories(plugin, settings);
        }
        return new YamlFrontierRepositories(new FrontierDataStore(plugin));
    }
}
