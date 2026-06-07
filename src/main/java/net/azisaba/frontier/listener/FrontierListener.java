package net.azisaba.frontier.listener;

import net.azisaba.frontier.domain.TutorialStatus;
import net.azisaba.frontier.message.MessageService;
import net.azisaba.frontier.service.FrontierService;
import net.azisaba.frontier.tab.FrontierTabListService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

public final class FrontierListener implements Listener {
    private final FrontierService service;
    private final MessageService messages;
    private final FrontierTabListService tabListService;

    public FrontierListener(FrontierService service, MessageService messages, FrontierTabListService tabListService) {
        this.service = service;
        this.messages = messages;
        this.tabListService = tabListService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.service.getActiveSeason().isEmpty()) {
            return;
        }
        this.service.touchProfile(event.getPlayer());
        this.tabListService.refreshPlayer(event.getPlayer());
        this.sendClaimStatusActionBar(event.getPlayer());
        this.showTutorialPrompt(event.getPlayer());
        event.joinMessage(Component.text("→ ", NamedTextColor.GREEN)
                .append(Component.text(event.getPlayer().getName(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text(" さんがログインしました", NamedTextColor.GRAY)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.tabListService.removePlayer(event.getPlayer());
        event.quitMessage(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || this.service.getActiveSeason().isEmpty()) {
            return;
        }
        if (event.getFrom().getChunk().getX() == event.getTo().getChunk().getX()
                && event.getFrom().getChunk().getZ() == event.getTo().getChunk().getZ()
                && event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        this.sendClaimStatusActionBar(event.getPlayer(), event.getTo().getChunk());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!this.service.isGameplayEnabled() && !this.service.hasPhaseOverride(player)) {
            event.setCancelled(true);
            this.messages.send(player, "error.phase_action_blocked", java.util.Map.of("prefix", this.messages.get("prefix"), "phase", displayPhase(this.service.currentSeasonPhase()), "action", "通常プレイ"));
            return;
        }
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
        if (!this.service.isGameplayEnabled() && !this.service.hasPhaseOverride(event.getPlayer())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "error.phase_action_blocked", java.util.Map.of("prefix", this.messages.get("prefix"), "phase", displayPhase(this.service.currentSeasonPhase()), "action", "通常プレイ"));
            return;
        }
        if (!this.service.canEdit(event.getPlayer(), event.getBlock().getChunk())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "claim.protected", java.util.Map.of("prefix", this.messages.get("prefix")));
            return;
        }
        if (event.getBlock().getType() == Material.FIRE && event.getBlock().getLocation().getNearbyPlayers(4).size() > 1) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "error.lava", java.util.Map.of("prefix", this.messages.get("prefix")));
        }
    }

    @EventHandler
    public void onPlaceLava(PlayerBucketEmptyEvent e) {
        if (e.getBucket() == Material.LAVA_BUCKET && e.getBlock().getLocation().getNearbyPlayers(4).size() > 1) {
            e.setCancelled(true);
            this.messages.send(e.getPlayer(), "error.lava", java.util.Map.of("prefix", this.messages.get("prefix")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (!this.service.isGameplayEnabled() && !this.service.hasPhaseOverride(event.getPlayer())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "error.phase_action_blocked", java.util.Map.of("prefix", this.messages.get("prefix"), "phase", displayPhase(this.service.currentSeasonPhase()), "action", "通常プレイ"));
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
        if (!this.service.isGameplayEnabled() && !this.service.hasPhaseOverride(player)) {
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
        if (!this.service.isGameplayEnabled() && !this.service.hasPhaseOverride(event.getPlayer())) {
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

    private void sendClaimStatusActionBar(Player player) {
        this.sendClaimStatusActionBar(player, player.getLocation().getChunk());
    }

    private void sendClaimStatusActionBar(Player player, Chunk chunk) {
        if (!this.service.isClaimsWorldEnabled(chunk.getWorld())) {
            player.sendActionBar(Component.empty());
            return;
        }
        this.service.getClaimAt(chunk)
                .ifPresentOrElse(
                        claim -> {
                            String key;
                            if (this.service.isClaimOwner(player, claim)) {
                                key = "claim.location_own_actionbar";
                            } else if (this.service.isSharedClaimMember(player, claim)) {
                                key = "claim.location_shared_actionbar";
                            } else {
                                key = "claim.location_other_actionbar";
                            }
                            player.sendActionBar(this.messages.get(key, java.util.Map.of("owner", claim.ownerName())));
                        },
                        () -> player.sendActionBar(this.messages.get("claim.location_empty_actionbar"))
                );
    }

    private static String displayPhase(net.azisaba.frontier.domain.SeasonPhase phase) {
        return switch (phase) {
            case PRESEASON -> "プレシーズン";
            case OPENING -> "オープニング";
            case ACTIVE -> "進行中";
            case FINALE -> "フィナーレ";
            case ARCHIVED -> "アーカイブ";
        };
    }

    private void showTutorialPrompt(Player player) {
        if (!this.service.tutorialAutoPromptOnJoin()) {
            return;
        }
        TutorialStatus status = this.service.tutorialStatus(player);
        if (!status.enabled() || status.completed() || status.currentStep() == null) {
            return;
        }
        this.messages.send(player, "tutorial.prompt", java.util.Map.of(
                "prefix", this.messages.get("prefix"),
                "title", status.currentStep().title()
        ));
        this.messages.send(player, "tutorial.step_objective", java.util.Map.of("objective", status.currentStep().objective()));
    }
}
