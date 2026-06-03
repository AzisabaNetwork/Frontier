package net.azisaba.frontier.message;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.azisaba.frontier.util.UserMessageException;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class MessageService {
    private final JavaPlugin plugin;
    private YamlConfiguration messages;
    private YamlConfiguration defaults;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        this.plugin.saveResource("messages.yml", false);
        File file = new File(this.plugin.getDataFolder(), "messages.yml");
        this.messages = YamlConfiguration.loadConfiguration(file);
        if (this.plugin.getResource("messages.yml") != null) {
            this.defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(this.plugin.getResource("messages.yml"), StandardCharsets.UTF_8));
        } else {
            this.defaults = new YamlConfiguration();
        }
    }

    public void send(CommandSender sender, String key) {
        this.send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(this.get(key, placeholders));
    }

    public void send(CommandSender sender, UserMessageException exception) {
        this.send(sender, exception.messageKey(), exception.placeholders());
    }

    public String get(String key) {
        return this.get(key, Map.of());
    }

    public String get(String key, Map<String, String> placeholders) {
        String value = this.messages.getString(key);
        if (value == null) {
            value = this.defaults.getString(key, "&cMissing message: " + key);
        }
        return this.format(value, placeholders);
    }

    public String format(String value) {
        return this.format(value, Map.of());
    }

    public String format(String value, Map<String, String> placeholders) {
        java.util.Map<String, String> resolved = new java.util.HashMap<>(placeholders);
        resolved.putIfAbsent("prefix", this.messages.getString("prefix", this.defaults.getString("prefix", "")));
        for (var entry : resolved.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
