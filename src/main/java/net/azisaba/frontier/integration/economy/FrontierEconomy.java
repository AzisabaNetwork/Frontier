package net.azisaba.frontier.integration.economy;

import java.util.UUID;

public interface FrontierEconomy {
    long balance(UUID playerId);

    boolean has(UUID playerId, long amount);

    void deposit(UUID playerId, long amount);

    void withdraw(UUID playerId, long amount);

    String mode();
}
