package net.azisaba.frontier.integration.economy;

import net.azisaba.frontier.domain.PlayerProfileRecord;
import net.azisaba.frontier.service.FrontierService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

public final class VaultEconomyBridge implements FrontierEconomy {
    private final FrontierService service;
    private final Economy economy;

    public VaultEconomyBridge(FrontierService service, Economy economy) {
        this.service = service;
        this.economy = economy;
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
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        if (player.hasPlayedBefore() || player.isOnline()) {
            this.economy.depositPlayer(player, amount);
        }
    }

    @Override
    public void withdraw(UUID playerId, long amount) {
        PlayerProfileRecord profile = this.service.getProfile(playerId);
        this.service.setCoins(playerId, profile.coins() - amount);
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        if (player.hasPlayedBefore() || player.isOnline()) {
            this.economy.withdrawPlayer(player, amount);
        }
    }

    @Override
    public String mode() {
        return "vault";
    }
}
