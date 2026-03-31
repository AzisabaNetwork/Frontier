package net.azisaba.frontier.integration.worldguard;

import org.bukkit.Bukkit;

public final class ClaimProtectionProvider {
    private ClaimProtectionProvider() {
    }

    public static ClaimProtection create() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null
                ? new WorldGuardClaimProtection()
                : new NoopClaimProtection();
    }
}
