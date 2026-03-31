package net.azisaba.frontier.listener;

import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.service.FrontierService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

public final class FrontierListener implements Listener {
    private final FrontierService service;
    private final MessageService messages;

    public FrontierListener(FrontierService service, MessageService messages) {
        this.service = service;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.service.getActiveSeason().isEmpty()) {
            return;
        }
        this.service.touchProfile(event.getPlayer());
        event.joinMessage(Component.text("→ ", NamedTextColor.GREEN)
                .append(Component.text(event.getPlayer().getName(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text(" さんがログインしました", NamedTextColor.GRAY)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!this.service.canEdit(player, event.getBlock().getChunk())) {
            event.setCancelled(true);
            this.messages.send(player, "claim.protected", java.util.Map.of("prefix", this.messages.get("prefix")));
            return;
        }
        this.service.recordGatherProgress(player, event.getBlock().getType(), 1L)
                .forEach(title -> this.notifyMissionCompleted(player, title));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!this.service.canEdit(event.getPlayer(), event.getBlock().getChunk())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "claim.protected", java.util.Map.of("prefix", this.messages.get("prefix")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (!this.service.canEdit(event.getPlayer(), event.getClickedBlock().getChunk())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "claim.protected", java.util.Map.of("prefix", this.messages.get("prefix")));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getRecipe().getResult();
        long craftedAmount = craftedAmount(event, player, result);
        this.service.recordCraftProgress(player, result.getType(), craftedAmount)
                .forEach(title -> this.notifyMissionCompleted(player, title));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof org.bukkit.entity.Item item)) {
            return;
        }
        this.service.recordFishProgress(event.getPlayer(), item.getItemStack().getType(), item.getItemStack().getAmount())
                .forEach(title -> this.notifyMissionCompleted(event.getPlayer(), title));
    }

    private static long craftedAmount(CraftItemEvent event, Player player, ItemStack result) {
        long singleCraftAmount = Math.max(1, result.getAmount());
        if (!event.isShiftClick()) {
            return singleCraftAmount;
        }
        if (!(event.getInventory() instanceof CraftingInventory craftingInventory)) {
            return singleCraftAmount;
        }
        int craftsByIngredients = Integer.MAX_VALUE;
        for (ItemStack ingredient : craftingInventory.getMatrix()) {
            if (ingredient == null || ingredient.getType().isAir()) {
                continue;
            }
            craftsByIngredients = Math.min(craftsByIngredients, ingredient.getAmount());
        }
        if (craftsByIngredients == Integer.MAX_VALUE) {
            craftsByIngredients = 1;
        }
        long inventoryCapacity = availableSpaceFor(player, result);
        long craftsByInventory = inventoryCapacity / singleCraftAmount;
        if (craftsByInventory <= 0L) {
            return 0L;
        }
        return singleCraftAmount * Math.min(craftsByIngredients, craftsByInventory);
    }

    private static long availableSpaceFor(Player player, ItemStack result) {
        long space = 0L;
        int maxStack = result.getMaxStackSize();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += maxStack;
                continue;
            }
            if (stack.isSimilar(result)) {
                space += maxStack - stack.getAmount();
            }
        }
        return space;
    }

    private void notifyMissionCompleted(Player player, String title) {
        this.messages.send(player, "mission.completed", java.util.Map.of("prefix", this.messages.get("prefix"), "title", title));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.8f, 1.15f);
    }
}
