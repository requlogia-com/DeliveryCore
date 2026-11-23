package com.deliverycore.placeholder;

import com.deliverycore.DeliveryCorePlugin;
import com.deliverycore.service.ActiveEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DeliveryExpansion extends PlaceholderExpansion {
    private final DeliveryCorePlugin plugin;

    public static Runnable registerFor(DeliveryCorePlugin plugin) {
        DeliveryExpansion expansion = new DeliveryExpansion(plugin);
        if (expansion.register()) return expansion::unregister;
        plugin.getLogger().warning("PlaceholderAPI could not register the deliverycore expansion.");
        return () -> {};
    }

    private DeliveryExpansion(DeliveryCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "deliverycore"; }
    @Override public String getAuthor() { return String.join(", ", plugin.getDescription().getAuthors()); }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        int split = params.indexOf('_');
        if (split < 1 || split == params.length() - 1) return null;
        String type = params.substring(0, split).toLowerCase(Locale.ROOT);
        String key = params.substring(split + 1);
        if (type.equals("amount") || type.equals("milestones")) {
            if (player == null) return "0";
            var progress = plugin.getCategoryMilestones().getProgress(player.getUniqueId(), key);
            return type.equals("amount") ? Long.toString(progress.amount()) : Integer.toString(progress.claimed().size());
        }
        if (type.equals("topname") || type.equals("topamount")) {
            int position = key.lastIndexOf('_');
            if (position < 1) return null;
            try {
                int rank = Integer.parseInt(key.substring(position + 1));
                if (rank < 1) return null;
                var event = plugin.getDeliveryService().getActiveEvent(key.substring(0, position));
                if (event.isEmpty()) return "";
                var entry = event.get().getPlayerDeliveries().entrySet().stream()
                        .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                        .skip(rank - 1L).findFirst();
                return entry.map(value -> type.equals("topamount") ? Integer.toString(value.getValue())
                        : Bukkit.getOfflinePlayer(value.getKey()).getName()).orElse("");
            } catch (NumberFormatException e) {
                return null;
            }
        }
        ActiveEvent event = plugin.getDeliveryService().getActiveEvent(key).orElse(null);
        return switch (type) {
            case "remaining" -> event == null ? "0" : Long.toString(Math.max(0,
                    Duration.between(Instant.now(), event.getEndTime().toInstant()).getSeconds()));
            case "items" -> event == null ? "" : String.join(", ", event.getAcceptedItems());
            case "active" -> Boolean.toString(event != null && event.isActive());
            default -> null;
        };
    }
}
