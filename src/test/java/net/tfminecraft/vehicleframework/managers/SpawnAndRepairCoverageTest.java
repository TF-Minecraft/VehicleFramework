package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.database.*;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.events.VehicleRepairStartEvent;
import net.tfminecraft.vehicleframework.loaders.VehicleLoader;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.util.SpawnLocation;
import net.tfminecraft.vehicleframework.vehicles.*;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.vehicles.util.Fire;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;

class SpawnAndRepairCoverageTest {
    @BeforeAll static void bootstrapRegistries() { net.tfminecraft.vehicleframework.test.RegistryFixture.initialize(); }
    @TempDir Path temp;
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>(), repeating = new ArrayList<>();
    private final VehicleManager manager = mock(VehicleManager.class);
    private final Player player = mock(Player.class, RETURNS_DEEP_STUBS);
    private final ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
    private final World world = mock(World.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    private final Map<String, Vehicle> previousVehicles = new HashMap<>(VehicleLoader.get());
    private VehicleFramework previousPlugin, plugin;
    private String previousRepairItem;
    private int previousDespawnDistance;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<VehicleFramework> framework;
    private MockedConstruction<InventoryManager> inventoryManagers;
    private RepairManager repairs;
    private SpawnManager spawns;
    private VehicleRepository repository;

    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin; plugin = mock(VehicleFramework.class); VehicleFramework.plugin = plugin;
        when(plugin.getName()).thenReturn("VehicleFramework"); when(plugin.namespace()).thenReturn("vehicleframework");
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        bukkit = keep(mockStatic(Bukkit.class)); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of()); bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        framework = keep(mockStatic(VehicleFramework.class)); framework.when(VehicleFramework::getVehicleRepository).thenAnswer(call -> repository);
        keep(mockStatic(TLibs.class)).when(TLibs::getItemAPI).thenReturn(api);
        keep(mockStatic(PersistenceLog.class)); keep(mockStatic(VFLogger.class));
        when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenAnswer(call -> { delayed.add(call.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> { repeating.add(call.getArgument(1)); return mock(BukkitTask.class); });
        inventoryManagers = keep(mockConstruction(InventoryManager.class));
        previousRepairItem = Cache.repairItem; Cache.repairItem = "custom.repair";
        previousDespawnDistance = Cache.despawnDistance; Cache.despawnDistance = 4096;
        when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.getOpenInventory().getTitle()).thenReturn("§7Repair Vehicle");
        when(api.getChecker().checkItemWithPath(any(), eq(Cache.repairItem))).thenReturn(true);
        when(vehicle.getCurrentState().getType()).thenReturn(State.GROUND); when(vehicle.getSeat(any(Entity.class))).thenReturn(null);
        when(manager.get("vehicle-id")).thenReturn(vehicle);
        when(world.getName()).thenReturn("world"); when(manager.get()).thenReturn(new HashMap<>());
        repairs = new RepairManager(manager); spawns = new SpawnManager(manager); spawns.reload(); repeating.clear();
        VehicleLoader.get().clear();
    }
    @AfterEach void cleanup() throws Exception {
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of()); spawns.reload();
        if (repository != null) repository.close();
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin; Cache.repairItem = previousRepairItem; Cache.despawnDistance = previousDespawnDistance;
        VehicleLoader.get().clear(); VehicleLoader.get().putAll(previousVehicles);
    }

    @Test void componentRepairTracksItsVehicleSoClosingTheMenuCancelsIt() {
        VehicleComponent hull = component(Component.HULL); startComponent(player, hull);
        assertSame(vehicle, repairs.getRepairTarget(player));
        when(player.getOpenInventory().getTitle()).thenReturn("Closed"); repairs.tick();
        assertFalse(repairs.isBeingRepaired(hull)); assertFalse(hull.getHealthData().isUnderRepair());
        assertNull(repairs.getRepairTarget(player));
    }

    @Test void onePlayerCannotStartTwoComponentRepairsAtOnce() {
        VehicleComponent hull = component(Component.HULL), pump = component(Component.PUMP); startComponent(player, hull);
        repairs.repairComponent(player, tagged("vf_component_type", "pump"), key("vf_component_type"), vehicle);
        assertFalse(pump.getHealthData().isUnderRepair()); assertTrue(repairs.isBeingRepaired(hull));
        verify(player).sendMessage("§cYou are already repairing something");
    }

    @Test void cancellingTwoClosedRepairMenusDoesNotModifyALiveMapIterator() {
        Player second = mock(Player.class, RETURNS_DEEP_STUBS); when(second.getWorld()).thenReturn(world);
        when(second.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        ActiveWeapon firstGun = weapon("first"), secondGun = weapon("second");
        startWeapon(player, firstGun); startWeapon(second, secondGun);
        when(player.getOpenInventory().getTitle()).thenReturn("Closed"); when(second.getOpenInventory().getTitle()).thenReturn("Closed");
        assertDoesNotThrow(repairs::tick);
        assertFalse(repairs.isBeingRepaired(firstGun)); assertFalse(repairs.isBeingRepaired(secondGun));
    }

    @Test void reversingVehiclesMustStopBeforeAnOutsidePlayerCanRepair() {
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(-.3); repairs.repair(player, vehicle);
        verify(player).sendMessage("§cCannot repair while moving"); verifyNoInteractions(inventoryManagers.constructed().get(0));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void destroyedTargetsReleasePendingRepairState(boolean weapon) {
        VehicleComponent hull = component(Component.HULL); ActiveWeapon gun = weapon("gun");
        if (weapon) startWeapon(player, gun); else startComponent(player, hull);
        when(vehicle.isDestroyed()).thenReturn(true); delayed.get(0).run();
        assertFalse(weapon ? repairs.isBeingRepaired(gun) : repairs.isBeingRepaired(hull));
        assertNull(repairs.getRepairTarget(player));
        assertFalse((weapon ? gun.getHealthData() : hull.getHealthData()).isUnderRepair());
    }

    @Test void openingRepairsChecksCancellationItemFlyingMovementAndMechanicSeats() {
        repairs.repair(null, vehicle); repairs.repair(player, null); verifyNoInteractions(plugins);
        doAnswer(call -> { ((VehicleRepairStartEvent) call.getArgument(0)).setCancelled(true); return null; }).when(plugins).callEvent(any());
        repairs.repair(player, vehicle); verifyNoInteractions(inventoryManagers.constructed().get(0));
        doNothing().when(plugins).callEvent(any()); when(api.getChecker().checkItemWithPath(any(), eq(Cache.repairItem))).thenReturn(false);
        repairs.repair(player, vehicle); assertNull(repairs.getTool(player));
        when(api.getChecker().checkItemWithPath(any(), eq(Cache.repairItem))).thenReturn(true);
        when(vehicle.getCurrentState().getType()).thenReturn(State.FLYING); repairs.repair(player, vehicle);
        verify(player).sendMessage("§cCannot repair while flying");
        when(vehicle.getCurrentState().getType()).thenReturn(State.GROUND); when(vehicle.getAccessPanel().getSpeed()).thenReturn(.3);
        repairs.repair(player, vehicle); verify(player).sendMessage("§cCannot repair while moving");
        when(vehicle.getSeat(player)).thenReturn(new Seat(SeatType.PASSENGER, "seat")); repairs.repair(player, vehicle);
        verify(player).sendMessage("§cYou are not in a mechanic seat");
        when(vehicle.getSeat(player)).thenReturn(new Seat(SeatType.MECHANIC, "mechanic")); repairs.repair(player, vehicle);
        assertEquals("repair", repairs.getTool(player)); verify(inventoryManagers.constructed().get(0)).repairWindow(null, player, vehicle, true, "repair");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completedRepairsRefreshHealthAndReleaseTracking(boolean weapon) {
        VehicleComponent hull = component(Component.HULL); ActiveWeapon gun = weapon("gun");
        if (weapon) startWeapon(player, gun); else startComponent(player, hull);
        repairs.tick(); verify(inventoryManagers.constructed().get(0)).repairWindow(player.getOpenInventory().getTopInventory(), player, vehicle, false, "repair");
        HealthData health = weapon ? gun.getHealthData() : hull.getHealthData(); for (int tick = 0; tick < 20; tick++) health.tick();
        delayed.get(0).run(); assertFalse(weapon ? repairs.isBeingRepaired(gun) : repairs.isBeingRepaired(hull));
        assertNull(repairs.getRepairTarget(player)); assertTrue(health.getDamage() >= 6 && health.getDamage() <= 13);
        verify(vehicle).updateBoard(); verify(player).sendMessage("§aRepaired §e" + (weapon ? "Cannon gun" : "Hull"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitCancellationPreventsDelayedSuccessMessages(boolean weapon) {
        VehicleComponent hull = component(Component.HULL); ActiveWeapon gun = weapon("gun");
        if (weapon) startWeapon(player, gun); else startComponent(player, hull);
        repairs.stop(player); delayed.get(0).run();
        assertNull(repairs.getTool(player)); assertFalse(weapon ? repairs.isBeingRepaired(gun) : repairs.isBeingRepaired(hull));
        verify(player).sendMessage("§cRepairing cancelled"); verify(vehicle, never()).updateBoard();
    }

    @Test void repairMenusDispatchToolsComponentsAndWeaponsAndIgnoreUnrelatedClicks() {
        InventoryClickEvent unrelated = click(null, null); repairs.repairEvent(unrelated); verify(unrelated, never()).setCancelled(true);
        InventoryClickEvent otherType = click(new VFInventoryHolder("vehicle-id", VFGUI.OWNERSHIP), null); repairs.repairEvent(otherType); verify(otherType, never()).setCancelled(true);
        InventoryClickEvent empty = click(new VFInventoryHolder("vehicle-id", VFGUI.REPAIR), null); repairs.repairEvent(empty); verify(empty).setCancelled(true);
        repairs.repairEvent(repairClick(tagged("unused", "unused")));
        repairs.repairEvent(repairClick(tagged("vf_tool_type", "water"))); assertEquals("water", repairs.getTool(player));
        verify(inventoryManagers.constructed().get(0)).repairWindow(null, player, vehicle, true, "water");
        VehicleComponent hull = component(Component.HULL); Fire fire = new Fire(); fire.setProgress(50);
        when(hull.isOnFire()).thenReturn(true); when(hull.getFire()).thenReturn(fire);
        repairs.repairEvent(repairClick(tagged("vf_component_type", "hull"))); assertTrue(fire.getProgress() >= 47 && fire.getProgress() <= 50);
        verify(vehicle).updateBoard();
        repairs.repairEvent(repairClick(tagged("vf_tool_type", "repair"))); ActiveWeapon gun = weapon("gun");
        repairs.repairEvent(repairClick(tagged("vf_weapon_repair_type", "gun"))); assertTrue(repairs.isBeingRepaired(gun));
    }

    @Test void repairActionsRejectMissingHealthyOccupiedOrCancelledTargets() {
        repairs.repair(player, vehicle);
        when(vehicle.getComponent(Component.HULL)).thenReturn(null);
        repairs.repairComponent(player, tagged("vf_component_type", "hull"), key("vf_component_type"), vehicle);
        when(vehicle.getWeaponHandler().getWeapon("missing")).thenReturn(null);
        repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "missing"), key("vf_weapon_repair_type"), vehicle);
        VehicleComponent hull = component(Component.HULL); hull.getHealthData().setDamage(0);
        repairs.repairComponent(player, tagged("vf_component_type", "hull"), key("vf_component_type"), vehicle);
        ActiveWeapon gun = weapon("gun"); gun.getHealthData().setDamage(0);
        repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "gun"), key("vf_weapon_repair_type"), vehicle); assertTrue(delayed.isEmpty());
        hull.getHealthData().setDamage(20); gun.getHealthData().setDamage(20);
        doAnswer(call -> { VehicleRepairStartEvent event = call.getArgument(0); assertSame(vehicle, event.getVehicle());
            assertSame(VehicleRepairStartEvent.getHandlerList(), event.getHandlers()); event.setCancelled(true); return null; }).when(plugins).callEvent(any());
        repairs.repairComponent(player, tagged("vf_component_type", "hull"), key("vf_component_type"), vehicle);
        repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "gun"), key("vf_weapon_repair_type"), vehicle); assertTrue(delayed.isEmpty());
        repairs.repairEvent(repairClick(tagged("vf_tool_type", "water"))); Fire fire = new Fire(); fire.setProgress(50);
        when(hull.isOnFire()).thenReturn(true); when(hull.getFire()).thenReturn(fire);
        repairs.repairComponent(player, tagged("vf_component_type", "hull"), key("vf_component_type"), vehicle); assertEquals(50, fire.getProgress());
        repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "gun"), key("vf_weapon_repair_type"), vehicle); assertTrue(delayed.isEmpty());
    }

    @Test void alreadyActiveTargetsAndPlayersCannotStartCompetingRepairs() {
        VehicleComponent hull = component(Component.HULL); ActiveWeapon gun = weapon("gun"); startComponent(player, hull);
        repairs.repairComponent(player, tagged("vf_component_type", "hull"), key("vf_component_type"), vehicle);
        verify(player).sendMessage("§cComponent is already under repair");
        repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "gun"), key("vf_weapon_repair_type"), vehicle);
        verify(player).sendMessage("§cYou are already repairing something"); repairs.stop(player); startWeapon(player, gun);
        repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "gun"), key("vf_weapon_repair_type"), vehicle);
        verify(player).sendMessage("§cWeapon is already under repair");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aCancelledAttemptCannotFinishANewerRepairOfTheSameTarget(boolean weapon) {
        VehicleComponent hull = component(Component.HULL); ActiveWeapon gun = weapon("gun");
        if (weapon) startWeapon(player, gun); else startComponent(player, hull);
        repairs.stop(player);
        if (weapon) startWeapon(player, gun); else startComponent(player, hull);
        delayed.get(0).run();
        assertTrue(weapon ? repairs.isBeingRepaired(gun) : repairs.isBeingRepaired(hull));
        assertSame(vehicle, repairs.getRepairTarget(player)); verify(vehicle, never()).updateBoard();
        delayed.get(1).run(); assertNull(repairs.getRepairTarget(player)); verify(vehicle).updateBoard();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anOldDestroyedVehicleCallbackCannotCancelRepairingADifferentVehicle(boolean weapon) {
        VehicleComponent hull = component(Component.HULL); ActiveWeapon gun = weapon("gun");
        if (weapon) startWeapon(player, gun); else startComponent(player, hull);
        repairs.stop(player); when(vehicle.isDestroyed()).thenReturn(true);
        ActiveVehicle replacement = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
        when(replacement.getSeat(player)).thenReturn(null); when(replacement.getCurrentState().getType()).thenReturn(State.GROUND);
        repairs.repair(player, replacement);
        if (weapon) {
            ActiveWeapon next = mock(ActiveWeapon.class); when(next.getHealthData()).thenReturn(new HealthData(100, 20, 20));
            when(replacement.getWeaponHandler().getWeapon("next")).thenReturn(next);
            repairs.repairWeapon(player, tagged("vf_weapon_repair_type", "next"), key("vf_weapon_repair_type"), replacement);
        } else {
            VehicleComponent next = mock(VehicleComponent.class); when(next.getType()).thenReturn(Component.HULL);
            when(next.getHealthData()).thenReturn(new HealthData(100, 20, 20)); when(replacement.getComponent(Component.HULL)).thenReturn(next);
            repairs.repairComponent(player, tagged("vf_component_type", "hull"), key("vf_component_type"), replacement);
        }
        delayed.get(0).run(); assertSame(replacement, repairs.getRepairTarget(player));
        verify(replacement, never()).updateBoard(); delayed.get(1).run(); assertNull(repairs.getRepairTarget(player));
    }

    @Test void clickingAnItemInThePlayersInventoryCannotDispatchRepairMenuMetadata() {
        repairs.repair(player, vehicle); VehicleComponent hull = component(Component.HULL);
        InventoryClickEvent event = repairClick(tagged("vf_component_type", "hull"));
        when(event.getRawSlot()).thenReturn(27); repairs.repairEvent(event);
        verify(event).setCancelled(true); assertFalse(hull.getHealthData().isUnderRepair()); assertTrue(delayed.isEmpty());
    }

    @Test void staleRepairMenusIgnoreVehiclesThatHaveUnloaded() {
        repairs.repair(player, vehicle); when(manager.get("vehicle-id")).thenReturn(null);
        InventoryClickEvent event = repairClick(tagged("vf_component_type", "hull"));
        assertDoesNotThrow(() -> repairs.repairEvent(event)); verify(event).setCancelled(true); assertTrue(delayed.isEmpty());
    }

    @Test void spawnQueueDeduplicatesFindsLegacyNamesAndRemovesCompletedEntries() {
        Chunk chunk = chunk(0, 0); Location loc = new Location(world, 2, 64, 3);
        SpawnLocation queued = new SpawnLocation(chunk, loc, "vehicle.json");
        assertSame(chunk, queued.getChunk());
        assertFalse(SpawnManager.exists(queued)); SpawnManager.remove(queued); SpawnManager.add(queued);
        assertFalse(SpawnManager.exists(new SpawnLocation(chunk, loc, "another-vehicle")));
        SpawnManager.add(new SpawnLocation(chunk, new Location(world, 4, 64, 3), "VEHICLE.JSON"));
        assertTrue(SpawnManager.exists(queued)); assertSame(loc, SpawnManager.findSpawnLocation("VEHICLE").orElseThrow());
        assertTrue(SpawnManager.findSpawnLocation(null).isEmpty()); assertTrue(SpawnManager.findSpawnLocation(" ").isEmpty());
        assertTrue(SpawnManager.findSpawnLocation("missing").isEmpty()); SpawnManager.remove(queued); assertFalse(SpawnManager.exists(queued));
        assertEquals("", SpawnManager.stripJson(null)); assertEquals("vehicle", SpawnManager.stripJson("vehicle.JSON"));
        assertEquals("vehicle", SpawnManager.stripJson("vehicle")); spawns.save();
    }

    @Test void loadedChunkSnapshotsQueueKnownWorldsAndSkipInvalidDeletedAndAlreadyActiveRows() {
        openRepository(); Chunk chunk = chunk(1, -1); when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
        when(world.getChunkAt(1, -1)).thenReturn(chunk); bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
        repository.upsert(snapshot("queued", "world", false)); spawns.start();
        assertEquals(new Location(world, 20, 64, -2, 45, 0), SpawnManager.findSpawnLocation("queued").orElseThrow());
        spawns.enqueueSnapshot(null); spawns.enqueueSnapshot(snapshot(null, "world", false)); spawns.enqueueSnapshot(snapshot("deleted", "world", true));
        when(manager.getByUUID("active")).thenReturn(vehicle); spawns.enqueueSnapshot(snapshot("active", "world", false));
        spawns.enqueueSnapshot(snapshot("missing-world", "unloaded", false));
        assertTrue(SpawnManager.findSpawnLocation("active").isEmpty()); assertTrue(SpawnManager.findSpawnLocation("deleted").isEmpty());
        assertTrue(SpawnManager.findSpawnLocation("missing-world").isEmpty());
        spawns.chunkLoad(new ChunkLoadEvent(chunk(9, 9), false)); spawns.reload(); assertTrue(SpawnManager.findSpawnLocation("queued").isPresent());
    }

    @Test void queuedVehiclesRetryTransientFailuresAndAreRemovedOnlyAfterSuccessfulSpawn() {
        openRepository(); Vehicle template = mock(Vehicle.class); VehicleLoader.get().put("cart", template);
        repository.upsert(snapshot("queued", "world", false)); Chunk chunk = chunk(1, -1); Location loc = new Location(world, 20, 64, -2);
        SpawnLocation queued = new SpawnLocation(chunk, loc, "queued.json"); SpawnManager.add(queued);
        assertFalse(spawns.trySpawn(queued)); assertTrue(SpawnManager.exists(queued));
        ActiveVehicle loaded = mock(ActiveVehicle.class); when(manager.spawn(eq(loc), same(template), any(IncompleteVehicle.class))).thenReturn(loaded);
        assertTrue(spawns.trySpawn(queued)); assertFalse(SpawnManager.exists(queued));
        SpawnManager.add(queued); when(manager.getByUUID("queued")).thenReturn(loaded); assertTrue(spawns.trySpawn(queued)); assertFalse(SpawnManager.exists(queued));
    }

    @Test void missingPersistencePayloadTypeOrWorldLeavesQueueEntriesAvailableForRetry() {
        SpawnLocation queued = new SpawnLocation(chunk(1, -1), new Location(world, 20, 64, -2), "queued"); SpawnManager.add(queued);
        assertFalse(spawns.trySpawn(queued)); spawns.chunkLoad(new ChunkLoadEvent(chunk(1, -1), false));
        openRepository(); assertFalse(spawns.trySpawn(queued)); repository.upsert(snapshot("queued", "world", false));
        assertFalse(spawns.trySpawn(queued)); VehicleLoader.get().put("cart", mock(Vehicle.class));
        assertFalse(spawns.trySpawn(new SpawnLocation(null, null, "queued")));
        assertFalse(spawns.trySpawn(new SpawnLocation(null, new Location(null, 0, 64, 0), "queued")));
        assertTrue(SpawnManager.exists(queued)); assertFalse(SpawnManager.isComplete(null));
        IncompleteVehicle blank = mock(IncompleteVehicle.class); when(blank.getId()).thenReturn(" "); assertFalse(SpawnManager.isComplete(blank));
    }

    @Test void scheduledSpawnCycleWaitsForNearbyPlayersAndRetriesAfterPlatformFailure() {
        openRepository(); Vehicle template = mock(Vehicle.class); VehicleLoader.get().put("cart", template);
        repository.upsert(snapshot("queued", "world", false)); Location loc = new Location(world, 20, 64, -2);
        SpawnManager.add(new SpawnLocation(chunk(1, -1), loc, "queued")); spawns.start(); Runnable cycle = repeating.get(0);
        cycle.run(); verify(manager, never()).spawn(any(Location.class), any(Vehicle.class), any(IncompleteVehicle.class));
        Player distant = mock(Player.class); when(distant.getWorld()).thenReturn(world); when(distant.getLocation()).thenReturn(new Location(world, 1000, 64, 0));
        Player otherWorld = mock(Player.class); when(otherWorld.getWorld()).thenReturn(mock(World.class));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(distant, otherWorld)); cycle.run();
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        when(manager.spawn(eq(loc), same(template), any(IncompleteVehicle.class))).thenThrow(new IllegalStateException("Model unavailable")).thenReturn(vehicle);
        assertThrows(IllegalStateException.class, cycle::run); assertTrue(SpawnManager.findSpawnLocation("queued").isPresent());
        cycle.run(); assertTrue(SpawnManager.findSpawnLocation("queued").isEmpty());
    }

    @Test void unloadEventsOnlyUnloadVehiclesInTheAffectedChunkWithoutLoadingOtherChunks() {
        Chunk affected = chunk(-1, 2); Entity inside = mock(Entity.class), outside = mock(Entity.class), broken = mock(Entity.class);
        when(inside.getLocation()).thenReturn(new Location(world, -.5, 64, 32)); when(outside.getLocation()).thenReturn(new Location(world, 0, 64, 32));
        when(broken.getLocation()).thenThrow(new IllegalStateException("Unloaded")); ActiveVehicle other = mock(ActiveVehicle.class);
        manager.get().put(inside, vehicle); manager.get().put(outside, other); manager.get().put(broken, other);
        spawns.chunkUnload(new ChunkUnloadEvent(affected)); verify(manager).unload(vehicle, "on chunk unload"); verify(manager, never()).unload(other, "on chunk unload");
        spawns.entitiesUnload(new EntitiesUnloadEvent(affected, List.of(inside, mock(Entity.class)))); verify(manager).unload(vehicle, "on entity unload");
        assertFalse(SpawnManager.entityInChunk(null, affected)); assertFalse(SpawnManager.entityInChunk(inside, null));
        assertFalse(SpawnManager.entityInChunk(broken, affected)); verify(world, never()).getChunkAt(any(Location.class));
    }

    private void openRepository() { repository = VehicleRepository.open(temp.resolve("vehicles.db").toFile()); }
    private Chunk chunk(int x, int z) {
        Chunk chunk = mock(Chunk.class); when(chunk.getWorld()).thenReturn(world); when(chunk.getX()).thenReturn(x); when(chunk.getZ()).thenReturn(z); return chunk;
    }
    private VehicleSnapshot snapshot(String id, String worldName, boolean deleted) {
        return new VehicleSnapshot(id, "cart", worldName, 20, 64, -2, 45, 1, -1,
                "{\"id\":\"cart\",\"name\":\"Cart\",\"skin\":\"default\",\"components\":{},\"rotators\":{}}", 2, 1, deleted, 1);
    }

    private InventoryClickEvent repairClick(ItemMeta meta) {
        ItemStack item = mock(ItemStack.class); when(item.getItemMeta()).thenReturn(meta);
        return click(new VFInventoryHolder("vehicle-id", VFGUI.REPAIR), item);
    }
    private InventoryClickEvent click(InventoryHolder holder, ItemStack item) {
        InventoryClickEvent event = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        when(event.getWhoClicked()).thenReturn(player); when(event.getView().getTopInventory().getHolder()).thenReturn(holder);
        when(event.getView().getTopInventory().getSize()).thenReturn(27); when(event.getRawSlot()).thenReturn(0);
        when(event.getCurrentItem()).thenReturn(item); return event;
    }

    private VehicleComponent component(Component type) {
        VehicleComponent component = mock(VehicleComponent.class); when(component.getType()).thenReturn(type);
        when(component.getHealthData()).thenReturn(new HealthData(100, 20, 20)); when(vehicle.getComponent(type)).thenReturn(component); return component;
    }
    private ActiveWeapon weapon(String id) {
        ActiveWeapon weapon = mock(ActiveWeapon.class); when(weapon.getId()).thenReturn(id); when(weapon.getName()).thenReturn("Cannon " + id);
        when(weapon.getHealthData()).thenReturn(new HealthData(100, 20, 20)); when(vehicle.getWeaponHandler().getWeapon(id)).thenReturn(weapon); return weapon;
    }
    private void startComponent(Player who, VehicleComponent component) {
        repairs.repair(who, vehicle); repairs.repairComponent(who, tagged("vf_component_type", component.getType().name().toLowerCase()), key("vf_component_type"), vehicle);
    }
    private void startWeapon(Player who, ActiveWeapon weapon) {
        repairs.repair(who, vehicle); repairs.repairWeapon(who, tagged("vf_weapon_repair_type", weapon.getId()), key("vf_weapon_repair_type"), vehicle);
    }
    private ItemMeta tagged(String name, String value) {
        ItemMeta meta = mock(ItemMeta.class, RETURNS_DEEP_STUBS);
        when(meta.getPersistentDataContainer().get(key(name), PersistentDataType.STRING)).thenReturn(value); return meta;
    }
    private NamespacedKey key(String name) { return new NamespacedKey("vehicleframework", name); }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
}
