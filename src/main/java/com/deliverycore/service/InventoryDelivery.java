package com.deliverycore.service;

import com.deliverycore.item.DeliveryItemRegistry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class InventoryDelivery {
    private InventoryDelivery() {}

    public static int submit(Player player, Inventory inventory, ActiveEvent event, int limit,
                             DeliveryItemRegistry registry, DeliveryServiceImpl service) {
        if (!event.isActive() || service.getActiveEvent(event.getDeliveryName()).orElse(null) != event) return 0;
        var contents = inventory.getContents();
        List<Removal> removals = new ArrayList<>();
        int total = 0;
        for (int slot = 0; slot < contents.length && total < limit; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getAmount() <= 0
                    || event.getAcceptedItems().stream().noneMatch(id -> registry.matches(id, stack))) continue;
            int count = Math.min(stack.getAmount(), limit - total);
            removals.add(new Removal(slot, stack.clone(), count));
            total += count;
        }
        if (total == 0) return 0;
        return service.recordDelivery(player.getUniqueId(), event.getDeliveryName(), total, () -> {
            for (Removal removal : removals) {
                if (!removal.original().equals(inventory.getItem(removal.slot()))) return false;
            }
            for (Removal removal : removals) {
                ItemStack remaining = removal.original();
                int amount = remaining.getAmount() - removal.count();
                if (amount > 0) remaining.setAmount(amount);
                inventory.setItem(removal.slot(), amount > 0 ? remaining : null);
            }
            return true;
        }) ? total : -1;
    }

    private record Removal(int slot, ItemStack original, int count) {}
}
