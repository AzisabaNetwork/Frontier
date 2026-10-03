package net.azisaba.frontier.listener;

import net.azisaba.frontier.gui.FrontierMenuService;
import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.util.UserMessageException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        var inventory = event.getView().getTopInventory();
        int slot = event.getRawSlot();
        var clicked = event.getCurrentItem() == null ? null : event.getCurrentItem().clone();
        var click = event.getClick();
        Bukkit.getScheduler().runTask(JavaPlugin.getProvidingPlugin(FrontierMenuListener.class), () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) {
                return;
            }
            try {
                this.menus.handleMenuClick(player, title, slot, clicked, click);
            } catch (UserMessageException e) {
                this.messages.send(player, e);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (this.menus.isMenuTitle(event.getView().getTitle())
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }
}
