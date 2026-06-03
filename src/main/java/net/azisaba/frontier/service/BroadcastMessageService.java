package net.azisaba.frontier.service;

import net.azisaba.frontier.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public final class BroadcastMessageService {
    private final JavaPlugin plugin;
    private final MessageService messages;
    private BukkitTask task;
    private int nextMessageIndex;

    public BroadcastMessageService(JavaPlugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void reload() {
        this.stop();
        this.nextMessageIndex = 0;

        if (!this.plugin.getConfig().getBoolean("broadcast.enabled", false)) {
            return;
        }

        List<String> configuredMessages = this.configuredMessages();
        if (configuredMessages.isEmpty()) {
            this.plugin.getLogger().warning("broadcast.enabled is true, but broadcast.messages is empty.");
            return;
        }

        long intervalMinutes = Math.max(1L, this.plugin.getConfig().getLong("broadcast.interval_minutes", 30L));
        long intervalTicks = intervalMinutes * 60L * 20L;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::broadcastNext, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void broadcastNext() {
        List<String> configuredMessages = this.configuredMessages();
        if (configuredMessages.isEmpty()) {
            return;
        }
        if (this.nextMessageIndex >= configuredMessages.size()) {
            this.nextMessageIndex = 0;
        }

        String message = this.messages.format(configuredMessages.get(this.nextMessageIndex));
        this.nextMessageIndex = (this.nextMessageIndex + 1) % configuredMessages.size();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    private List<String> configuredMessages() {
        return this.plugin.getConfig().getStringList("broadcast.messages").stream()
                .filter(message -> !message.isBlank())
                .toList();
    }
}
