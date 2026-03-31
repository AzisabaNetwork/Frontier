package net.azisaba.frontier.audit;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditService {
    private final JavaPlugin plugin;
    private final File file;

    public AuditService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "audit.log");
    }

    public synchronized void log(String type, String actor, Map<String, ?> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", Instant.now().toString());
        payload.put("type", type);
        payload.put("actor", actor);
        payload.put("details", details);
        try {
            Files.writeString(
                    this.file.toPath(),
                    toJson(payload) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            this.plugin.getLogger().warning("Failed to write audit log: " + e.getMessage());
        }
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':').append(toJson(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
