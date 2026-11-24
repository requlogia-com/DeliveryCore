package com.deliverycore.config;

import com.deliverycore.model.DeliveryDefinition;
import com.deliverycore.model.ImportedDelivery;
import com.deliverycore.model.RewardConfig;
import com.deliverycore.model.ScheduleConfig;
import com.deliverycore.model.SelectionConfig;
import com.deliverycore.model.SelectionMode;
import com.deliverycore.model.WebhookConfig;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImportedDeliveryParser {
    private ImportedDeliveryParser() {}

    static ImportedDelivery read(String id, Map<?, ?> values, ZoneId timezone) {
        List<String> items = strings(values.get("items"));
        if (items.isEmpty() || items.stream().anyMatch(item -> !ItemValidator.isValidItemName(item))) {
            throw new IllegalArgumentException("categories." + id + ".items must contain valid item IDs");
        }
        ScheduleConfig schedule = new ScheduleConfig(string(values, "start", ""), string(values, "end", ""));
        Map<Integer, List<String>> rankingRewards = rankingRewards(id, section(values.get("specialRewards")));
        int winnerCount = rankingRewards.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        DeliveryDefinition definition = new DeliveryDefinition(id, true, false, SelectionConfig.fixed(id),
                new SelectionConfig(SelectionMode.ALL, null), timezone, schedule, winnerCount,
                RewardConfig.command(List.of()), WebhookConfig.disabled(), items.stream().distinct().toList());
        Map<?, ?> gui = section(values.get("gui"));
        String title = string(values, "title", id);
        return new ImportedDelivery(definition, title, string(gui, "material", ""),
                string(gui, "name", title), strings(gui.get("lore")), strings(values.get("delivery")),
                string(values, "premium_perm", "delivery.premium"), checkpoints(id, section(values.get("checkpoints"))),
                rankingRewards, Boolean.TRUE.equals(values.get("send-webhooks")));
    }

    private static List<ImportedDelivery.Checkpoint> checkpoints(String delivery, Map<?, ?> section) {
        List<ImportedDelivery.Checkpoint> checkpoints = new ArrayList<>();
        for (var entry : section.entrySet()) {
            String id = entry.getKey().toString();
            Map<?, ?> value = section(entry.getValue());
            if (!(value.get("amount") instanceof Number amount) || amount.longValue() <= 0) {
                throw new IllegalArgumentException("categories." + delivery + ".checkpoints." + id
                        + ".amount must be positive");
            }
            checkpoints.add(new ImportedDelivery.Checkpoint(id, amount.longValue(), strings(value.get("lore")),
                    strings(value.get("rewards")), strings(value.get("premium_rewards"))));
        }
        return List.copyOf(checkpoints);
    }

    private static Map<Integer, List<String>> rankingRewards(String delivery, Map<?, ?> section) {
        Map<Integer, List<String>> rewards = new LinkedHashMap<>();
        for (var entry : section.entrySet()) {
            int rank;
            try {
                rank = Integer.parseInt(entry.getKey().toString());
                if (rank < 1) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("categories." + delivery + ".specialRewards ranks must be positive integers");
            }
            rewards.put(rank, strings(entry.getValue()));
        }
        return Map.copyOf(rewards);
    }

    private static Map<?, ?> section(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String string(Map<?, ?> values, String key, String fallback) {
        return values.get(key) instanceof String text ? text : fallback;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }
}
