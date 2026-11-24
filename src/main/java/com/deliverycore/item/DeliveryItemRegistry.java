package com.deliverycore.item;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves configured delivery item IDs and stores items registered by administrators. */
public final class DeliveryItemRegistry {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_-]*");

    private final Path file;
    private final ExternalItems externalItems;
    private final Map<String, ItemStack> items = new LinkedHashMap<>();

    public DeliveryItemRegistry(JavaPlugin plugin) {
        file = plugin.getDataFolder().toPath().resolve("custom-items.yml");
        externalItems = new ExternalItems(plugin.getServer().getPluginManager(), plugin.getLogger());
        reload();
    }

    public void register(String key, ItemStack stack) throws IOException {
        String normalized = normalizeKey(key);
        if (stack == null || stack.getType().isAir() || stack.getAmount() < 1) {
            throw new IllegalArgumentException("Hold an item before registering it");
        }
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        Map<String, ItemStack> updated = new LinkedHashMap<>(items);
        updated.put(normalized, copy);
        save(updated);
        items.put(normalized, copy);
    }

    public boolean remove(String key) throws IOException {
        String normalized = normalizeKey(key);
        if (!items.containsKey(normalized)) {
            return false;
        }
        Map<String, ItemStack> updated = new LinkedHashMap<>(items);
        updated.remove(normalized);
        save(updated);
        items.remove(normalized);
        return true;
    }

    public boolean matches(String itemId, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() < 1) {
            return false;
        }
        ItemId ref = ItemId.parse(itemId);
        return switch (ref.namespace()) {
            case "minecraft" -> {
                Material vanilla = material(ref.id());
                yield vanilla != null ? stack.getType() == vanilla
                    : itemId != null && !itemId.contains(":") && stack.isSimilar(items.get(ref.id().toLowerCase(Locale.ROOT)));
            }
            case "custom" -> stack.isSimilar(items.get(ref.id().toLowerCase(Locale.ROOT)));
            default -> externalItems.matches(ref.namespace(), ref.id(), stack);
        };
    }

    /** Returns a display item or null if the configured item is unavailable. */
    public ItemStack createItem(String itemId) {
        ItemId ref = ItemId.parse(itemId);
        if ("minecraft".equals(ref.namespace())) {
            Material material = material(ref.id());
            if (material != null) return new ItemStack(material);
            if (itemId == null || itemId.contains(":")) return null;
            ItemStack template = items.get(ref.id().toLowerCase(Locale.ROOT));
            return template == null ? null : template.clone();
        }
        ItemStack template = "custom".equals(ref.namespace())
            ? items.get(ref.id().toLowerCase(Locale.ROOT))
            : externalItems.createItem(ref.namespace(), ref.id());
        return template == null ? null : template.clone();
    }

    private static Material material(String id) {
        if (id.isEmpty()) {
            return null;
        }
        Material material = Material.matchMaterial(id);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    private static String normalizeKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Item ID must start with a letter and use only letters, digits, _ or -");
        }
        return normalized;
    }

    public void reload() {
        if (Files.notExists(file)) {
            Path imported = file.resolveSibling("custom_items.yml");
            Map<String, ItemStack> loaded = new LinkedHashMap<>();
            if (Files.isRegularFile(imported)) {
                try {
                    ImportedItems.read(imported).forEach((key, stack) -> loaded.put(normalizeKey(key), stack));
                    save(loaded);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not import " + imported, e);
                }
            }
            items.clear();
            items.putAll(loaded);
            return;
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
        ConfigurationSection section = config.getConfigurationSection("items");
        if (section == null) {
            if (config.contains("items")) {
                throw new IllegalStateException("Invalid items section in " + file);
            }
            items.clear();
            return;
        }
        Map<String, ItemStack> loaded = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ItemStack stack = section.getItemStack(key);
            if (stack == null || stack.getType().isAir()) {
                throw new IllegalStateException("Invalid registered item '" + key + "' in " + file);
            }
            loaded.put(normalizeKey(key), stack);
        }
        items.clear();
        items.putAll(loaded);
    }

    private void save(Map<String, ItemStack> updated) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.createSection("items", updated);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "custom-items-", ".yml");
        try {
            config.save(temp.toFile());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private record ItemId(String namespace, String id) {
        private static ItemId parse(String value) {
            if (value == null) {
                return new ItemId("minecraft", "");
            }
            int separator = value.indexOf(':');
            if (separator < 0) {
                return new ItemId("minecraft", value);
            }
            return new ItemId(value.substring(0, separator).toLowerCase(Locale.ROOT),
                value.substring(separator + 1));
        }
    }
}
