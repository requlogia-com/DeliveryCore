package com.deliverycore;

import com.deliverycore.command.CommandHandler;
import com.deliverycore.command.DeliverCommand;
import com.deliverycore.config.ConfigManager;
import com.deliverycore.config.ConfigManagerImpl;
import com.deliverycore.gui.DeliveryGUI;
import com.deliverycore.item.DeliveryItemRegistry;
import com.deliverycore.event.DeliveryStartEvent;
import com.deliverycore.event.DeliveryEndEvent;
import com.deliverycore.placeholder.DeliveryExpansion;
import com.deliverycore.service.ActiveEvent;
import com.deliverycore.service.CategoryMilestones;
import com.deliverycore.service.InventoryDelivery;
import com.deliverycore.service.ImportedDeliveryRewards;
import org.bukkit.inventory.Inventory;
import com.deliverycore.placeholder.PlaceholderEngine;
import com.deliverycore.placeholder.PlaceholderEngineImpl;
import com.deliverycore.reward.PendingRewardStore;
import com.deliverycore.reward.PendingRewardStoreImpl;
import com.deliverycore.reward.RewardService;
import com.deliverycore.reward.RewardServiceImpl;
import com.deliverycore.service.CategoryService;
import com.deliverycore.service.CategoryServiceImpl;
import com.deliverycore.service.DataManager;
import com.deliverycore.service.DeliveryService;
import com.deliverycore.service.DeliveryServiceImpl;
import com.deliverycore.service.MessageService;
import com.deliverycore.service.MessageServiceImpl;
import com.deliverycore.service.SchedulerService;
import com.deliverycore.service.SchedulerServiceImpl;
import com.deliverycore.util.LoggingService;
import com.deliverycore.webhook.WebhookService;
import com.deliverycore.webhook.WebhookServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.block.Chest;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

/**
 * Main plugin class for DeliveryCore.
 */
public class DeliveryCorePlugin extends JavaPlugin implements Listener, TabCompleter {

    private ConfigManager configManager;
    private PlaceholderEngine placeholderEngine;
    private CategoryService categoryService;
    private SchedulerService schedulerService;
    private DeliveryService deliveryService;
    private RewardService rewardService;
    private WebhookServiceImpl webhookService;
    private MessageService messageService;
    private PendingRewardStore pendingRewardStore;
    private CommandHandler commandHandler;
    private DeliverCommand deliverCommand;
    private DeliveryGUI deliveryGUI;
    private ScheduledExecutorService executorService;
    private LoggingService loggingService;
    private DataManager dataManager;
    private DeliveryItemRegistry itemRegistry;
    private CategoryMilestones categoryMilestones;
    private Runnable unregisterPlaceholders = () -> {};
    private boolean initialized;
    private final java.util.Map<String, org.bukkit.scheduler.BukkitTask> expiryTasks = new java.util.HashMap<>();

    private String currentLanguage = "tr";
    private Sound deliverSound = Sound.ENTITY_PLAYER_LEVELUP;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        getLogger().info("");
        getLogger().info("  DeliveryCore v1.0.0 (Optimized)");
        getLogger().info("  Teslimat Etkinlik Sistemi");
        getLogger().info("");

        try {
            if (!getDataFolder().exists()) {
                boolean ignored = getDataFolder().mkdirs();
            }

            saveDefaultConfigs();
            executorService = Executors.newScheduledThreadPool(2);

            dataManager = new DataManager(this);
            itemRegistry = new DeliveryItemRegistry(this);
            categoryMilestones = new CategoryMilestones(dataManager);

            initializeServices();
            loadConfigurations();
            registerListeners();
            loadSavedEvents();
            resumeActiveEvents();
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                unregisterPlaceholders = DeliveryExpansion.registerFor(this);
            }

            deliveryGUI.loadItemsConfig(getDataFolder());

            initialized = true;
            long loadTime = System.currentTimeMillis() - startTime;
            getLogger().info("Basariyla yuklendi! (" + loadTime + "ms)");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable DeliveryCore", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("DeliveryCore kapatiliyor...");

        unregisterPlaceholders.run();
        if (initialized) {
            dataManager.saveAllActiveEvents(deliveryService.getAllActiveEvents());
            dataManager.saveDataSync();
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }

