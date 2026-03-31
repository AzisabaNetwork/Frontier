package net.azisaba.frontier.listener;

import net.azisaba.frontier.gui.FrontierMenuService;
import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.util.UserMessageException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class FrontierMenuListener implements Listener {
    private final FrontierMenuService menus;
    private final MessageService messages;

    public FrontierMenuListener(FrontierMenuService menus, MessageService messages) {
        this.menus = menus;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = event.getView().getTitle();
        if (!this.menus.isMenuTitle(title)) {
            return;
        }
        event.setCancelled(true);
        try {
            this.menus.handleMenuClick(player, title, event.getRawSlot(), event.getCurrentItem(), event.getClick());
        } catch (UserMessageException e) {
            this.messages.send(player, e);
        }
    }
}
