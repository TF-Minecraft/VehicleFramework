package net.tfminecraft.vehicleframework;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bstats.bukkit.Metrics;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsIntegrationTest {
    @TempDir Path directory;

    @Test void dependencyPreservesExistingOptOutAndServerIdentity() throws Exception {
        Path config=directory.resolve("bStats/config.yml"); Files.createDirectories(config.getParent());
        String original="enabled: false\nserverUuid: retained-server-id\nlogFailedRequests: true\n";
        Files.writeString(config,original);
        Metrics metrics=new Metrics(plugin(),26823);
        try { assertEquals(original,Files.readString(config)); }
        finally { metrics.shutdown(); }
    }

    @Test void dependencyCreatesACompatibleConfigurationOnFirstStartup() {
        Metrics metrics=new Metrics(plugin(),26823);
        try {
            YamlConfiguration config=YamlConfiguration.loadConfiguration(directory.resolve("bStats/config.yml").toFile());
            assertTrue(config.getBoolean("enabled")); assertNotNull(config.getString("serverUuid"));
            assertFalse(config.getBoolean("logSentData")); assertFalse(config.getBoolean("logFailedRequests"));
        } finally { metrics.shutdown(); }
    }

    private Plugin plugin() {
        Plugin plugin=mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.resolve("VehicleFramework").toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("metrics-integration-test"));
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }
}
