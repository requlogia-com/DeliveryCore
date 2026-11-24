package com.deliverycore.gui;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuLayoutTest {
    @Test
    void readsDeliveryCharacterLayouts() {
        var config = new MemoryConfiguration();
        config.set("gui.main-layout", List.of("    h    ", " c  c  c ", "p       n"));
        config.set("gui.delivery-layout", List.of("         ", "   d t   ", " ccccccc ", "p       n"));
        config.set("gui.top-ten-layout", List.of("    p    ", "   p p   ", "  p p p  ", " p p p p "));

        MenuLayout main = MenuLayout.from(config, "main", MenuLayout.main());
        assertEquals(27, main.size());
        assertEquals(4, main.slot("help"));
        assertEquals(18, main.slot("previous"));
        assertEquals(26, main.slot("next"));
        assertArrayEquals(new int[] {10, 13, 16}, main.group("categories"));

        MenuLayout delivery = MenuLayout.from(config, "delivery", MenuLayout.delivery());
        assertEquals(12, delivery.slot("inventory"));
        assertEquals(14, delivery.slot("leaderboard"));
        assertArrayEquals(new int[] {19, 20, 21, 22, 23, 24, 25}, delivery.group("milestones"));

        MenuLayout ranking = MenuLayout.from(config, "leaderboard", MenuLayout.ranking());
        assertArrayEquals(new int[] {4, 12, 14, 20, 22, 24, 28, 30, 32, 34}, ranking.group("entries"));
    }

    @Test
    void loadsACompleteLayoutWithReorderedSlots() {
        var config = new MemoryConfiguration();
        config.set("menus.delivery.size", 18);
        config.set("menus.delivery.info", 0);
        config.set("menus.delivery.inventory", 1);
        config.set("menus.delivery.chest", 2);
        config.set("menus.delivery.back", 3);
        config.set("menus.delivery.previous", 4);
        config.set("menus.delivery.next", 5);
        config.set("menus.delivery.milestones", List.of(14, 13, 12));

        MenuLayout layout = MenuLayout.from(config, "delivery", MenuLayout.delivery());

        assertEquals(18, layout.size());
        assertEquals(1, layout.slot("inventory"));
        assertArrayEquals(new int[] {14, 13, 12}, layout.group("milestones"));
    }

    @Test
    void fallsBackWhenAnActionCollidesWithAContentSlot() {
        var config = new MemoryConfiguration();
        config.set("menus.main.help", 11);

        assertEquals(MenuLayout.main(), MenuLayout.from(config, "main", MenuLayout.main()));
    }
}
