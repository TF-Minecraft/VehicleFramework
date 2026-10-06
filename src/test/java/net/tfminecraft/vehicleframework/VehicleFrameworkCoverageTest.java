package net.tfminecraft.vehicleframework;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.database.SqliteDatabase;
import net.tfminecraft.coreprotect.CoreProtect;
import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.loaders.*;

class VehicleFrameworkCoverageTest {
    @BeforeAll static void bootstrapRegistries() {
        net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
    }
    @TempDir Path temp;
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final Server server = mock(Server.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final Logger log = mock(Logger.class);
    private final ProtocolManager protocol = mock(ProtocolManager.class);
    private VehicleFramework plugin;
    private VehicleFramework previousPlugin;
    private boolean previousCoreProtect;

    @BeforeEach void setup() throws Exception {
        previousPlugin = VehicleFramework.plugin;
        previousCoreProtect = Cache.coreProtect; Cache.coreProtect = false;
        MockedStatic<Bukkit> bukkit = keep(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getLogger).thenReturn(log); bukkit.when(Bukkit::getServer).thenReturn(server);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of()); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        bukkit.when(Bukkit::getVersion).thenReturn("Paper (MC: 1.21.10)");
        bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.10-R0.1-SNAPSHOT");
        bukkit.when(Bukkit::getUnsafe).thenReturn(mock(UnsafeValues.class, RETURNS_DEEP_STUBS));
        when(server.getPluginManager()).thenReturn(plugins); when(server.getLogger()).thenReturn(log);
        when(server.getScheduler()).thenReturn(scheduler); when(server.getWorlds()).thenReturn(List.of());
        when(server.getVersion()).thenReturn("Paper (MC: 1.21.10)");
        when(server.getBukkitVersion()).thenReturn("1.21.10-R0.1-SNAPSHOT");
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));
        keep(mockStatic(TLibs.class)).when(TLibs::getItemAPI).thenReturn(mock(ItemAPI.class, RETURNS_DEEP_STUBS));
        keep(mockStatic(ProtocolLibrary.class)).when(ProtocolLibrary::getProtocolManager).thenReturn(protocol);
        keep(mockStatic(net.kyori.adventure.util.Services.class, CALLS_REAL_METHODS))
                .when(() -> net.kyori.adventure.util.Services.service(PluginLoader.class)).thenReturn(Optional.of(mock(PluginLoader.class)));
        var description = new PluginDescriptionFile("VehicleFramework", "test", VehicleFramework.class.getName());
        var loader = new TestPluginLoader(description);
        // The platform fixture supplies JavaPlugin's required configured classloader; the production constructor runs.
        plugin = new ByteBuddy().subclass(VehicleFramework.class).make()
                .load(loader, ClassLoadingStrategy.Default.INJECTION).getLoaded().getConstructor().newInstance();
        PluginCommand command = mock(PluginCommand.class); when(command.getPlugin()).thenReturn(plugin);
        when(server.getPluginCommand("vf")).thenReturn(command);
        Files.createDirectories(temp.resolve("bStats"));
        Files.writeString(temp.resolve("bStats/config.yml"), "enabled: false\nserverUuid: lifecycle-test\n");
    }

    @AfterEach void cleanup() throws Exception {
        if (plugin != null) plugin.onDisable();
        VehicleFramework.plugin = previousPlugin;
        Cache.coreProtect = previousCoreProtect;
        VehicleLoader.get().clear(); AmmunitionLoader.get().clear(); FuelLoader.get().clear();
        new WeaponTemplateLoader().clear(); new DeathTemplateLoader().clear(); new ArmorTemplateLoader().clear();
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
    }

    @Test void startupCreatesDefaultsRegistersSystemsAndClosesItsDatabase() throws Exception {
        MockedConstruction<Metrics> metrics = keep(mockConstruction(Metrics.class, (mock, context) -> {
            assertSame(plugin, context.arguments().get(0));
            assertEquals(Integer.valueOf(26823), context.arguments().get(1));
        }));
        plugin.onEnable();
        assertEquals(1, metrics.constructed().size());
        assertSame(plugin, VehicleFramework.getInstance()); assertNotNull(VehicleFramework.getVehicleRepository());
        assertNotNull(VehicleFramework.getTrackRegistry()); assertNotNull(VehicleFramework.getTrackDisplayManager());
        assertNotNull(VehicleFramework.getPacketListener()); assertNotNull(VehicleFramework.getLog());
        assertTrue(Files.isRegularFile(temp.resolve("plugin/data/vehicles.db")));
        assertTrue(Files.isRegularFile(temp.resolve("plugin/templates/weapons/gun_turret.yml")));
        assertNotNull(FuelLoader.getByString("coal")); assertFalse(WeaponTemplateLoader.map.isEmpty());
        verify(plugins, times(6)).registerEvents(any(Listener.class), same(plugin));
        verify(protocol).addPacketListener(any()); verify(plugins, never()).disablePlugin(plugin);
        plugin.onDisable(); assertNull(VehicleFramework.getVehicleRepository());
    }

    @Test void reloadRemovesDeletedFuelVehicleAndAmmunitionDefinitions() throws Exception {
        plugin.createFolders(); plugin.createConfigs();
        Path oldVehicle = temp.resolve("plugin/vehicles/old.yml"), oldAmmo = temp.resolve("plugin/ammunition/old.yml");
        Files.writeString(oldVehicle, "old_cart:\n  model: default\n  skins: {}\n  states: {}\n  components: {}\n  seats: []\n");
        Files.writeString(oldAmmo, "old_shell: {}\n"); Files.writeString(temp.resolve("plugin/fuel.yml"), "old_fuel: {}\n");
        plugin.onEnable();
        assertNotNull(VehicleLoader.getByString("old_cart")); assertNotNull(AmmunitionLoader.getByString("old_shell"));
        assertNotNull(FuelLoader.getByString("old_fuel"));
        Files.delete(oldVehicle); Files.delete(oldAmmo); Files.writeString(temp.resolve("plugin/fuel.yml"), "{}\n");
        plugin.reload();
        assertAll(() -> assertNull(VehicleLoader.getByString("old_cart")),
                () -> assertNull(AmmunitionLoader.getByString("old_shell")), () -> assertNull(FuelLoader.getByString("old_fuel")));
    }

    @Test void defaultFilesAreCreatedOnceAndUserEditsArePreserved() throws Exception {
        plugin.createFolders(); plugin.createConfigs();
        for (String folder : List.of("data/tracks", "data/backups", "vehicles", "ammunition", "templates/weapons", "templates/armor", "templates/roles", "templates/death")) {
            assertTrue(Files.isDirectory(temp.resolve("plugin/" + folder)));
        }
        Path config = temp.resolve("plugin/config.yml"); Files.writeString(config, "skin-item: user.changed\n");
        plugin.createFolders(); plugin.createConfigs(); assertEquals("skin-item: user.changed\n", Files.readString(config));
        Files.createDirectory(temp.resolve("plugin/vehicles/nested")); Files.createDirectory(temp.resolve("plugin/ammunition/nested"));
        plugin.loadConfigs(); assertEquals("user.changed", Cache.skinItem);
        assertTrue(VehicleLoader.get().isEmpty()); assertTrue(AmmunitionLoader.get().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"vehicles/old.yml", "ammunition/old.yml", "fuel.yml"})
    void malformedReloadRetainsTheLastWorkingDefinitions(String failedFile) throws Exception {
        plugin.createFolders();
        plugin.createConfigs();
        Files.writeString(temp.resolve("plugin/vehicles/old.yml"),
                "old_cart: {model: default, skins: {}, states: {}, components: {}, seats: []}\n");
        Files.writeString(temp.resolve("plugin/ammunition/old.yml"), "old_shell: {}\n");
        Files.writeString(temp.resolve("plugin/fuel.yml"), "old_fuel: {}\n");
        plugin.onEnable();
        Object previous = failedFile.startsWith("vehicles") ? VehicleLoader.getByString("old_cart")
                : failedFile.startsWith("ammunition") ? AmmunitionLoader.getByString("old_shell")
                : FuelLoader.getByString("old_fuel");
        assertNotNull(previous);

        Files.writeString(temp.resolve("plugin/" + failedFile), "broken: [unterminated\n");
        assertDoesNotThrow(plugin::reload);

        Object retained = failedFile.startsWith("vehicles") ? VehicleLoader.getByString("old_cart")
                : failedFile.startsWith("ammunition") ? AmmunitionLoader.getByString("old_shell")
                : FuelLoader.getByString("old_fuel");
        assertSame(previous, retained, "A failed load must not discard the last usable registry");
    }

    @Test void databaseOpenFailureDisablesThePluginBeforeRegisteringListeners() throws Exception {
        Files.createDirectory(temp.resolve("plugin")); Files.writeString(temp.resolve("plugin/data"), "not a directory");
        plugin.onEnable(); assertNull(VehicleFramework.getVehicleRepository());
        verify(plugins).disablePlugin(plugin); verify(plugins, never()).registerEvents(any(), any());
        verify(log).warning(contains("Failed to open SQLite vehicles.db"));
    }

    @Test void databaseCloseFailureStillReleasesTheRepositoryReference() throws Exception {
        SqliteDatabase actual = new SqliteDatabase(temp.resolve("connection-fault.db").toFile());
        try {
            MockedConstruction<SqliteDatabase> database = keep(mockConstruction(SqliteDatabase.class,
                    withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(actual)), (db, context) ->
                    doThrow(new IllegalStateException("connection close failed")).when(db).close()));
            plugin.onEnable(); assertNotNull(VehicleFramework.getVehicleRepository());
            assertDoesNotThrow(plugin::onDisable); assertNull(VehicleFramework.getVehicleRepository());
            verify(database.constructed().get(0)).close();
            verify(log).warning(contains("Failed to close SQLite vehicles.db: connection close failed"));
        } finally { actual.close(); }
    }

    @Test void integrationDetectionRegistersMythicMobsAndOnlyEnablesCoreProtectForItsActivePlugin() {
        VehicleFramework.plugin = plugin;
        plugin.setPlugins(); assertFalse(Cache.coreProtect);
        Plugin unrelated = mock(Plugin.class); when(unrelated.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("CoreProtect")).thenReturn(unrelated); plugin.setPlugins(); assertFalse(Cache.coreProtect);
        CoreProtect core = mock(CoreProtect.class); when(plugins.getPlugin("CoreProtect")).thenReturn(core);
        plugin.setPlugins(); assertFalse(Cache.coreProtect);
        when(core.isEnabled()).thenReturn(true); when(plugins.isPluginEnabled("MythicMobs")).thenReturn(true);
        plugin.setPlugins(); assertTrue(Cache.coreProtect);
        verify(plugins).registerEvents(isA(net.tfminecraft.vehicleframework.util.MythicMobsIntegration.class), same(plugin));
    }

    @Test void coreProtectApiRejectsMissingWrongDisabledAndOutdatedImplementations() {
        VehicleFramework.plugin = plugin; assertNull(VehicleFramework.getCoreProtect());
        when(plugins.getPlugin("CoreProtect")).thenReturn(mock(Plugin.class)); assertNull(VehicleFramework.getCoreProtect());
        CoreProtect core = mock(CoreProtect.class); CoreProtectAPI api = mock(CoreProtectAPI.class);
        when(plugins.getPlugin("CoreProtect")).thenReturn(core); when(core.getAPI()).thenReturn(api);
        assertNull(VehicleFramework.getCoreProtect()); when(api.isEnabled()).thenReturn(true); when(api.APIVersion()).thenReturn(9);
        assertNull(VehicleFramework.getCoreProtect()); when(api.APIVersion()).thenReturn(10); assertSame(api, VehicleFramework.getCoreProtect());
    }

    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }

    private final class TestPluginLoader extends ClassLoader implements ConfiguredPluginClassLoader {
        private final PluginDescriptionFile description;
        private JavaPlugin loaded;
        TestPluginLoader(PluginDescriptionFile description) { super(VehicleFramework.class.getClassLoader()); this.description = description; }
        @Override public PluginMeta getConfiguration() { return description; }
        @Override public Class<?> loadClass(String name, boolean resolve, boolean global, boolean libraries) throws ClassNotFoundException {
            return super.loadClass(name, resolve);
        }
        @Override public void init(JavaPlugin value) {
            loaded = value;
            value.init(server, description, temp.resolve("plugin").toFile(), temp.resolve("plugin.jar").toFile(), this, description, log);
        }
        @Override public JavaPlugin getPlugin() { return loaded; }
        @Override public PluginClassLoaderGroup getGroup() { return null; }
        @Override public void close() {}
    }
}
