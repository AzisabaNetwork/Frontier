package net.azisaba.frontier.gui;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderMenuTest {
    @Test
    void recognizesColoredOrderAndClaimNumbersButNotButtons() throws Exception {
        var parse = FrontierMenuService.class.getDeclaredMethod("parsePrefixedId", ItemStack.class, String.class);
        parse.setAccessible(true);
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getDisplayName()).thenReturn("\u00a76#123");
        assertEquals(123L, parse.invoke(null, item, "#"));
        when(meta.getDisplayName()).thenReturn("\u00a7a#45 \u00a7f有効");
        assertEquals(45L, parse.invoke(null, item, "#"));
        when(meta.getDisplayName()).thenReturn("\u00a7a注文を作成");
        assertEquals(-1L, parse.invoke(null, item, "#"));
    }
}
