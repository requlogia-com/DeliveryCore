package com.deliverycore.command;

import com.deliverycore.config.ConfigManager;
import com.deliverycore.gui.DeliveryGUI;
import com.deliverycore.service.ActiveEvent;
import com.deliverycore.service.DeliveryService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.logging.Logger;

public class DeliverCommand {

    public static final String PERM_USE = "deliverycore.use";
    public static final String PERM_DELIVER = "deliverycore.deliver";

    private final ConfigManager configManager;
    private final BiFunction<String, String, Boolean> permissionChecker;
    private final BiConsumer<String, String> messageSender;
    private final Logger logger;
    private final DeliveryGUI deliveryGUI;
    private DeliveryService deliveryService;
    private InventorySubmission inventorySubmission;
    private java.util.function.Supplier<String> languageSupplier = () -> "tr";

    public void setLanguageSupplier(java.util.function.Supplier<String> supplier) {
        this.languageSupplier = supplier;
    }

    public void setInventorySubmission(InventorySubmission inventorySubmission) {
        this.inventorySubmission = inventorySubmission;
    }

    @FunctionalInterface
    public interface InventorySubmission {
        void submit(Player player, ActiveEvent event, int limit);
    }

    private static final String PREFIX = "&e&lD&6elivery&e&lC&6ore &8» &r";

    public DeliverCommand(ConfigManager configManager, BiFunction<String, String, Boolean> permissionChecker,
            BiConsumer<String, String> messageSender, Logger logger, DeliveryGUI deliveryGUI) {
        this.configManager = configManager;
        this.permissionChecker = permissionChecker;
        this.messageSender = messageSender;
        this.logger = logger;
        this.deliveryGUI = deliveryGUI;
    }

