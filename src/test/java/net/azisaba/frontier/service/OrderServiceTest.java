package net.azisaba.frontier.service;

import net.azisaba.frontier.domain.*;
import net.azisaba.frontier.integration.economy.FrontierEconomy;
import net.azisaba.frontier.repository.FrontierRepositories;
import net.azisaba.frontier.util.UserMessageException;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    @TempDir Path directory;
    private final UUID playerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final AtomicReference<OrderRecord> stored = new AtomicReference<>();
    private FrontierRepositories repositories;
    private FrontierEconomy economy;
    private Player player;
    private FrontierService service;
    private Material stoneMaterial;
    private MockedStatic<Material> materials;

    @BeforeEach
    void setup() {
        // Paper's material properties require live registries; isolate those API calls.
        stoneMaterial = mock(Material.class);
        when(stoneMaterial.getMaxStackSize()).thenReturn(64);
        materials = mockStatic(Material.class);
        materials.when(() -> Material.matchMaterial("stone", false)).thenReturn(stoneMaterial);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        repositories = mock(FrontierRepositories.class);
        economy = mock(FrontierEconomy.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("buyer");
        when(repositories.activeSeason()).thenReturn(Optional.of(new SeasonRecord(
                1, "season", "Season", "world", SeasonPhase.ACTIVE, NOW, NOW, null, null, true)));
        when(repositories.findOrder(1)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return null; })
                .when(repositories).saveOrder(any());
        service = new FrontierService(plugin, repositories, Clock.fixed(NOW, ZoneOffset.UTC));
        service.attachIntegrations(economy, null, null);
    }

    @AfterEach
    void cleanup() {
        materials.close();
    }

    private OrderRecord order(OrderType type, OrderStatus status, UUID owner, Instant expiry) {
        return new OrderRecord(1, 1, owner, "owner", type, "minecraft:stone", 2, 10, 25,
                status, null, null, null, NOW.minusSeconds(3600), expiry);
    }

    private void fullInventory() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack occupied = mock(ItemStack.class);
        when(occupied.getType()).thenReturn(mock(Material.class));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{occupied});
        when(player.getInventory()).thenReturn(inventory);
    }

    @Test
    void orderCanBeReservedOnlyByOnePlayer() {
        stored.set(order(OrderType.SELL_ITEM, OrderStatus.OPEN, ownerId, NOW.plusSeconds(60)));
        service.reserveOrder(player, 1);
        assertEquals(playerId, stored.get().reservedByUuid());
        assertEquals(OrderStatus.RESERVED, stored.get().status());
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        assertThrows(UserMessageException.class, () -> service.reserveOrder(other, 1));
    }

    @Test
    void cannotReserveOwnOrder() {
        stored.set(order(OrderType.BUY_ITEM, OrderStatus.OPEN, playerId, NOW.plusSeconds(60)));
        assertThrows(UserMessageException.class, () -> service.reserveOrder(player, 1));
        assertEquals(OrderStatus.OPEN, stored.get().status());
    }

    @Test
    void expiredBuyOrderRefundsEscrowOnceWithoutRefundingFee() {
        stored.set(order(OrderType.BUY_ITEM, OrderStatus.EXPIRED, playerId, NOW.minusSeconds(1)));
        service.reclaimOrder(player, 1);
        verify(economy).deposit(playerId, 20);
        assertEquals(OrderStatus.RETURNED, stored.get().status());
        assertThrows(UserMessageException.class, () -> service.reclaimOrder(player, 1));
        verifyNoMoreInteractions(economy);
    }

    @Test
    void expiryAtCurrentInstantIsReclaimableWithoutOpeningBoard() {
        stored.set(order(OrderType.BUY_ITEM, OrderStatus.RESERVED, playerId, NOW));
        service.reclaimOrder(player, 1);
        assertEquals(OrderStatus.RETURNED, stored.get().status());
    }

    @Test
    void activeCompletedAndOtherPlayersOrdersCannotBeReclaimed() {
        for (OrderStatus status : List.of(OrderStatus.OPEN, OrderStatus.COMPLETED, OrderStatus.RETURNED)) {
            stored.set(order(OrderType.BUY_ITEM, status, playerId, NOW.plusSeconds(60)));
            assertThrows(UserMessageException.class, () -> service.reclaimOrder(player, 1));
        }
        stored.set(order(OrderType.BUY_ITEM, OrderStatus.EXPIRED, ownerId, NOW.minusSeconds(1)));
        assertThrows(UserMessageException.class, () -> service.reclaimOrder(player, 1));
        verifyNoInteractions(economy);
    }

    @Test
    void fullInventoryKeepsExpiredItemsAvailableForLaterReclaim() {
        fullInventory();
        stored.set(order(OrderType.SELL_ITEM, OrderStatus.EXPIRED, playerId, NOW.minusSeconds(1)));
        assertThrows(UserMessageException.class, () -> service.reclaimOrder(player, 1));
        assertEquals(OrderStatus.EXPIRED, stored.get().status());
        verify(player.getInventory(), never()).addItem(any(ItemStack[].class));
    }

    @Test
    void expiredSellOrderReturnsItemsOnlyOnce() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null});
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        stored.set(order(OrderType.SELL_ITEM, OrderStatus.EXPIRED, playerId, NOW.minusSeconds(1)));
        // ItemStack's Paper constructor needs a running server; the inventory is mocked here.
        try (var items = mockConstruction(ItemStack.class)) {
            service.reclaimOrder(player, 1);
            assertEquals(OrderStatus.RETURNED, stored.get().status());
            assertEquals(1, items.constructed().size());
            assertThrows(UserMessageException.class, () -> service.reclaimOrder(player, 1));
            verify(inventory).addItem(any(ItemStack[].class));
        }
        verifyNoInteractions(economy);
    }

    @Test
    void fullInventoryDoesNotChargeBuyerOrCompleteOrder() {
        fullInventory();
        when(economy.balance(playerId)).thenReturn(100L);
        stored.set(order(OrderType.SELL_ITEM, OrderStatus.OPEN, ownerId, NOW.plusSeconds(60)));
        service.reserveOrder(player, 1);
        assertThrows(UserMessageException.class, () -> service.deliverOrder(player, 1));
        verify(economy, never()).withdraw(any(), anyLong());
        assertEquals(OrderStatus.RESERVED, stored.get().status());
    }

    @Test
    void threeSuccessfulDeliveriesCompleteWeeklyTradeMission() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack stone = mock(ItemStack.class);
        when(stone.getType()).thenReturn(stoneMaterial);
        when(stone.getAmount()).thenReturn(2);
        when(inventory.getContents()).thenReturn(new ItemStack[]{stone});
        when(player.getInventory()).thenReturn(inventory);
        when(repositories.missions()).thenReturn(List.of(new MissionRecord(
                1, 1, MissionScope.WEEKLY, "取引を3回完了する", "", "action:trade", 3, 0, 0, true)));
        AtomicReference<MissionProgressRecord> progress = new AtomicReference<>();
        when(repositories.findMissionProgress(anyString())).thenAnswer(invocation -> progress.get());
        doAnswer(invocation -> { progress.set(invocation.getArgument(1)); return null; })
                .when(repositories).saveMissionProgress(anyString(), any());
        when(repositories.findProfile(anyString())).thenReturn(new PlayerProfileRecord(
                playerId, "buyer", 1, 100, 0, 0, 0, false, 0, false, null, null, NOW, NOW));
        for (int i = 1; i <= 3; i++) {
            stored.set(order(OrderType.BUY_ITEM, OrderStatus.OPEN, null, NOW.plusSeconds(60)));
            service.fillOrder(player, 1);
            assertEquals(OrderStatus.COMPLETED, stored.get().status());
            assertEquals(i, progress.get().progress());
            assertEquals(i == 3, progress.get().completed());
            assertThrows(UserMessageException.class, () -> service.deliverOrder(player, 1));
        }
        verify(economy, times(3)).deposit(playerId, 20);
    }
}
