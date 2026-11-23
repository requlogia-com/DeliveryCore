package com.deliverycore.service;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optimize edilmiş Veri Yöneticisi.
 * - Asenkron otomatik kayıt (Auto-Save)
 * - Thread-safe veri yapıları
 * - Lag önleyici yapı
 */
public class DataManager {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    private final JavaPlugin plugin;
    private final DataStorage storage;
    private final Logger logger;
    private YamlConfiguration data;

    private boolean isDirty;
    private final Object lock = new Object();
    private final Object storageLock = new Object();

    public DataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storage = DataStorage.open(plugin.getDataFolder().toPath(), plugin.getConfig());
        loadData();
        startAutoSaveTask();
    }

    public void loadData() {
        synchronized (storageLock) {
            synchronized (lock) {
                try {
                    data = YamlDataStorage.parse(storage.load());
                    isDirty = false;
                } catch (IOException | InvalidConfigurationException e) {
                    throw new IllegalStateException("Unable to initialize DeliveryCore data storage", e);
                }
            }
        }
    }

    private void startAutoSaveTask() {
        long interval = plugin.getConfig().getInt("data.auto-save-interval", 5) * 1200L;
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> save(false), interval, interval);
        }
    }

    public void saveDataSync() {
        save(true);
    }

    private void save(boolean force) {
        synchronized (storageLock) {
            String snapshot;
            synchronized (lock) {
                if (!force && !isDirty) return;
                snapshot = data.saveToString();
                isDirty = false;
            }
            try {
                storage.save(snapshot);
            } catch (IOException e) {
                synchronized (lock) {
                    isDirty = true;
                }
                logger.log(Level.WARNING, "Unable to save DeliveryCore progress", e);
            }
        }
    }

    public void saveActiveEvent(ActiveEvent event) {
        synchronized (lock) {
            String path = "active-events." + event.getDeliveryName();

            data.set(path + ".category", event.getResolvedCategory());
            data.set(path + ".item", event.getResolvedItem());
            data.set(path + ".accepted-items", event.getAcceptedItems());
            data.set(path + ".winner-count", event.getWinnerCount());
            data.set(path + ".start-time", event.getStartTime() != null ? event.getStartTime().format(DATE_FORMAT) : null);
            data.set(path + ".end-time", event.getEndTime() != null ? event.getEndTime().format(DATE_FORMAT) : null);
            data.set(path + ".timezone", event.getTimezone() != null ? event.getTimezone().getId() : "Europe/Istanbul");

            ConfigurationSection deliveries = data.createSection(path + ".player-deliveries");
            for (Map.Entry<UUID, Integer> entry : event.getPlayerDeliveries().entrySet()) {
                deliveries.set(entry.getKey().toString(), entry.getValue());
            }

            isDirty = true;
        }
    }

    public void saveAllActiveEvents(List<ActiveEvent> events) {
        synchronized (lock) {
            data.set("active-events", null);
            isDirty = true;
            for (ActiveEvent event : events) {
                saveActiveEvent(event);
            }
        }
    }

    public List<SavedEventData> loadActiveEvents() {
        synchronized (lock) {
            List<SavedEventData> events = new ArrayList<>();
            ConfigurationSection section = data.getConfigurationSection("active-events");
            if (section == null) return events;

            for (String deliveryName : section.getKeys(false)) {
                try {
                    String path = "active-events." + deliveryName;
                    String category = data.getString(path + ".category");
                    String item = data.getString(path + ".item");
                    List<String> acceptedItems = data.getStringList(path + ".accepted-items");
                    if (acceptedItems.isEmpty()) {
                        if (item == null || item.isBlank()) {
                            throw new IllegalArgumentException("Saved event has no accepted items");
                        }
                        acceptedItems = List.of(item);
                    } else {
                        item = acceptedItems.get(0);
                    }
                    int winnerCount = data.getInt(path + ".winner-count", 1);
                    String startTimeStr = data.getString(path + ".start-time");
                    String endTimeStr = data.getString(path + ".end-time");
                    String timezoneStr = data.getString(path + ".timezone", "Europe/Istanbul");

                    ZoneId timezone = ZoneId.of(timezoneStr);
                    ZonedDateTime startTime = startTimeStr != null ? ZonedDateTime.parse(startTimeStr, DATE_FORMAT) : null;
                    ZonedDateTime endTime = endTimeStr != null ? ZonedDateTime.parse(endTimeStr, DATE_FORMAT) : null;

                    Map<UUID, Integer> playerDeliveries = new HashMap<>();
                    ConfigurationSection deliveriesSection = data.getConfigurationSection(path + ".player-deliveries");
                    if (deliveriesSection != null) {
                        for (String uuidStr : deliveriesSection.getKeys(false)) {
                            try {
                                UUID uuid = UUID.fromString(uuidStr);
                                int count = deliveriesSection.getInt(uuidStr);
                                playerDeliveries.put(uuid, count);
                            } catch (Exception ignored) {}
                        }
                    }

                    events.add(new SavedEventData(deliveryName, category, item, startTime, endTime, timezone,
                        playerDeliveries, List.copyOf(acceptedItems), winnerCount));
                } catch (Exception e) {
                    logger.warning("[DataManager] Etkinlik yüklenemedi: " + deliveryName + " - " + e.getMessage());
                }
            }
            return events;
        }
    }

    public void removeActiveEvent(String deliveryName) {
        synchronized (lock) {
            data.set("active-events." + deliveryName, null);
            isDirty = true;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OYUNCU İSTATİSTİKLERİ
    // ═══════════════════════════════════════════════════════════════

    public void updatePlayerStats(UUID playerUuid, String playerName, int deliveryCount) {
        synchronized (lock) {
            String path = "player-stats." + playerUuid.toString();
            int current = data.getInt(path + ".total-deliveries", 0);

            data.set(path + ".total-deliveries", current + deliveryCount);
            data.set(path + ".last-name", playerName);
            data.set(path + ".last-active", ZonedDateTime.now().format(DATE_FORMAT));

            isDirty = true;
        }
    }

    public Map<UUID, PlayerStats> getAllPlayerStats() {
        synchronized (lock) {
            Map<UUID, PlayerStats> stats = new HashMap<>();
            ConfigurationSection section = data.getConfigurationSection("player-stats");
            if (section == null) return stats;

            for (String uuidStr : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String path = "player-stats." + uuidStr;
                    String name = data.getString(path + ".last-name", "Unknown");
                    int total = data.getInt(path + ".total-deliveries", 0);
                    stats.put(uuid, new PlayerStats(uuid, name, total));
                } catch (Exception ignored) {}
            }
            return stats;
        }
    }

    public record SavedEventData(String deliveryName, String category, String item, ZonedDateTime startTime,
                                 ZonedDateTime endTime, ZoneId timezone, Map<UUID, Integer> playerDeliveries,
                                 List<String> acceptedItems, int winnerCount) {
        public SavedEventData(String deliveryName, String category, String item, ZonedDateTime startTime,
                              ZonedDateTime endTime, ZoneId timezone, Map<UUID, Integer> playerDeliveries) {
            this(deliveryName, category, item, startTime, endTime, timezone, playerDeliveries, List.of(item), 1);
        }

        public ActiveEvent toActiveEvent() {
            ActiveEvent event = new ActiveEvent(deliveryName, category, acceptedItems, startTime, endTime, timezone);
            event.setWinnerCount(winnerCount);
            playerDeliveries.forEach(event::recordDelivery);
            return event;
        }
    }
    public CategoryProgress getCategoryProgress(UUID player, String category) {
        synchronized (lock) {
            String path = categoryProgressPath(player, category);
            return new CategoryProgress(data.getLong(path + ".amount"), Set.copyOf(data.getStringList(path + ".claimed")));
        }
    }

    public void saveCategoryProgress(UUID player, String category, CategoryProgress progress) {
        synchronized (lock) {
            String path = categoryProgressPath(player, category);
            data.set(path + ".amount", progress.amount());
            data.set(path + ".claimed", new ArrayList<>(progress.claimed()));
            isDirty = true;
        }
    }

    private String categoryProgressPath(UUID player, String category) {
        String key = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(category.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "category-progress." + player + "." + key;
    }

    public record CategoryProgress(long amount, Set<String> claimed) {}
    public record PlayerStats(UUID uuid, String name, int totalDeliveries) {}
}
