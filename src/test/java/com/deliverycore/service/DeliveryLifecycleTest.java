package com.deliverycore.service;

import com.deliverycore.config.CategoryConfigImpl;
import com.deliverycore.config.DeliveryConfigImpl;
import com.deliverycore.model.DeliveryDefinition;
import com.deliverycore.model.RewardConfig;
import com.deliverycore.model.ScheduleConfig;
import com.deliverycore.model.SelectionConfig;
import com.deliverycore.model.WebhookConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryLifecycleTest {
    @Test
    void manualStartUsesExplicitItemsAndConfiguredMetadata() {
        var definition = new DeliveryDefinition("farm", false, false, SelectionConfig.fixed("farm"),
            SelectionConfig.random(), ZoneOffset.UTC, new ScheduleConfig("", ""), 1,
            RewardConfig.inventory("DIAMOND", 1), WebhookConfig.disabled(), List.of("WHEAT", "CARROT"));
        var deliveries = new DeliveryConfigImpl(List.of(definition));
        var categories = new CategoryServiceImpl(new CategoryConfigImpl(Map.of("farm", List.of())));
        var service = new DeliveryServiceImpl(deliveries, categories, new SchedulerServiceImpl());

        var event = service.startManualEvent("farm", Duration.ofMinutes(10), 3).orElseThrow();

        assertThat(event.getAcceptedItems()).containsExactly("WHEAT", "CARROT");
        assertThat(event.getEndTime()).isEqualTo(event.getStartTime().plusMinutes(10));
        assertThat(event.getWinnerCount()).isEqualTo(3);
        assertThat(service.startManualEvent("farm", Duration.ofMinutes(5), 2)).isEmpty();
        assertThat(service.getActiveEvent("farm")).containsSame(event);
    }
}
