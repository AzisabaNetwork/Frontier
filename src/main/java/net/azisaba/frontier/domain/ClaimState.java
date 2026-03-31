package net.azisaba.frontier.domain;

public enum ClaimState {
    ACTIVE,
    WARNING,
    EXPIRED,
    ABANDONED,
    FROZEN;

    public boolean protects() {
        return this != ABANDONED;
    }
}
