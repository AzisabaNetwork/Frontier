package net.azisaba.frontier.integration.economy;

import net.azisaba.frontier.service.FrontierService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyProvider {
    private EconomyProvider() {
    }

    public static FrontierEconomy create(JavaPlugin plugin, FrontierService service) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return new LocalEconomy(service);
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            plugin.getLogger().warning("Vault was found, but no economy provider is registered. Falling back to local economy.");
            return new LocalEconomy(service);
        }
        return new VaultEconomyBridge(service, registration.getProvider());
    }
}
