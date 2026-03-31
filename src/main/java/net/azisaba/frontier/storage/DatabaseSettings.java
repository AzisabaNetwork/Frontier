package net.azisaba.frontier.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public record DatabaseSettings(
        String type,
        String host,
        int port,
        String database,
        String username,
        String password,
        int maximumPoolSize
) {
    public static DatabaseSettings load(JavaPlugin plugin) {
        plugin.saveResource("database.yml", false);
        File file = new File(plugin.getDataFolder(), "database.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return new DatabaseSettings(
                yaml.getString("storage.type", "yaml"),
                yaml.getString("mysql.host", "127.0.0.1"),
                yaml.getInt("mysql.port", 3306),
                yaml.getString("mysql.database", "frontier"),
                yaml.getString("mysql.username", "frontier"),
                yaml.getString("mysql.password", ""),
                yaml.getInt("mysql.pool.maximum_pool_size", 10)
        );
    }

    public boolean useMySql() {
        return "mysql".equalsIgnoreCase(this.type);
    }

    public DatabaseSettings asMySql() {
        return new DatabaseSettings("mysql", this.host, this.port, this.database, this.username, this.password, this.maximumPoolSize);
    }

    public String jdbcUrl() {
        return "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8".formatted(
                this.host,
                this.port,
                this.database
        );
    }
}
