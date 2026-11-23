package com.deliverycore.gui;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record MenuLayout(int size, Map<String, Integer> slots, Map<String, List<Integer>> groups) {
    static MenuLayout main() {
        return new MenuLayout(54, Map.of("leaderboard", 47, "help", 51),
                Map.of("active", List.of(11, 12, 13, 14, 15), "waiting", List.of(29, 30, 31, 32, 33)));
    }

    static MenuLayout delivery() {
        return new MenuLayout(45, Map.of("info", 4, "inventory", 20, "chest", 24, "back", 40,
                "previous", 36, "next", 44), Map.of("milestones", List.of(28, 29, 30, 31, 32, 33, 34)));
    }

    static MenuLayout ranking() {
        return new MenuLayout(54, Map.of("empty", 22, "back", 49),
                Map.of("entries", List.of(13, 21, 23, 29, 30, 31, 32, 33, 38, 42)));
    }

    static MenuLayout from(ConfigurationSection config, String menu, MenuLayout defaults) {
        ConfigurationSection section = config.getConfigurationSection("menus." + menu);
        if (section == null) return defaults;
        int size = section.getInt("size", defaults.size());
        if (size < 9 || size > 54 || size % 9 != 0) return defaults;

        Map<String, Integer> slots = new LinkedHashMap<>();
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        var occupied = new HashSet<Integer>();
        for (var slot : defaults.slots().entrySet()) {
            int configured = section.getInt(slot.getKey(), slot.getValue());
            if (!reserve(occupied, configured, size)) return defaults;
            slots.put(slot.getKey(), configured);
        }
        for (var group : defaults.groups().entrySet()) {
            List<Integer> values = section.contains(group.getKey()) ? section.getIntegerList(group.getKey()) : group.getValue();
            if (values.isEmpty()) return defaults;
            for (int value : values) {
                if (!reserve(occupied, value, size)) return defaults;
            }
            groups.put(group.getKey(), List.copyOf(values));
        }
        return new MenuLayout(size, Map.copyOf(slots), Map.copyOf(groups));
    }

    private static boolean reserve(HashSet<Integer> occupied, int slot, int size) {
        return slot >= 0 && slot < size && occupied.add(slot);
    }

    int slot(String name) { return slots.get(name); }
    int[] group(String name) { return groups.get(name).stream().mapToInt(Integer::intValue).toArray(); }
}
