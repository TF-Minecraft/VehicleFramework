package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ModeledEntity;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.*;
import net.tfminecraft.vehicleframework.database.*;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.events.*;
import net.tfminecraft.vehicleframework.loaders.*;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.managers.spawner.VehicleSpawner;
import net.tfminecraft.vehicleframework.tracks.*;
import net.tfminecraft.vehicleframework.util.Damager;
import net.tfminecraft.vehicleframework.vehicles.*;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Harness;
import net.tfminecraft.vehicleframework.vehicles.handlers.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.SeatHandler.MountResult;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.*;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;

class VehicleManagerCoverageTest {
    @BeforeAll
    static void initializeSounds() {
        net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
    }

    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<VehiclePersistence> storage;
    private MockedStatic<VehicleLoader> loader;
    private MockedStatic<SpawnManager> spawning;
    private MockedStatic<DeckBody> decks;
    private MockedStatic<VehicleTicketItems> tickets;
    private MockedStatic<TrainTapeInteract> tapeInteractions;
    private MockedStatic<VehicleTicketInteract> ticketInteractions;
    private MockedConstruction<InventoryManager> inventories;
    private MockedConstruction<OwnershipGUIManager> ownership;
    private MockedConstruction<VehicleSpawner> spawners;
    private VehicleManager manager;
    private final ItemAPI items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    private final PluginManager plugins = mock(PluginManager.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final World world = mock(World.class);
    private VehicleFramework oldPlugin;
    private VehicleFramework plugin;
    private String oldDestroyItem, oldRepairItem, oldSkinItem;

    @BeforeEach
    void setup() {
        oldPlugin = VehicleFramework.plugin;
        oldDestroyItem = Cache.destroyItem; oldRepairItem = Cache.repairItem; oldSkinItem = Cache.skinItem;
        Cache.destroyItem = "test:destroy"; Cache.repairItem = "test:repair"; Cache.skinItem = "test:skin";
        plugin = mock(VehicleFramework.class, RETURNS_DEEP_STUBS);
        VehicleFramework.plugin = plugin;
        when(plugin.getName()).thenReturn("VehicleFramework");
        when(plugin.namespace()).thenReturn("vehicleframework");
        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        MockedStatic<TLibs> libs = keep(mockStatic(TLibs.class));
        libs.when(TLibs::getItemAPI).thenReturn(items);
        bukkit = keep(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        keep(mockStatic(VFLogger.class));
        keep(mockStatic(PersistenceLog.class));
        keep(mockStatic(ConsistRelinker.class));
        keep(mockStatic(TrainCollision.class));
        keep(mockStatic(DeckRiders.class));
        tapeInteractions = keep(mockStatic(TrainTapeInteract.class));
        ticketInteractions = keep(mockStatic(VehicleTicketInteract.class));
        decks = keep(mockStatic(DeckBody.class));
        tickets = keep(mockStatic(VehicleTicketItems.class));
        storage = keep(mockStatic(VehiclePersistence.class));
        loader = keep(mockStatic(VehicleLoader.class));
        spawning = keep(mockStatic(SpawnManager.class));
        spawning.when(() -> SpawnManager.findSpawnLocation(anyString())).thenReturn(Optional.empty());
        inventories = keep(mockConstruction(InventoryManager.class));
        ownership = keep(mockConstruction(OwnershipGUIManager.class));
        spawners = keep(mockConstruction(VehicleSpawner.class));
        keep(mockConstruction(SpawnManager.class));
        keep(mockConstruction(RepairManager.class));
        when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(call -> task(call.getArgument(1), scheduled));
        when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenAnswer(call -> task(call.getArgument(1), scheduled));
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> task(call.getArgument(1), timers));
        when(world.getName()).thenReturn("world");
        manager = new VehicleManager();
    }

    @AfterEach
    void teardown() throws Exception {
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = oldPlugin;
        Cache.destroyItem = oldDestroyItem; Cache.repairItem = oldRepairItem; Cache.skinItem = oldSkinItem;
    }

    private BukkitTask task(Runnable runnable, List<Runnable> target) {
        target.add(runnable); BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(target.size()); return task;
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private Player player(String name) {
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        ItemStack air = item(Material.AIR, null);
        when(player.getInventory().getItemInMainHand()).thenReturn(air);
        return player;
    }
    private ActiveVehicle vehicle(String id) {
        ActiveVehicle v = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
        when(v.getUUID()).thenReturn(id);
        when(v.getId()).thenReturn("cart");
        when(v.getName()).thenReturn("Cart " + id);
        when(v.getEntity().isValid()).thenReturn(true);
        when(v.getEntity().getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(v.getLocation()).thenAnswer(ignored -> v.getEntity().getLocation());
        when(v.getOwnerData()).thenReturn(new OwnerData());
        when(v.ticketSource()).thenReturn(v);
        TrainHandler train = v.getBehaviourHandler().getTrainHandler();
        when(v.getTrainHandler()).thenReturn(train);
        return v;
    }
    private void register(ActiveVehicle v) { manager.get().put(v.getEntity(), v); }
    private Seat mount(Player player, ActiveVehicle vehicle) {
        Seat seat = mock(Seat.class); when(seat.getBone()).thenReturn("captain"); when(seat.getType()).thenReturn(SeatType.CAPTAIN);
        when(vehicle.getSeatHandler().getSeat("captain")).thenReturn(seat);
        when(vehicle.addPassenger(player, seat)).thenReturn(MountResult.MOUNTED);
        when(vehicle.changeSeat(player, seat)).thenReturn(MountResult.MOUNTED);
        manager.mount(player, "captain", vehicle);
        when(vehicle.isPassenger(player, false)).thenReturn(true);
        when(vehicle.getSeatHandler().isMounted(player)).thenReturn(true);
        return seat;
    }
    private ItemStack item(Material material, String value) {
        ItemStack item = mock(ItemStack.class, RETURNS_DEEP_STUBS);
        when(item.getType()).thenReturn(material);
        when(item.getItemMeta().getPersistentDataContainer().get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(value);
        return item;
    }
    private InventoryClickEvent click(Player player, ActiveVehicle v, VFGUI type, int slot, ItemStack item) {
        InventoryClickEvent event = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
        Inventory top = mock(Inventory.class);
        VFInventoryHolder holder = new VFInventoryHolder(v.getUUID(), type, v);
        when(top.getHolder()).thenReturn(holder);
        when(top.getSize()).thenReturn(27);
        when(event.getView().getTopInventory()).thenReturn(top);
        when(event.getClickedInventory()).thenReturn(top);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(item);
        when(event.getSlot()).thenReturn(slot);
        when(event.getRawSlot()).thenReturn(slot);
        return event;
    }
    private PlayerInteractEntityEvent interact(Player player, Entity target) {
        return new PlayerInteractEntityEvent(player, target);
    }
    private VehiclePersistence persistence() {
        VehiclePersistence persistence = mock(VehiclePersistence.class);
        storage.when(VehiclePersistence::current).thenReturn(persistence);
        return persistence;
    }
    @SuppressWarnings("unchecked")
    private <K, V> Map<K, V> map(String name) throws Exception {
        Field field = VehicleManager.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<K, V>) field.get(manager);
    }

    @Test
    void registryQueriesAndUnregisterClearOnlyTheRemovedVehicle() throws Exception {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("ID"), other = vehicle("other");
        register(v); register(other);
        assertSame(v, manager.getByUUID("id")); assertSame(v, manager.get("id")); assertSame(v, manager.get(v.getEntity()));
        assertNull(manager.getByUUID("missing")); assertNull(manager.get("missing")); assertNull(manager.get(player));
        assertNull(manager.getByPassenger(player)); assertFalse(manager.isPassenger(player));
        when(v.isPassenger(player, true)).thenReturn(true);
        assertSame(v, manager.getByPassenger(player)); assertTrue(manager.isPassenger(player));
        map("activeVehicle").put(player, v); map("tempVehicle").put(player, v); map("tow").put(player, v);
        manager.unregister(v.getEntity()); manager.unregister(v.getEntity());
        assertNull(manager.get(v.getEntity())); assertSame(other, manager.get(other.getEntity()));
        assertTrue(map("activeVehicle").isEmpty()); assertTrue(map("tempVehicle").isEmpty()); assertTrue(map("tow").isEmpty());
        assertNotNull(manager.getRepairManager()); assertNotNull(manager.getSpawnManager());
    }

    @Test
    void spawnPublishesOnlyInitializedVehiclesAndCleansFailedSpawns() {
        Vehicle type = mock(Vehicle.class); when(type.getId()).thenReturn("cart");
        Location location = new Location(world, 0, 64, 0);
        assertNull(manager.spawn(location, "missing"));
        loader.when(() -> VehicleLoader.getByString("cart")).thenReturn(type);
        assertNull(manager.spawn(location, "cart"));
        ActiveVehicle invalid = mock(ActiveVehicle.class);
        when(spawners.constructed().getFirst().spawn(location, type, manager, null)).thenReturn(invalid);
        assertNull(manager.spawn(location, type));
        ActiveVehicle valid = vehicle("v");
        when(spawners.constructed().getFirst().spawn(location, type, manager, null)).thenReturn(valid);
        assertSame(valid, manager.spawn(location, type));
        assertSame(valid, manager.get(valid.getEntity()));
        verify(valid).restorePassengers(null);
        verify(plugins).callEvent(isA(VehicleSpawnEvent.class));
        doThrow(new IllegalStateException("event failure")).when(plugins).callEvent(isA(VehicleSpawnEvent.class));
        doThrow(new IllegalStateException("cleanup failure")).when(valid).remove(VehicleRemoveReason.UNLOAD);
        assertNull(manager.spawn(location, type));
        verify(valid).remove(VehicleRemoveReason.UNLOAD);
    }

    @Test
    void killIgnoresNonVehiclesAndTombstonesRemovedVehicles() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        Location location = v.getLocation();
        assertEquals(0, manager.kill(player, new Location(null, 0, 0, 0), 10));
        List<Entity> nearby = List.of(player, v.getEntity());
        when(world.getNearbyEntities(location, 10, 10, 10)).thenReturn(nearby);
        assertEquals(1, manager.kill(player, location, 10));
        verify(v).remove(VehicleRemoveReason.ADMIN_KILL);
        VehiclePersistence persistence = persistence(); when(persistence.tombstone("v")).thenReturn(true);
        assertEquals(1, manager.kill(null, location, 10)); verify(persistence).tombstone("v");
    }

    @Test
    void swapAndMouseBindingsRequireAMountedPassenger() throws Exception {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        PlayerSwapHandItemsEvent swap = mock(PlayerSwapHandItemsEvent.class); when(swap.getPlayer()).thenReturn(player);
        manager.swap(swap); verify(v, never()).key(any(), any());
        mount(player, v); manager.swap(swap); verify(v).key(player, Keybind.SWAP);
        PlayerInteractEvent click = mock(PlayerInteractEvent.class); when(click.getPlayer()).thenReturn(player);
        when(click.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        manager.clickWhileMounted(click); verify(v).key(player, Keybind.RIGHT_CLICK);
        manager.inputPacket(player, 0, 0, false, true);
        manager.clickWhileMounted(click); verify(v).key(player, Keybind.SHIFT_RIGHT_CLICK);
        when(click.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
        manager.clickWhileMounted(click); verify(v).key(player, Keybind.SHIFT_LEFT_CLICK);
        map("mountedLeftClickAt").put(player.getUniqueId(), Long.MAX_VALUE);
        PlayerAnimationEvent animation = mock(PlayerAnimationEvent.class);
        when(animation.getPlayer()).thenReturn(player); when(animation.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING);
        manager.swingWhileMounted(animation); verify(v, times(1)).key(player, Keybind.SHIFT_LEFT_CLICK);
        manager.dismount(player); manager.dismount(null);
        manager.swap(swap); verify(v, times(1)).key(player, Keybind.SWAP);
    }

    @Test
    void inputPacketsRouteMovementAndCaptainJunctionRequests() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        manager.inputPacket(player, 1, 1, false, false); verify(v, never()).key(any(), any());
        mount(player, v);
        when(v.getSeatHandler().isMounted(player)).thenReturn(false);
        manager.inputPacket(player, 1, 1, false, false); verify(v, never()).key(any(), any());
        when(v.getSeatHandler().isMounted(player)).thenReturn(true);
        when(v.isTrain()).thenReturn(true); when(v.getSeatHandler().isCaptain(player)).thenReturn(true);
        manager.inputPacket(player, 1, 1, false, false);
        manager.inputPacket(player, -1, -1, false, false);
        verify(v.getTrainHandler()).holdJunction(TrackJunction.Side.LEFT);
        verify(v.getTrainHandler()).holdJunction(TrackJunction.Side.RIGHT);
        for (Keybind key : List.of(Keybind.W, Keybind.S, Keybind.A, Keybind.D)) verify(v).key(player, key);
    }

    @Test
    void leadInteractionOnlyUsesHarnessForSupportedAnimals() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v");
        when(v.getComponent(Component.HARNESS)).thenReturn(null);
        assertFalse(manager.leadInteract(player, v));
        LivingEntity cow = mock(LivingEntity.class); manager.leashedInteract(player, v, cow); verify(cow).setLeashHolder(null);
        Harness harness = mock(Harness.class); when(v.getComponent(Component.HARNESS)).thenReturn(harness);
        when(harness.dismount(player)).thenReturn(true); assertTrue(manager.leadInteract(player, v));
        for (Class<? extends LivingEntity> type : List.of(Horse.class, Donkey.class, Mule.class)) {
            LivingEntity animal = mock(type); manager.leashedInteract(player, v, animal); verify(harness).mount(player, animal);
        }
    }

    @Test
    void towSelectionCanBeReplacedAndAttachmentChecksOccupancyAndDistance() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"), trailer = vehicle("trailer");
        Location towLocation = v.getLocation();
        when(v.getTowHandler().getTowLocation()).thenReturn(towLocation);
        manager.towSelect(player, trailer); manager.towSelect(player, trailer);
        when(v.getTowHandler().isOccupied()).thenReturn(true);
        manager.towAttach(player, v); verify(v.getTowHandler(), never()).attach(any());
        when(v.getTowHandler().isOccupied()).thenReturn(false);
        when(trailer.getEntity().getLocation()).thenReturn(new Location(world, 10, 64, 0));
        manager.towAttach(player, v); verify(v.getTowHandler(), never()).attach(any());
        when(trailer.getEntity().getLocation()).thenReturn(new Location(world, 1, 64, 0));
        manager.towAttach(player, v); verify(v.getTowHandler()).attach(trailer);
    }

    @Test
    void persistenceFailuresPreserveLiveVehiclesAndSuccessfulUnloadRemovesThem() {
        ActiveVehicle v = vehicle("v"); register(v);
        assertFalse(manager.unload(null)); assertFalse(manager.persistDestroy(null));
        assertFalse(manager.persistDestroy(mock(ActiveVehicle.class)));
        assertFalse(manager.unload(v)); verify(v, never()).remove(any(VehicleRemoveReason.class));
        VehiclePersistence persistence = persistence();
        when(persistence.saveLiveResult(v)).thenReturn(VehiclePersistResult.failed("disk full"));
        assertFalse(manager.unload(v, " ")); verify(v, never()).remove(any(VehicleRemoveReason.class));
        when(persistence.saveLiveResult(v)).thenReturn(VehiclePersistResult.alreadyStored("chunk unloaded"));
        assertTrue(manager.unload(v, null)); verify(v).remove(VehicleRemoveReason.UNLOAD);
        when(persistence.saveLiveResult(v)).thenReturn(VehiclePersistResult.saved());
        manager.unloadAll(); verify(v, times(2)).remove(VehicleRemoveReason.UNLOAD);
        when(v.isDestroyed()).thenReturn(true);
        assertTrue(manager.unload(v)); verify(persistence).tombstone("v");
    }

    @Test
    void storedAndLiveDamageOwnershipAndMetadataUseTheCurrentRepository() {
        assertFalse(manager.unloadedDamage(null, .2, .03)); assertFalse(manager.unloadedDamage(" ", .2, .03));
        assertFalse(manager.unloadedDamage("offline", .2, .03));
        assertTrue(manager.readStoredVehicle("offline").isEmpty());
        manager.clearOwnership(null); manager.clearOwnership(" "); manager.clearOwnership("offline");
        ActiveVehicle v = vehicle("v"); register(v); v.getOwnerData().setOwner("player_Ryan");
        try (MockedStatic<VehicleHealthDecay> decay = mockStatic(VehicleHealthDecay.class)) {
            manager.unloadedDamage(" v ", .2, .03);
            decay.verify(() -> VehicleHealthDecay.applyToLive(v, .2, .03));
        }
        VehiclePersistence persistence = persistence();
        when(persistence.applyStoredDecay("offline", .2, .03)).thenReturn(true);
        assertTrue(manager.unloadedDamage(" offline ", .2, .03));
        StoredVehicleMeta meta = new StoredVehicleMeta("offline", "Old cart", "cart", "player_Ryan");
        when(persistence.readMeta("offline")).thenReturn(Optional.of(meta)); assertSame(meta, manager.readStoredVehicle("offline").orElseThrow());
        manager.clearOwnership("v"); assertEquals("none", v.getOwnerData().getOwner()); assertFalse(v.getOwnerData().isWhiteListed());
        verify(persistence).saveLive(v);
        manager.clearOwnership("offline"); verify(persistence).clearOwnership("offline");
    }

    @Test
    void ownerListingsPreferLiveStateAndDeduplicateStoredRows() {
        ActiveVehicle v = vehicle("LIVE"), other = vehicle("other"); register(v); register(other);
        v.getOwnerData().setOwner("player_Ryan"); other.getOwnerData().setOwner("npc_trader");
        assertTrue(manager.listOwnedVehicles(null).isEmpty()); assertTrue(manager.listOwnedVehicles(" ").isEmpty());
        assertEquals(1, manager.listOwnedVehicles("PLAYER_RYAN").size());
        assertEquals(1, manager.listAllPlayerOwnedVehicles().size());
        VehiclePersistence persistence = persistence();
        List<StoredVehicleMeta> stored = List.of(new StoredVehicleMeta("live", "Stale", "cart", "player_Ryan"),
                new StoredVehicleMeta("offline", "Other cart", "cart", "player_Ryan"));
        when(persistence.listByOwner("player_Ryan")).thenReturn(stored);
        when(persistence.listPlayerOwned()).thenReturn(stored);
        List<OwnedVehicleSummary> result = manager.listOwnedVehicles("player_Ryan");
        assertEquals(2, result.size()); assertEquals("Cart LIVE", result.getFirst().getName()); assertTrue(result.getFirst().isSpawned());
        assertFalse(result.get(1).isSpawned()); assertEquals(2, manager.listAllPlayerOwnedVehicles().size());
        Vehicle type = mock(Vehicle.class); loader.when(() -> VehicleLoader.getByString("cart")).thenReturn(type);
        when(persistence.countByOwner(eq("player_Ryan"), anySet())).thenReturn(Map.of("cart", 2, "unknown", 5));
        assertEquals(Map.of(type, 3), manager.getVehiclesByOwner("player_Ryan"));
        verify(persistence).countByOwner("player_Ryan", Set.of("live"));
    }

    @Test
    void offlineLocationsUseLivePendingThenStoredCoordinatesWithoutLoadingChunks() {
        assertTrue(manager.getOfflineLocation(null).isEmpty()); assertTrue(manager.getOfflineLocation(" ").isEmpty());
        assertTrue(manager.getOfflineLocation("missing").isEmpty());
        ActiveVehicle v = vehicle("live"); register(v); assertEquals(v.getLocation(), manager.getOfflineLocation("live").orElseThrow());
        Location pending = new Location(world, 10, 64, 20);
        spawning.when(() -> SpawnManager.findSpawnLocation("pending")).thenReturn(Optional.of(pending));
        assertSame(pending, manager.getOfflineLocation("pending").orElseThrow());
        VehiclePersistence persistence = persistence(); VehicleSnapshot snapshot = mock(VehicleSnapshot.class);
        when(persistence.findLive("stored")).thenReturn(Optional.of(snapshot));
        assertTrue(manager.getOfflineLocation("stored").isEmpty());
        when(snapshot.getWorld()).thenReturn("world"); assertTrue(manager.getOfflineLocation("stored").isEmpty());
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        when(snapshot.getX()).thenReturn(12d); when(snapshot.getY()).thenReturn(65d); when(snapshot.getZ()).thenReturn(-3d); when(snapshot.getYaw()).thenReturn(45f);
        assertEquals(new Location(world, 12, 65, -3, 45, 0), manager.getOfflineLocation("stored").orElseThrow());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void diagnosticDescriptionsHandleMissingAndInvalidEntities() {
        assertEquals("unknown vehicle", VehicleManager.describeVehicle(null));
        ActiveVehicle empty = mock(ActiveVehicle.class); assertEquals("unknown (no-uuid)", VehicleManager.describeVehicle(empty));
        assertEquals("unknown location", VehicleManager.describeLocation(null)); assertEquals("unknown location", VehicleManager.describeLocation(empty));
        ActiveVehicle v = vehicle("v"); assertEquals("cart 'Cart v' (v)", VehicleManager.describeVehicle(v));
        assertEquals("world 0 64 0", VehicleManager.describeLocation(v));
        when(v.getEntity().getLocation()).thenThrow(new IllegalStateException("unloaded")); assertEquals("unknown location", VehicleManager.describeLocation(v));
        when(v.getEntity().isDead()).thenReturn(true); assertEquals("unknown location", VehicleManager.describeLocation(v));
    }

    @Test
    void timersRunIndependentTicksAndSkipUnavailablePersistence() throws Exception {
        ActiveVehicle v = vehicle("v"); register(v); Player player = player("Ryan");
        NamingData naming = mock(NamingData.class); when(naming.tick()).thenReturn(true); map("naming").put(player, naming);
        manager.start(); assertEquals(3, timers.size()); verify(manager.getSpawnManager()).start();
        timers.get(0).run(); timers.get(1).run(); timers.get(2).run();
        verify(v).tick(); verify(v).slowTick(); verify(player).sendMessage("§cNaming timed out.");
        doThrow(new IllegalStateException("broken tick")).when(v).tick();
        doThrow(new IllegalStateException("broken slow tick")).when(v).slowTick();
        manager.towSelect(player, v); when(v.getEntity().getLocation()).thenReturn(new Location(world, 100, 64, 0));
        assertDoesNotThrow(() -> timers.get(0).run()); assertDoesNotThrow(() -> timers.get(1).run());
        assertTrue(map("tow").isEmpty());
        VehiclePersistence persistence = persistence(); when(persistence.saveLiveResult(v)).thenReturn(VehiclePersistResult.failed("full"));
        timers.get(2).run(); verify(persistence).checkpointWal(false); verify(persistence).vacuumIntoBackup();
        when(v.isDestroyed()).thenReturn(true); timers.get(2).run(); verify(persistence, times(1)).saveLiveResult(v);
        manager.reload(); verify(manager.getSpawnManager()).reload();
    }

    @Test
    void menuRefreshUpdatesOnlyMatchingLiveVehicleMenus() {
        ActiveVehicle v = vehicle("v"); register(v); Player player = player("Ryan");
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        Inventory top = mock(Inventory.class); when(player.getOpenInventory().getTopInventory()).thenReturn(top);
        manager.updateInventory();
        for (VFGUI type : List.of(VFGUI.SEAT_SELECTION, VFGUI.SKIN_SELECTION, VFGUI.REPAIR, VFGUI.OWNERSHIP)) {
            when(top.getHolder()).thenReturn(new VFInventoryHolder("v", type)); manager.updateInventory();
        }
        InventoryManager inv = inventories.constructed().getFirst();
        verify(inv).seatSelection(top, player, v, false); verify(inv).skinSelection(top, player, v, false);
        verify(inv).repairWindow(top, player, v, false, null);
        when(top.getHolder()).thenReturn(new VFInventoryHolder("missing", VFGUI.SEAT_SELECTION)); manager.updateInventory();
        when(player.getOpenInventory().getTopInventory()).thenReturn(null); manager.updateInventory();
    }

    @Test
    void seatMenusAndMountsRespectTicketsAndExistingRiders() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        v.getOwnerData().setWhiteListed(true); v.getOwnerData().setOwner("player_Other");
        manager.seatInteract(player, v); verify(inventories.constructed().getFirst(), never()).seatSelection(any(), any(), any(), anyBoolean());
        v.getOwnerData().setTicketsEnabled(true); manager.seatInteract(player, v);
        verify(player).sendMessage("§cYou need a ticket to board this vehicle.");
        tickets.when(() -> VehicleTicketItems.inventoryHas(player, v.getOwnerData().getTicketId())).thenReturn(true);
        manager.seatInteract(player, v); verify(inventories.constructed().getFirst()).seatSelection(null, player, v, true);
        Seat seat = mock(Seat.class); when(seat.getType()).thenReturn(SeatType.PASSENGER); when(seat.getBone()).thenReturn("passenger");
        when(v.getSeatHandler().getSeat("passenger")).thenReturn(seat);
        when(v.addPassenger(player, seat)).thenReturn(MountResult.MOUNTED);
        manager.mount(player, "missing", v); manager.mount(player, "passenger", v); verify(v).addPassenger(player, seat);
        manager.seatInteract(player, v); verify(inventories.constructed().getFirst(), times(1)).seatSelection(null, player, v, true);
    }

    @Test
    void ownershipMenuTogglesListsTicketsAndClearOwnership() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan");
        v.getOwnerData().setOwner("player_Ryan");
        InventoryClickEvent event = click(player, v, VFGUI.OWNERSHIP, 0, item(Material.PAPER, null));
        manager.ownershipClick(event); assertTrue(v.getOwnerData().isWhiteListed()); verify(event).setCancelled(true);
        when(event.getSlot()).thenReturn(8); manager.ownershipClick(event); assertTrue(v.getOwnerData().isTicketsEnabled());
        when(event.getSlot()).thenReturn(4); manager.ownershipClick(event); verify(ownership.constructed().getFirst()).whitelistGui(null, player, v, true);
        when(event.getSlot()).thenReturn(6); manager.ownershipClick(event); assertEquals("none", v.getOwnerData().getOwner()); assertFalse(v.getOwnerData().isWhiteListed());
    }