    public void setDeliveryService(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public boolean handleTeslimatCommand(String sender, UUID senderUuid, String[] args) {
        if (!hasPerm(sender, PERM_USE)) { noPermission(sender); return true; }

        Player player = Bukkit.getPlayer(sender);
        if (player == null) { prefix(sender, "&cBu komut sadece oyuncular kullanabilir."); return true; }

        if (args.length == 0) {
            deliveryGUI.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "help", "yardim", "?" -> { sendPlayerHelp(sender); yield true; }
            case "top", "siralama" -> { showLeaderboard(sender, senderUuid); yield true; }
            case "bilgi", "info" -> { showActiveInfo(sender); yield true; }
            case "teslim", "deliver" -> handleTeslimCommand(sender, senderUuid, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                localized(sender, "general.unknown_command", PREFIX + "&cBilinmeyen komut: &f{command}",
                    Map.of("command", sub));
                yield true;
            }
        };
    }

    // /teslim [teslimat] [sayi] - Envanterdeki eşyaları direkt teslim et
    public boolean handleTeslimCommand(String sender, UUID senderUuid, String[] args) {
        if (!hasPerm(sender, PERM_DELIVER)) { noPermission(sender); return true; }

        Player player = Bukkit.getPlayer(sender);
        if (player == null) { prefix(sender, "&cBu komut sadece oyuncular kullanabilir."); return true; }

        if (deliveryService == null || deliveryService.getAllActiveEvents().isEmpty()) {
            prefix(sender, "&cŞu an aktif teslimat yok.");
            return true;
        }

        ActiveEvent targetEvent;
        int maxAmount = -1; // -1 = hepsini teslim et
        
        if (args.length == 0) {
            // İlk aktif teslimata hepsini teslim et
            targetEvent = deliveryService.getAllActiveEvents().get(0);
        } else {
            // Belirtilen teslimata teslim et
            String deliveryName = args[0].toLowerCase();
            targetEvent = deliveryService.getAllActiveEvents().stream()
                .filter(e -> e.getDeliveryName().equalsIgnoreCase(deliveryName))
                .findFirst()
                .orElse(null);
            
            if (targetEvent == null) {
                localized(sender, "command.status.not_found", PREFIX + "&cAktif teslimat bulunamadı: &f{name}",
                    Map.of("name", deliveryName));
                showActiveDeliveries(sender);
                return true;
            }
            
            // Sayı belirtilmişse
            if (args.length >= 2) {
                try {
                    maxAmount = Integer.parseInt(args[1]);
                    if (maxAmount <= 0) {
                        prefix(sender, "&cGeçersiz sayı! Pozitif bir sayı girin.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    prefix(sender, "&cGeçersiz sayı: &f" + args[1]);
                    return true;
                }
            }
        }

        inventorySubmission.submit(player, targetEvent, maxAmount > 0 ? maxAmount : Integer.MAX_VALUE);
        return true;
    }

    private void showActiveDeliveries(String sender) {
        var events = deliveryService.getAllActiveEvents();
        if (events.isEmpty()) return;
        
        StringBuilder sb = new StringBuilder();
        for (var event : events) {
            if (sb.length() > 0) sb.append("&7, ");
            sb.append("&f").append(event.getDeliveryName());
        }
        prefix(sender, "&7Aktif teslimatlar: " + sb);
    }

    public List<String> handleTeslimatTabComplete(String sender, String[] args) {
        if (args.length == 1) return filter(Arrays.asList("help", "top", "bilgi", "teslim", "deliver"), args[0]);
        if (args.length > 1 && (args[0].equalsIgnoreCase("teslim") || args[0].equalsIgnoreCase("deliver"))) {
            return handleTeslimTabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        return List.of();
    }
    
    public List<String> handleTeslimTabComplete(String sender, String[] args) {
        if (deliveryService == null) return List.of();
        
        if (args.length == 1) {
            List<String> names = deliveryService.getAllActiveEvents().stream()
                .map(ActiveEvent::getDeliveryName)
                .toList();
            return filter(new ArrayList<>(names), args[0]);
        }
        
        if (args.length == 2) {
            return filter(Arrays.asList("1", "10", "32", "64", "100", "500"), args[1]);
        }
        
        return List.of();
    }

    private void sendPlayerHelp(String sender) {
        var configured = configManager.getLanguageConfig().getMessage("messages.help", languageSupplier.get());
        if (configured.isPresent()) {
            for (String line : configured.get().split("\\R", -1)) msg(sender, line);
            return;
        }
        msg(sender, "");
        msg(sender, "&e&lD&6elivery&e&lC&6ore &8| &fOyuncu Rehberi");
        msg(sender, "");
        msg(sender, "&e/teslimat &8- &7Teslimat menüsünü aç");
        msg(sender, "&e/teslimat top &8- &7Sıralama tablosu");
        msg(sender, "&e/teslimat bilgi &8- &7Aktif teslimatlar");
        msg(sender, "");
        msg(sender, "&e/teslim &8- &7Tüm eşyaları teslim et");
        msg(sender, "&e/teslim <teslimat> &8- &7Belirli teslimata teslim et");
        msg(sender, "&e/teslim <teslimat> <sayı> &8- &7Belirli miktarda teslim et");
        msg(sender, "");
    }
    
    private void showLeaderboard(String sender, UUID senderUuid) {
        var events = deliveryService != null ? deliveryService.getAllActiveEvents() : List.<ActiveEvent>of();
        if (events.isEmpty()) { prefix(sender, "&7Aktif etkinlik yok."); return; }
        
        ActiveEvent event = events.get(0);
        var deliveries = event.getPlayerDeliveries();
        
        msg(sender, "");
        msg(sender, "&b&lSıralama &8| &f" + deliveryGUI.getDeliveryDisplayName(event.getDeliveryName()));
        msg(sender, "");
        
        if (deliveries.isEmpty()) {
            msg(sender, "&7Henüz kimse teslim etmedi.");
        } else {
            List<Map.Entry<UUID, Integer>> sorted = deliveries.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(10).toList();
            int rank = 1;
            for (var entry : sorted) {
                Player p = Bukkit.getPlayer(entry.getKey());
                String name = p != null ? p.getName() : entry.getKey().toString().substring(0, 8);
                String hl = entry.getKey().equals(senderUuid) ? " &a(Sen)" : "";
                String color = rank == 1 ? "&6" : rank == 2 ? "&f" : rank == 3 ? "&c" : "&7";
                msg(sender, color + rank + ". &f" + name + hl + " &8- &e" + entry.getValue());
                rank++;
            }
        }
        msg(sender, "");
    }
    
    private void showActiveInfo(String sender) {
        var events = deliveryService != null ? deliveryService.getAllActiveEvents() : List.<ActiveEvent>of();
        if (events.isEmpty()) { prefix(sender, "&7Şu an aktif teslimat yok."); return; }
        
        msg(sender, "");
        msg(sender, "&e&lD&6elivery&e&lC&6ore &8| &fAktif Teslimatlar");
        msg(sender, "");
        for (ActiveEvent event : events) {
            String displayName = deliveryGUI.getDeliveryDisplayName(event.getDeliveryName());
            String itemName = deliveryGUI.getItemDisplayName(event.getResolvedItem());
            msg(sender, "&a" + displayName + " &8- &7Eşya: &e" + itemName);
        }
        msg(sender, "");
    }

    private int calculateRank(ActiveEvent event, UUID playerUuid) {
        var deliveries = event.getPlayerDeliveries();
        int playerCount = deliveries.getOrDefault(playerUuid, 0);
        if (playerCount == 0) return 0;
        int rank = 1;
        for (int count : deliveries.values()) if (count > playerCount) rank++;
        return rank;
    }
    
    private void prefix(String sender, String message) { msg(sender, PREFIX + message); }
    private boolean hasPerm(String sender, String permission) { return permissionChecker.apply(sender, permission); }
    private void noPermission(String sender) {
        localized(sender, "general.no_permission", PREFIX + "&cBu işlem için yetkiniz yok.", Map.of());
    }

    private void localized(String sender, String key, String fallback, Map<String, String> values) {
        msg(sender, configManager.getLanguageConfig().format(key, languageSupplier.get(), fallback, values));
    }
    private void msg(String sender, String message) { messageSender.accept(sender, message.replace("&", "\u00A7")); }
    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase();
        return list.stream().filter(s -> s.toLowerCase().startsWith(p)).toList();
    }
}
