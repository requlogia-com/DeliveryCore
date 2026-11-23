package com.deliverycore.config;

import com.deliverycore.model.SelectionMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryAcceptedItemsTest {
    @Test
    void explicitItemsRoundTripWithProviderIds() {
        DeliveryConfigImpl config = parse("""
            deliveries:
              farm:
                accepted-items: [WHEAT, custom:produce, itemsadder:foods:tomato, nexo:pear, oraxen:corn]
            """);

        assertThat(parse(config.toYaml()).getDelivery("farm").orElseThrow().acceptedItems())
            .containsExactly("WHEAT", "custom:produce", "itemsadder:foods:tomato", "nexo:pear", "oraxen:corn");
    }

    @Test
    void allSelectsTheCategoryPoolWithoutChangingLegacySelection() {
        DeliveryConfigImpl config = parse("""
            deliveries:
              farm:
                item: {mode: all}
              mine:
                item: DIAMOND
            """);

        assertThat(config.getDelivery("farm").orElseThrow().item().mode()).isEqualTo(SelectionMode.ALL);
        assertThat(config.getDelivery("mine").orElseThrow().item().mode()).isEqualTo(SelectionMode.FIXED);
        assertThat(config.getDelivery("mine").orElseThrow().acceptedItems()).isEmpty();
    }

    @Test
    void rejectsInvalidExplicitListInsteadOfFallingBackToRandomItems() {
        assertThatThrownBy(() -> parse("""
            deliveries:
              farm:
                accepted-items: WHEAT
            """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accepted-items");
    }

    private DeliveryConfigImpl parse(String yaml) {
        return new DeliveryConfigImpl(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
