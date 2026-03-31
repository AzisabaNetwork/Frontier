package net.azisaba.frontier.domain;

public enum SeasonPhase {
    PRESEASON,
    OPENING,
    ACTIVE,
    FINALE,
    ARCHIVED;

    public boolean canTransitionTo(SeasonPhase next) {
        return switch (this) {
            case PRESEASON -> next == OPENING;
            case OPENING -> next == ACTIVE;
            case ACTIVE -> next == FINALE;
            case FINALE -> next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
