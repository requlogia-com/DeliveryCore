package com.deliverycore.service;

import com.mysql.cj.jdbc.NonRegisteringDriver;
import org.sqlite.JDBC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/** Stores the full progress snapshot in one atomically updated database row. */
final class SqlDataStorage implements DataStorage {
    @FunctionalInterface
    interface LegacySnapshot {
        String load() throws IOException;
    }

    private static final String SELECT = "SELECT payload FROM deliverycore_state WHERE snapshot_id = 1";
    private static final String INSERT = "INSERT INTO deliverycore_state (snapshot_id, payload) VALUES (1, ?) ";

    private final String url;
    private final Properties properties;
    private final Driver driver;
    private final String sqlType;
    private final String insertIfMissing;
    private final String upsert;
    private final Path sqliteFile;
    private final LegacySnapshot legacy;

    static SqlDataStorage sqlite(Path database, LegacySnapshot legacy) {
        Properties properties = new Properties();
        properties.setProperty("busy_timeout", "5000");
        return new SqlDataStorage("jdbc:sqlite:" + database, properties, new JDBC(), "TEXT",
            "ON CONFLICT(snapshot_id) DO NOTHING", "ON CONFLICT(snapshot_id) DO UPDATE SET payload = ?", database, legacy);
    }

    static SqlDataStorage mysql(String url, Properties properties, LegacySnapshot legacy) {
        try {
            return new SqlDataStorage(url, properties, new NonRegisteringDriver(), "LONGTEXT CHARACTER SET utf8mb4",
                "ON DUPLICATE KEY UPDATE snapshot_id = snapshot_id", "ON DUPLICATE KEY UPDATE payload = ?", null, legacy);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to initialize the MySQL JDBC driver", e);
        }
    }

    private SqlDataStorage(String url, Properties properties, Driver driver, String sqlType,
                           String insertIfMissing, String upsert, Path sqliteFile, LegacySnapshot legacy) {
        this.url = url;
        this.properties = properties;
        this.driver = driver;
        this.sqlType = sqlType;
        this.insertIfMissing = insertIfMissing;
        this.upsert = upsert;
        this.sqliteFile = sqliteFile;
        this.legacy = legacy;
    }

    @Override
    public String load() throws IOException {
        if (sqliteFile != null) {
            Files.createDirectories(sqliteFile.getParent());
        }
        try (Connection connection = connect()) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS deliverycore_state "
                    + "(snapshot_id INTEGER PRIMARY KEY, payload " + sqlType + " NOT NULL)");
            }
            String stored = read(connection);
            if (stored == null) {
                try (var statement = connection.prepareStatement(INSERT + insertIfMissing)) {
                    statement.setString(1, legacy.load());
                    statement.executeUpdate();
                }
                stored = read(connection);
            }
            if (stored == null) {
                throw new IOException("DeliveryCore database did not retain the initial snapshot");
            }
            return stored;
        } catch (SQLException e) {
            throw new IOException("Unable to load progress from the DeliveryCore database", e);
        }
    }

    private static String read(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT); var rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    @Override
    public void save(String snapshot) throws IOException {
        try (Connection connection = connect(); var statement = connection.prepareStatement(INSERT + upsert)) {
            statement.setString(1, snapshot);
            statement.setString(2, snapshot);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Unable to save progress to the DeliveryCore database", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("JDBC driver rejected the configured connection URL");
        }
        return connection;
    }
}
