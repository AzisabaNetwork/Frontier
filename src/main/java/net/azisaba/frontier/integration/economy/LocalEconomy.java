package net.azisaba.frontier.integration.economy;

import net.azisaba.frontier.domain.PlayerProfileRecord;
import net.azisaba.frontier.service.FrontierService;

import java.util.UUID;

public final class LocalEconomy implements FrontierEconomy {
    private final FrontierService service;

    public LocalEconomy(FrontierService service) {
        this.service = service;
    }

    @Override
    public long balance(UUID playerId) {
        return this.service.getProfile(playerId).coins();
    }

    @Override
    public boolean has(UUID playerId, long amount) {
        return this.balance(playerId) >= amount;
    }

    @Override
    public void deposit(UUID playerId, long amount) {
        PlayerProfileRecord profile = this.service.getProfile(playerId);
        this.service.setCoins(playerId, profile.coins() + amount);
    }

    @Override
    public void withdraw(UUID playerId, long amount) {
        PlayerProfileRecord profile = this.service.getProfile(playerId);
        this.service.setCoins(playerId, profile.coins() - amount);
    }

    @Override
    public String mode() {
        return "local";
    }
}
