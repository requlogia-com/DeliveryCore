package com.deliverycore.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

interface DataStorage {
    String load() throws IOException;

    void save(String snapshot) throws IOException;

    static DataStorage open(Path directory, ConfigurationSection config) {
        Path legacy = directory.resolve("data.yml").toAbsolutePath().normalize();
        YamlDataStorage yaml = new YamlDataStorage(legacy);
        String backend = (config.isSet("data.storage") ? config.getString("data.storage", "yaml")
            : config.getString("database.database-type", "yaml")).trim().toLowerCase(Locale.ROOT);
        return switch (backend) {
            case "yaml" -> yaml;
            case "sqlite" -> {
                Path database = directory.resolve(config.getString("data.sqlite.file", "deliverycore.db"))
                    .toAbsolutePath().normalize();
                if (database.equals(legacy)) {
                    throw new IllegalArgumentException("data.sqlite.file cannot refer to data.yml");
                }
                yield SqlDataStorage.sqlite(database, yaml::validatedSnapshot);
            }
            case "mysql" -> {
                String url = config.isSet("data.mysql.url") ? config.getString("data.mysql.url") : importedMysqlUrl(config);
                if (!url.startsWith("jdbc:mysql://")) {
                    throw new IllegalArgumentException("data.mysql.url must start with jdbc:mysql://");
                }
                Properties properties = new Properties();
                properties.setProperty("user", config.isSet("data.mysql.username") ? config.getString("data.mysql.username")
                    : config.getString("database.user-name", "deliverycore"));
                properties.setProperty("password", config.isSet("data.mysql.password") ? config.getString("data.mysql.password")
                    : config.getString("database.password", ""));
                properties.setProperty("connectTimeout", "5000");
                properties.setProperty("socketTimeout", "10000");
                yield SqlDataStorage.mysql(url, properties, yaml::validatedSnapshot);
            }
            default -> throw new IllegalArgumentException("Invalid data.storage. Choose yaml, sqlite or mysql.");
        };
    }

    private static String importedMysqlUrl(ConfigurationSection config) {
        String host = config.getString("database.host", "localhost");
        String port = config.getString("database.port", "3306");
        String database = config.getString("database.table-name", "deliverycore");
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }
}

final class YamlDataStorage implements DataStorage {
    private final Path file;

    YamlDataStorage(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public String load() throws IOException {
        return Files.notExists(file) ? "" : Files.readString(file);
    }

    String validatedSnapshot() throws IOException {
        String snapshot = load();
        try {
            parse(snapshot);
            return snapshot;
        } catch (InvalidConfigurationException e) {
            throw new IOException("Unable to import invalid DeliveryCore YAML progress", e);
        }
    }

    static YamlConfiguration parse(String snapshot) throws InvalidConfigurationException {
        YamlConfiguration data = new YamlConfiguration();
        data.loadFromString(snapshot);
        return data;
    }

    @Override
    public void save(String snapshot) throws IOException {
        Path directory = Files.createDirectories(file.getParent());
        Path stagingFile = Files.createTempFile(directory, "deliverycore-data-", ".tmp");
        try {
            Files.writeString(stagingFile, snapshot);
            try {
                Files.move(stagingFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stagingFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(stagingFile);
        }
    }
}
