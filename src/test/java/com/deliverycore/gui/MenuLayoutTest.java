package com.deliverycore.gui;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuLayoutTest {
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
