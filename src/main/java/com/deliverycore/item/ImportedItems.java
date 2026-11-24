package com.deliverycore.item;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ImportedItems {
    private ImportedItems() {}

    static Map<String, ItemStack> read(Path source) throws IOException {
        try (var input = Files.newInputStream(source)) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            Map<String, ItemStack> items = new LinkedHashMap<>();
            for (var entry : section(section(root).get("items")).entrySet()) {
                String id = entry.getKey().toString();
                try {
                    items.put(id, readItem(id, section(entry.getValue())));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid imported item '" + id + "' in " + source, e);
                }
            }
            return items;
        }
    }

    private static ItemStack readItem(String id, Map<?, ?> values) {
        Material material = Material.matchMaterial(text(values, "type", ""));
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Expected a valid item type");
        }
        ItemStack item = new ItemStack(material);
        Map<?, ?> meta = section(values.get("meta"));
        if (meta.isEmpty()) return item;
        if ("ItemMeta".equals(meta.get("==")) && meta.get("meta-type") instanceof String) {
            return readSerializedItem(values);
        }

        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) throw new IllegalArgumentException("Item type does not support metadata");
        if (meta.get("display-name") instanceof String name) itemMeta.setDisplayName(color(name));
        if (meta.get("lore") instanceof List<?> lore) {
            itemMeta.setLore(lore.stream().map(Object::toString).map(ImportedItems::color).toList());
        }
        if (meta.get("custom-model-data") instanceof Number data) itemMeta.setCustomModelData(data.intValue());
        if (meta.get("item-flags") instanceof List<?> flags) {
            for (Object flag : flags) itemMeta.addItemFlags(ItemFlag.valueOf(flag.toString()));
        }
        section(meta.get("enchants")).forEach((key, level) -> {
            Enchantment enchantment = Enchantment.getByName(key.toString());
            if (enchantment == null || !(level instanceof Number number)) {
                throw new IllegalArgumentException("Invalid enchantment " + key);
            }
            itemMeta.addEnchant(enchantment, number.intValue(), true);
        });
        section(meta.get("attribute-modifiers")).forEach((key, entry) -> {
            Map<?, ?> modifier = section(entry);
            if (!(modifier.get("amount") instanceof Number amount)) {
                throw new IllegalArgumentException("Invalid attribute amount for " + key);
            }
            String name = key.toString();
            String slot = text(modifier, "slot", "");
            var operation = AttributeModifier.Operation.valueOf(text(modifier, "operation", "ADD_NUMBER"));
            UUID uuid = UUID.nameUUIDFromBytes((id + ":" + name + ":" + slot).getBytes(StandardCharsets.UTF_8));
            itemMeta.addAttributeModifier(attribute(name), new AttributeModifier(uuid, "deliverycore:" + id,
                amount.doubleValue(), operation, slot.isEmpty() ? null : EquipmentSlot.valueOf(slot)));
        });
        item.setItemMeta(itemMeta);
        return item;
    }

    private static ItemStack readSerializedItem(Map<?, ?> values) {
        var config = new YamlConfiguration();
        try {
            config.loadFromString(new Yaml().dump(Map.of("item", values)));
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("Could not deserialize item metadata", e);
        }
        ItemStack item = config.getItemStack("item");
        if (item == null) throw new IllegalArgumentException("Missing serialized item");
        item.setAmount(1);
        return item;
    }

    private static Attribute attribute(String name) {
        // Bukkit 1.21 removed the GENERIC_ prefix from attribute constants.
        for (String key : List.of(name, name.replaceFirst("^GENERIC_", ""))) {
            try {
                return Attribute.class.cast(Attribute.class.getField(key).get(null));
            } catch (ReflectiveOperationException ignored) {
                // Try the equivalent name on the other API generation.
            }
        }
        throw new IllegalArgumentException("Unknown item attribute " + name);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static Map<?, ?> section(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Map<?, ?> values, String key, String fallback) {
        return values.get(key) instanceof String value ? value : fallback;
    }
}
