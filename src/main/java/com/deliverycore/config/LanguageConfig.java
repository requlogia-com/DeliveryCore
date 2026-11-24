package com.deliverycore.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interface for accessing language configuration data.
 * Supports Turkish (tr) and English (en) languages with Turkish as fallback.
 */
public interface LanguageConfig {
    
    /** Turkish locale identifier */
    String TURKISH = "tr";
    
    /** English locale identifier */
    String ENGLISH = "en";
    
    /** Default locale (Turkish) */
    String DEFAULT_LOCALE = TURKISH;
    
    /**
     * Gets a message by key for the specified locale.
     *
     * @param key    the message key
     * @param locale the locale (tr or en)
     * @return an Optional containing the message if found, empty otherwise
     */
    Optional<String> getMessage(String key, String locale);
    
    /**
     * Gets a message by key, falling back to Turkish if not found in locale.
     *
     * @param key    the message key
     * @param locale the preferred locale
     * @return the message, or the key itself if not found in any locale
     */
    String getMessageWithFallback(String key, String locale);

    default String format(String key, String locale, String fallback, Map<String, String> values) {
        String message = getMessage(key, locale)
            .or(() -> getMessage(key, DEFAULT_LOCALE))
            .orElse(fallback);
        String prefix = getMessage("general.prefix", locale)
            .or(() -> getMessage("general.prefix", DEFAULT_LOCALE)).orElse("");
        message = message.replace("{prefix}", prefix);
        for (var value : values.entrySet()) {
            message = message.replace("{" + value.getKey() + "}", value.getValue());
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
    }
    
    /**
     * Gets all messages for a specific locale.
     *
     * @param locale the locale
     * @return an unmodifiable map of message keys to values
     */
    Map<String, String> getMessages(String locale);
    
    /**
     * Gets all available message keys.
     *
     * @return an unmodifiable set of all message keys
     */
    Set<String> getMessageKeys();
    
    /**
     * Gets all supported locales.
     *
     * @return an unmodifiable set of locale identifiers
     */
    Set<String> getSupportedLocales();
}
