package com.deliverycore.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlDataStorageTest {
    @TempDir
    Path folder;

    @Test
    void importsLegacyProgressOnlyOnFirstOpenAndPreservesUnknownFields() throws Exception {
        Path legacy = folder.resolve("data.yml");
        String legacyData = "player-stats:\n  player:\n    total-deliveries: 12\n"
            + "milestones:\n  farming:\n    player: [first]\n";
        Files.writeString(legacy, legacyData);

        DataStorage store = sqlite(() -> Files.readString(legacy));
        assertEquals(legacyData, store.load());
        String updatedData = legacyData.replace("12", "19");
        store.save(updatedData);

        assertEquals(legacyData, Files.readString(legacy));
        Files.delete(legacy);
        assertEquals(updatedData, sqlite(() -> Files.readString(legacy)).load());
    }

    @Test
    void aBrokenLegacyImportCanBeRetriedAfterTheFileIsFixed() throws Exception {
        Path legacy = folder.resolve("data.yml");
        DataStorage store = sqlite(() -> Files.readString(legacy));
        assertThrows(IOException.class, store::load);
        Files.writeString(legacy, "progress: 7\n");
        assertEquals("progress: 7\n", store.load());
    }

    private DataStorage sqlite(SqlDataStorage.LegacySnapshot migration) {
        return SqlDataStorage.sqlite(folder.resolve("deliverycore.db"), migration);
    }
}