        getLogger().info("Kapatildi!");
    }

    private void saveDefaultConfigs() {
        saveResourceIfNotExists("config.yml");
        saveResourceIfNotExists("categories.yml");
        saveResourceIfNotExists("deliveries.yml");

        // items.yml'yi HER ZAMAN güncelle (yeni eşyalar için)
        File itemsFile = new File(getDataFolder(), "items.yml");
        if (itemsFile.exists()) {
            itemsFile.delete();
        }
        saveResource("items.yml", false);

        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        saveResourceIfNotExists("lang/tr.yml");
        saveResourceIfNotExists("lang/en.yml");
    }

    private void saveResourceIfNotExists(String resourcePath) {
        File file = new File(getDataFolder(), resourcePath);
        if (!file.exists()) {
            saveResource(resourcePath, false);
        }
    }

    private void initializeServices() {
        configManager = new ConfigManagerImpl(getDataFolder().getAbsolutePath());
        placeholderEngine = new PlaceholderEngineImpl();
        loggingService = new LoggingService(getLogger(), placeholderEngine);
        pendingRewardStore = new PendingRewardStoreImpl();

        SchedulerServiceImpl schedulerImpl = new SchedulerServiceImpl(executorService);
        schedulerImpl.setEventStartCallback(name -> {
            var window = schedulerImpl.getScheduledEventInfos().get(name);
            Bukkit.getScheduler().runTask(this, () -> {
                if (window != null && schedulerImpl.getScheduledEventInfos().get(name) == window
                        && window.scheduledEnd().isAfter(java.time.ZonedDateTime.now())) {
                    handleScheduledEventEnd(name);
                    handleEventStart(name);
                }
            });
        });
        schedulerImpl.setEventEndCallback(name -> Bukkit.getScheduler().runTask(this, () -> handleScheduledEventEnd(name)));
        schedulerService = schedulerImpl;

        webhookService = new WebhookServiceImpl(placeholderEngine);
        rewardService = new RewardServiceImpl(pendingRewardStore, placeholderEngine);

        getLogger().info("Servisler hazir.");
    }

    private void handleEventStart(String deliveryName) {
        if (deliveryService != null) {
            deliveryService.startEvent(deliveryName).ifPresent(event -> {
                String itemTR = deliveryGUI.getAcceptedItemDisplay(event);
                String catTR = deliveryGUI.getCategoryDisplayName(event.getResolvedCategory());
                String delTR = deliveryGUI.getDeliveryDisplayName(deliveryName);

                // Console log
                getLogger().info("[ETKINLIK BASLADI] " + delTR + " | Kategori: " + catTR + " | Esya: " + itemTR);

                // Broadcast
                String msg = "§e§lD§6elivery§e§lC§6ore §8» §a" + delTR + " §7basladi! §eIstenen: §f" + itemTR;
                Bukkit.broadcastMessage(msg);

                // Title
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle("§a§l" + delTR.toUpperCase(), "§7Istenen: §e" + itemTR, 10, 70, 20);
                    try { p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f); } catch (Exception ignored) {}
                }

                // Webhook
                sendWebhook(deliveryName, "start", itemTR, catTR);
            });
        }
    }

    private void handleScheduledEventEnd(String deliveryName) {
        deliveryService.getActiveEvent(deliveryName).filter(event -> !event.isActive())
                .ifPresent(event -> handleEventEnd(deliveryName));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDeliveryStart(DeliveryStartEvent event) {
        if (deliveryService.getActiveEvent(event.getDeliveryName()).orElse(null) == event.getActiveEvent()) {
            dataManager.saveActiveEvent(event.getActiveEvent());
            scheduleExpiry(event.getActiveEvent());
            notifyImportedWebhook(event.getActiveEvent(), true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDeliveryEnd(DeliveryEndEvent event) {
        if (deliveryService.getActiveEvent(event.getDeliveryName()).isEmpty()) {
            var task = expiryTasks.remove(event.getDeliveryName());
            if (task != null) task.cancel();
            dataManager.removeActiveEvent(event.getDeliveryName());
            notifyImportedWebhook(event.getActiveEvent(), false);
            ImportedDeliveryRewards.distribute(configManager.getDeliveryConfig(), event, rewardService);
        }
    }

    private void handleEventEnd(String deliveryName) {
        if (deliveryService != null) {
            var activeEvent = deliveryService.getActiveEvent(deliveryName);
            if (activeEvent.isEmpty()) return;
            String itemTR = deliveryGUI.getAcceptedItemDisplay(activeEvent.orElseThrow());
            String category = activeEvent.map(e -> e.getResolvedCategory()).orElse("unknown");
            String catTR = deliveryGUI.getCategoryDisplayName(category);
            String delTR = deliveryGUI.getDeliveryDisplayName(deliveryName);

            var winners = deliveryService.endEvent(deliveryName);

            // Console log
            getLogger().info("[ETKINLIK BITTI] " + delTR + " | Kazananlar: " + winners.size());

            // Broadcast
            String msg = "§e§lD§6elivery§e§lC§6ore §8» §c" + delTR + " §7sona erdi!";
            Bukkit.broadcastMessage(msg);

            if (!winners.isEmpty()) {
                Bukkit.broadcastMessage("§e§lD§6elivery§e§lC§6ore §8» §6Kazananlar:");
                int rank = 1;
                for (var winner : winners) {
                    String color = rank == 1 ? "§6" : rank == 2 ? "§f" : rank == 3 ? "§c" : "§7";
                    Bukkit.broadcastMessage("  " + color + rank + ". §f" + winner.playerName() + " §8- §e" + winner.deliveryCount() + " §7teslimat");
                    rank++;
                    if (rank > 3) break;
                }
            }

            // Title
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§c§lETKINLIK BITTI!", "§7" + delTR, 10, 70, 20);
            }

            // Webhook - kazananlarla birlikte
            sendWebhookWithWinners(deliveryName, itemTR, catTR, winners);
        }
    }

    private void sendWebhookWithWinners(String deliveryName, String itemTR, String categoryTR,
                                         java.util.List<com.deliverycore.model.Winner> winners) {
        if (isImportedDelivery(deliveryName)) return;
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.exists()) return;

            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            boolean enabled = config.getBoolean("webhook.enabled", false);
            String url = config.getString("webhook.url", "");

            if (!enabled || url == null || url.isEmpty() || url.contains("YOUR_WEBHOOK")) return;

            String delTR = deliveryGUI.getDeliveryDisplayName(deliveryName);

            // Kazananlar listesi oluştur
            StringBuilder winnersText = new StringBuilder();
            if (!winners.isEmpty()) {
                winnersText.append("\\n\\n**🏆 Kazananlar:**");
                int rank = 1;
                for (var winner : winners) {
                    String medal = rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : rank + ".";
                    winnersText.append("\\n").append(medal).append(" **").append(winner.playerName())
                              .append("** - ").append(winner.deliveryCount()).append(" teslimat");
                    rank++;
                    if (rank > 5) break;
                }
            } else {
                winnersText.append("\\n\\n*Kimse katılmadı*");
            }

            String desc = "**Eşya:** " + itemTR + "\\n**Kategori:** " + categoryTR + winnersText;
            String json = "{\"embeds\":[{\"title\":\"🏆 " + delTR + " Bitti!\",\"description\":\"" + desc + "\",\"color\":16766720,\"footer\":{\"text\":\"DeliveryCore\"}}]}";

            // Async webhook gönder
            final String finalUrl = url;
            final String finalJson = json;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                sendWebhookRequest(finalUrl, finalJson, "END");
            });
        } catch (Exception e) {
            getLogger().warning("[WEBHOOK] Hata: " + e.getMessage());
        }
    }

    /**
     * Etkinlik başlangıcı için webhook gönderir
     */
    private void sendWebhook(String deliveryName, String type, String itemTR, String categoryTR) {
        if (isImportedDelivery(deliveryName)) return;
        try {
            // Global webhook URL'yi al
            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.exists()) return;

            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            boolean enabled = config.getBoolean("webhook.enabled", false);
            String url = config.getString("webhook.url", "");

            if (!enabled || url == null || url.isEmpty() || url.contains("YOUR_WEBHOOK")) return;

            String delTR = deliveryGUI.getDeliveryDisplayName(deliveryName);
            String title = "📦 " + delTR + " Başladı!";
            String desc = "**Teslim Edilecek:** " + itemTR + "\\n**Kategori:** " + categoryTR;

            String json = "{\"embeds\":[{\"title\":\"" + title + "\",\"description\":\"" + desc + "\",\"color\":65280,\"footer\":{\"text\":\"DeliveryCore\"}}]}";

            // Async webhook gönder
            final String finalUrl = url;
            final String finalJson = json;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                sendWebhookRequest(finalUrl, finalJson, "START");
            });
        } catch (Exception e) {
            getLogger().warning("[WEBHOOK] Hata: " + e.getMessage());
        }
    }

    private boolean isImportedDelivery(String deliveryName) {
        return configManager.getDeliveryConfig().getImportedDeliveries().containsKey(deliveryName);
    }

    private void notifyImportedWebhook(ActiveEvent event, boolean start) {
        var imported = configManager.getDeliveryConfig().getImportedDeliveries().get(event.getDeliveryName());
        if (imported == null || !imported.sendWebhooks()) return;
        if (!getConfig().getBoolean(start ? "settings.start-webhook" : "settings.end-webhook")) return;
        String category = ChatColor.stripColor(deliveryGUI.getCategoryDisplayName(event.getResolvedCategory()));
        Duration remaining = start ? Duration.between(Instant.now(), event.getEndTime().toInstant()) : Duration.ZERO;
        String time = ChatColor.stripColor(deliveryGUI.formatRemaining(remaining));
        var file = getDataFolder().toPath().resolve("webhooks.yml");
        webhookService.sendImportedNotification(file, start, category, time).thenAccept(result -> {
            if (!result.success()) getLogger().warning("Delivery webhook: " + result.message());
        });
    }

    /**
     * Discord Webhook gönderici - Tüm sunucularda çalışır
     * TLSv1.2 ve TLSv1.3 destekler, sistem proxy ayarlarını kullanır
     */
    private void sendWebhookRequest(String urlString, String jsonPayload, String tag) {
        try {
            // URL doğrula
            java.net.URL url = new java.net.URL(urlString);

            // Sistem varsayılan SSL context'ini kullan (en güvenilir yöntem)
            javax.net.ssl.HttpsURLConnection connection = (javax.net.ssl.HttpsURLConnection) url.openConnection();

            // TLS 1.2/1.3 zorla
            try {
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, null, null);
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
            } catch (Exception e) {
                // Varsayılan SSL kullan
            }

            // Bağlantı ayarları
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 DeliveryCore/1.0");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            // Bağlan
            connection.connect();

            // JSON gönder
            try (java.io.OutputStream os = connection.getOutputStream();
                 java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(jsonPayload);
                writer.flush();
            }

            // Yanıt al
            int responseCode = connection.getResponseCode();

            if (responseCode == 200 || responseCode == 204 || responseCode == 201) {
                getLogger().info("[WEBHOOK] Gonderildi (" + tag + ")");
            } else {
                getLogger().warning("[WEBHOOK] Hata: " + responseCode);
            }

            connection.disconnect();

        } catch (java.net.SocketTimeoutException e) {
            getLogger().warning("[WEBHOOK] Zaman asimi - Discord'a ulasilamiyor");
        } catch (java.io.IOException e) {
            getLogger().warning("[WEBHOOK] Baglanti hatasi: " + e.getMessage());
        } catch (Exception e) {
            getLogger().warning("[WEBHOOK] Hata: " + e.getMessage());
        }
    }

    private void loadConfigurations() {
        configManager.loadAll();
        reloadMilestones();

        configManager.validate().forEach(error ->
            getLogger().warning(String.format("[%s] %s - %s: %s",
                error.severity(),
                error.file(),
                error.field() != null ? error.field() : "genel",
                error.message())));

        // Dil ayarını yükle
        loadLanguageSetting();
        loadDeliverySound();

        initializeConfigDependentServices();
    }

    /**
     * config.yml'den dil ayarını yükler
     */
    private void loadLanguageSetting() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            if (configFile.exists()) {
                var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
                String lang = config.getString("general.language", config.getString("settings.lang", "tr"));
                currentLanguage = lang.toLowerCase().trim();
                if (!currentLanguage.equals("tr") && !currentLanguage.equals("en")) {
                    currentLanguage = "tr";
                }
                getLogger().info("Dil ayari: " + currentLanguage);
            }
        } catch (Exception e) {
            currentLanguage = "tr";
            getLogger().warning("Dil ayari yuklenemedi, varsayilan: tr");
        }
    }

    /**
     * Mevcut dil ayarını döndürür
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    private void loadDeliverySound() {
        String name = getConfig().getString("settings.deliver-sound", "ENTITY_PLAYER_LEVELUP");
        try {
            deliverSound = Sound.class.cast(Sound.class.getField(name.trim().toUpperCase(java.util.Locale.ROOT)).get(null));
        } catch (ReflectiveOperationException | ClassCastException e) {
            deliverSound = Sound.ENTITY_PLAYER_LEVELUP;
            getLogger().warning("Unknown settings.deliver-sound: " + name);
        }
    }

    private void initializeConfigDependentServices() {
        categoryService = new CategoryServiceImpl(configManager.getCategoryConfig());

        deliveryService = new DeliveryServiceImpl(
            configManager.getDeliveryConfig(),
            categoryService,
            schedulerService
        );

        messageService = new MessageServiceImpl(
            configManager.getLanguageConfig(),
            placeholderEngine,
            this::sendMessageToPlayer,
            getLogger()
        );

        commandHandler = new CommandHandler(
            configManager,
            this::checkPermission,
            this::sendMessageToPlayer,
            getLogger()
        );

        deliveryGUI = new DeliveryGUI(configManager, deliveryService);
        deliveryGUI.setLanguageSupplier(this::getCurrentLanguage);
        deliveryGUI.setMilestones(categoryMilestones);
        deliveryGUI.setScheduler(schedulerService);
        deliveryGUI.setInventoryAction(this::deliverFromInventory);
        deliveryGUI.setItemRegistry(itemRegistry);
        loadGUISettings();

        deliverCommand = new DeliverCommand(
            configManager,
            this::checkPermission,
            this::sendMessageToPlayer,
            getLogger(),
            deliveryGUI
        );

        commandHandler.setDeliveryService(deliveryService);
        commandHandler.setLanguageSupplier(this::getCurrentLanguage);
        commandHandler.setReloadCallback(this::reloadAllSettings);
        commandHandler.setManualEndScheduler(this::scheduleManualEnd);
        commandHandler.setWebhookTester(this::testWebhook);
        deliverCommand.setDeliveryService(deliveryService);
        deliverCommand.setLanguageSupplier(this::getCurrentLanguage);
        deliverCommand.setInventorySubmission((player, event, count) ->
                submitInventory(player, player.getInventory(), event, count));
    }

    /**
     * Manuel başlatılan etkinlik için otomatik bitiş zamanlayıcısı
     */
    private void scheduleManualEnd(String deliveryName, Long durationMinutes) {
        var expected = deliveryService.getActiveEvent(deliveryName).orElse(null);
        if (expected != null) scheduleExpiry(expected);
    }

    private void scheduleExpiry(ActiveEvent expected) {
        String name = expected.getDeliveryName();
        long millis = java.time.Duration.between(java.time.Instant.now(), expected.getEndTime().toInstant()).toMillis();
        var task = Bukkit.getScheduler().runTaskLater(this, () -> {
            expiryTasks.remove(name);
            if (deliveryService.getActiveEvent(name).orElse(null) == expected) {
                if (expected.isActive()) scheduleExpiry(expected);
                else handleEventEnd(name);
            }
        }, Math.max(1L, millis / 50L + 1L));
        var previous = expiryTasks.put(name, task);
        if (previous != null) previous.cancel();
    }

    /**
     * Webhook test fonksiyonu
     */
    private void testWebhook() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                getLogger().warning("[WEBHOOK TEST] config.yml bulunamadi!");
                return;
            }

            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            String url = config.getString("webhook.url", "");

            getLogger().info("[WEBHOOK TEST] URL uzunluk: " + url.length());

            if (url.isEmpty() || url.contains("YOUR_WEBHOOK")) {
                getLogger().warning("[WEBHOOK TEST] Webhook URL ayarlanmamis!");
                return;
            }

            String json = "{\"embeds\":[{\"title\":\"🧪 DeliveryCore Test\",\"description\":\"Webhook baglantisi basarili!\\n\\nBu bir test mesajidir.\",\"color\":65280,\"footer\":{\"text\":\"DeliveryCore v1.0.0\"}}]}";

            getLogger().info("[WEBHOOK TEST] Gonderiliyor...");

            final String finalUrl = url;
            final String finalJson = json;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                sendWebhookRequest(finalUrl, finalJson, "TEST");
            });
        } catch (Exception e) {
            getLogger().warning("[WEBHOOK TEST] Hata: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tüm ayarları yeniden yükler (reload komutu için)
     */
    private void reloadMilestones() {
        categoryMilestones.reload(new File(getDataFolder(), "categories.yml"), configManager.getDeliveryConfig());
    }

    private void reloadAllSettings() {
        reloadConfig();
        dataManager.reloadAutoSaveInterval();
        itemRegistry.reload();
        reloadMilestones();
        categoryService = new CategoryServiceImpl(configManager.getCategoryConfig());
        ((DeliveryServiceImpl) deliveryService).reloadConfiguration(configManager.getDeliveryConfig(), categoryService);
        ((SchedulerServiceImpl) schedulerService).getScheduledEventInfos().keySet()
                .forEach(schedulerService::cancelScheduledEvent);
        resumeActiveEvents();
        // Dil ayarını yeniden yükle
        loadLanguageSetting();
        loadDeliverySound();

        // items.yml'yi yeniden yükle
        deliveryGUI.loadItemsConfig(getDataFolder());

        // GUI ayarlarını yeniden yükle
        loadGUISettings();

        getLogger().info("Tum ayarlar yeniden yuklendi. Dil: " + currentLanguage);
    }


    private void loadGUISettings() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            if (configFile.exists()) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

                deliveryGUI.loadLayouts(config);

                // Kategori display names
                ConfigurationSection catDisplaySection = config.getConfigurationSection("category-display-names");
                if (catDisplaySection != null) {
                    for (String key : catDisplaySection.getKeys(false)) {
                        String displayName = catDisplaySection.getString(key);
                        if (displayName != null && !displayName.isEmpty()) {
                            deliveryGUI.setCategoryDisplayName(key, displayName);
                        }
                    }
                }

                // Teslimat display names
                ConfigurationSection delDisplaySection = config.getConfigurationSection("delivery-display-names");
                if (delDisplaySection != null) {
                    for (String key : delDisplaySection.getKeys(false)) {
                        String displayName = delDisplaySection.getString(key);
                        if (displayName != null && !displayName.isEmpty()) {
                            deliveryGUI.setDeliveryDisplayName(key, displayName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("GUI ayarlari yuklenemedi: " + e.getMessage());
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void resumeActiveEvents() {
        if (schedulerService != null) {
            schedulerService.resumeActiveEvents();
        }

        if (configManager != null && configManager.getDeliveryConfig() != null) {
            var enabledDeliveries = configManager.getDeliveryConfig().getEnabledDeliveries();
            if (!enabledDeliveries.isEmpty()) {
                getLogger().info("Zamanlanmis teslimatlar:");
                enabledDeliveries.forEach(delivery -> {
                    try {
                        schedulerService.scheduleEvent(delivery);
                        getLogger().info("  + " + delivery.name());
                    } catch (Exception e) {
                        getLogger().warning("  x " + delivery.name() + ": " + e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Sunucu kapanmadan önce kaydedilmiş aktif etkinlikleri yükler.
     */
    private void loadSavedEvents() {
        if (dataManager == null || deliveryService == null) return;

        var savedEvents = dataManager.loadActiveEvents();
        if (savedEvents.isEmpty()) return;

        getLogger().info("Kaydedilmis etkinlikler yukleniyor...");

        for (var savedData : savedEvents) {
            try {
                // Etkinlik hala geçerli mi kontrol et (bitiş zamanı geçmemiş mi)
                if (savedData.endTime() != null && savedData.endTime().isBefore(java.time.ZonedDateTime.now())) {
                    getLogger().info("  - " + savedData.deliveryName() + " (suresi dolmus, atlanıyor)");
                    dataManager.removeActiveEvent(savedData.deliveryName());
                    continue;
                }

                // ActiveEvent oluştur ve servise ekle
                var activeEvent = savedData.toActiveEvent();
                ((DeliveryServiceImpl) deliveryService).restoreEvent(activeEvent);
                scheduleExpiry(activeEvent);

                getLogger().info("  + " + savedData.deliveryName() + " (yuklendi, " +
                    savedData.playerDeliveries().size() + " oyuncu verisi)");

            } catch (Exception e) {
                getLogger().warning("  x " + savedData.deliveryName() + ": " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (rewardService != null && rewardService.hasPendingRewards(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                deliverPendingRewardsToPlayer(player);
            }, 20L);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (!(event.getView().getTopInventory().getHolder() instanceof DeliveryGUI.Menu menu)) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= 0 && event.getRawSlot() < menu.getInventory().getSize()) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() == menu) {
                    menu.click(player, event.getRawSlot());
                }
            });
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DeliveryGUI.Menu
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    // Sandık tıklama event'i
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!deliveryGUI.isInChestSelectionMode(player.getUniqueId())) return;

        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        // Koruma kontrolü - oyuncunun bu bloğa erişim hakkı var mı?
        // Bu, claim/ada pluginleri ile uyumlu çalışır (WorldGuard, GriefPrevention, ASkyBlock vb.)
        org.bukkit.block.Block block = event.getClickedBlock();
        if (!canAccessChest(player, block)) {
            player.sendMessage("§e§lD§6elivery§e§lC§6ore §8» §cBu sandığa erişim izniniz yok!");
            deliveryGUI.cancelChestSelection(player.getUniqueId());
            return;
        }

        // Sandık kontrolü - normal ve çift sandık
        org.bukkit.block.BlockState state = event.getClickedBlock().getState();
        org.bukkit.inventory.Inventory chestInventory = null;

        if (state instanceof Chest chest) {
            chestInventory = chest.getInventory();
        } else if (event.getClickedBlock().getType() == org.bukkit.Material.CHEST ||
                   event.getClickedBlock().getType() == org.bukkit.Material.TRAPPED_CHEST) {
            // Blok tipi sandık ama state farklı olabilir
            if (state instanceof org.bukkit.block.Container container) {
                chestInventory = container.getInventory();
            }
        }

        if (chestInventory == null) {
            player.sendMessage("§e§lD§6elivery§e§lC§6ore §8» §cBu bir sandık değil!");
            return;
        }

        event.setCancelled(true);

        String deliveryName = deliveryGUI.getChestSelectionDelivery(player.getUniqueId());
        deliveryGUI.cancelChestSelection(player.getUniqueId());

        var activeEvent = deliveryService.getActiveEvent(deliveryName).orElse(null);
        if (activeEvent == null) {
            player.sendMessage("§e§lD§6elivery§e§lC§6ore §8» §cTeslimat artık aktif değil!");
            return;
        }

        deliverFromRealChestInventory(player, chestInventory, activeEvent);
    }

    /**
     * Oyuncunun sandığa erişim hakkı olup olmadığını kontrol eder.
     * - Tabela kilidi kontrolü (LWC, Lockette, BlockLocker vb.)
     * - Claim/ada koruma kontrolü (WorldGuard, GriefPrevention, ASkyBlock vb.)
     */
    private boolean canAccessChest(Player player, org.bukkit.block.Block block) {
        // Admin bypass
        if (player.hasPermission("deliverycore.bypass.protection")) {
            return true;
        }

        // Tabela kilidi kontrolü - sandığın yanındaki tabelaları kontrol et
        if (hasSignLock(block, player)) {
            return false;
        }

        // PlayerInteractEvent ile koruma kontrolü
        // Bu, WorldGuard, GriefPrevention, ASkyBlock gibi pluginler tarafından dinlenir
        try {
            org.bukkit.event.player.PlayerInteractEvent testEvent =
                new org.bukkit.event.player.PlayerInteractEvent(
                    player,
                    org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                    player.getInventory().getItemInMainHand(),
                    block,
                    org.bukkit.block.BlockFace.NORTH
                );

            // Event'i çağır ve iptal edilip edilmediğini kontrol et
            Bukkit.getPluginManager().callEvent(testEvent);

            // Eğer başka bir plugin event'i iptal ettiyse, erişim yok
            if (testEvent.isCancelled()) {
                return false;
            }
        } catch (Exception e) {
            // Hata durumunda izin ver
            getLogger().warning("Koruma kontrolü hatası: " + e.getMessage());
        }

        return true;
    }

    /**
     * Sandığın yanında kilit tabelası olup olmadığını kontrol eder.
     * [Private] veya oyuncu ismi içeren tabelalar kilit olarak kabul edilir.
     */
    private boolean hasSignLock(org.bukkit.block.Block block, Player player) {
        // Sandığın etrafındaki blokları kontrol et
        org.bukkit.block.BlockFace[] faces = {
            org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST,
            org.bukkit.block.BlockFace.WEST,
            org.bukkit.block.BlockFace.UP
        };

        for (org.bukkit.block.BlockFace face : faces) {
            org.bukkit.block.Block adjacent = block.getRelative(face);

            // Tabela mı kontrol et
            if (adjacent.getState() instanceof org.bukkit.block.Sign sign) {
                String[] lines = sign.getLines();

                // [Private] veya [Lock] kontrolü
                if (lines.length > 0) {
                    String firstLine = org.bukkit.ChatColor.stripColor(lines[0]).toLowerCase();
                    if (firstLine.contains("private") || firstLine.contains("lock") ||
                        firstLine.contains("kilit") || firstLine.contains("özel")) {

                        // Sahibi kontrol et (2. satır genellikle sahip ismi)
                        if (lines.length > 1) {
                            String ownerLine = org.bukkit.ChatColor.stripColor(lines[1]);
                            if (!ownerLine.isEmpty() && !ownerLine.equalsIgnoreCase(player.getName())) {
                                // Sahip değil, erişim yok
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false; // Kilit yok
    }

    private void deliverFromInventory(Player player, ActiveEvent activeEvent) {
        submitInventory(player, player.getInventory(), activeEvent, Integer.MAX_VALUE);
    }

    private void deliverFromRealChestInventory(Player player, Inventory inventory, ActiveEvent activeEvent) {
        submitInventory(player, inventory, activeEvent, Integer.MAX_VALUE);
    }

    private void submitInventory(Player player, Inventory inventory, ActiveEvent event, int limit) {
        int delivered = InventoryDelivery.submit(player, inventory, event, limit, itemRegistry,
                (DeliveryServiceImpl) deliveryService);
        if (delivered < 0) return;
        var language = configManager.getLanguageConfig();
        String item = deliveryGUI.getAcceptedItemDisplay(event);
        if (delivered == 0) {
            player.sendMessage(language.format("delivery.inventory.empty", currentLanguage,
                "&cTeslim edilecek uygun eşya bulunamadı.", Map.of("item", item)));
            return;
        }
        dataManager.saveActiveEvent(event);
        dataManager.updatePlayerStats(player.getUniqueId(), player.getName(), delivered);
        deliveryGUI.cachePlayerName(player.getUniqueId(), player.getName());
        String category = deliveryGUI.getCategoryDisplayName(event.getResolvedCategory());
        categoryMilestones.record(player, event.getResolvedCategory(), delivered, line -> {
            String message = language.format("messages.checkpoint-completed", currentLanguage, "",
                Map.of("category", category, "line", Integer.toString(line)));
            if (!message.isBlank()) player.sendMessage(message);
        });

        int total = event.getPlayerDeliveryCount(player.getUniqueId());
        int rank = calculateRank(event, player.getUniqueId());
        Map<String, String> values = Map.of("amount", Integer.toString(delivered), "item", item,
            "category", category,
            "total", Integer.toString(total), "rank", Integer.toString(rank));
        player.sendTitle(language.format("delivery.success.title", currentLanguage, "&a&lTESLİM EDİLDİ!", values),
            language.format("delivery.success.subtitle", currentLanguage, "&e{amount} &7adet", values), 10, 50, 10);
        player.sendMessage(language.format("delivery.success.message", currentLanguage,
            "&a{amount} &7adet teslim edildi.", values) + " " + language.format("delivery.success.stats",
            currentLanguage, "&7Toplam: &f{total} &8| &7Sıra: &e#{rank}", values));
        player.playSound(player.getLocation(), deliverSound, 1.0f, 1.0f);
    }

    // Eski metod - uyumluluk için
    private void deliverFromRealChest(Player player, Chest chest, com.deliverycore.service.ActiveEvent activeEvent) {
        deliverFromRealChestInventory(player, chest.getInventory(), activeEvent);
    }

    private int calculateRank(com.deliverycore.service.ActiveEvent event, UUID playerUuid) {
        int playerCount = event.getPlayerDeliveries().getOrDefault(playerUuid, 0);
        if (playerCount == 0) return 0;
        int rank = 1;
        for (int count : event.getPlayerDeliveries().values()) {
            if (count > playerCount) rank++;
        }
        return rank;
    }


    private void deliverPendingRewardsToPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        RewardService.PlayerResolver resolver = createPlayerResolver();
        RewardService.CommandExecutor executor = createCommandExecutor();

        int delivered = rewardService.deliverPendingRewards(
            player.getUniqueId(),
            resolver,
            executor,
            null
        );

        if (delivered > 0) {
            getLogger().info("Delivered " + delivered + " pending reward(s) to " + player.getName());
        }
    }

    private RewardService.PlayerResolver createPlayerResolver() {
        return new RewardService.PlayerResolver() {
            @Override
            public boolean isOnline(UUID uuid) {
                Player player = Bukkit.getPlayer(uuid);
                return player != null && player.isOnline();
            }

            @Override
            public String getName(UUID uuid) {
                Player player = Bukkit.getPlayer(uuid);
                return player != null ? player.getName() : null;
            }

            @Override
            public boolean giveItem(UUID uuid, String item, int amount) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    return false;
                }

                try {
                    org.bukkit.Material material = org.bukkit.Material.matchMaterial(item);
                    if (material != null) {
                        org.bukkit.inventory.ItemStack itemStack = new org.bukkit.inventory.ItemStack(material, amount);
                        player.getInventory().addItem(itemStack);
                        return true;
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to give item " + item + " to " + player.getName() + ": " + e.getMessage());
                }
                return false;
            }
        };
    }

    private RewardService.CommandExecutor createCommandExecutor() {
        return command -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Exception e) {
                getLogger().warning("Failed to execute reward command: " + command + " - " + e.getMessage());
            }
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String senderName = sender instanceof Player ? sender.getName() : "CONSOLE";
        UUID senderUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;

        String cmdName = command.getName().toLowerCase();

        return switch (cmdName) {
            case "deliverycore" -> commandHandler.handleCommand(senderName, args);
            case "teslimat", "delivery" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    yield commandHandler.handleCommand(senderName, args);
                }
                if (args.length > 0 && handleDeliveryAdmin(sender, args)) yield true;
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cBu komut sadece oyuncular kullanabilir.");
                    yield true;
                }
                yield deliverCommand.handleTeslimatCommand(senderName, senderUuid, args);
            }
            case "teslim", "deliver" -> {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cBu komut sadece oyuncular kullanabilir.");
                    yield true;
                }
                yield deliverCommand.handleTeslimCommand(senderName, senderUuid, args);
            }
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String senderName = sender instanceof Player ? sender.getName() : "CONSOLE";
        String cmdName = command.getName().toLowerCase();

        return switch (cmdName) {
            case "deliverycore" -> commandHandler.handleTabComplete(senderName, args);
            case "teslimat", "delivery" -> {
                if (sender.hasPermission("deliverycore.admin") && args.length == 1) {
                    yield java.util.stream.Stream.of("help", "info", "top", "teslim", "deliver", "reload", "set", "reset", "customitem", "removecustomitem")
                            .filter(value -> value.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
                }
                yield deliverCommand.handleTeslimatTabComplete(senderName, args);
            }
            case "teslim", "deliver" -> deliverCommand.handleTeslimTabComplete(senderName, args);
            default -> List.of();
        };
    }

    private boolean handleDeliveryAdmin(CommandSender sender, String[] args) {
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        if (!List.of("set", "reset", "customitem", "removecustomitem").contains(action)) return false;
        if (!sender.hasPermission("deliverycore.admin")) {
            sender.sendMessage("§cBu komut için yetkiniz yok.");
            return true;
        }
        try {
            switch (action) {
                case "customitem" -> {
                    if (args.length != 2 || !(sender instanceof Player player)) {
                        sender.sendMessage("§e/delivery customitem <id> (eşya elinizde olmalı)");
                    } else {
                        itemRegistry.register(args[1], player.getInventory().getItemInMainHand());
                        sender.sendMessage("§aÖzel eşya kaydedildi: " + args[1]);
                    }
                }
                case "removecustomitem" -> {
                    if (args.length != 2) sender.sendMessage("§e/delivery removecustomitem <id>");
                    else sender.sendMessage(itemRegistry.remove(args[1]) ? "§aÖzel eşya silindi." : "§cÖzel eşya bulunamadı.");
                }
                default -> {
                    boolean reset = action.equals("reset");
                    if (args.length != (reset ? 3 : 4)) {
                        sender.sendMessage("§e/delivery " + action + " <oyuncu> <kategori>" + (reset ? "" : " <miktar>"));
                        return true;
                    }
                    String category = configManager.getCategoryConfig().getCategoryNames().stream()
                            .filter(name -> name.equalsIgnoreCase(args[2])).findFirst().orElse(null);
                    if (category == null) throw new IllegalArgumentException("Kategori bulunamadı: " + args[2]);
                    Player online = Bukkit.getPlayerExact(args[1]);
                    org.bukkit.OfflinePlayer target = online != null ? online : java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                            .filter(player -> args[1].equalsIgnoreCase(player.getName())
                                    || args[1].equalsIgnoreCase(player.getUniqueId().toString()))
                            .findFirst().orElse(null);
                    if (target == null) throw new IllegalArgumentException("Oyuncu bulunamadı: " + args[1]);
                    long amount = reset ? 0 : Long.parseLong(args[3]);
                    if (amount < 0) throw new IllegalArgumentException("Miktar negatif olamaz.");
                    categoryMilestones.setProgress(target.getUniqueId(), category, amount);
                    sender.sendMessage("§a" + args[1] + " / " + category + " ilerlemesi: " + amount);
                }
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cGeçersiz miktar.");
        } catch (IllegalArgumentException | java.io.IOException e) {
            sender.sendMessage("§c" + e.getMessage());
        }
        return true;
    }

    public CategoryMilestones getCategoryMilestones() { return categoryMilestones; }

    private boolean checkPermission(String playerName, String permission) {
        if ("CONSOLE".equals(playerName)) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerName);
        return player != null && player.hasPermission(permission);
    }

    private void sendMessageToPlayer(String playerName, String message) {
        if ("CONSOLE".equals(playerName)) {
            getLogger().info(message);
            return;
        }
        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    public ConfigManager getConfigManager() { return configManager; }
    public PlaceholderEngine getPlaceholderEngine() { return placeholderEngine; }
    public CategoryService getCategoryService() { return categoryService; }
    public SchedulerService getSchedulerService() { return schedulerService; }
    public DeliveryService getDeliveryService() { return deliveryService; }
    public RewardService getRewardService() { return rewardService; }
    public WebhookService getWebhookService() { return webhookService; }
    public MessageService getMessageService() { return messageService; }
}
