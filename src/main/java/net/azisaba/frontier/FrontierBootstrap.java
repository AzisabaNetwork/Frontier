package net.azisaba.frontier;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

@SuppressWarnings("UnstableApiUsage")
public class FrontierBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(@NonNull BootstrapContext context) {
    }

    @Override
    public @NonNull JavaPlugin createPlugin(@NonNull PluginProviderContext context) {
        return new FrontierPlugin();
    }
}
