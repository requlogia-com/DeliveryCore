package com.deliverycore.webhook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ImportedWebhookTest {
    @TempDir Path directory;

    private final WebhookServiceImpl service = new WebhookServiceImpl(null);

    @Test
    void deliversLegacyEnvelopeAndRestrictsMentionsToConfiguredRecipients() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                received.add(exchange.getRequestURI().getPath());
                received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path file = directory.resolve("webhooks.yml");
            Files.writeString(file, """
                    category-start:
                      url: '%s/start'
                      username: '{category} Delivery'
                      title: '{category} Delivery has Begun'
                      description: 'Time: %%remaining%%'
                      footer: 'See {category}'
                      color: ORANGE
                      image: https://example.invalid/avatar
                      thumbnail: https://example.invalid/thumbnail
                      webUrl: https://example.invalid/info
                      content: '@everyone <@&12> <@!34> {category}'
                    category-end:
                      url: '%s/end'
                      title: '{category} Delivery has Ended'
                      color: '#0066b3'
                    """.formatted(base, base));

            assertThat(service.sendImportedNotification(file, true, "Farm @here <@56>", "2h 30m")
                    .get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(received.remove()).isEqualTo("/start");
            JsonObject start = new JsonParser().parse(received.remove()).getAsJsonObject();
            assertThat(start.get("username").getAsString()).isEqualTo("Farm @here <@56> Delivery");
            assertThat(start.get("avatar_url").getAsString()).isEqualTo("https://example.invalid/avatar");
            assertThat(start.get("content").getAsString()).isEqualTo("@everyone <@&12> <@!34> Farm @\u200Bhere <@\u200B56>");
            JsonObject permissions = start.getAsJsonObject("allowed_mentions");
            assertThat(permissions.getAsJsonArray("parse").get(0).getAsString()).isEqualTo("everyone");
            assertThat(permissions.getAsJsonArray("roles").get(0).getAsString()).isEqualTo("12");
            assertThat(permissions.getAsJsonArray("users").get(0).getAsString()).isEqualTo("34");
            JsonObject embed = start.getAsJsonArray("embeds").get(0).getAsJsonObject();
            assertThat(embed.get("title").getAsString()).isEqualTo("Farm @here <@56> Delivery has Begun");
            assertThat(embed.get("description").getAsString()).isEqualTo("Time: 2h 30m");
            assertThat(embed.get("color").getAsInt()).isEqualTo(0xFFC800);
            assertThat(embed.get("url").getAsString()).isEqualTo("https://example.invalid/info");
            assertThat(embed.getAsJsonObject("thumbnail").get("url").getAsString()).isEqualTo("https://example.invalid/thumbnail");
            assertThat(embed.getAsJsonObject("footer").get("text").getAsString()).isEqualTo("See Farm @here <@56>");

            assertThat(service.sendImportedNotification(file, false, "Farm", "0m")
                    .get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(received.remove()).isEqualTo("/end");
            JsonObject end = new JsonParser().parse(received.remove()).getAsJsonObject();
            assertThat(end.has("content")).isFalse();
            assertThat(end.getAsJsonObject("allowed_mentions").getAsJsonArray("parse")).isEmpty();
            assertThat(end.getAsJsonArray("embeds").get(0).getAsJsonObject().get("color").getAsInt())
                    .isEqualTo(0x0066B3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsMalformedUrlsWithoutIncludingTheirCredentials() throws Exception {
        Path file = directory.resolve("webhooks.yml");
        Files.writeString(file, "category-start:\n  url: 'https://example.invalid/secret-token invalid'\n");

        WebhookResult result = service.sendImportedNotification(file, true, "Farm", "0m").join();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Invalid webhook URL");
    }
}