    @Test
    void chatAddsWhitelistNamesRejectsDuplicatesAndSupportsCancellation() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan");
        InventoryClickEvent menu = click(player, v, VFGUI.OWNERSHIP, 2, item(Material.PAPER, null));
        for (String message : List.of(" Alice ", "Alice", "cancel")) {
            manager.ownershipClick(menu);
            AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, player, message, new HashSet<>());
            manager.nameVehicle(chat); assertTrue(chat.isCancelled());
            scheduled.removeFirst().run();
        }
        assertEquals(List.of("player_Alice"), v.getOwnerData().getWhiteList());
        verify(player).sendMessage("§cAlice is already on the whitelist."); verify(player).sendMessage("§cCancelled.");
        AsyncPlayerChatEvent normal = new AsyncPlayerChatEvent(true, player, "hello", new HashSet<>()); manager.nameVehicle(normal); assertFalse(normal.isCancelled());
        InventoryClickEvent remove = click(player, v, VFGUI.WHITELIST, 0, item(Material.PLAYER_HEAD, "player_Alice"));
        manager.whitelistClick(remove); assertTrue(v.getOwnerData().getWhiteList().isEmpty());
        when(remove.getSlot()).thenReturn(26); manager.whitelistClick(remove); verify(ownership.constructed().getFirst()).ownershipGui(null, player, v, true);
    }

    @Test
    void takeoverCanExpireAndCanReplaceAnExistingOwner() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        manager.startTakeover(player); manager.startTakeover(player); verify(scheduler).cancelTask(1);
        scheduled.removeLast().run(); verify(player).sendMessage("§cTakeover expired.");
        manager.startTakeover(player); PlayerInteractEntityEvent event = interact(player, v.getEntity());
        manager.vehicleInteract(event); assertEquals("player_Ryan", v.getOwnerData().getOwner()); assertTrue(event.isCancelled());
        v.getOwnerData().setOwner("player_Other"); manager.startTakeover(player); manager.vehicleInteract(interact(player, v.getEntity()));
        assertEquals("player_Ryan", v.getOwnerData().getOwner());
    }

    @Test
    void interactionCancellationAndDeckMappingPrecedeOwnershipAndActions() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        Entity orphan = mock(Entity.class); decks.when(() -> DeckBody.isPart(orphan)).thenReturn(true);
        PlayerInteractEntityEvent event = interact(player, orphan); manager.vehicleInteract(event); assertTrue(event.isCancelled());
        Entity deck = mock(Entity.class); decks.when(() -> DeckBody.owner(deck)).thenReturn(v);
        doAnswer(call -> { if (call.getArgument(0) instanceof VehiclePreInteractEvent pre) pre.setCancelled(true); return null; }).when(plugins).callEvent(any(Event.class));
        event = interact(player, deck); manager.vehicleInteract(event); assertTrue(event.isCancelled()); assertEquals("none", v.getOwnerData().getOwner());
        Entity passenger = mock(Entity.class); when(v.isPassenger(passenger, true)).thenReturn(true);
        event = interact(player, passenger); manager.vehicleInteract(event); assertTrue(event.isCancelled());
    }

    @Test
    void interactionClaimsOwnershipAndRoutesToolsFuelSkinNamingAndSeatMenus() throws Exception {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        when(v.getComponent(Component.HARNESS)).thenReturn(null);
        manager.vehicleInteract(interact(player, v.getEntity()));
        assertEquals("player_Ryan", v.getOwnerData().getOwner());
        verify(inventories.constructed().getFirst()).seatSelection(null, player, v, true);
        map("cooldown").clear();
        when(items.getChecker().checkItemWithPath(any(), eq(Cache.repairItem))).thenReturn(true);
        manager.vehicleInteract(interact(player, v.getEntity())); verify(manager.getRepairManager()).repair(player, v);
        when(items.getChecker().checkItemWithPath(any(), eq(Cache.repairItem))).thenReturn(false);
        map("cooldown").clear(); ItemStack nameTag = item(Material.NAME_TAG, null); when(player.getInventory().getItemInMainHand()).thenReturn(nameTag);
        manager.vehicleInteract(interact(player, v.getEntity()));
        AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, player, "Express_Cart", new HashSet<>());
        manager.nameVehicle(chat); assertTrue(chat.isCancelled()); scheduled.removeFirst().run(); verify(v).setName("Express Cart");
        map("cooldown").clear(); when(items.getChecker().checkItemWithPath(any(), eq(Cache.destroyItem))).thenReturn(true);
        manager.vehicleInteract(interact(player, v.getEntity())); verify(v).remove(VehicleRemoveReason.PLAYER_DESTROY);
    }

    @Test
    void seatSelectionMountsEjectsAndEstablishesEntitySeatSelection() throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"), rider = player("Rider"); register(v);
        v.getOwnerData().setOwner("player_Ryan"); manager.seatInteract(player, v);
        Seat seat = mock(Seat.class); when(seat.getBone()).thenReturn("seat"); when(seat.getType()).thenReturn(SeatType.PASSENGER);
        when(v.getSeat("seat")).thenReturn(seat); when(v.addPassenger(player, seat)).thenReturn(MountResult.MOUNTED);
        InventoryClickEvent event = click(player, v, VFGUI.SEAT_SELECTION, 0, item(Material.GREEN_CONCRETE, "seat"));
        manager.seatSelect(event); verify(v).addPassenger(player, seat);
        doReturn(item(Material.YELLOW_CONCRETE, "seat")).when(event).getCurrentItem(); when(seat.getEntity()).thenReturn(rider);
        manager.seatSelect(event); verify(v).dismountPassenger(rider, false);
        assertFalse(map("ejectCooldown").isEmpty());
        when(v.getSeatHandler().getSeat("seat")).thenReturn(seat); manager.mount(rider, "seat", v); verify(v, never()).addPassenger(rider, seat);
        doReturn(item(Material.CYAN_CONCRETE, "seat")).when(event).getCurrentItem(); when(seat.getType()).thenReturn(SeatType.ENTITY);
        manager.seatSelect(event); assertSame(v, map("pendingEntityVehicle").get(player));
        Entity cow = mock(Entity.class); when(cow.getType()).thenReturn(EntityType.COW); when(v.getEntitySeatWhitelist()).thenReturn(List.of("v.cow"));
        when(v.addPassenger(cow, seat)).thenReturn(MountResult.MOUNTED);
        manager.vehicleInteract(interact(player, cow)); verify(v).addPassenger(cow, seat);
        doReturn(item(Material.GRAY_CONCRETE, "seat")).when(event).getCurrentItem(); when(seat.isOccupied()).thenReturn(true); when(seat.getEntity()).thenReturn(cow);
        manager.seatSelect(event); verify(v).dismountPassenger(cow, false); verify(cow).teleport(player.getLocation());
        when(event.getSlot()).thenReturn(25); manager.seatSelect(event); verify(ownership.constructed().getFirst()).ownershipGui(null, player, v, true);
        when(event.getSlot()).thenReturn(26); manager.seatSelect(event); verify(v).dismountPassenger(player, false);
    }

    @Test
    void skinSelectionValidatesModelAndRefreshesOnSuccessfulChange() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); manager.skinInteract(player, v);
        InventoryClickEvent event = click(player, v, VFGUI.SKIN_SELECTION, 0, item(Material.YELLOW_CONCRETE, "blue"));
        manager.skinSelect(event); verify(player).sendMessage("§cAlready using this skin");
        doReturn(item(Material.GREEN_CONCRETE, "blue")).when(event).getCurrentItem();
        try (MockedStatic<SkinHandler> skins = mockStatic(SkinHandler.class)) {
            manager.skinSelect(event); verify(player).sendMessage("§cThat skin is not available.");
            skins.when(() -> SkinHandler.isModelAvailable(any(net.tfminecraft.vehicleframework.vehicles.handlers.skins.VehicleSkin.class))).thenReturn(true);
            manager.skinSelect(event); verify(player).sendMessage("§cCould not change skin.");
            when(v.changeSkin("blue")).thenReturn(true); manager.skinSelect(event);
            verify(inventories.constructed().getFirst()).skinSelection(player.getOpenInventory().getTopInventory(), player, v, false);
        }
    }

    @ParameterizedTest
    @EnumSource(value = InventoryAction.class, names = {"PLACE_ALL", "PLACE_ONE", "PLACE_SOME", "SWAP_WITH_CURSOR", "MOVE_TO_OTHER_INVENTORY", "HOTBAR_SWAP", "HOTBAR_MOVE_AND_READD"})
    void containerRejectsEveryIncomingTransferOfDisallowedItems(InventoryAction action) {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); when(v.hasContainers()).thenReturn(true);
        Container container = mock(Container.class); when(v.getContainerHandler().get("v")).thenReturn(container);
        ItemStack incoming = item(Material.STONE, null);
        InventoryClickEvent event = click(player, v, VFGUI.CONTAINER, 0, incoming);
        when(event.getAction()).thenReturn(action); when(event.getCursor()).thenReturn(incoming);
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) { Inventory inventory = player.getInventory(); when(event.getClickedInventory()).thenReturn(inventory); }
        when(event.getHotbarButton()).thenReturn(2); when(player.getInventory().getItem(2)).thenReturn(incoming);
        manager.containerClick(event); verify(event).setCancelled(true);
        when(container.allows(incoming)).thenReturn(true); clearInvocations(event); manager.containerClick(event); verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void containerDragsCheckTopSlotsAndCloseSanitizesSavedContents() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); when(v.hasContainers()).thenReturn(true);
        Container container = mock(Container.class); when(v.getContainerHandler().get("v")).thenReturn(container);
        Inventory top = mock(Inventory.class); when(top.getSize()).thenReturn(27); when(top.getHolder()).thenReturn(new VFInventoryHolder("v", VFGUI.CONTAINER, v));
        InventoryDragEvent drag = mock(InventoryDragEvent.class, RETURNS_DEEP_STUBS);
        when(drag.getView().getTopInventory()).thenReturn(top); when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getRawSlots()).thenReturn(Set.of(30)); manager.containerDrag(drag); verify(drag, never()).setCancelled(anyBoolean());
        when(drag.getRawSlots()).thenReturn(Set.of(1, 30)); manager.containerDrag(drag); verify(drag).setCancelled(true);
        InventoryCloseEvent close = mock(InventoryCloseEvent.class, RETURNS_DEEP_STUBS);
        when(close.getView().getTopInventory()).thenReturn(top); when(close.getPlayer()).thenReturn(player);
        manager.saveContainer(close); verify(container).stripDisallowed(top, player); verify(container).close(top);
        manager.closeInv(close);
    }

    @Test
    void harnessSuffocationCreativePassengersAndVehicleDamageHaveSeparateRules() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        EntityDamageEvent damage = mock(EntityDamageEvent.class); when(damage.getEntity()).thenReturn(player); when(damage.getCause()).thenReturn(DamageCause.SUFFOCATION);
        when(v.isPassenger(player, SeatType.HARNESS)).thenReturn(true); manager.damagePassenger(damage); verify(damage).setCancelled(true);
        VFEntityDamageEvent direct = new VFEntityDamageEvent(v.getEntity(), null, "FIRE", 12); manager.damageVehicle(direct); assertTrue(direct.isCancelled()); verify(v).damage("FIRE", 12);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        VFEntityDamageEvent passenger = new VFEntityDamageEvent(player, null, "FIRE", 12); manager.damageVehicle(passenger); assertTrue(passenger.isCancelled());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(v.isPassenger(player, false)).thenReturn(true);
        passenger = new VFEntityDamageEvent(player, null, "FIRE", 12); manager.damageVehicle(passenger); assertEquals(6, passenger.getDamage());
        manager.setDamaged(player, true); clearInvocations(damage); manager.damagePassenger(damage); verify(damage, never()).setCancelled(anyBoolean()); manager.setDamaged(player, false);
    }

    @Test
    void mountedMeleeCannotDamageTheRidersVehicleOrItsModelParts() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v); mount(player, v);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Entity chassis = v.getEntity();
        when(event.getDamager()).thenReturn(player); when(event.getEntity()).thenReturn(chassis); when(event.getCause()).thenReturn(DamageCause.ENTITY_ATTACK);
        manager.damagePassenger(event); verify(event).setCancelled(true); verify(v).key(player, Keybind.LEFT_CLICK);
        assertTrue(VehicleManager.isPassengerMeleeOnOwnVehicle(event, manager.get()));
        Entity part = mock(Entity.class); when(event.getEntity()).thenReturn(part);
        try (MockedStatic<ModelEngineAPI> models = mockStatic(ModelEngineAPI.class)) {
            ModeledEntity modeled = mock(ModeledEntity.class);
            models.when(() -> ModelEngineAPI.getModeledEntity(part)).thenReturn(modeled); models.when(() -> ModelEngineAPI.getModeledEntity(v.getEntity())).thenReturn(modeled);
            clearInvocations(event); manager.damagePassenger(event); verify(event).setCancelled(true);
        }
        when(event.getCause()).thenReturn(DamageCause.PROJECTILE); assertFalse(VehicleManager.isPassengerMeleeOnOwnVehicle(event, manager.get()));
        assertFalse(VehicleManager.isPassengerMeleeOnOwnVehicle(mock(EntityDamageEvent.class), manager.get()));
    }

    @Test
    void damageBridgePublishesCustomEventAndDamagesOnlyUncancelledLivingEntities() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v);
        when(v.isPassenger(player, true)).thenReturn(true);
        EntityDamageEvent event = mock(EntityDamageEvent.class); when(event.getEntity()).thenReturn(player); when(event.getCause()).thenReturn(DamageCause.FIRE); when(event.getDamage()).thenReturn(8d);
        try (MockedStatic<Damager> damager = mockStatic(Damager.class)) {
            manager.damagePassenger(event); verify(event).setCancelled(true); damager.verify(() -> Damager.damage(player, 8));
        }
        verify(plugins).callEvent(isA(VFEntityDamageEvent.class));
    }

    @Test
    void quitAndDeathDismountRidersAndCancelPendingTakeovers() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v"); register(v); when(v.isPassenger(player, false)).thenReturn(true);
        manager.startTakeover(player);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player);
        manager.playerLeave(quit); verify(scheduler).cancelTask(1); verify(v).dismountPassenger(player, false);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class); when(death.getEntity()).thenReturn(player);
        manager.passengerDeath(death); verify(v, times(2)).dismountPassenger(player, false);
    }

    @Test
    void rejectedMountRecoveryDeduplicatesAndReleasesItsReservationWhenVehicleDisappears() {
        Player player = player("Ryan"); ActiveVehicle v = vehicle("v");
        manager.recoverRejectedMount(null, player, "seat", player);
        manager.recoverRejectedMount(v, player, "seat", player);
        manager.recoverRejectedMount(v, player, "seat", player); assertEquals(1, scheduled.size());
        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
        scheduled.removeFirst().run();
        manager.recoverRejectedMount(v, player, "seat", player); assertEquals(1, scheduled.size());
        when(v.isDestroyed()).thenReturn(true); manager.recoverRejectedMount(v, player, "seat", player); assertEquals(1, scheduled.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unload-failed", "storage-closed", "missing-save", "unknown-type", "spawn-failed", "missing-seat", "missing-rider", "player", "animal", "rejected-animal"})
    void rejectedMountReloadEitherRetriesTheSavedSeatOrReportsWhyItCannot(String outcome) {
        Player player = player("Ryan"); ActiveVehicle old = vehicle("v"), fresh = vehicle("v"); register(old);
        VehiclePersistence persistence = persistence();
        when(persistence.saveLiveResult(old)).thenReturn(outcome.equals("unload-failed") ? VehiclePersistResult.failed("locked") : VehiclePersistResult.saved());
        doAnswer(call -> { manager.unregister(old.getEntity()); return null; }).when(old).remove(VehicleRemoveReason.UNLOAD);
        IncompleteVehicle saved = mock(IncompleteVehicle.class); when(saved.getId()).thenReturn("cart");
        when(persistence.loadIncomplete("v")).thenReturn(outcome.equals("missing-save") ? Optional.empty() : Optional.of(saved));
        Vehicle type = mock(Vehicle.class);
        loader.when(() -> VehicleLoader.getByString("cart")).thenReturn(outcome.equals("unknown-type") ? null : type);
        when(spawners.constructed().getFirst().spawn(any(), eq(type), eq(manager), eq(saved)))
                .thenReturn(outcome.equals("spawn-failed") ? null : fresh);
        Seat seat = mock(Seat.class); when(seat.getBone()).thenReturn("seat");
        when(fresh.getSeat("seat")).thenReturn(outcome.equals("missing-seat") ? null : seat);
        Entity rider = outcome.contains("animal") ? mock(Entity.class) : player;
        UUID riderId = UUID.randomUUID(); when(rider.getUniqueId()).thenReturn(riderId); when(rider.isValid()).thenReturn(true);
        bukkit.when(() -> Bukkit.getEntity(riderId)).thenReturn(outcome.equals("missing-rider") ? null : rider);
        UUID notifyId = player.getUniqueId(); bukkit.when(() -> Bukkit.getPlayer(notifyId)).thenReturn(player);
        when(fresh.addPassenger(rider, seat)).thenReturn(outcome.equals("rejected-animal") ? MountResult.REJECTED : MountResult.MOUNTED);
        if (outcome.equals("storage-closed")) storage.when(VehiclePersistence::current).thenReturn(persistence, (VehiclePersistence) null);
        manager.recoverRejectedMount(old, rider, "seat", player);
        assertEquals(1, scheduled.size()); scheduled.removeFirst().run();
        if (Set.of("player", "animal", "rejected-animal").contains(outcome)) {
            verify(fresh).addPassenger(rider, seat);
            assertSame(fresh, manager.get(fresh.getEntity()));
        } else if (outcome.equals("unload-failed")) {
            verify(old, never()).remove(any(VehicleRemoveReason.class));
        } else {
            verify(old).remove(VehicleRemoveReason.UNLOAD);
        }
        assertTrue(scheduled.isEmpty(), "A rejected retry must not schedule an unbounded reload loop");
    }

    @ParameterizedTest
    @ValueSource(strings = {"destroyed", "forbidden", "missing-seat", "occupied", "rejected", "unavailable", "mythic-absent"})
    void pendingEntitySeatSelectionChecksAvailabilityBeforeMounting(String outcome) throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); Entity animal = mock(Entity.class);
        when(animal.getType()).thenReturn(EntityType.COW);
        when(v.getEntitySeatWhitelist()).thenReturn(outcome.equals("mythic-absent") ? List.of("mm.pet") : List.of("v.cow"));
        if (outcome.equals("forbidden")) when(v.getEntitySeatWhitelist()).thenReturn(List.of("v.pig"));
        when(v.isDestroyed()).thenReturn(outcome.equals("destroyed"));
        Seat seat = mock(Seat.class); when(seat.getBone()).thenReturn("seat"); when(seat.isOccupied()).thenReturn(outcome.equals("occupied"));
        when(v.getSeat("seat")).thenReturn(outcome.equals("missing-seat") ? null : seat);
        when(v.addPassenger(animal, seat)).thenReturn(outcome.equals("rejected") ? MountResult.REJECTED : MountResult.UNAVAILABLE);
        map("pendingEntityVehicle").put(player, v); map("pendingEntitySeat").put(player, "seat");
        PlayerInteractEntityEvent event = interact(player, animal);
        manager.vehicleInteract(event); assertTrue(event.isCancelled());
        assertTrue(map("pendingEntityVehicle").isEmpty()); assertTrue(map("pendingEntitySeat").isEmpty());
        if (Set.of("rejected", "unavailable").contains(outcome)) verify(v).addPassenger(animal, seat);
        else verify(v, never()).addPassenger(any(), any());
    }

    @Test
    void interactionHonorsCancelledOwnershipCooldownAndItemIntegrations() throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        doAnswer(call -> { if (call.getArgument(0) instanceof VehicleOwnerClaimedEvent claim) claim.setCancelled(true); return null; }).when(plugins).callEvent(any(Event.class));
        manager.vehicleInteract(interact(player, v.getEntity())); assertEquals("none", v.getOwnerData().getOwner());
        map("cooldown").put(player, Long.MAX_VALUE);
        manager.vehicleInteract(interact(player, v.getEntity())); verify(plugins, times(1)).callEvent(isA(VehicleOwnerClaimedEvent.class));
        map("cooldown").clear(); tapeInteractions.when(() -> TrainTapeInteract.handle(player, v)).thenReturn(true);
        PlayerInteractEntityEvent event = interact(player, v.getEntity()); manager.vehicleInteract(event); assertTrue(event.isCancelled());
        map("cooldown").clear(); tapeInteractions.when(() -> TrainTapeInteract.handle(player, v)).thenReturn(false);
        ticketInteractions.when(() -> VehicleTicketInteract.handle(player, v)).thenReturn(true);
        event = interact(player, v.getEntity()); manager.vehicleInteract(event); assertTrue(event.isCancelled());
        map("cooldown").clear(); ticketInteractions.when(() -> VehicleTicketInteract.handle(player, v)).thenReturn(false);
        when(v.hasContainers()).thenReturn(true); when(v.getContainerHandler().open(player)).thenReturn(true);
        manager.vehicleInteract(interact(player, v.getEntity())); verify(v.getContainerHandler()).open(player);
        map("cooldown").clear(); when(v.hasContainers()).thenReturn(false);
        when(v.usesFuel()).thenReturn(true); when(items.getChecker().getAsStringPath(any())).thenReturn("fuel");
        try (MockedStatic<FuelLoader> fuel = mockStatic(FuelLoader.class)) {
            fuel.when(() -> FuelLoader.itemIsFuel("fuel")).thenReturn(true);
            manager.vehicleInteract(interact(player, v.getEntity())); verify(v).refuel(player, "fuel");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "attach", "train-attach", "cannot-tow", "detach", "attachable-train", "not-towable"})
    void sneakingInteractionRoutesTowSelectionAttachmentAndDetachment(String outcome) {
        ActiveVehicle v = vehicle("v"), selected = vehicle("selected"); Player player = player("Ryan"); register(v);
        when(player.isSneaking()).thenReturn(true);
        when(v.isTowable()).thenReturn(outcome.equals("select"));
        when(v.hasTowHandler()).thenReturn(Set.of("attach", "detach").contains(outcome));
        when(v.getTowHandler().isOccupied()).thenReturn(outcome.equals("detach"));
        when(v.getTowHandler().getTowLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(v.isTrain()).thenReturn(Set.of("train-attach", "attachable-train").contains(outcome));
        when(v.getTrainHandler().isAttachable()).thenReturn(true);
        when(selected.getTrainHandler().attach(player, v)).thenReturn(true);
        if (Set.of("attach", "train-attach", "cannot-tow").contains(outcome)) manager.towSelect(player, selected);
        manager.vehicleInteract(interact(player, v.getEntity()));
        switch (outcome) {
            case "select", "attachable-train" -> verify(player).sendMessage(contains("Selected Cart v"));
            case "attach" -> verify(v.getTowHandler()).attach(selected);
            case "train-attach" -> verify(selected.getTrainHandler()).attach(player, v);
            case "cannot-tow" -> verify(player).sendMessage("§cThis vehicle cannot tow anything");
            case "detach" -> verify(v.getTowHandler()).unattach();
            case "not-towable" -> verify(player).sendMessage("§cThis vehicle cannot be towed");
        }
    }

    @Test
    void interactionHandlesLeashedAnimalsLeadDismountSkinAndExistingSeat() throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        Harness harness = mock(Harness.class); when(v.getComponent(Component.HARNESS)).thenReturn(harness);
        Horse horse = mock(Horse.class); when(horse.isLeashed()).thenReturn(true); when(horse.getLeashHolder()).thenReturn(player);
        when(player.getNearbyEntities(10, 10, 10)).thenReturn(List.of(mock(Entity.class), horse));
        manager.vehicleInteract(interact(player, v.getEntity())); verify(harness).mount(player, horse);
        map("cooldown").clear(); when(horse.getPassengers()).thenReturn(List.of(player));
        ItemStack lead = item(Material.LEAD, null); when(player.getInventory().getItemInMainHand()).thenReturn(lead);
        when(harness.dismount(player)).thenReturn(true); manager.vehicleInteract(interact(player, v.getEntity())); verify(harness).dismount(player);
        map("cooldown").clear(); when(harness.dismount(player)).thenReturn(false); when(v.getSeatHandler().isPassenger(player)).thenReturn(true);
        manager.vehicleInteract(interact(player, v.getEntity())); verify(v).key(player, Keybind.RIGHT_CLICK);
        map("cooldown").clear(); when(v.getSeatHandler().isPassenger(player)).thenReturn(false);
        when(items.getChecker().checkItemWithPath(any(), eq(Cache.skinItem))).thenReturn(true);
        manager.vehicleInteract(interact(player, v.getEntity())); verify(inventories.constructed().getFirst()).skinSelection(null, player, v, true);
    }

    @Test
    void seatActionsRejectSelfEjectionTicketsDestroyedVehiclesAndExpiredCooldowns() throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v); manager.seatInteract(player, v);
        Seat seat = mock(Seat.class); when(seat.getType()).thenReturn(SeatType.PASSENGER); when(seat.getEntity()).thenReturn(player);
        when(v.getSeat("seat")).thenReturn(seat); when(v.getSeatHandler().getSeat("seat")).thenReturn(seat);
        InventoryClickEvent event = click(player, v, VFGUI.SEAT_SELECTION, 0, item(Material.YELLOW_CONCRETE, "seat"));
        v.getOwnerData().setOwner("player_Ryan"); manager.seatSelect(event); verify(player).sendMessage("§cYou cannot eject yourself");
        v.getOwnerData().setOwner("player_Other"); manager.seatSelect(event); verify(player).sendMessage("§cSeat is occupied");
        v.getOwnerData().setTicketsEnabled(true); ItemStack green = item(Material.GREEN_CONCRETE, "seat"); when(event.getCurrentItem()).thenReturn(green);
        manager.seatSelect(event); manager.mount(player, "seat", v); verify(player, times(2)).sendMessage("§cYou need a ticket for this vehicle.");
        v.getOwnerData().setTicketsEnabled(false);
        Map<Player, HashMap<String, Long>> cooldowns = map("ejectCooldown"); cooldowns.put(player, new HashMap<>(Map.of("v", System.currentTimeMillis() + 100000)));
        manager.seatSelect(event); manager.seatInteract(player, v); verify(v, never()).addPassenger(any(), any());
        cooldowns.get(player).put("v", 0L); when(v.addPassenger(player, seat)).thenReturn(MountResult.UNAVAILABLE);
        manager.seatSelect(event); assertTrue(cooldowns.isEmpty());
        when(v.isDestroyed()).thenReturn(true); manager.seatSelect(event); verify(player).sendMessage("Vehicle is destroyed");
        manager.skinInteract(player, v); InventoryClickEvent skin = click(player, v, VFGUI.SKIN_SELECTION, 0, green);
        manager.skinSelect(skin); verify(player, times(2)).sendMessage("Vehicle is destroyed");
        manager.mount(null, "seat", v);
    }

    @Test
    void containerGuardsIgnoreUnrelatedMissingAndOutgoingInventories() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan");
        InventoryClickEvent event = click(player, v, VFGUI.SEAT_SELECTION, 0, item(Material.STONE, null));
        Inventory top = event.getView().getTopInventory();
        manager.containerClick(event);
        when(top.getHolder()).thenReturn(null); manager.containerClick(event);
        when(top.getHolder()).thenReturn(new VFInventoryHolder("v", VFGUI.CONTAINER)); manager.containerClick(event);
        when(top.getHolder()).thenReturn(new VFInventoryHolder("v", VFGUI.CONTAINER, v)); manager.containerClick(event);
        when(v.hasContainers()).thenReturn(true); when(v.getContainerHandler().get("v")).thenReturn(null); manager.containerClick(event);
        Container container = mock(Container.class); when(v.getContainerHandler().get("v")).thenReturn(container);
        when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL); manager.containerClick(event); verify(event, never()).setCancelled(anyBoolean());
        InventoryDragEvent drag = mock(InventoryDragEvent.class, RETURNS_DEEP_STUBS); when(drag.getView().getTopInventory()).thenReturn(top);
        when(top.getHolder()).thenReturn(null); manager.containerDrag(drag);
        when(top.getHolder()).thenReturn(new VFInventoryHolder("v", VFGUI.SEAT_SELECTION)); manager.containerDrag(drag);
        when(top.getHolder()).thenReturn(new VFInventoryHolder("v", VFGUI.CONTAINER)); manager.containerDrag(drag);
        when(top.getHolder()).thenReturn(new VFInventoryHolder("v", VFGUI.CONTAINER, v)); when(v.getContainerHandler().get("v")).thenReturn(null); manager.containerDrag(drag);
        verify(drag, never()).setCancelled(anyBoolean());
    }

    @Test
    void initializationFailureStillReturnsNullWhenCleanupSucceeds() {
        Vehicle type = mock(Vehicle.class); ActiveVehicle v = vehicle("v"); Location location = v.getLocation();
        when(spawners.constructed().getFirst().spawn(location, type, manager, null)).thenReturn(v);
        doThrow(new IllegalStateException("failed restoration")).when(v).restorePassengers(null);
        assertNull(manager.spawn(location, type)); verify(v).remove(VehicleRemoveReason.UNLOAD);
    }

    @Test
    void mythicWhitelistRecognizesOnlyTheConfiguredMobType() throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); Entity pet = mock(Entity.class);
        UUID id = UUID.randomUUID(); when(pet.getUniqueId()).thenReturn(id);
        when(v.getEntitySeatWhitelist()).thenReturn(List.of("mm.other", "mm.pet"));
        Seat seat = mock(Seat.class); when(v.getSeat("seat")).thenReturn(seat);
        when(v.addPassenger(pet, seat)).thenReturn(MountResult.MOUNTED);
        try (MockedStatic<io.lumine.mythic.bukkit.MythicBukkit> mythic = mockStatic(io.lumine.mythic.bukkit.MythicBukkit.class)) {
            var plugin = mock(io.lumine.mythic.bukkit.MythicBukkit.class, RETURNS_DEEP_STUBS);
            var mob = mock(io.lumine.mythic.core.mobs.ActiveMob.class, RETURNS_DEEP_STUBS);
            when(mob.getType().getInternalName()).thenReturn("pet");
            when(plugin.getMobManager().getActiveMob(id)).thenReturn(Optional.of(mob));
            mythic.when(io.lumine.mythic.bukkit.MythicBukkit::inst).thenReturn(plugin);
            map("pendingEntityVehicle").put(player, v); map("pendingEntitySeat").put(player, "seat");
            manager.vehicleInteract(interact(player, pet));
        }
        verify(v).addPassenger(pet, seat);
    }

    @Test
    void passengerValidationHandlesStaleRidersAndRejectedMounts() throws Exception {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        when(v.isPassenger(player, true)).thenReturn(true);
        manager.seatInteract(player, v); verify(inventories.constructed().getFirst(), never()).seatSelection(any(), any(), any(), anyBoolean());
        when(v.isPassenger(player, true)).thenReturn(false);
        Seat seat = mock(Seat.class); when(seat.getType()).thenReturn(SeatType.CAPTAIN); when(seat.getBone()).thenReturn("seat");
        when(v.getSeatHandler().getSeat("seat")).thenReturn(seat); when(v.addPassenger(player, seat)).thenReturn(MountResult.REJECTED);
        manager.mount(player, "seat", v); verify(player).closeInventory(); assertEquals(1, scheduled.size());
        PlayerAnimationEvent event = mock(PlayerAnimationEvent.class); when(event.getPlayer()).thenReturn(player);
        when(event.getAnimationType()).thenReturn(PlayerAnimationType.OFF_ARM_SWING); manager.swingWhileMounted(event);
        when(event.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING); manager.swingWhileMounted(event);
        when(event.getPlayer()).thenReturn(null); manager.swingWhileMounted(event);
        verify(v, never()).key(any(), any());
        Map<Player, HashMap<String, Long>> cooldowns = map("ejectCooldown"); cooldowns.put(player, new HashMap<>(Map.of("different-vehicle", Long.MAX_VALUE)));
        manager.seatInteract(player, v); verify(inventories.constructed().getFirst()).seatSelection(null, player, v, true);
    }

    @Test
    void cleanupWithoutPassengersStillUnregistersPacketsAndPreservesUnrelatedDamage() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        try (MockedStatic<VehicleFramework> framework = mockStatic(VehicleFramework.class)) {
            var packets = mock(net.tfminecraft.vehicleframework.protocol.VehiclePacketListener.class);
            framework.when(VehicleFramework::getPacketListener).thenReturn(packets);
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player);
            manager.playerLeave(quit); verify(packets).unregisterPlayer(player);
        }
        PlayerDeathEvent death = mock(PlayerDeathEvent.class); when(death.getEntity()).thenReturn(player);
        manager.passengerDeath(death); verify(v, never()).dismountPassenger(any(), anyBoolean());
        VFEntityDamageEvent damage = new VFEntityDamageEvent(player, null, "FIRE", 10);
        manager.damageVehicle(damage); assertEquals(10, damage.getDamage()); assertFalse(damage.isCancelled());
        when(v.isTrain()).thenReturn(true); when(v.hasParent()).thenReturn(true);
        ActiveVehicle locomotive = vehicle("locomotive"); when(v.getParent()).thenReturn(locomotive); when(locomotive.isLocomotive()).thenReturn(true);
        assertEquals(5, VehicleManager.passengerDamage(v, 20));
        when(v.getEntity().getLocation()).thenReturn(null); assertEquals("unknown location", VehicleManager.describeLocation(v));
    }

    @Test
    void meleeAliasesAreProtectedAndModelLookupErrorsDoNotCancelUnrelatedAttacks() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        Entity alias = mock(Entity.class); manager.get().put(alias, v);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(player); when(event.getEntity()).thenReturn(alias); when(event.getCause()).thenReturn(DamageCause.ENTITY_ATTACK);
        manager.damagePassenger(event); verify(event, never()).setCancelled(anyBoolean());
        mount(player, v); manager.damagePassenger(event); verify(event).setCancelled(true);
        manager.get().remove(alias);
        try (MockedStatic<ModelEngineAPI> models = mockStatic(ModelEngineAPI.class)) {
            models.when(() -> ModelEngineAPI.getModeledEntity(alias)).thenThrow(new IllegalStateException("removed model"));
            clearInvocations(event); manager.damagePassenger(event); verify(event, never()).setCancelled(anyBoolean());
        }
        when(event.getDamager()).thenReturn(alias);
        assertFalse(VehicleManager.isPassengerMeleeOnOwnVehicle(event, manager.get()));
    }

    @Test
    void damageAdapterFailureIsReportedWithoutEscapingTheEventHandler() {
        ActiveVehicle v = vehicle("v"); Player player = player("Ryan"); register(v);
        when(v.isPassenger(player, true)).thenReturn(true);
        EntityDamageEvent event = mock(EntityDamageEvent.class); when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(DamageCause.FIRE); when(event.getDamage()).thenReturn(4d);
        java.io.PrintStream previous = System.err;
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        try (MockedStatic<Damager> damage = mockStatic(Damager.class);
             java.io.PrintStream output = new java.io.PrintStream(errors)) {
            damage.when(() -> Damager.damage(player, 4)).thenThrow(new IllegalStateException("damage adapter unavailable"));
            System.setErr(output);
            assertDoesNotThrow(() -> manager.damagePassenger(event));
        } finally { System.setErr(previous); }
        verify(event).setCancelled(true);
        assertTrue(errors.toString(java.nio.charset.StandardCharsets.UTF_8).contains("damage adapter unavailable"));
    }

    @Test
    void ownershipAndInputDefensiveHelpersRejectMissingParticipants() throws Exception {
        // These guards protect future event/API callers; assert their null contract without fabricated Bukkit events.
        var claim = VehicleManager.class.getDeclaredMethod("claimOwnership", Player.class, ActiveVehicle.class);
        claim.setAccessible(true);
        assertEquals(false, claim.invoke(manager, null, vehicle("v")));
        assertEquals(false, claim.invoke(manager, player("Ryan"), null));
        var shifting = VehicleManager.class.getDeclaredMethod("mountedShifting", Player.class);
        shifting.setAccessible(true); assertEquals(false, shifting.invoke(manager, new Object[]{null}));
        var cooldown = VehicleManager.class.getDeclaredMethod("putEjectCooldown", Player.class, ActiveVehicle.class);
        cooldown.setAccessible(true); cooldown.invoke(manager, null, vehicle("v"));
        assertTrue(map("ejectCooldown").isEmpty());
    }
}
