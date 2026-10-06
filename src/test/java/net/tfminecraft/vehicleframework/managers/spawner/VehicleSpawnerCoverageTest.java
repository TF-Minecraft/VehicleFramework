package net.tfminecraft.vehicleframework.managers.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.manager.BehaviorManager;
import com.ticxo.modelengine.api.model.bone.manager.MountManager;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.IncompleteVehicle;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.SkinHandler;

class VehicleSpawnerCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final World world = mock(World.class);
    private final Location location = new Location(world, 2, 65, 3);
    private final ArmorStand stand = mock(ArmorStand.class);
    private final Vehicle template = mock(Vehicle.class);
    private final VehicleManager manager = mock(VehicleManager.class);
    private final ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
    private final ModeledEntity modeled = mock(ModeledEntity.class);
    private final MountManager mounts = mock(MountManager.class, withSettings().extraInterfaces(BehaviorManager.class));
    private final Logger logger = mock(Logger.class);
    private final List<List<?>> constructorArguments = new ArrayList<>();
    private MockedStatic<ModelEngineAPI> models;
    private MockedConstruction<ActiveVehicle> vehicles;
    private String previousMob;

    @BeforeAll static void registries() { RegistryFixture.initialize(); }

    @BeforeEach void setup() {
        previousMob = Cache.mythicMob; Cache.mythicMob = "none";
        MockedStatic<Bukkit> bukkit = keep(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
        models = keep(mockStatic(ModelEngineAPI.class));
        var blueprint = model.getBlueprint();
        models.when(() -> ModelEngineAPI.getBlueprint(anyString())).thenReturn(blueprint);
        YamlConfiguration skins = new YamlConfiguration();
        skins.set("red.model", "red_model"); skins.set("blue.model", "blue_model");
        SkinHandler skinHandler = new SkinHandler("red", skins);
        when(template.getSkinHandler()).thenReturn(skinHandler); when(template.getId()).thenReturn("cart");
        when(world.spawn(location, ArmorStand.class)).thenReturn(stand);
        doReturn(Optional.of(mounts)).when(model).getMountManager();
        models.when(() -> ModelEngineAPI.createModeledEntity(any(Entity.class))).thenReturn(modeled);
        models.when(() -> ModelEngineAPI.createActiveModel(anyString())).thenReturn(model);
        vehicles = keep(mockConstruction(ActiveVehicle.class,
                (vehicle, context) -> constructorArguments.add(new ArrayList<>(context.arguments()))));
    }

    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        Cache.mythicMob = previousMob;
    }

    @Test void defaultSkinSpawnsInvisibleTransientMountAndPassesOwnershipToActiveVehicle() {
        ActiveVehicle result = new VehicleSpawner().spawn(location, template, manager, null);
        assertSame(vehicles.constructed().get(0), result);
        assertEquals(Arrays.asList(template, stand, model, manager, null), constructorArguments.get(0));
        verify(stand).setVisible(false); verify(stand).setPersistent(false);
        verify(modeled).setSaved(false); verify(modeled).addModel(model, true); verify(mounts).setCanRide(true);
        models.verify(() -> ModelEngineAPI.createActiveModel("red_model")); verify(stand, never()).remove();
    }

    @Test void restoringUsesPersistedSkinAndPassesTheEntireSnapshotThrough() {
        IncompleteVehicle saved = snapshot("blue");
        ActiveVehicle result = new VehicleSpawner().spawn(location, template, manager, saved);
        assertNotNull(result); assertSame(saved, constructorArguments.get(0).get(4));
        models.verify(() -> ModelEngineAPI.createActiveModel("blue_model"));
    }

    @Test void missingSavedSkinAndUnloadedModelAreRejectedBeforeAnyEntityExists() {
        assertNull(new VehicleSpawner().spawn(location, template, manager, snapshot("removed")));
        models.when(() -> ModelEngineAPI.getBlueprint("red_model")).thenReturn(null);
        assertNull(new VehicleSpawner().spawn(location, template, manager, null));
        verify(world, never()).spawn(any(Location.class), eq(ArmorStand.class));
        assertTrue(vehicles.constructed().isEmpty());
        verify(logger).warning(contains("Missing vehicle skin/model: removed"));
        verify(logger).warning(contains("Missing vehicle skin/model: red"));
    }

    @Test void missingMythicTemplateReportsTheConfiguredNameWithoutCreatingAFallbackEntity() {
        Cache.mythicMob = "VehicleDummy";
        MythicBukkit mythic = mock(MythicBukkit.class, RETURNS_DEEP_STUBS);
        MockedStatic<MythicBukkit> api = keep(mockStatic(MythicBukkit.class)); api.when(MythicBukkit::inst).thenReturn(mythic);
        when(mythic.getMobManager().getMythicMob("VehicleDummy")).thenReturn(Optional.empty());
        assertNull(new VehicleSpawner().spawn(location, template, manager, null));
        verify(world, never()).spawn(any(Location.class), eq(ArmorStand.class));
        verify(logger).warning(contains("could not find the VehicleDummy mythicmob"));
    }

    @Test void mythicSpawnUsesItsBukkitEntityAndDisablesIndependentPersistence() {
        Cache.mythicMob = "VehicleDummy";
        MythicBukkit mythic = mock(MythicBukkit.class, RETURNS_DEEP_STUBS); MythicMob mob = mock(MythicMob.class);
        ActiveMob spawned = mock(ActiveMob.class, RETURNS_DEEP_STUBS); Entity base = mock(Entity.class);
        AbstractLocation adapted = mock(AbstractLocation.class);
        MockedStatic<MythicBukkit> api = keep(mockStatic(MythicBukkit.class)); api.when(MythicBukkit::inst).thenReturn(mythic);
        MockedStatic<BukkitAdapter> adapter = keep(mockStatic(BukkitAdapter.class)); adapter.when(() -> BukkitAdapter.adapt(location)).thenReturn(adapted);
        when(mythic.getMobManager().getMythicMob("VehicleDummy")).thenReturn(Optional.of(mob));
        when(mob.spawn(adapted, 1)).thenReturn(spawned); when(spawned.getEntity().getBukkitEntity()).thenReturn(base);
        assertNotNull(new VehicleSpawner().spawn(location, template, manager, null));
        verify(base).setPersistent(false); assertSame(base, constructorArguments.get(0).get(1));
        verify(world, never()).spawn(any(Location.class), eq(ArmorStand.class)); verify(base, never()).remove();
    }

    @Test void modelCreationFailureRemovesTheAlreadySpawnedBackingEntity() {
        IllegalStateException failure = new IllegalStateException("model creation failed");
        models.when(() -> ModelEngineAPI.createActiveModel("red_model")).thenThrow(failure);
        assertNull(new VehicleSpawner().spawn(location, template, manager, null));
        verify(stand).remove(); assertEquals(0, failure.getSuppressed().length);
        verify(logger).warning(contains("model creation failed")); assertTrue(vehicles.constructed().isEmpty());
    }

    @Test void missingMountManagerUnregistersThePartialModelBeforeRemovingItsEntity() {
        doReturn(Optional.empty()).when(model).getMountManager();
        ModelEngineAPI api = mock(ModelEngineAPI.class, RETURNS_DEEP_STUBS); models.when(ModelEngineAPI::getAPI).thenReturn(api);
        models.when(() -> ModelEngineAPI.getModeledEntity(stand)).thenReturn(modeled);
        assertNull(new VehicleSpawner().spawn(location, template, manager, null));
        var calls = inOrder(api.getModelUpdaters(), stand);
        calls.verify(api.getModelUpdaters()).forceRemoveModeledEntity(modeled); calls.verify(stand).remove();
    }

    @Test void cleanupFailureIsSuppressedAndDoesNotPreventBackingEntityRemoval() {
        IllegalStateException failure = new IllegalStateException("spawn failed");
        IllegalArgumentException cleanup = new IllegalArgumentException("model cleanup failed");
        models.when(() -> ModelEngineAPI.createActiveModel("red_model")).thenThrow(failure);
        models.when(() -> ModelEngineAPI.getModeledEntity(stand)).thenThrow(cleanup);
        assertNull(new VehicleSpawner().spawn(location, template, manager, null));
        verify(stand).remove(); assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        verify(logger).warning(contains("spawn failed"));
    }

    @Test void failureConfiguringANewArmorStandStillRemovesThatEntity() {
        doThrow(new IllegalStateException("entity configuration failed")).when(stand).setVisible(false);
        assertNull(new VehicleSpawner().spawn(location, template, manager, null));
        verify(stand).remove();
        models.verify(() -> ModelEngineAPI.createModeledEntity(any(Entity.class)), never());
    }

    private IncompleteVehicle snapshot(String skin) {
        return new IncompleteVehicle("uuid", "cart", "Name", skin, List.of(), List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, 0, "none", false, List.of());
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
}
