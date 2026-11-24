package com.deliverycore.gui;

import com.deliverycore.config.ConfigManager;
import com.deliverycore.item.DeliveryItemRegistry;
import com.deliverycore.model.ImportedDelivery;
import com.deliverycore.service.CategoryMilestones;
import com.deliverycore.service.SchedulerService;
import org.bukkit.inventory.InventoryHolder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.deliverycore.service.ActiveEvent;
import com.deliverycore.service.DeliveryService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class DeliveryGUI {
    private final ConfigManager configManager;
    private final DeliveryService deliveryService;
    
    private final Map<String, String> itemDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, Double> itemPrices = new ConcurrentHashMap<>();
    private final Map<String, String> categoryDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, String> deliveryDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, String> categoryHeads = new ConcurrentHashMap<>();
    private final Map<UUID, String> chestSelectionMode = new ConcurrentHashMap<>();
    private final Map<UUID, Long> chestSelectionTimeout = new ConcurrentHashMap<>();
    private final Set<UUID> invalidChestSelectionWarned = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> playerNameCache = new ConcurrentHashMap<>();
    
    // Dil desteği
    private Supplier<String> languageSupplier;
    private CategoryMilestones milestones;
    private SchedulerService scheduler;
    private DeliveryItemRegistry itemRegistry;
    private BiConsumer<Player, ActiveEvent> inventoryAction;
    private MenuLayout mainLayout = MenuLayout.main();
    private MenuLayout deliveryLayout = MenuLayout.delivery();
    private MenuLayout rankingLayout = MenuLayout.ranking();
    private ConfigurationSection guiSettings;

    public void setMilestones(CategoryMilestones milestones) { this.milestones = milestones; }
    public void setScheduler(SchedulerService scheduler) { this.scheduler = scheduler; }
    public void setItemRegistry(DeliveryItemRegistry itemRegistry) { this.itemRegistry = itemRegistry; }
    public void setInventoryAction(BiConsumer<Player, ActiveEvent> action) { this.inventoryAction = action; }

    public void loadLayouts(YamlConfiguration config) {
        mainLayout = MenuLayout.from(config, "main", MenuLayout.main());
        deliveryLayout = MenuLayout.from(config, "delivery", MenuLayout.delivery());
        rankingLayout = MenuLayout.from(config, "leaderboard", MenuLayout.ranking());
        guiSettings = config.getConfigurationSection("gui");
    }

    public String getAcceptedItemDisplay(ActiveEvent event) {
        return event.getAcceptedItems().stream().map(this::getItemDisplayName).collect(Collectors.joining(", "));
    }

    // Head Textures
    private static final String HEAD_DELIVERY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGFhMTg3ZmVkZTg4ZGUwMDJjYmQ5MzA1NzVlYjdiYTQ4ZDNiMWEwNmQ5NjFiZGM1MzU4MDA3NTBhZjc2NDkyNiJ9fX0=";
    private static final String HEAD_CHEST = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDVjNmRjMmJiZjUxYzM2Y2ZjNzcxNDU4NWE2YTU2ODNlZjJiMTRkNDdkOGZmNzE0NjU0YTg5M2Y1ZGE2MjIifX19";
    private static final String HEAD_INVENTORY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWQ5Y2M1OGFkMjVhMWFiMTZkMzZiYjVkNmQ0OTNjOGY1ODkxMjMyNTEyYjVjNDg0YzY2Y2ZkM2I0YjZhYzRiNCJ9fX0=";
    private static final String HEAD_TOP = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjdkYzNlMjlhMDkyM2U1MmVjZWU2YjRjOWQ1MzNhNzllNzRiYjZiZWQ1NDFiNDk1YTEzYWJkMzU5NjI3NjUzIn19fQ==";
    private static final String HEAD_HELP = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVlZWQxZTNkYWYyMTdhMzhjMWEifX19";
    private static final String HEAD_BACK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmQ2OWUwNmU1ZGFkZmQ4NGU1ZjNkMWMyMTA2M2YyNTUzYjJmYTk0NWVlMWQ0ZDcxNTJmZGM1NDI1YmMxMmE5In19fQ==";
    private static final String HEAD_WAITING = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmNlZjlhYTE0ZTg4NDc3M2VhYzEzNGE0ZWU4OTcyMDYzZjQ2NmRlNjc4MzYzY2Y3YjFhMjFhODViNyJ9fX0=";
    private static final String HEAD_GOLD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTQ1ZjQ3ZTc1YzM2NTU0ZTk0ZTI2YTBjNzc2NmU3OTc1NWQ0MTc2NDcyMzA4ZTQ3YTI0NTI5MTY1MTk0YzYifX19";
    private static final String HEAD_SILVER = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTllYjlkYTI2Y2YyZDMzNDEzOTdhN2Y0OTEzYmEzZDM3ZDFhZDEwMWE0N2VhNGNlYzk2NjY0OGY4ZWI5NyJ9fX0=";
    private static final String HEAD_BRONZE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZmYWI5OTFkMDgzOTkzY2I4M2E1NzAwMTk5MTdmMzBhNTY3MjRiYWMxNmI4YzRmMTMzNjEyNTYyZTRlYjYifX19";

    // SmallCaps
    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    // Dinamik başlıklar - dil dosyasından yüklenir
    public static final String MAIN_TITLE_TR = "ᴛᴇsʟɪᴍᴀᴛ ᴍᴇɴᴜ̈sᴜ̈";
    public static final String MAIN_TITLE_EN = "ᴅᴇʟɪᴠᴇʀʏ ᴍᴇɴᴜ";
    public static final String DELIVERY_PREFIX_TR = "ᴛᴇsʟɪᴍᴀᴛ: ";
    public static final String DELIVERY_PREFIX_EN = "ᴅᴇʟɪᴠᴇʀʏ: ";
    public static final String LEADERBOARD_TITLE_TR = "sɪʀᴀʟᴀᴍᴀ";
    public static final String LEADERBOARD_TITLE_EN = "ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ";
    
    // Uyumluluk için varsayılan değerler
    public static final String MAIN_TITLE = MAIN_TITLE_TR;
    public static final String DELIVERY_PREFIX = DELIVERY_PREFIX_TR;
    public static final String LEADERBOARD_TITLE = LEADERBOARD_TITLE_TR;

    public DeliveryGUI(ConfigManager cm, DeliveryService ds) {
        this.configManager = cm;
        this.deliveryService = ds;
        initDefaults();
    }

    private void initDefaults() {
        categoryDisplayNames.put("farm", "Çiftlik");
        categoryDisplayNames.put("ore", "Maden");
        categoryDisplayNames.put("block", "Blok");
        categoryDisplayNames.put("food", "Yiyecek");
        categoryDisplayNames.put("wood", "Odun");
        categoryDisplayNames.put("rare", "Nadir");
        categoryDisplayNames.put("nether", "Nether");
        categoryDisplayNames.put("mob", "Mob Drop");
        categoryDisplayNames.put("end", "End");
        categoryDisplayNames.put("dye", "Boya");
        categoryDisplayNames.put("tool", "Alet");
        categoryDisplayNames.put("armor", "Zırh");
        categoryDisplayNames.put("combat", "Savaş");
        categoryDisplayNames.put("potion", "İksir");
        categoryDisplayNames.put("decoration", "Dekorasyon");
        categoryDisplayNames.put("redstone", "Kızıltaş");
        categoryDisplayNames.put("misc", "Çeşitli");
    }
    
    /**
     * Dil sağlayıcısını ayarlar
     */
    public void setLanguageSupplier(Supplier<String> supplier) {
        this.languageSupplier = supplier;
    }
    
    
    /**
     * Mevcut dili döndürür
     */
    private String getCurrentLanguage() {
        if (languageSupplier != null) {
            return languageSupplier.get();
        }
        return "tr";
    }
    
    /**
     * Dil dosyasından mesaj alır
     */
    private String getLang(String path, String defaultValue) {
        return getLang(path, defaultValue, Map.of());
    }

    private String getLang(String path, String defaultValue, Map<String, String> values) {
        return configManager.getLanguageConfig().format(path, getCurrentLanguage(), defaultValue, values);
    }

    private List<String> getLore(String path, List<String> fallback, Map<String, String> values) {
        return Arrays.asList(getLang(path, String.join("\n", fallback), values).split("\\R", -1));
    }
    
    /**
     * Mevcut dile göre ana menü başlığını döndürür
     */
    public String getMainTitle() {
        return getLang("gui.main.title", "en".equals(getCurrentLanguage()) ? MAIN_TITLE_EN : MAIN_TITLE_TR);
    }
    
    /**
     * Mevcut dile göre teslimat prefix'ini döndürür
     */
    public String getDeliveryPrefix() {
        return "en".equals(getCurrentLanguage()) ? DELIVERY_PREFIX_EN : DELIVERY_PREFIX_TR;
    }
    
    /**
     * Mevcut dile göre sıralama başlığını döndürür
     */
    public String getLeaderboardTitle() {
        return getLeaderboardTitle("");
    }

    private String getLeaderboardTitle(String delivery) {
        return getLang("gui.leaderboard.title", "en".equals(getCurrentLanguage()) ? LEADERBOARD_TITLE_EN : LEADERBOARD_TITLE_TR,
            Map.of("delivery", delivery, "name", delivery));
    }

    public void loadItemsConfig(File dataFolder) {
        File itemsFile = new File(dataFolder, "items.yml");
        if (!itemsFile.exists()) {
            System.out.println("[DeliveryGUI] items.yml bulunamadi: " + itemsFile.getAbsolutePath());
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(itemsFile);
        var itemsSection = config.getConfigurationSection("items");
        if (itemsSection == null) {
            System.out.println("[DeliveryGUI] items.yml'de 'items' section bulunamadi!");
            return;
        }
        int loadedCount = 0;
        for (String key : itemsSection.getKeys(false)) {
            String displayName = itemsSection.getString(key + ".display-name");
            double price = itemsSection.getDouble(key + ".price", 0);
            if (displayName != null) {
                itemDisplayNames.put(key.toUpperCase(), displayName);
                loadedCount++;
            }
            if (price > 0) itemPrices.put(key.toUpperCase(), price);
        }
        System.out.println("[DeliveryGUI] " + loadedCount + " esya yuklendi (items.yml)");
    }

    public static String sc(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            int idx = NORMAL.indexOf(c);
            sb.append(idx >= 0 ? SMALL.charAt(idx) : c);
        }
        return sb.toString();
    }

    public String getItemDisplayName(String itemName) {
        if (itemName == null) return "Bilinmiyor";
        return itemDisplayNames.getOrDefault(itemName.toUpperCase(), itemName.replace("_", " "));
    }
    public double getItemPrice(String itemName) { return itemPrices.getOrDefault(itemName.toUpperCase(), 0.0); }
    public String getCategoryDisplayName(String catName) {
        return categoryDisplayNames.getOrDefault(catName.toLowerCase(Locale.ROOT), importedTitle(catName));
    }

    public String getDeliveryDisplayName(String deliveryName) {
        return deliveryDisplayNames.getOrDefault(deliveryName.toLowerCase(Locale.ROOT), importedTitle(deliveryName));
    }

    private ImportedDelivery importedDelivery(String id) {
        return configManager.getDeliveryConfig().getImportedDeliveries().get(id);
    }

    private String importedTitle(String id) {
        ImportedDelivery imported = importedDelivery(id);
        return imported == null ? id : color(imported.title());
    }
    public void setCategoryDisplayName(String cat, String name) { categoryDisplayNames.put(cat.toLowerCase(), name); }
    public void setDeliveryDisplayName(String del, String name) { deliveryDisplayNames.put(del.toLowerCase(), name); }
    public void setCategoryHead(String cat, String texture) { categoryHeads.put(cat.toLowerCase(), texture); }
    public void setItemDisplayName(String item, String name) { itemDisplayNames.put(item.toUpperCase(), name); }
    public void setChestCapacity(int capacity) { }
    public void cachePlayerName(UUID id, String name) { if (id != null && name != null) playerNameCache.put(id, name); }

    public void startChestSelection(Player player, String deliveryName) {
        invalidChestSelectionWarned.remove(player.getUniqueId());
        chestSelectionMode.put(player.getUniqueId(), deliveryName);
        chestSelectionTimeout.put(player.getUniqueId(), System.currentTimeMillis() + 10000);
    }
    public boolean isInChestSelectionMode(UUID id) {
        Long timeout = chestSelectionTimeout.get(id);
        if (timeout == null) return false;
        if (System.currentTimeMillis() > timeout) { cancelChestSelection(id); return false; }
        return chestSelectionMode.containsKey(id);
    }
    public String getChestSelectionDelivery(UUID id) { return chestSelectionMode.get(id); }
    public boolean markInvalidChestSelectionWarned(UUID id) { return invalidChestSelectionWarned.add(id); }
    public void cancelChestSelection(UUID id) {
        chestSelectionMode.remove(id);
        chestSelectionTimeout.remove(id);
        invalidChestSelectionWarned.remove(id);
    }

    public static boolean isDeliveryGUI(String title) { 
        return title.contains(MAIN_TITLE_TR) || title.contains(MAIN_TITLE_EN) || 
               title.contains(DELIVERY_PREFIX_TR) || title.contains(DELIVERY_PREFIX_EN) || 
               title.contains(LEADERBOARD_TITLE_TR) || title.contains(LEADERBOARD_TITLE_EN); 
    }
    public static boolean isMainMenu(String title) { 
        return title.contains(MAIN_TITLE_TR) || title.contains(MAIN_TITLE_EN); 
    }
    public static boolean isDeliveryMenu(String title) { 
        return title.contains(DELIVERY_PREFIX_TR) || title.contains(DELIVERY_PREFIX_EN); 
    }
    public static boolean isLeaderboardMenu(String title) { 
        return title.contains(LEADERBOARD_TITLE_TR) || title.contains(LEADERBOARD_TITLE_EN); 
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // ANA MENÜ
    // ═══════════════════════════════════════════════════════════════════════════

    public void openMainMenu(Player player) {
        openMainMenu(player, 0);
    }

    private void openMainMenu(Player player, int page) {
        boolean isEnglish = "en".equals(getCurrentLanguage());
        Menu menu = new Menu(mainLayout.size(), getMainTitle());
        Inventory inv = menu.getInventory();
        fillBackground(inv);

        var events = deliveryService.getAllActiveEvents();
        var waiting = configManager.getDeliveryConfig().getEnabledDeliveries().stream()
            .filter(delivery -> events.stream().noneMatch(event -> event.getDeliveryName().equals(delivery.name())))
            .toList();
        if (mainLayout.groups().containsKey("categories")) {
            int[] slots = mainLayout.group("categories");
            int count = events.size() + waiting.size();
            int start = Math.min(page, Math.max(0, (count - 1) / slots.length)) * slots.length;
            for (int i = 0; i < slots.length && start + i < count; i++) {
                int index = start + i;
                if (index < events.size()) addActiveDelivery(menu, player, slots[i], events.get(index));
                else addWaitingDelivery(menu, player, slots[i], waiting.get(index - events.size()), isEnglish);
            }
            if (start > 0) addPageButton(menu, mainLayout.slot("previous"), "previous", isEnglish,
                viewer -> openMainMenu(viewer, start / slots.length - 1));
            if (start + slots.length < count) addPageButton(menu, mainLayout.slot("next"), "next", isEnglish,
                viewer -> openMainMenu(viewer, start / slots.length + 1));
        } else {
            int[] activeSlots = mainLayout.group("active");
            int[] waitingSlots = mainLayout.group("waiting");
            for (int i = 0; i < events.size() && i < activeSlots.length; i++) {
                addActiveDelivery(menu, player, activeSlots[i], events.get(i));
            }
            for (int i = 0; i < waiting.size() && i < waitingSlots.length; i++) {
                addWaitingDelivery(menu, player, waitingSlots[i], waiting.get(i), isEnglish);
            }
        }

        // Sıralama butonu (slot 47)
        String leaderboardName = isEnglish ? sc("leaderboard") : sc("siralama");
        String leaderboardLore = isEnglish ? sc("see top deliverers") : sc("en cok teslim edenleri gor");
        String clickText = isEnglish ? sc("click") : sc("tikla");
        menu.onClick(mainLayout.slot("leaderboard"), this::openLeaderboard);
        inv.setItem(mainLayout.slot("leaderboard"), createGuiIcon("top-ten-gui", HEAD_TOP, player,
            getLang("gui.top-ten-gui-item-name", "§b" + leaderboardName), getLore("gui.top-ten-gui-item-lore", List.of(
            "",
            "§7" + leaderboardLore,
            "",
            "§a" + clickText
        ), Map.of())));

        // Yardım butonu (slot 51)
        String helpName = isEnglish ? sc("help") : sc("yardim");
        String helpLore = isEnglish ? sc("learn about the system") : sc("teslimat sistemini ogren");
        menu.onClick(mainLayout.slot("help"), viewer -> {
            viewer.closeInventory();
            viewer.performCommand("teslimat help");
        });
        inv.setItem(mainLayout.slot("help"), createGuiIcon("help", HEAD_HELP, player,
            getLang("gui.help-item-name", "§f" + helpName), getLore("gui.help-item-lore", List.of(
            "",
            "§7" + helpLore,
            "",
            "§a" + clickText
        ), Map.of())));

        player.openInventory(inv);
    }

    private void addActiveDelivery(Menu menu, Player player, int slot, ActiveEvent event) {
        menu.getInventory().setItem(slot, createDeliveryItem(player, event));
        menu.onClick(slot, viewer -> openDeliveryMenu(viewer, event));
    }

    private void addWaitingDelivery(Menu menu, Player player, int slot, com.deliverycore.model.DeliveryDefinition delivery, boolean english) {
        ImportedDelivery imported = importedDelivery(delivery.name());
        menu.getInventory().setItem(slot, imported == null ? createWaitingDeliveryItem(delivery)
            : createImportedCard(player, imported, null));
        menu.onClick(slot, viewer -> viewer.sendMessage(imported == null
            ? getLang("delivery.error.not_started", english ? "&7This delivery has not started." : "&7Bu teslimat henüz başlamadı.")
            : importedTime(imported, null)));
    }
    
    private ItemStack createWaitingDeliveryItem(com.deliverycore.model.DeliveryDefinition delivery) {
        boolean isEnglish = "en".equals(getCurrentLanguage());
        String deliveryName = getDeliveryDisplayName(delivery.name());
        String schedule = delivery.schedule().start();
        String nextTime = parseScheduleToReadable(schedule);
        
        String waitingText = isEnglish ? sc("waiting") : sc("bekliyor");
        String startText = isEnglish ? sc("start") : sc("baslangic");
        String notStartedText = isEnglish ? sc("not started yet") : sc("henuz baslamadi");
        
        return createHead(HEAD_WAITING, "§7" + deliveryName, Arrays.asList(
            "",
            "§8" + waitingText,
            "",
            "§7" + startText + " §e" + nextTime,
            "",
            "§8" + notStartedText
        ));
    }
    
    private String parseScheduleToReadable(String schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return "en".equals(getCurrentLanguage()) ? "Unknown" : "Bilinmiyor";
        }
        String lower = schedule.toLowerCase();
        boolean isEnglish = "en".equals(getCurrentLanguage());
        
        Map<String, String> daysTR = Map.of(
            "every day", "Her gün", "every monday", "Her Pazartesi",
            "every tuesday", "Her Salı", "every wednesday", "Her Çarşamba",
            "every thursday", "Her Perşembe", "every friday", "Her Cuma",
            "every saturday", "Her Cumartesi", "every sunday", "Her Pazar"
        );
        Map<String, String> daysEN = Map.of(
            "every day", "Every day", "every monday", "Every Monday",
            "every tuesday", "Every Tuesday", "every wednesday", "Every Wednesday",
            "every thursday", "Every Thursday", "every friday", "Every Friday",
            "every saturday", "Every Saturday", "every sunday", "Every Sunday"
        );
        
        Map<String, String> days = isEnglish ? daysEN : daysTR;
        for (var entry : days.entrySet()) {
            if (lower.contains(entry.getKey())) {
                String time = schedule.replaceAll("(?i)" + entry.getKey().replace(" ", "\\s+") + "\\s*", "").trim();
                return entry.getValue() + " " + time;
            }
        }
        return schedule;
    }

    private ItemStack createDeliveryItem(Player player, ActiveEvent event) {
        ImportedDelivery imported = importedDelivery(event.getDeliveryName());
        if (imported != null) return createImportedCard(player, imported, event);
        boolean isEnglish = "en".equals(getCurrentLanguage());
        String itemName = getAcceptedItemDisplay(event);
        String deliveryName = getDeliveryDisplayName(event.getDeliveryName());
        int playerCount = event.getPlayerDeliveries().getOrDefault(player.getUniqueId(), 0);
        int totalCount = event.getPlayerDeliveries().values().stream().mapToInt(Integer::intValue).sum();
        int rank = calculateRank(event, player.getUniqueId());

        String itemText = isEnglish ? sc("item") : sc("esya");
        String categoryText = isEnglish ? sc("category") : sc("kategori");
        String totalText = isEnglish ? sc("total") : sc("toplam");
        String yoursText = isEnglish ? sc("yours") : sc("senin");
        String rankText = isEnglish ? sc("rank") : sc("sira");
        String clickDeliverText = isEnglish ? sc("click to deliver") : sc("tikla ve teslim et");

        return createHead(HEAD_DELIVERY, "§a" + deliveryName, Arrays.asList(
            "",
            "§7" + itemText + " §f" + itemName,
            "§7" + categoryText + " §f" + getCategoryDisplayName(event.getResolvedCategory()),
            "",
            "§7" + totalText + " §e" + totalCount,
            "§7" + yoursText + " §a" + playerCount,
            "§7" + rankText + " §e#" + (rank > 0 ? rank : "-"),
            "",
            "§a" + clickDeliverText
        ));
    }

    private ItemStack createImportedCard(Player player, ImportedDelivery imported, ActiveEvent event) {
        String prefix = "%delivery_" + imported.definition().name() + "_";
        String amount = Long.toString(importedAmount(player, imported.definition().name()));
        String time = importedTime(imported, event);
        List<String> lore = imported.menuLore().stream()
            .map(line -> color(line.replace(prefix + "time%", time).replace(prefix + "amount%", amount)))
            .toList();
        return createIcon(imported.icon(), event == null ? HEAD_WAITING : HEAD_DELIVERY, color(imported.menuName()), lore);
    }

    private long importedAmount(Player player, String category) {
        return milestones == null ? 0 : milestones.getProgress(player.getUniqueId(), category).amount();
    }

    private String importedTime(ImportedDelivery imported, ActiveEvent event) {
        var definition = imported.definition();
        ZonedDateTime now = ZonedDateTime.now(definition.timezone());
        if (event != null) return formatRemaining(Duration.between(now, event.getEndTime()));
        if (scheduler == null) return definition.schedule().start();
        var start = scheduler.parseScheduleExpression(definition.schedule().start(), definition.timezone());
        if (start.isPresent() && start.get().isAfter(now)) return formatRemaining(Duration.between(now, start.get()));
        var end = scheduler.parseScheduleExpression(definition.schedule().end(), definition.timezone());
        if (end.isPresent() && end.get().isAfter(now)) return formatRemaining(Duration.between(now, end.get()));
        return getLang("system.ended-placeholder", "&c" + ("en".equals(getCurrentLanguage()) ? "Ended" : "Bitti"));
    }

    public String formatRemaining(Duration remaining) {
        boolean english = "en".equals(getCurrentLanguage());
        String[] units = getLang("system.time-placeholders", english ? "d-h-m" : "g-s-dk").split("-", -1);
        if (units.length != 3) units = new String[] {"d", "h", "m"};
        Duration time = remaining.isNegative() ? Duration.ZERO : remaining;
        return "%d%s %d%s %d%s".formatted(time.toDays(), units[0], time.toHoursPart(), units[1], time.toMinutesPart(), units[2]);
    }

    private static String color(String text) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TESLİMAT DETAY MENÜSÜ
    // ═══════════════════════════════════════════════════════════════════════════

    public void openDeliveryMenu(Player player, ActiveEvent event) {
        openDeliveryMenu(player, event, 0);
    }

    private void openDeliveryMenu(Player player, ActiveEvent event, int page) {
        if (deliveryService.getActiveEvent(event.getDeliveryName()).orElse(null) != event) {
            openMainMenu(player);
            return;
        }
        boolean isEnglish = "en".equals(getCurrentLanguage());
        String deliveryName = getDeliveryDisplayName(event.getDeliveryName());
        Menu menu = new Menu(deliveryLayout.size(), getLang("gui.delivery.title", getDeliveryPrefix() + deliveryName,
            Map.of("name", deliveryName, "delivery", deliveryName)));
        Inventory inv = menu.getInventory();
        
        fillBackground(inv);

        String itemName = getAcceptedItemDisplay(event);
        int playerCount = event.getPlayerDeliveries().getOrDefault(player.getUniqueId(), 0);
        int totalCount = event.getPlayerDeliveries().values().stream().mapToInt(Integer::intValue).sum();
        int rank = calculateRank(event, player.getUniqueId());

        // Dil string'leri
        String requiredItemText = isEnglish ? sc("required item") : sc("istenen esya");
        String categoryText = isEnglish ? sc("category") : sc("kategori");
        String totalDeliveriesText = isEnglish ? sc("total deliveries") : sc("toplam teslimat");
        String yourDeliveriesText = isEnglish ? sc("your deliveries") : sc("senin teslimat");
        String yourRankText = isEnglish ? sc("your rank") : sc("siran");
        String deliverFromInvText = isEnglish ? sc("deliver from inventory") : sc("envanterden teslim et");
        String allItemsText = isEnglish ? sc("all your") : sc("envanterindeki tum");
        String itemsDeliverText = isEnglish ? sc("items will be delivered") : sc("esyalarini teslim et");
        String deliverFromChestText = isEnglish ? sc("deliver from chest") : sc("sandiktan teslim et");
        String rightClickChestText = isEnglish ? sc("right click a chest") : sc("bir sandiga sag tikla");
        String insideText = isEnglish ? sc("inside") : sc("icindeki");
        String timeLeftText = isEnglish ? sc("10 seconds to select") : sc("10 saniye suren var");
        String clickText = isEnglish ? sc("click") : sc("tikla");
        String backText = isEnglish ? sc("back") : sc("geri");
        String returnMainText = isEnglish ? sc("return to main menu") : sc("ana menuye don");

        // Bilgi (slot 4)
        ItemStack icon = itemRegistry == null ? null : itemRegistry.createItem(event.getResolvedItem());
        inv.setItem(deliveryLayout.slot("info"), withDescription(icon == null ? createHead(HEAD_DELIVERY, "", List.of()) : icon, "§e" + deliveryName, Arrays.asList(
            "",
            "§7" + requiredItemText,
            "§f  " + itemName,
            "",
            "§7" + categoryText + " §f" + getCategoryDisplayName(event.getResolvedCategory()),
            "§7" + totalDeliveriesText + " §e" + totalCount,
            "",
            "§7" + yourDeliveriesText + " §a" + playerCount,
            "§7" + yourRankText + " §e#" + (rank > 0 ? rank : "-")
        )));

        // Envanterden teslim et (slot 20)
        menu.onClick(deliveryLayout.slot("inventory"), viewer -> {
            viewer.closeInventory();
            if (inventoryAction != null) inventoryAction.accept(viewer, event);
        });
        ImportedDelivery imported = importedDelivery(event.getDeliveryName());
        String itemLore = imported == null || imported.itemLore().isEmpty() ? itemName : String.join("\n", imported.itemLore());
        long displayedAmount = imported == null ? playerCount : importedAmount(player, event.getResolvedCategory());
        Map<String, String> deliveryValues = Map.of("amount", Long.toString(displayedAmount), "delivery", itemLore);
        inv.setItem(deliveryLayout.slot("inventory"), createGuiIcon("deliver", HEAD_INVENTORY, player,
            getLang("gui.deliver-item-name", "§a" + deliverFromInvText, deliveryValues),
            getLore("gui.deliver-item-lore", List.of(
            "",
            "§7" + allItemsText,
            "§f" + itemName + " §7" + itemsDeliverText,
            "",
            "§a" + clickText
        ), deliveryValues)));
        if (deliveryLayout.slots().containsKey("leaderboard")) {
            int slot = deliveryLayout.slot("leaderboard");
            menu.onClick(slot, viewer -> openLeaderboard(viewer, event, true));
            inv.setItem(slot, createGuiIcon("top-ten-gui", HEAD_TOP, player,
                getLang("gui.top-ten-gui-item-name", isEnglish ? "§bLeaderboard" : "§bSıralama"),
                getLore("gui.top-ten-gui-item-lore", List.of(), Map.of())));
        }

        // Sandıktan teslim et (slot 24)
        menu.onClick(deliveryLayout.slot("chest"), viewer -> {
            viewer.closeInventory();
            if (deliveryService.getActiveEvent(event.getDeliveryName()).orElse(null) != event) return;
            startChestSelection(viewer, event.getDeliveryName());
            viewer.sendMessage(getLang("delivery.chest.prompt",
                isEnglish ? "&aRight-click a chest within 10 seconds." : "&a10 saniye içinde bir sandığa sağ tıkla."));
        });
        inv.setItem(deliveryLayout.slot("chest"), createHead(HEAD_CHEST, "§6" + deliverFromChestText, Arrays.asList(
            "",
            "§7" + rightClickChestText,
            "§7" + insideText + " §f" + itemName,
            "§7" + itemsDeliverText,
            "",
            "§e" + timeLeftText,
            "",
            "§a" + clickText
        )));

        // Geri butonu (slot 40)
        menu.onClick(deliveryLayout.slot("back"), this::openMainMenu);
        inv.setItem(deliveryLayout.slot("back"), createHead(HEAD_BACK, "§c" + backText, Arrays.asList(
            "",
            "§7" + returnMainText
        )));

        showMilestones(player, event, page, menu, isEnglish);
        player.openInventory(inv);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // SIRALAMA MENÜSÜ
    // ═══════════════════════════════════════════════════════════════════════════

    public void openLeaderboard(Player player) {
        var events = deliveryService.getAllActiveEvents();
        openLeaderboard(player, events.isEmpty() ? null : events.get(0), false);
    }

    private void openLeaderboard(Player player, ActiveEvent event, boolean returnToDelivery) {
        boolean isEnglish = "en".equals(getCurrentLanguage());
        String delivery = event == null ? "" : getDeliveryDisplayName(event.getDeliveryName());
        Menu menu = new Menu(rankingLayout.size(), getLeaderboardTitle(delivery));
        Inventory inv = menu.getInventory();
        fillBackground(inv);

        if (event == null) {
            String noActiveText = isEnglish ? sc("no active delivery") : sc("aktif teslimat yok");
            String waitingText = isEnglish ? sc("waiting for delivery event") : sc("siralama icin teslimat bekleniyor");
            inv.setItem(rankingLayout.slot("empty"), createHead(HEAD_HELP, "§7" + noActiveText, Arrays.asList(
                "",
                "§8" + waitingText
            )));
        } else {
            var deliveries = event.getPlayerDeliveries();
            
            List<Map.Entry<UUID, Integer>> sorted = deliveries.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(10)
                .toList();

            int[] slots = rankingLayout.group("entries");
            String[] colors = {"§6", "§f", "§c", "§7", "§7", "§7", "§7", "§7", "§7", "§7"};
            
            String deliveriesText = isEnglish ? sc("deliveries") : sc("teslimat");
            for (int i = 0; i < sorted.size() && i < slots.length; i++) {
                var entry = sorted.get(i);
                String name = getPlayerName(entry.getKey());
                
                Map<String, String> values = Map.of("line", Integer.toString(i + 1), "amount", entry.getValue().toString());
                inv.setItem(slots[i], createPlayerHead(entry.getKey(),
                    getLang("gui.top-ten-head-name", colors[i] + "#" + (i + 1) + " " + name, values)
                        .replace("%player_name%", name), getLore("gui.top-ten-head-lore", List.of(
                    "",
                    "§7" + deliveriesText + " §e" + entry.getValue()
                ), values)));
            }
        }

        String backText = isEnglish ? sc("back") : sc("geri");
        String returnMainText = isEnglish ? sc("return to main menu") : sc("ana menuye don");
        menu.onClick(rankingLayout.slot("back"), viewer -> {
            if (returnToDelivery && event != null) openDeliveryMenu(viewer, event);
            else openMainMenu(viewer);
        });
        inv.setItem(rankingLayout.slot("back"), createHead(HEAD_BACK, "§c" + backText, Arrays.asList(
            "",
            "§7" + returnMainText
        )));

        player.openInventory(inv);
    }
    
    private String getPlayerName(UUID playerId) {
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null) {
            playerNameCache.put(playerId, onlinePlayer.getName());
            return onlinePlayer.getName();
        }
        String cachedName = playerNameCache.get(playerId);
        if (cachedName != null) return cachedName;
        try {
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String name = offlinePlayer.getName();
            if (name != null) { playerNameCache.put(playerId, name); return name; }
        } catch (Exception ignored) {}
        return "en".equals(getCurrentLanguage()) ? "Player" : "Oyuncu";
    }

    private int calculateRank(ActiveEvent event, UUID playerUuid) {
        int playerCount = event.getPlayerDeliveries().getOrDefault(playerUuid, 0);
        if (playerCount == 0) return 0;
        int rank = 1;
        for (int count : event.getPlayerDeliveries().values()) {
            if (count > playerCount) rank++;
        }
        return rank;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ITEM OLUŞTURMA - 1.16.5+ Uyumlu
    // ═══════════════════════════════════════════════════════════════════════════

    private ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); item.setItemMeta(meta); }
        return item;
    }

    private void fillBackground(Inventory inventory) {
        if (guiSettings != null && !guiSettings.getBoolean("filler-item", true)) return;
        ItemStack filler = createGuiIcon("filler", Material.BLACK_STAINED_GLASS_PANE.name(), null, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }

    private ItemStack createGuiIcon(String key, String fallback, Player player, String name, List<String> lore) {
        String icon = guiSettings == null ? fallback : guiSettings.getString(key + "-material", fallback);
        if ("%player_name%".equals(icon) && player != null) return createPlayerHead(player.getUniqueId(), name, lore);
        return createIcon(icon, fallback, name, lore);
    }

    private ItemStack createIcon(String icon, String fallback, String name, List<String> lore) {
        Material material = Material.matchMaterial(icon);
        if (material != null && material.isItem() && !material.isAir()) {
            return withDescription(new ItemStack(material), name, lore);
        }
        if (icon.length() > 64) return createHead(icon, name, lore);
        if (!icon.equals(fallback)) return createIcon(fallback, fallback, name, lore);
        return withDescription(new ItemStack(Material.PAPER), name, lore);
    }

    private ItemStack createHead(String texture, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            // Custom texture uygula
            applyTexture(meta, texture);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createPlayerHead(UUID playerId, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            try {
                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
                meta.setOwningPlayer(offlinePlayer);
            } catch (Exception ignored) {}
            head.setItemMeta(meta);
        }
        return head;
    }

    /**
     * Custom texture ile head oluşturma - 1.16.5 - 1.20.4 uyumlu
     */
    private void applyTexture(SkullMeta meta, String texture) {
        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            
            // GameProfile oluştur
            Object profile = gameProfileClass.getConstructor(UUID.class, String.class)
                .newInstance(UUID.randomUUID(), "DeliveryCore");
            
            // Properties al ve texture ekle
            Object properties = gameProfileClass.getMethod("getProperties").invoke(profile);
            Object property = propertyClass.getConstructor(String.class, String.class)
                .newInstance("textures", texture);
            
            // Multimap.put kullan
            properties.getClass().getMethod("put", Object.class, Object.class)
                .invoke(properties, "textures", property);
            
            // Profile'ı SkullMeta'ya set et - birden fazla yöntem dene
            boolean success = false;
            
            // Yöntem 1: profile field (1.16.5 - 1.18)
            try {
                java.lang.reflect.Field profileField = meta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                profileField.set(meta, profile);
                success = true;
            } catch (NoSuchFieldException e1) {
                // Yöntem 2: setProfile metodu (bazı sürümler)
                try {
                    java.lang.reflect.Method setProfile = meta.getClass().getDeclaredMethod("setProfile", gameProfileClass);
                    setProfile.setAccessible(true);
                    setProfile.invoke(meta, profile);
                    success = true;
                } catch (NoSuchMethodException e2) {
                    // Yöntem 3: Tüm field'ları tara
                    for (java.lang.reflect.Field field : meta.getClass().getDeclaredFields()) {
                        if (field.getType().equals(gameProfileClass)) {
                            field.setAccessible(true);
                            field.set(meta, profile);
                            success = true;
                            break;
                        }
                    }
                }
            }
            
            if (!success) {
                // Son çare: ResolvableProfile için (1.20.2+)
                try {
                    Class<?> resolvableClass = Class.forName("net.minecraft.world.item.component.ResolvableProfile");
                    // Bu sürümde farklı bir yaklaşım gerekiyor
                } catch (ClassNotFoundException ignored) {}
            }
        } catch (Exception e) {
            // Debug: System.out.println("[DeliveryGUI] Texture hatası: " + e.getMessage());
        }
    }

    private void showMilestones(Player player, ActiveEvent event, int page, Menu menu, boolean english) {
        if (milestones == null) return;
        String category = event.getResolvedCategory();
        var entries = milestones.getMilestones(category);
        var progress = milestones.getProgress(player.getUniqueId(), category);
        int[] slots = deliveryLayout.group("milestones");
        int from = page * slots.length;
        Inventory inv = menu.getInventory();
        for (int i = 0; i < slots.length && from + i < entries.size(); i++) {
            var entry = entries.get(from + i);
            boolean complete = progress.claimed().contains(entry.id());
            String status = complete ? (english ? "Completed" : "Tamamlandı")
                    : (english ? "In progress" : "Devam ediyor");
            String key = complete ? "gui.checkpoint-completed" : "gui.checkpoint-await";
            Map<String, String> values = Map.of("line", Integer.toString(from + i + 1),
                "amount", Long.toString(entry.amount()), "checkpoint_lore", String.join("\n", entry.lore()));
            List<String> fallback = new ArrayList<>(List.of("§7" + status, "§f" + progress.amount() + " §7/ §f" + entry.amount()));
            fallback.addAll(entry.lore());
            inv.setItem(slots[i], createGuiIcon(complete ? "checkpoint-completed" : "checkpoint-await",
                    (complete ? Material.LIME_DYE : Material.PAPER).name(), player,
                    getLang(key + "-name", (complete ? "§a" : "§e") + entry.name(), values),
                    getLore(key + "-lore", fallback, values)));
        }
        if (page > 0) addPageButton(menu, deliveryLayout.slot("previous"), "previous", english,
                viewer -> openDeliveryMenu(viewer, event, page - 1));
        if (from + slots.length < entries.size()) addPageButton(menu, deliveryLayout.slot("next"), "next", english,
                viewer -> openDeliveryMenu(viewer, event, page + 1));
    }

    private void addPageButton(Menu menu, int slot, String key, boolean english, Consumer<Player> action) {
        String title = key.equals("next") ? (english ? "§eNext page" : "§eSonraki sayfa")
            : (english ? "§ePrevious page" : "§eÖnceki sayfa");
        menu.getInventory().setItem(slot, createGuiIcon(key + "-page", Material.ARROW.name(), null,
            getLang("gui." + key + "-page-name", title), List.of()));
        menu.onClick(slot, action);
    }

    private ItemStack withDescription(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static final class Menu implements InventoryHolder {
        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        private Menu(int size, String title) {
            inventory = Bukkit.createInventory(this, size, title);
        }

        private void onClick(int slot, Consumer<Player> action) {
            actions.put(slot, action);
        }

        public void click(Player player, int slot) {
            Consumer<Player> action = actions.get(slot);
            if (action != null) action.accept(player);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
