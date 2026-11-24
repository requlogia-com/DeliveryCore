package com.deliverycore.model;

import java.util.List;
import java.util.Map;

/** Presentation and rewards provided by a category in an imported deliveries.yml. */
public record ImportedDelivery(
        DeliveryDefinition definition,
        String title,
        String icon,
        String menuName,
        List<String> menuLore,
        List<String> itemLore,
        String premiumPermission,
        List<Checkpoint> checkpoints,
        Map<Integer, List<String>> rankingRewards,
        boolean sendWebhooks
) {
    public record Checkpoint(String id, long amount, List<String> lore, List<String> rewards,
                             List<String> premiumRewards) {}
}
