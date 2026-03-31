package net.azisaba.frontier.util;

import java.util.Map;

public final class UserMessageException extends RuntimeException {
    private final String messageKey;
    private final Map<String, String> placeholders;

    public UserMessageException(String messageKey) {
        this(messageKey, Map.of());
    }

    public UserMessageException(String messageKey, Map<String, String> placeholders) {
        super(messageKey);
        this.messageKey = messageKey;
        this.placeholders = placeholders;
    }

    public String messageKey() {
        return this.messageKey;
    }

    public Map<String, String> placeholders() {
        return this.placeholders;
    }
}
