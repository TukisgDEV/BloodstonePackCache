package dev.tukisg.packcache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackCacheConfigTest {
    @TempDir Path temp;
    private static final String MAIN = "http://resources2.bloodstone.boo:8880/BloodStone.zip";
    private static final String FACTIONS = "http://resources2.bloodstone.boo:8880/BloodStoneFactions.zip";

    @Test void defaultsApplyBothPacks() throws Exception {
        assertEquals(List.of(MAIN, FACTIONS), PackCacheConfig.load(temp.resolve("new.json")).preloadPacks);
    }

    @Test void migratesOldDefaultWithoutChangingFile() throws Exception {
        Path file = temp.resolve("old.json");
        String json = "{\"preloadPacks\":[\"" + MAIN + "\"],\"startupPacks\":[\"" + MAIN + "\",\"" + FACTIONS + "\"]}";
        Files.writeString(file, json);
        assertEquals(List.of(MAIN, FACTIONS), PackCacheConfig.load(file).preloadPacks);
        assertEquals(json, Files.readString(file));
    }

    @Test void preservesCustomSelection() throws Exception {
        Path file = temp.resolve("custom.json");
        Files.writeString(file, "{\"preloadPacks\":[]}");
        assertTrue(PackCacheConfig.load(file).preloadPacks.isEmpty());
    }
}
