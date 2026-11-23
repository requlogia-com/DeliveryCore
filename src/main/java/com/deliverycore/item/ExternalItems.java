package com.deliverycore.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Optional integrations through each installed plugin's public item API. */
final class ExternalItems {
    private static final Map<String, Provider> PROVIDERS = Map.of(
        "itemsadder", new Provider("ItemsAdder", "dev.lone.itemsadder.api.CustomStack",
            "byItemStack", "getInstance", "getItemStack"),
        "nexo", new Provider("Nexo", "com.nexomc.nexo.api.NexoItems",
            "idFromItem", "itemFromId", "build"),
        "oraxen", new Provider("Oraxen", "io.th0rgal.oraxen.api.OraxenItems",
            "getIdByItem", "getItemById", "build"));

    private final PluginManager plugins;
    private final Logger logger;
    private final Set<String> reportedFailures = new HashSet<>();

    ExternalItems(PluginManager plugins, Logger logger) {
        this.plugins = plugins;
        this.logger = logger;
    }

    boolean matches(String namespace, String id, ItemStack stack) {
        Object result = invoke(namespace, "read item ID", (provider, api) -> {
            Object found = api.getMethod(provider.identify(), ItemStack.class).invoke(null, stack);
            if (found == null || !"itemsadder".equals(namespace)) {
                return id.equals(found);
            }
            var namespacedId = api.getMethod("getNamespacedID");
            Object actual = namespacedId.invoke(found);
            if (id.contains(":")) {
                return id.equals(actual);
            }
            Object expected = api.getMethod(provider.lookup(), String.class).invoke(null, id);
            return expected != null && actual instanceof String actualId && actualId.equals(namespacedId.invoke(expected));
        });
        return Boolean.TRUE.equals(result);
    }

    ItemStack createItem(String namespace, String id) {
        Object result = invoke(namespace, "build item", (provider, api) -> {
            Object builder = api.getMethod(provider.lookup(), String.class).invoke(null, id);
            return builder == null ? null : builder.getClass().getMethod(provider.build()).invoke(builder);
        });
        return result instanceof ItemStack stack ? stack : null;
    }

    private Object invoke(String namespace, String operation, Adapter adapter) {
        Provider provider = PROVIDERS.get(namespace);
        if (provider == null) {
            return null;
        }
        Plugin plugin = plugins.getPlugin(provider.plugin());
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            Class<?> api = Class.forName(provider.api(), false, plugin.getClass().getClassLoader());
            return adapter.run(provider, api);
        } catch (ReflectiveOperationException | LinkageError e) {
            if (reportedFailures.add(namespace + ':' + operation)) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logger.warning("Could not " + operation + " via " + provider.plugin() + ": " + cause);
            }
            return null;
        }
    }

    private interface Adapter {
        Object run(Provider provider, Class<?> api) throws ReflectiveOperationException;
    }

    private record Provider(String plugin, String api, String identify, String lookup, String build) {}
}
