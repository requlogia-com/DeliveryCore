package com.deliverycore.service;

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

public final class CategoryMilestones {
    private final DataManager data;
    private Map<String, List<Milestone>> categories = Map.of();

    public CategoryMilestones(DataManager data) {
        this.data = data;
    }

    public void reload(File file) {
        Map<String, List<Milestone>> loaded = new HashMap<>();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("categories");
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
                            entry.getStringList("commands"), entry.getStringList("premium-commands")));
                }
                milestones.sort(Comparator.comparingLong(Milestone::amount));
                loaded.put(category.toLowerCase(Locale.ROOT), List.copyOf(milestones));
            }
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
        UUID uuid = player.getUniqueId();
        var previous = getProgress(uuid, category);
        long amount = previous.amount() + Math.min(delivered, Long.MAX_VALUE - previous.amount());
        var claimed = new HashSet<>(previous.claimed());
        List<String> commands = new ArrayList<>();
        for (Milestone milestone : getMilestones(category)) {
            if (amount < milestone.amount() || !claimed.add(milestone.id())) continue;
            boolean premium = !milestone.premiumCommands().isEmpty()
                    && player.hasPermission(milestone.premiumPermission());
            commands.addAll(premium ? milestone.premiumCommands() : milestone.commands());
        }
        data.saveCategoryProgress(uuid, category, new DataManager.CategoryProgress(amount, claimed));
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
    }

    public record Milestone(String id, String name, long amount, String premiumPermission, List<String> commands,
                            List<String> premiumCommands) {}
}
