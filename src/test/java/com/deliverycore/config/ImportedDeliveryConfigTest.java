package com.deliverycore.config;

import com.deliverycore.service.CategoryServiceImpl;
import com.deliverycore.service.CategoryMilestones;
import com.deliverycore.service.DeliveryServiceImpl;
import com.deliverycore.service.SchedulerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportedDeliveryConfigTest {
    @TempDir Path folder;

    @Test
    void usesDeliveryCategoriesFromTheSameFile() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "general:\n  timezone: Europe/Paris\n");
        Files.writeString(folder.resolve("deliveries.yml"), """
                categories:
                  ciftcilik:
                    start: '01/12/2025 00:30:00'
                    end: '31/12/2025 23:59:00'
                    items: [WHEAT, CARROT]
                    specialRewards:
                      '3': ['eco give {player} 100']
                """);
        var config = new ConfigManagerImpl(folder.toString());
        config.loadAll();
        var delivery = config.getDeliveryConfig().getDelivery("ciftcilik").orElseThrow();
        var scheduler = new SchedulerServiceImpl();

        assertThat(config.getCategoryConfig().getCategory("ciftcilik").orElseThrow().items())
                .containsExactly("WHEAT", "CARROT");
        assertThat(delivery.winnerCount()).isEqualTo(3);
        assertThat(delivery.timezone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(scheduler.parseScheduleExpression(delivery.schedule().start(), delivery.timezone())
                .orElseThrow().toLocalDate()).isEqualTo(LocalDate.of(2025, 12, 1));
        var service = new DeliveryServiceImpl(config.getDeliveryConfig(),
                new CategoryServiceImpl(config.getCategoryConfig()), scheduler);
        assertThat(service.startEvent("ciftcilik", true).orElseThrow().getAcceptedItems())
                .containsExactly("WHEAT", "CARROT");
    }

    @Test
    void preservesPresentationAndMilestonesAcrossParsingAndRoundTrip() {
        var config = DeliveryConfigImpl.fromYaml("""
                categories:
                  ore:
                    start: '01/12/2025 00:30:00'
                    end: '31/12/2025 23:59:00'
                    items: [GOLD_INGOT, IRON_INGOT]
                    title: '&8Madencilik'
                    premium_perm: delivery.standard
                    send-webhooks: true
                    delivery: ['&fCevherler']
                    gui:
                      material: GOLD_INGOT
                      name: '&6Madencilik'
                      lore: ['&fTest']
                    specialRewards:
                      '1': ['eco give {player} 100']
                    checkpoints:
                      second:
                        amount: 300
                        rewards: ['eco give {player} 30']
                      first:
                        amount: 100
                        lore: ['&fÖdüller']
                        rewards: ['eco give {player} 10']
                        premium_rewards: ['eco give {player} 20']
                """);
        var imported = config.getImportedDeliveries().get("ore");

        assertThat(imported.title()).isEqualTo("&8Madencilik");
        assertThat(imported.icon()).isEqualTo("GOLD_INGOT");
        assertThat(imported.menuName()).isEqualTo("&6Madencilik");
        assertThat(imported.menuLore()).containsExactly("&fTest");
        assertThat(imported.itemLore()).containsExactly("&fCevherler");
        assertThat(imported.rankingRewards()).containsEntry(1, List.of("eco give {player} 100"));
        assertThat(imported.sendWebhooks()).isTrue();
        assertThat(DeliveryConfigImpl.fromYaml(config.toYaml()).getImportedDeliveries()).isEqualTo(config.getImportedDeliveries());

        var milestones = new CategoryMilestones(null);
        milestones.reload(folder.resolve("categories.yml").toFile(), config);
        assertThat(milestones.getMilestones("ore")).extracting(CategoryMilestones.Milestone::id)
                .containsExactly("first", "second");
        var first = milestones.getMilestones("ore").get(0);
        assertThat(first.lore()).containsExactly("&fÖdüller");
        assertThat(first.premiumPermission()).isEqualTo("delivery.standard");
        assertThat(first.commands()).containsExactly("eco give {player} 10");
        assertThat(first.premiumCommands()).containsExactly("eco give {player} 20");
    }

    @Test
    void reloadKeepsThePreviousDeliveriesWhenImportedItemsAreInvalid() throws Exception {
        Path deliveries = folder.resolve("deliveries.yml");
        Files.writeString(deliveries, """
                categories:
                  ore:
                    start: '01/12/2025 00:30:00'
                    end: '31/12/2025 23:59:00'
                    items: [IRON_INGOT]
                """);
        var config = new ConfigManagerImpl(folder.toString());
        config.loadAll();

        Files.writeString(deliveries, "categories:\n  ore:\n    items: ['Invalid item']\n");
        assertThat(config.reloadWithResult().success()).isFalse();
        assertThat(config.getDeliveryConfig().getDelivery("ore").orElseThrow().acceptedItems())
                .containsExactly("IRON_INGOT");
    }

    @Test
    void nativeDefinitionsAndPricesRemainAvailableAlongsideImportedData() throws Exception {
        Files.writeString(folder.resolve("categories.yml"), """
                categories:
                  native:
                    items:
                      WHEAT: 5
                """);
        Files.writeString(folder.resolve("deliveries.yml"), """
                deliveries:
                  native:
                    category: native
                    item: WHEAT
                    schedule:
                      start: 'every day 12:00'
                      end: '1h'
                categories:
                  native:
                    items: [CARROT]
                  imported:
                    start: '01/12/2025 00:30:00'
                    end: '31/12/2025 23:59:00'
                    items: [IRON_INGOT]
                """);
        var config = new ConfigManagerImpl(folder.toString());
        config.loadAll();

        assertThat(config.getDeliveryConfig().getImportedDeliveries()).containsOnlyKeys("imported");
        assertThat(config.getDeliveryConfig().getDelivery("native").orElseThrow().item().value()).isEqualTo("WHEAT");
        assertThat(config.getCategoryConfig().getCategory("native").orElseThrow().getPrice("WHEAT")).isEqualTo(5);
        assertThat(config.getCategoryConfig().getCategory("imported").orElseThrow().items()).containsExactly("IRON_INGOT");
        assertThat(config.isValid()).isTrue();
    }
}
