package com.deliverycore.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportedLanguageTest {
    @Test
    void readsLegacyNamesPrefixesAndLore() {
        var language = new LanguageConfigImpl(Map.of());
        var yaml = """
            messages:
              prefix: '&6 Teslimat'
              delivered-message: '{prefix} &e{amount}x &a{category}'
            gui:
              delivery-gui-name: '&8{delivery} Teslimat'
              deliver-item-lore: ['', '&aTıkla', '']
            """;
        language.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "tr");

        assertEquals("§6 Teslimat §e42x §aMaden", language.format("delivery.success.message", "tr", "",
            Map.of("amount", "42", "category", "Maden")));
        assertEquals("§8Maden Teslimat", language.format("gui.delivery.title", "tr", "", Map.of("delivery", "Maden")));
        assertEquals("\n§aTıkla\n", language.format("gui.deliver-item-lore", "tr", "", Map.of()));
    }
}
