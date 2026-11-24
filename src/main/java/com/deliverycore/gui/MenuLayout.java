package com.deliverycore.gui;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
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
        if (section == null) return fromLegacy(config, menu, defaults);
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

    private static MenuLayout fromLegacy(ConfigurationSection config, String menu, MenuLayout defaults) {
        String key = menu.equals("leaderboard") ? "top-ten" : menu;
        List<String> rows = config.getStringList("gui." + key + "-layout");
        if (rows.isEmpty() || rows.size() > 6 || rows.stream().anyMatch(row -> row.length() != 9)) return defaults;

        Map<Character, String> controls = switch (menu) {
            case "main" -> Map.of('h', "help", 'p', "previous", 'n', "next");
            case "delivery" -> Map.of('d', "inventory", 't', "leaderboard", 'p', "previous", 'n', "next");
            default -> Map.of();
        };
        String groupName = switch (menu) {
            case "main" -> "categories";
            case "delivery" -> "milestones";
            default -> "entries";
        };
        char groupMarker = menu.equals("leaderboard") ? 'p' : 'c';
        String layout = String.join("", rows);
        Map<String, Integer> slots = new LinkedHashMap<>();
        List<Integer> group = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < layout.length(); i++) {
            char marker = layout.charAt(i);
            if (marker == ' ') free.add(i);
            else if (marker == groupMarker) group.add(i);
            else {
                String control = controls.get(marker);
                if (control == null || slots.putIfAbsent(control, i) != null) return defaults;
            }
        }
        if (group.isEmpty()) return defaults;

        // Preserve the existing actions by placing them in any available slots.
        List<String> required = switch (menu) {
            case "main" -> List.of("help", "leaderboard", "previous", "next");
            case "delivery" -> List.of("inventory", "info", "chest", "back", "previous", "next");
            default -> List.of("back");
        };
        for (String control : required) {
            if (slots.containsKey(control)) continue;
            if (free.isEmpty()) return defaults;
            slots.put(control, free.remove(0));
        }
        if (menu.equals("leaderboard")) slots.put("empty", group.get(0));
        return new MenuLayout(layout.length(), Map.copyOf(slots), Map.of(groupName, List.copyOf(group)));
    }

    private static boolean reserve(HashSet<Integer> occupied, int slot, int size) {
        return slot >= 0 && slot < size && occupied.add(slot);
    }

    int slot(String name) { return slots.get(name); }
    int[] group(String name) { return groups.get(name).stream().mapToInt(Integer::intValue).toArray(); }
}
