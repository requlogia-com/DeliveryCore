package com.deliverycore.service;

import com.deliverycore.config.DeliveryConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

public final class CategoryMilestones {
    private final DataManager data;
    private Map<String, List<Milestone>> categories = Map.of();

    public CategoryMilestones(DataManager data) {
        this.data = data;
    }

    public void reload(File file) {
        reload(file, null);
    }

    public void reload(File file, DeliveryConfig deliveries) {
        Map<String, List<Milestone>> loaded = new HashMap<>();
        ConfigurationSection root = file.isFile()
                ? YamlConfiguration.loadConfiguration(file).getConfigurationSection("categories") : null;
        if (root != null) {
            for (String category : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(category + ".milestones");
                if (section == null) continue;
                List<Milestone> milestones = new ArrayList<>();
                for (String id : section.getKeys(false)) {
                    ConfigurationSection entry = section.getConfigurationSection(id);
                    if (entry == null || entry.getLong("amount") <= 0) continue;
                    milestones.add(new Milestone(id, entry.getString("name", id), entry.getLong("amount"),
                            entry.getString("premium-permission", "deliverycore.premium"),
                            entry.getStringList("commands"), entry.getStringList("premium-commands"),
                            entry.getStringList("lore")));
                }
                milestones.sort(Comparator.comparingLong(Milestone::amount));
                loaded.put(category.toLowerCase(Locale.ROOT), List.copyOf(milestones));
            }
        }
        if (deliveries != null) {
            deliveries.getImportedDeliveries().forEach((id, delivery) -> {
                List<Milestone> milestones = delivery.checkpoints().stream()
                        .map(checkpoint -> new Milestone(checkpoint.id(), checkpoint.id(), checkpoint.amount(),
                                delivery.premiumPermission(), checkpoint.rewards(), checkpoint.premiumRewards(),
                                checkpoint.lore()))
                        .sorted(Comparator.comparingLong(Milestone::amount))
                        .toList();
                if (!milestones.isEmpty()) {
                    loaded.putIfAbsent(id.toLowerCase(Locale.ROOT), milestones);
                }
            });
        }
        categories = Map.copyOf(loaded);
    }

    public List<Milestone> getMilestones(String category) {
        return categories.getOrDefault(category.toLowerCase(Locale.ROOT), List.of());
    }

    public DataManager.CategoryProgress getProgress(UUID player, String category) {
        return data.getCategoryProgress(player, category);
    }

    public void setProgress(UUID player, String category, long amount) {
        var old = getProgress(player, category);
        data.saveCategoryProgress(player, category, new DataManager.CategoryProgress(amount, old.claimed()));
    }

    public void record(Player player, String category, int delivered) {
        record(player, category, delivered, ignored -> {});
    }

    public void record(Player player, String category, int delivered, IntConsumer notifyCompleted) {
        UUID uuid = player.getUniqueId();
        var previous = getProgress(uuid, category);
        long amount = previous.amount() + Math.min(delivered, Long.MAX_VALUE - previous.amount());
        var claimed = new HashSet<>(previous.claimed());
        List<String> commands = new ArrayList<>();
        List<Integer> completed = new ArrayList<>();
        List<Milestone> milestones = getMilestones(category);
        for (int i = 0; i < milestones.size(); i++) {
            Milestone milestone = milestones.get(i);
            if (amount < milestone.amount() || !claimed.add(milestone.id())) continue;
            completed.add(i + 1);
            boolean premium = !milestone.premiumCommands().isEmpty()
                    && player.hasPermission(milestone.premiumPermission());
            commands.addAll(premium ? milestone.premiumCommands() : milestone.commands());
        }
        data.saveCategoryProgress(uuid, category, new DataManager.CategoryProgress(amount, claimed));
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
        completed.forEach(notifyCompleted::accept);
    }

    public record Milestone(String id, String name, long amount, String premiumPermission, List<String> commands,
                            List<String> premiumCommands, List<String> lore) {
        public Milestone(String id, String name, long amount, String premiumPermission, List<String> commands,
                         List<String> premiumCommands) {
            this(id, name, amount, premiumPermission, commands, premiumCommands, List.of());
        }

        public Milestone {
            lore = List.copyOf(lore);
        }
    }
}
