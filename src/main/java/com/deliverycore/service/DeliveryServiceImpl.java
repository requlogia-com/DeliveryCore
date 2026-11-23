package com.deliverycore.service;

import com.deliverycore.config.DeliveryConfig;
import com.deliverycore.event.DeliveryDeliverEvent;
import com.deliverycore.event.DeliveryEndEvent;
import com.deliverycore.event.DeliveryStartEvent;
import com.deliverycore.model.Category;
import com.deliverycore.model.DeliveryDefinition;
import com.deliverycore.model.SelectionMode;
import com.deliverycore.model.Winner;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DeliveryServiceImpl implements DeliveryService {

    private static final Logger LOGGER = Logger.getLogger(DeliveryServiceImpl.class.getName());

    private DeliveryConfig deliveryConfig;
    private CategoryService categoryService;
    private final SchedulerService schedulerService;
    private final Map<String, ActiveEvent> activeEvents = new ConcurrentHashMap<>();

    public DeliveryServiceImpl(DeliveryConfig deliveryConfig, CategoryService categoryService, SchedulerService schedulerService) {
        this.deliveryConfig = deliveryConfig;
        this.categoryService = categoryService;
        this.schedulerService = schedulerService;
    }

    public void reloadConfiguration(DeliveryConfig deliveryConfig, CategoryService categoryService) {
        this.deliveryConfig = deliveryConfig;
        this.categoryService = categoryService;
    }

    @Override
    public Optional<ActiveEvent> startEvent(String deliveryName) {
        return startEvent(deliveryName, false);
    }

    @Override
    public Optional<ActiveEvent> startEvent(String deliveryName, boolean force) {
        return startEvent(deliveryName, force, null, 0);
    }

    @Override
    public Optional<ActiveEvent> startManualEvent(String deliveryName, Duration duration, int winnerCount) {
        if (duration.isZero() || duration.isNegative()) {
            return Optional.empty();
        }
        return startEvent(deliveryName, true, duration, winnerCount);
    }

    private Optional<ActiveEvent> startEvent(String deliveryName, boolean force, Duration manualDuration,
                                             int winnerCount) {
        Optional<DeliveryDefinition> defOpt = deliveryConfig.getDelivery(deliveryName);
        if (defOpt.isEmpty()) {
            LOGGER.warning("Delivery not found: " + deliveryName);
            return Optional.empty();
        }

        DeliveryDefinition def = defOpt.get();
        if (!force && !def.enabled()) {
            LOGGER.info("Delivery is disabled: " + deliveryName);
            return Optional.empty();
        }

        try {
            Category category = categoryService.resolveCategory(def.category().mode(), def.category().value());
            if (category == null) {
                LOGGER.warning("Category could not be resolved for delivery: " + deliveryName);
                return Optional.empty();
            }

            List<String> acceptedItems = def.acceptedItems();
            if (acceptedItems.isEmpty() && def.item().mode() == SelectionMode.ALL) {
                acceptedItems = category.items();
            }
            if (acceptedItems.isEmpty()) {
                String item = categoryService.resolveItem(category, def.item().mode(), def.item().value());
                if (item != null && !item.isBlank()) {
                    acceptedItems = List.of(item);
                }
            }
            if (acceptedItems.isEmpty()) {
                LOGGER.warning("Item could not be resolved for delivery: " + deliveryName);
                return Optional.empty();
            }

            ZonedDateTime now = ZonedDateTime.now(def.timezone());
            ZonedDateTime endTime = manualDuration != null
                ? now.plus(manualDuration)
                : schedulerService.getEventEndTime(def, now, force).orElse(now.plusHours(1));

            ActiveEvent event = new ActiveEvent(deliveryName, category.name(), acceptedItems, now, endTime, def.timezone());
            event.setWinnerCount(winnerCount > 0 ? winnerCount : def.winnerCount());
            if (activeEvents.putIfAbsent(deliveryName, event) != null) {
                return Optional.empty();
            }
            if (Bukkit.getServer() != null) {
                Bukkit.getPluginManager().callEvent(new DeliveryStartEvent(event));
            }
            LOGGER.info("Started delivery: " + deliveryName + " [" + category.name() + ", " + acceptedItems + "]");
            return Optional.of(event);
        } catch (Exception e) {
            LOGGER.warning("Failed to start delivery " + deliveryName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<Winner> endEvent(String deliveryName) {
        ActiveEvent event = activeEvents.remove(deliveryName);
        if (event == null) return List.of();

        int winnerCount = event.getWinnerCount();
        if (winnerCount <= 0) {
            winnerCount = deliveryConfig.getDelivery(deliveryName).map(DeliveryDefinition::winnerCount).orElse(1);
        }

        List<Winner> winners = calculateWinners(event, winnerCount, uuid -> {
            Player p = Bukkit.getServer() == null ? null : Bukkit.getPlayer(uuid);
            return p != null ? p.getName() : "Player_" + uuid.toString().substring(0, 8);
        });
        if (Bukkit.getServer() != null) {
            Bukkit.getPluginManager().callEvent(new DeliveryEndEvent(event, winners));
        }
        LOGGER.info("Ended delivery: " + deliveryName + " with " + winners.size() + " winners");
        return winners;
    }

    /**
     * Teslimat kaydeder.
     * Güncelleme: Artık Event fırlatıyor ve iptal edilirse işlem yapmıyor.
     */
    @Override
    public boolean recordDelivery(UUID playerUuid, String deliveryName, int amount) {
        return recordDelivery(playerUuid, deliveryName, amount, () -> true);
    }

    public boolean recordDelivery(UUID playerUuid, String deliveryName, int amount,
                                  java.util.function.BooleanSupplier consumeItems) {
        ActiveEvent event = activeEvents.get(deliveryName);
        if (event == null || !event.isActive() || amount <= 0) return false;

        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            DeliveryDeliverEvent deliverEvent = new DeliveryDeliverEvent(player, deliveryName, event.getResolvedItem(), amount);
            Bukkit.getPluginManager().callEvent(deliverEvent);

            if (deliverEvent.isCancelled()) {
                if (deliverEvent.getCancelReason() != null) {
                    player.sendMessage("§cTeslimat iptal edildi: " + deliverEvent.getCancelReason());
                }
                return false;
            }
        }

        if (activeEvents.get(deliveryName) != event || !event.isActive() || !consumeItems.getAsBoolean()) {
            return false;
        }
        event.recordDelivery(playerUuid, amount);
        return true;
    }

    @Override
    public Optional<ActiveEvent> getActiveEvent(String deliveryName) {
        return Optional.ofNullable(activeEvents.get(deliveryName));
    }

    @Override
    public List<ActiveEvent> getAllActiveEvents() {
        return List.copyOf(activeEvents.values());
    }

    public void restoreEvent(ActiveEvent event) {
        if (event != null && event.getDeliveryName() != null) {
            activeEvents.put(event.getDeliveryName(), event);
            LOGGER.info("Restored event: " + event.getDeliveryName());
        }
    }

    @Override
    public List<Winner> calculateWinners(ActiveEvent event, int winnerCount, PlayerNameResolver nameResolver) {
        Map<UUID, Integer> deliveries = event.getPlayerDeliveries();
        if (deliveries.isEmpty()) return List.of();

        List<Map.Entry<UUID, Integer>> sorted = deliveries.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(winnerCount)
                .toList();

        List<Winner> winners = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : sorted) {
            winners.add(new Winner(entry.getKey(), nameResolver.resolve(entry.getKey()), entry.getValue(), rank++));
        }
        return winners;
    }
}
