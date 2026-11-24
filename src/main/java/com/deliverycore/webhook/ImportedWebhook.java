package com.deliverycore.webhook;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ImportedWebhook(String url, String username, String avatar, String title, String description,
                       String color, String footer, String thumbnail, String embedUrl, String content) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{category\\}|%remaining%");
    private static final Pattern MENTION = Pattern.compile("<@([!&]?)([0-9]{1,20})>|@(everyone|here)\\b|@");
    private static final Map<String, String> COLOR_CODES = Map.of(
            "ORANGE", "FFC800", "RED", "FF0000", "BLACK", "000000", "GREEN", "00FF00",
            "YELLOW", "FFFF00", "CYAN", "00FFFF", "WHITE", "FFFFFF", "BLUE", "0000FF");

    static Optional<ImportedWebhook> load(Path file, boolean start) throws IOException {
        try (var input = Files.newInputStream(file)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(document instanceof Map<?, ?> root)
                    || !(root.get(start ? "category-start" : "category-end") instanceof Map<?, ?> section)) {
                return Optional.empty();
            }
            String url = value(section, "url").trim();
            if (url.isEmpty()) return Optional.empty();
            return Optional.of(new ImportedWebhook(url, value(section, "username"), value(section, "image"),
                    value(section, "title"), value(section, "description"), value(section, "color"),
                    value(section, "footer"), value(section, "thumbnail"), value(section, "webUrl"),
                    value(section, "content")));
        }
    }

    private static String value(Map<?, ?> section, String key) {
        return section.get(key) instanceof String text ? text : "";
    }

    String colorCode() {
        String text = color.trim().toUpperCase(Locale.ROOT);
        String code = COLOR_CODES.getOrDefault(text, text);
        return code.matches("#?[0-9A-F]{1,6}") ? code : "000000";
    }

    String render(String template, String category, String remaining) {
        return PLACEHOLDER.matcher(template).replaceAll(match ->
                Matcher.quoteReplacement(match.group().equals("{category}") ? category : remaining));
    }

    RenderedContent renderContent(String category, String remaining) {
        var mentions = MENTION.matcher(content);
        var users = new LinkedHashSet<String>();
        var roles = new LinkedHashSet<String>();
        boolean everyone = false;
        var template = new StringBuilder();
        while (mentions.find()) {
            if (mentions.group(2) != null) {
                var recipients = mentions.group(1).equals("&") ? roles : users;
                recipients.add(mentions.group(2));
            } else if (mentions.group(3) != null) {
                everyone = true;
            } else {
                mentions.appendReplacement(template, "@\u200B");
            }
        }
        mentions.appendTail(template);
        return new RenderedContent(render(template.toString(), neutralizeAtSigns(category), neutralizeAtSigns(remaining)),
                everyone, List.copyOf(users), List.copyOf(roles));
    }

    private static String neutralizeAtSigns(String text) {
        return text.replace("@", "@\u200B");
    }

    record RenderedContent(String text, boolean everyone, List<String> users, List<String> roles) {}
}
