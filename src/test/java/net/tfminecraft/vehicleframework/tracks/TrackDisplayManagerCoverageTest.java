package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;

class TrackDisplayManagerCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final VehicleFramework plugin = mock(VehicleFramework.class, RETURNS_DEEP_STUBS);
    private final TrackRegistry registry = mock(TrackRegistry.class);
    private final World world = mock(World.class);
    private final ItemAPI items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    private final Map<String, ItemStack> itemPaths = new HashMap<>();
    private final Map<UUID, TrackSpline> tracks = new LinkedHashMap<>();
    private final Map<UUID, TrackJunction> junctions = new LinkedHashMap<>();
    private final Map<Long, Chunk> chunks = new LinkedHashMap<>();
    private final Set<Long> loaded = new HashSet<>();
    private final List<DisplayState> spawned = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private MockedStatic<VehicleFramework> framework;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<TrackBuildAnimator> animation;
    private MockedStatic<TrackCommands> commands;
    private MockedStatic<TrackTools> tools;
    private MockedStatic<TrackFx> fx;
    private MockedStatic<TrackPieces> pieces;
    private MockedStatic<VFLogger> log;
    private MockedStatic<TrackPlaceKeepout> keepout;
    private TrackDisplayManager manager;
    private Runnable tick;
    private SavedStyle old;

    @BeforeEach
    void setup() {
        old = new SavedStyle();
        Cache.trackItemSmall = Cache.appliedTrackItemSmall = "small";
        Cache.trackItemMedium = Cache.appliedTrackItemMedium = "medium";
        Cache.trackItemLarge = Cache.appliedTrackItemLarge = "large";
        Cache.trackSwitchItem = "switch";
        Cache.appliedTrackDisplayYOffset = .25;
        Cache.trackDisplayYOffset = .5;
        Cache.trackResyncChunksPerTick = 1;
        framework = keep(mockStatic(VehicleFramework.class));
        bukkit = keep(mockStatic(Bukkit.class));
        animation = keep(mockStatic(TrackBuildAnimator.class));
        commands = keep(mockStatic(TrackCommands.class));
        tools = keep(mockStatic(TrackTools.class));
        fx = keep(mockStatic(TrackFx.class));
        pieces = keep(mockStatic(TrackPieces.class));
        log = keep(mockStatic(VFLogger.class));
        keepout = keep(mockStatic(TrackPlaceKeepout.class));
        MockedStatic<TLibs> libs = keep(mockStatic(TLibs.class));
        libs.when(TLibs::getItemAPI).thenReturn(items);
        for (String path : List.of("small", "medium", "large", "switch")) {
            ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(mock(Material.class));
            itemPaths.put(path, item);
        }
        when(items.getCreator().getItemFromPath(anyString())).thenAnswer(call -> itemPaths.get(call.getArgument(0)));
        framework.when(VehicleFramework::getInstance).thenReturn(plugin);
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
        when(plugin.namespace()).thenReturn("vehicleframework");
        when(plugin.getServer().getScheduler().runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenAnswer(call -> { tick = call.getArgument(1); return null; });
        when(world.getName()).thenReturn("world");
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> loaded.contains(TrackChunks.key(call.getArgument(0), call.getArgument(1))));
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> chunk(call.getArgument(0), call.getArgument(1)));
        when(world.getLoadedChunks()).thenAnswer(call -> chunks.entrySet().stream()
                .filter(e -> loaded.contains(e.getKey())).map(Map.Entry::getValue).toArray(Chunk[]::new));
        when(world.spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class))).thenAnswer(call -> {
            DisplayState state = new DisplayState(call.getArgument(0));
            Consumer<ItemDisplay> initialize = call.getArgument(2);
            initialize.accept(state.entity);
            spawned.add(state);
            return state.entity;
        });
        when(registry.get(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(tracks.get(call.getArgument(0))));
        when(registry.inWorld(anyString())).thenAnswer(call -> tracks.values().stream()
                .filter(s -> s.getWorld().equals(call.getArgument(0))).toList());
        when(registry.junctionsOn(any(UUID.class))).thenAnswer(call -> junctions.values().stream()
                .filter(j -> j.stemSplineId.equals(call.getArgument(0))).toList());
        when(registry.getJunction(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(junctions.get(call.getArgument(0))));
        doAnswer(call -> { TrackSpline s = call.getArgument(0); tracks.put(s.getId(), s); return null; }).when(registry).replace(any());
        load(0, 0);
        manager = new TrackDisplayManager();
    }

    @AfterEach
    void teardown() throws Exception {
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
        old.restore();
    }

    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private Chunk load(int x, int z) { loaded.add(TrackChunks.key(x, z)); return chunk(x, z); }
    private Chunk chunk(int x, int z) {
        return chunks.computeIfAbsent(TrackChunks.key(x, z), ignored -> {
            Chunk chunk = mock(Chunk.class);
            when(chunk.getWorld()).thenReturn(world);
            when(chunk.getX()).thenReturn(x);
            when(chunk.getZ()).thenReturn(z);
            when(chunk.getEntities()).thenAnswer(call -> entities.stream().filter(e -> !e.isDead())
                    .filter(e -> TrackChunks.inChunk(e.getLocation().getX(), e.getLocation().getZ(), x, z)).toArray(Entity[]::new));
            return chunk;
        });
    }
    private TrackVisual visual(int edge, double x) { return visual(TrackVisual.Type.SMALL, edge, 1, x); }
    private TrackVisual visual(TrackVisual.Type type, int edge, int span, double x) {
        return new TrackVisual(type, edge, span, edge, span, x, 64, 8, 12, 3);
    }
    private TrackSpline spline(TrackVisual... visuals) { return spline(UUID.randomUUID(), visuals); }
    private TrackSpline spline(UUID id, TrackVisual... visuals) {
        TrackSpline spline = mock(TrackSpline.class);
        when(spline.getId()).thenReturn(id);
        when(spline.getWorld()).thenReturn("world");
        when(spline.visuals()).thenReturn(List.of(visuals));
        tracks.put(id, spline);
        return spline;
    }
    private TrackSpline rail() {
        TrackSpline spline = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
                List.of(new double[] {8, 64, 8}, new double[] {8, 64, 9}, new double[] {8, 64, 10}, new double[] {8, 64, 11}));
        tracks.put(spline.getId(), spline);
        return spline;
    }
    private TrackJunction junction(TrackSpline stem) {
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), 1, 1,
                TrackJunction.Side.LEFT, UUID.randomUUID());
        junctions.put(junction.id, junction);
        return junction;
    }
    private DisplayState spawn(UUID id, TrackVisual visual) {
        manager.spawnVisuals(id, List.of(visual), world, null);
        return spawned.getLast();
    }
    private DisplayState external(String id, Integer edge, Integer span) {
        DisplayState state = new DisplayState(new Location(world, 8, 64.25, 8));
        if (id != null) state.data.put(manager.idKey(), id);
        if (edge != null) state.data.put(manager.edgeKey(), edge);
        if (span != null) state.data.put(manager.spanKey(), span);
        return state;
    }
    private Player player() {
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 8, 64, 8, 80, 25));
        return player;
    }
    private EntityDamageByEntityEvent punch(Entity damager, Entity victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(victim);
        manager.onPunch(event);
        return event;
    }
    private PlayerInteractEntityEvent interact(Player player, Entity entity, EquipmentSlot hand) {
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, entity, hand);
        manager.onInteract(event);
        return event;
    }

    @Test
    void initializesStableMetadataKeysAndSchedulesServerTick() {
        assertNotNull(tick);
        assertEquals("vehicleframework:track_id", manager.idKey().toString());
        assertSame(manager.idKey(), manager.idKey());
        assertSame(manager.edgeKey(), manager.edgeKey());
        assertSame(manager.spanKey(), manager.spanKey());
        assertSame(manager.switchKey(), manager.switchKey());
        tick.run();
        animation.verify(TrackBuildAnimator::tick);
        framework.when(VehicleFramework::getInstance).thenReturn(null);
        assertDoesNotThrow(TrackDisplayManager::new);
    }

    @Test
    void spawnsConfiguredDisplayTypesWithMetadataAndCachesSuccessfulItems() {
        UUID id = UUID.randomUUID();
        for (TrackVisual.Type type : TrackVisual.Type.values()) {
            TrackVisual visual = visual(type, type.ordinal(), type.ordinal() + 1, 8 + type.ordinal());
            DisplayState display = spawn(id, visual);
            assertEquals(id.toString(), display.data.get(manager.idKey()));
            assertEquals(visual.fromEdge, display.data.get(manager.edgeKey()));
            assertEquals(visual.span, display.data.get(manager.spanKey()));
            assertEquals(64.25, display.location.getY());
            assertEquals(12, display.location.getYaw());
            assertEquals(3, display.location.getPitch());
            verify(display.entity).setPersistent(false);
            verify(display.entity).setGravity(false);
            verify(display.entity).setInvulnerable(false);
            verify(display.entity).setBillboard(Display.Billboard.FIXED);
            verify(display.entity).setItemStack(itemPaths.get(type.name().toLowerCase()));
        }
        spawn(id, visual(0, 9));
        verify(items.getCreator(), times(1)).getItemFromPath("small");
    }

    @Test
    void missingLargerPieceFallsBackToSmallAndLogsOncePerType() {
        itemPaths.remove("large");
        UUID id = UUID.randomUUID();
        DisplayState first = spawn(id, visual(TrackVisual.Type.LARGE, 0, 2, 8));
        spawn(id, visual(TrackVisual.Type.LARGE, 2, 2, 10));
        verify(first.entity).setItemStack(itemPaths.get("small"));
        log.verify(() -> VFLogger.log("Track display item is missing: large"), times(1));
        verify(items.getCreator(), times(2)).getItemFromPath("large");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "blank", "missing", "air", "exception"})
    void skipsUnusableItemsWithoutCachingFailure(String failure) {
        switch (failure) {
            case "null" -> Cache.appliedTrackItemSmall = null;
            case "blank" -> Cache.appliedTrackItemSmall = " ";
            case "missing" -> itemPaths.remove("small");
            case "air" -> when(itemPaths.get("small").getType().isAir()).thenReturn(true);
            case "exception" -> when(items.getCreator().getItemFromPath("small")).thenThrow(new IllegalStateException("items loading"));
        }
        manager.spawnVisuals(UUID.randomUUID(), List.of(visual(0, 8)), world, null);
        assertTrue(spawned.isEmpty());
    }

    @Test
    void missingLargeAndSmallItemsProduceNoEntityAndCanRecoverWhenPluginLoads() {
        itemPaths.clear();
        UUID id = UUID.randomUUID();
        manager.spawnVisuals(id, List.of(visual(TrackVisual.Type.LARGE, 0, 3, 8)), world, null);
        assertTrue(spawned.isEmpty());
        log.verify(() -> VFLogger.log("Track display item is missing: small"));
        ItemStack recovered = mock(ItemStack.class);
        when(recovered.getType()).thenReturn(mock(Material.class));
        itemPaths.put("small", recovered);
        DisplayState display = spawn(id, visual(0, 8));
        verify(display.entity).setItemStack(recovered);
    }

    @Test
    void splineSpawnsOnlyLoadedChunksAndAnimatedBuildSpawnsOncePerChunk() {
        TrackSpline spline = spline(visual(0, 8), visual(1, 9), visual(2, 24), visual(3, 40));
        load(1, 0);
        manager.spawnSpline(spline);
        assertEquals(3, spawned.size());
        verify(world, times(1)).isChunkLoaded(0, 0);
        manager.despawnAll();
        animation.when(() -> TrackBuildAnimator.isBuilding(spline.getId())).thenReturn(true);
        manager.spawnSpline(spline);
        animation.verify(() -> TrackBuildAnimator.spawnIntoChunk(spline, chunk(0, 0)));
        animation.verify(() -> TrackBuildAnimator.spawnIntoChunk(spline, chunk(1, 0)));
        assertEquals(3, spawned.size());
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
        manager.spawnSpline(spline);
        assertEquals(3, spawned.size());
    }

    @Test
    void refreshReplacesOnlyChangedChunksAndKeepsOtherSplines() {
        load(1, 0);
        UUID id = UUID.randomUUID();
        TrackSpline before = spline(id, visual(0, 8), visual(1, 24));
        manager.spawnSpline(before);
        DisplayState changed = spawned.get(0), unchanged = spawned.get(1);
        DisplayState other = spawn(UUID.randomUUID(), visual(0, 9));
        TrackSpline after = spline(id, visual(0, 10), visual(1, 24));
        manager.refreshSpline(before, id);
        assertTrue(changed.removed);
        assertFalse(unchanged.removed);
        assertFalse(other.removed);
        assertEquals(4, spawned.size());
        manager.refreshSpline(after, id);
        assertEquals(4, spawned.size());
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
        manager.refreshSpline(after, id);
        assertEquals(4, spawned.size());
    }

    @Test
    void refreshRebakesUnknownBeforeAndRemovesDeletedTracks() {
        TrackSpline after = spline(visual(0, 8));
        DisplayState previous = spawn(after.getId(), visual(0, 8));
        manager.refreshSpline(null, after.getId());
        assertTrue(previous.removed);
        assertEquals(2, spawned.size());
        manager.refreshSpline(spline(visual(0, 8)), after.getId());
        assertEquals(3, spawned.size());
        animation.when(() -> TrackBuildAnimator.isBuilding(after.getId())).thenReturn(true);
        manager.refreshSpline(after, after.getId());
        animation.verify(() -> TrackBuildAnimator.spawnIntoChunk(after, chunk(0, 0)));
        tracks.remove(after.getId());
        manager.refreshSpline(after, after.getId());
        assertTrue(spawned.stream().allMatch(d -> d.removed));
    }

    @Test
    void replacingAnimatedSuffixLeavesMatchingPrefixAndOtherEdgesAlone() {
        UUID id = UUID.randomUUID();
        TrackVisual first = visual(0, 8), old = visual(1, 9), next = visual(1, 10);
        DisplayState foreign = spawn(UUID.randomUUID(), old);
        DisplayState prefix = spawn(id, first);
        DisplayState remote = spawn(id, visual(1, 40));
        DisplayState changed = spawn(id, old);
        manager.replaceFrom(id, List.of(first, old), List.of(first, next, visual(2, 24)), world, chunk(0, 0));
        assertFalse(foreign.removed);
        assertFalse(prefix.removed);
        assertFalse(remote.removed);
        assertTrue(changed.removed);
        assertEquals(5, spawned.size());
        assertEquals(10, spawned.getLast().location.getX());
        manager.replaceFrom(id, List.of(first, next), null, world, null);
        assertTrue(prefix.removed);
        assertTrue(spawned.getLast().removed);
        manager.replaceFrom(id, null, List.of(first), world, null);
        assertEquals(6, spawned.size());
    }

    @Test
    void replacementAfterChunkUnloadRecreatesSuffixWithoutRemovingOtherTrack() {
        UUID id = UUID.randomUUID();
        TrackVisual before = visual(0, 8), after = visual(0, 9);
        DisplayState removed = spawn(id, before);
        manager.onChunkUnload(new ChunkUnloadEvent(chunk(0, 0)));
        assertTrue(removed.removed);
        DisplayState other = spawn(UUID.randomUUID(), visual(0, 10));
        manager.replaceFrom(id, List.of(before), List.of(after), world, chunk(0, 0));
        assertFalse(other.removed);
        assertEquals(id.toString(), spawned.getLast().data.get(manager.idKey()));
        assertEquals(9, spawned.getLast().location.getX());
    }

    @Test
    void replacingSuffixSkipsMissingPreviousPiecesAndSwitchTaggedMetadata() {
        UUID id = UUID.randomUUID();
        TrackVisual before = visual(0, 8), after = visual(0, 9);
        DisplayState switchTagged = spawn(id, before);
        // Persisted entity metadata can contain tags from both display formats.
        switchTagged.entity.getPersistentDataContainer().set(manager.switchKey(),
                PersistentDataType.STRING, UUID.randomUUID().toString());
        manager.replaceFrom(id, Arrays.asList(null, before), List.of(after), world, null);
        assertFalse(switchTagged.removed);
        assertEquals(2, spawned.size());
        assertEquals(9, spawned.getLast().location.getX());
    }

    @Test
    void ghostAndVisualSpawnsRespectChunksAndIgnoreIncompleteInput() {
        UUID id = UUID.randomUUID();
        manager.spawnGhost(null, List.of(), world, null);
        manager.spawnGhost(id, null, world, null);
        manager.spawnGhost(id, List.of(new double[] {1, 2, 3}), world, null);
        manager.spawnGhost(id, List.of(new double[] {1, 2, 3}, new double[] {2, 2, 3}), null, null);
        manager.replaceFrom(null, null, null, world, null);
        manager.replaceFrom(id, null, null, null, null);
        manager.spawnVisuals(null, List.of(), world, null);
        manager.spawnVisuals(id, null, world, null);
        manager.spawnVisuals(id, List.of(), null, null);
        assertTrue(spawned.isEmpty());
        manager.spawnGhost(id, rail().xyz(), world, chunk(0, 0));
        assertFalse(spawned.isEmpty());
        int count = spawned.size();
        manager.spawnVisuals(id, List.of(visual(0, 8), visual(1, 24)), world, chunk(0, 0));
        assertEquals(count + 1, spawned.size());
    }

    @Test
    void chunkLifecycleCleansOnlyTaggedDisplaysAndSpawnsMatchingPieces() {
        TrackSpline spline = spline(visual(0, 8), visual(1, 24));
        DisplayState stale = external(spline.getId().toString(), 0, 1);
        DisplayState ordinary = external(null, null, null);
        DisplayState staleSwitch = external(null, null, null);
        staleSwitch.data.put(manager.switchKey(), UUID.randomUUID().toString());
        Entity unrelated = mock(Entity.class);
        when(unrelated.getLocation()).thenReturn(new Location(world, 8, 64, 8));
        entities.add(unrelated);
        manager.spawnLoadedChunks();
        assertTrue(stale.removed);
        assertTrue(staleSwitch.removed);
        assertFalse(ordinary.removed);
        assertEquals(1, spawned.size());
        verify(unrelated, never()).remove();
        manager.onChunkUnload(new ChunkUnloadEvent(chunk(0, 0)));
        assertTrue(spawned.getFirst().removed);
        manager.onChunkLoad(new ChunkLoadEvent(chunk(0, 0), false));
        assertEquals(2, spawned.size());
        animation.when(() -> TrackBuildAnimator.isBuilding(spline.getId())).thenReturn(true);
        manager.spawnChunk(chunk(0, 0));
        animation.verify(() -> TrackBuildAnimator.spawnIntoChunk(spline, chunk(0, 0)));
    }

    @Test
    void resyncWorksInBatchesKeepsSwitchesAndFinishesOnlyOnce() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        manager.refreshSwitch(junction);
        DisplayState lever = spawned.getLast();
        spline(visual(0, 8), visual(1, 24));
        load(1, 0);
        DisplayState stale = external(UUID.randomUUID().toString(), 0, 1);
        DisplayState ordinary = external(null, null, null);
        Entity unrelated = mock(Entity.class);
        when(unrelated.getLocation()).thenReturn(new Location(world, 9, 64, 8));
        entities.add(unrelated);
        CommandSender sender = mock(CommandSender.class);
        manager.startRailResync(sender);
        verify(registry).invalidateAllVisuals();
        verify(sender).sendMessage("§aResyncing track displays in 2 loaded chunks.");
        assertEquals(.5, Cache.appliedTrackDisplayYOffset);
        tick.run();
        assertTrue(stale.removed);
        assertFalse(lever.removed);
        assertFalse(ordinary.removed);
        verify(sender, never()).sendMessage("§aTrack display resync finished.");
        tick.run();
        tick.run();
        verify(sender, times(1)).sendMessage("§aTrack display resync finished.");
        assertTrue(spawned.stream().anyMatch(d -> d.location.getX() == 24));
    }

    @Test
    void resyncSkipsVanishedWorldsAndUnloadedChunksAndSupportsAnimatedRails() {
        TrackSpline rail = spline(visual(0, 8));
        animation.when(() -> TrackBuildAnimator.isBuilding(rail.getId())).thenReturn(true);
        CommandSender sender = mock(CommandSender.class);
        manager.startRailResync(sender);
        Cache.trackResyncChunksPerTick = 0;
        tick.run();
        animation.verify(() -> TrackBuildAnimator.spawnIntoChunk(rail, chunk(0, 0)));
        manager.startRailResync(sender);
        loaded.clear();
        tick.run();
        load(0, 0);
        manager.startRailResync(sender);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
        tick.run();
        verify(sender, times(3)).sendMessage("§aTrack display resync finished.");
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
        manager.startRailResync(sender);
        verify(sender).sendMessage("§aTrack displays already match config (no loaded chunks).");
    }

    @Test
    void despawnAllCancelsPendingResyncAndIgnoresAlreadyDeadEntities() {
        DisplayState alive = spawn(UUID.randomUUID(), visual(0, 8));
        DisplayState dead = spawn(UUID.randomUUID(), visual(1, 9));
        dead.removed = true;
        CommandSender sender = mock(CommandSender.class);
        manager.startRailResync(sender);
        manager.despawnAll();
        assertTrue(alive.removed);
        verify(dead.entity, never()).remove();
        tick.run();
        verify(sender, never()).sendMessage("§aTrack display resync finished.");
        assertEquals(2, spawned.size());
    }

    @Test
    void switchesUseConfiguredItemPositionAndAnimateTowardChangedThrow() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        manager.spawnSwitchesForSpline(rail);
        DisplayState lever = spawned.getLast();
        TrackSwitchPose pose = TrackSwitchPose.of(rail.sampleAt(junction.s), junction,
                Cache.trackSwitchOffsetAlong, Cache.trackSwitchOffsetOut, Cache.trackSwitchOffsetY,
                Cache.trackSwitchYawInward, Cache.trackSwitchThrowDegrees);
        assertEquals(pose.x, lever.location.getX());
        assertEquals(pose.y + Cache.trackDisplayYOffset, lever.location.getY());
        assertEquals(pose.z, lever.location.getZ());
        assertEquals(junction.id.toString(), lever.data.get(manager.switchKey()));
        verify(lever.entity).setItemStack(itemPaths.get("switch"));
        verify(lever.entity).setPersistent(false);
        verify(lever.entity).setGravity(false);
        verify(lever.entity).setInvulnerable(false);
        verify(lever.entity).setBillboard(Display.Billboard.FIXED);
        junctions.put(junction.id, junction.withThrown(true));
        tick.run();
        assertEquals(pose.targetYaw - Cache.trackSwitchThrowDegreesPerSecond / 20, lever.location.getYaw());
        manager.refreshSwitch(junction.withThrown(true));
        assertTrue(lever.removed);
        assertEquals(pose.divergeYaw, spawned.getLast().location.getYaw());
        manager.despawnSwitch(null);
        manager.despawnSwitch(junction.id);
        assertTrue(spawned.getLast().removed);
    }

    @Test
    void switchReloadPreservesRailsReplacesSwitchesAndClearsItemCache() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        DisplayState track = spawn(rail.getId(), visual(0, 8));
        manager.refreshSwitch(junction);
        DisplayState first = spawned.getLast();
        manager.reloadSwitches();
        assertFalse(track.removed);
        assertTrue(first.removed);
        assertFalse(spawned.getLast().removed);
        verify(items.getCreator(), times(2)).getItemFromPath("switch");
        manager.despawnTrackDisplays(rail.getId());
        assertTrue(track.removed);
        assertFalse(spawned.getLast().removed);
        manager.despawnSpline(rail.getId());
        assertTrue(spawned.getLast().removed);
    }

    @Test
    void switchCreationIgnoresUnloadedOrOtherChunksAndIncompleteJunctions() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        manager.spawnSwitchesForSpline(null);
        manager.refreshSwitch(null);
        junctions.put(junction.id, junction.withBranch(null));
        manager.spawnLoadedChunks();
        assertTrue(spawned.stream().noneMatch(d -> d.data.containsKey(manager.switchKey())));
        junctions.put(junction.id, junction);
        load(1, 0);
        manager.spawnChunk(chunk(1, 0));
        assertTrue(spawned.stream().noneMatch(d -> d.data.containsKey(manager.switchKey())));
        loaded.clear();
        manager.spawnSwitchesForSpline(rail);
        manager.refreshSwitch(junction);
        assertTrue(spawned.stream().noneMatch(d -> d.data.containsKey(manager.switchKey())));
        load(0, 0);
        manager.refreshSwitch(junction);
        DisplayState lever = spawned.getLast();
        manager.refreshSwitch(junction.withBranch(null));
        assertTrue(lever.removed);
    }

    @Test
    void missingSwitchItemIsLoggedOnceUntilReload() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        itemPaths.remove("switch");
        manager.refreshSwitch(junction);
        manager.refreshSwitch(junction);
        log.verify(() -> VFLogger.log("Track switch item is missing: switch"), times(1));
        assertTrue(spawned.isEmpty());
        manager.reloadSwitches();
        log.verify(() -> VFLogger.log("Track switch item is missing: switch"), times(2));
    }

    @Test
    void orphanedSwitchesAreRemovedAndMissingStemIsSafeDuringTick() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        manager.refreshSwitch(junction);
        DisplayState lever = spawned.getLast();
        tracks.clear();
        tick.run();
        assertFalse(lever.removed);
        verify(lever.entity, never()).teleport(any(Location.class));
        manager.refreshSwitch(junction);
        assertTrue(lever.removed);
        tracks.put(rail.getId(), rail);
        manager.refreshSwitch(junction);
        DisplayState incomplete = spawned.getLast();
        junctions.put(junction.id, junction.withBranch(null));
        tick.run();
        assertTrue(incomplete.removed);
        junctions.put(junction.id, junction);
        manager.refreshSwitch(junction);
        DisplayState orphan = spawned.getLast();
        junctions.clear();
        tick.run();
        assertTrue(orphan.removed);
        manager.refreshSwitch(junction);
        DisplayState invalid = spawned.getLast();
        invalid.valid = false;
        tick.run();
        manager.despawnAll();
        verify(invalid.entity, never()).remove();
    }

    @Test
    void unloadedWorldAndUnavailableRegistryLeaveNoNewDisplays() {
        TrackSpline rail = rail();
        TrackJunction junction = junction(rail);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
        manager.spawnSwitchesForSpline(rail);
        manager.refreshSwitch(junction);
        assertTrue(spawned.isEmpty());
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(null);
        manager.spawnSwitchesForSpline(rail);
        manager.refreshSwitch(junction);
        manager.spawnChunk(chunk(0, 0));
        manager.reloadSwitches();
        manager.despawnSpline(rail.getId());
        manager.rebakeSpline(rail.getId());
        manager.refreshSpline(rail, rail.getId());
        manager.breakInRadius(new Location(world, 8, 64, 8), 2);
        manager.startRailResync(mock(CommandSender.class));
        tick.run();
        assertTrue(spawned.isEmpty());
    }

    @Test
    void malformedEntityTagsDoNotDeleteUnrelatedDisplaysOrCrashTick() {
        DisplayState track = spawn(UUID.randomUUID(), visual(0, 8));
        track.data.put(manager.idKey(), "bad uuid");
        track.data.put(manager.switchKey(), "bad switch uuid");
        manager.despawnTrackDisplays(UUID.randomUUID());
        manager.despawnSwitch(UUID.randomUUID());
        tick.run();
        assertFalse(track.removed);
        DisplayState dead = spawn(UUID.randomUUID(), visual(0, 9));
        dead.removed = true;
        tick.run();
        manager.despawnAll();
        verify(dead.entity, never()).remove();
    }

    @Test
    void explosionsBreakAndDropEachAffectedEdgeExactlyOnce() {
        TrackSpline rail = rail();
        manager.breakInRadius(null, 2);
        manager.breakInRadius(new Location(null, 8, 64, 8), 2);
        manager.breakInRadius(new Location(world, 8, 64, 8), 0);
        manager.breakInRadius(new Location(world, 8, 64, 8), .5);
        TrackSpline after = tracks.get(rail.getId());
        assertTrue(after.segment(0).broken);
        assertFalse(after.segment(1).broken);
        pieces.verify(() -> TrackPieces.dropAt(world, 8, 64, 8));
        manager.breakInRadius(new Location(world, 8, 64, 8), .5);
        pieces.verify(() -> TrackPieces.dropAt(world, 8, 64, 8), times(1));
        EntityExplodeEvent entity = mock(EntityExplodeEvent.class);
        when(entity.getLocation()).thenReturn(new Location(world, 8, 64, 10));
        when(entity.getYield()).thenReturn(0f);
        manager.onEntityExplode(entity);
        assertTrue(tracks.get(rail.getId()).getSegments().stream().allMatch(s -> s.broken));
        BlockExplodeEvent block = mock(BlockExplodeEvent.class, RETURNS_DEEP_STUBS);
        when(block.getBlock().getLocation()).thenReturn(new Location(world, 8, 64, 10));
        when(block.getYield()).thenReturn(5f);
        manager.onBlockExplode(block);
        verify(registry, times(2)).replace(any());
    }

    @Test
    void blockAndBucketPlacementRespectTrackKeepout() {
        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        Block block = mock(Block.class);
        when(place.getBlock()).thenReturn(block);
        manager.onBlockPlace(place);
        verify(place, never()).setCancelled(true);
        keepout.when(() -> TrackPlaceKeepout.blocked(block)).thenReturn(true);
        manager.onBlockPlace(place);
        verify(place).setCancelled(true);
        PlayerBucketEmptyEvent bucket = mock(PlayerBucketEmptyEvent.class);
        Block clicked = mock(Block.class);
        when(bucket.getBlockClicked()).thenReturn(clicked);
        when(bucket.getBlockFace()).thenReturn(BlockFace.UP);
        when(clicked.getRelative(BlockFace.UP)).thenReturn(block);
        manager.onBucketEmpty(bucket);
        verify(bucket).setCancelled(true);
    }

    @Test
    void ordinaryEntityInteractionsPassThroughAndSwitchInteractionsAreCancelled() {
        Player player = player();
        Entity other = mock(Entity.class);
        EntityDamageByEntityEvent nonPlayer = punch(other, other);
        verify(nonPlayer, never()).setCancelled(true);
        verify(punch(player, other), never()).setCancelled(true);
        DisplayState ordinary = external(null, null, null);
        verify(punch(player, ordinary.entity), never()).setCancelled(true);
        assertFalse(interact(player, other, EquipmentSlot.HAND).isCancelled());
        assertFalse(interact(player, ordinary.entity, EquipmentSlot.HAND).isCancelled());
        DisplayState lever = external(null, null, null);
        lever.data.put(manager.switchKey(), UUID.randomUUID().toString());
        verify(punch(player, lever.entity)).setCancelled(true);
        assertTrue(interact(player, lever.entity, EquipmentSlot.HAND).isCancelled());
        assertFalse(interact(player, lever.entity, EquipmentSlot.OFF_HAND).isCancelled());
        commands.verifyNoInteractions();
    }

    @Test
    void trackToolsUseAimedDisplayPositionAndAvoidDuplicatePunches() {
        Player player = player();
        ItemStack hand = player.getInventory().getItemInMainHand();
        DisplayState display = external(UUID.randomUUID().toString(), 0, 1);
        Location aimed = new Location(world, 8, 64.25, 8, 80, 25);
        tools.when(() -> TrackTools.isLayer(hand)).thenReturn(true);
        commands.when(() -> TrackCommands.markStart(player, aimed)).thenReturn(true);
        verify(punch(player, display.entity)).setCancelled(true);
        commands.verify(() -> TrackCommands.markStart(player, aimed));
        fx.verify(() -> TrackFx.hitNear(new Location(world, 8, 64.25, 8)));
        assertTrue(interact(player, display.entity, EquipmentSlot.HAND).isCancelled());
        commands.verify(() -> TrackCommands.markEnd(player, aimed));
        commands.when(() -> TrackCommands.skipDuplicateToolUse(player)).thenReturn(true);
        punch(player, display.entity);
        commands.verify(() -> TrackCommands.markStart(player, aimed), times(1));
        tools.when(() -> TrackTools.isLayer(hand)).thenReturn(false);
        tools.when(() -> TrackTools.isJunction(hand)).thenReturn(true);
        punch(player, display.entity);
        commands.when(() -> TrackCommands.skipDuplicateToolUse(player)).thenReturn(false);
        interact(player, display.entity, EquipmentSlot.HAND);
        commands.verify(() -> TrackCommands.startJunction(player, aimed));
        tools.when(() -> TrackTools.isJunction(hand)).thenReturn(false);
        tools.when(() -> TrackTools.isRemover(hand)).thenReturn(true);
        punch(player, display.entity);
        commands.verify(() -> TrackCommands.digAt(player, new Location(world, 8, 64.25, 8)));
        commands.when(() -> TrackCommands.skipDuplicateToolUse(player)).thenReturn(true);
        punch(player, display.entity);
        commands.verify(() -> TrackCommands.digAt(player, new Location(world, 8, 64.25, 8)), times(1));
    }

    @Test
    void punchingMultiEdgeDisplayBreaksWrappedEdgesAndDropsOnlyNewDamage() {
        TrackSpline rail = rail();
        DisplayState display = external(rail.getId().toString(), 2, 3);
        Player player = player();
        pieces.when(() -> TrackPieces.pays(player)).thenReturn(true);
        punch(player, display.entity);
        assertTrue(tracks.get(rail.getId()).getSegments().stream().allMatch(s -> s.broken));
        pieces.verify(() -> TrackPieces.dropAt(world, 8, 64, 8));
        pieces.verify(() -> TrackPieces.dropAt(world, 8, 64, 9));
        pieces.verify(() -> TrackPieces.dropAt(world, 8, 64, 10));
        punch(player, display.entity);
        verify(registry, times(1)).replace(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void oldDisplayWithoutValidSpanBreaksOnlyOneEdge(int span) {
        TrackSpline rail = rail();
        DisplayState display = external(rail.getId().toString(), 1, span == 0 ? null : span);
        punch(player(), display.entity);
        TrackSpline after = tracks.get(rail.getId());
        assertTrue(after.segment(1).broken);
        assertFalse(after.segment(0).broken);
        assertFalse(after.segment(2).broken);
        pieces.verify(() -> TrackPieces.dropAt(any(World.class), anyDouble(), anyDouble(), anyDouble()), never());
    }

    @Test
    void staleOrIncompleteTrackMetadataDoesNotApplyDamage() {
        Player player = player();
        punch(player, external("bad uuid", 0, 1).entity);
        punch(player, external(UUID.randomUUID().toString(), null, 1).entity);
        UUID missing = UUID.randomUUID();
        DisplayState stale = spawn(missing, visual(0, 8));
        punch(player, stale.entity);
        assertTrue(stale.removed);
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(null);
        punch(player, external(UUID.randomUUID().toString(), 0, 1).entity);
        verify(registry, never()).replace(any());
    }

    private final class DisplayState {
        final ItemDisplay entity = mock(ItemDisplay.class);
        final Map<NamespacedKey, Object> data = new HashMap<>();
        Location location;
        boolean removed;
        boolean valid = true;
        @SuppressWarnings({"rawtypes", "unchecked"})
        DisplayState(Location location) {
            this.location = location.clone();
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(entity.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                    .thenAnswer(call -> data.get(call.getArgument(0)));
            when(pdc.has(any(NamespacedKey.class), any(PersistentDataType.class)))
                    .thenAnswer(call -> data.containsKey(call.getArgument(0)));
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(pdc).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
            when(entity.getLocation()).thenAnswer(call -> this.location.clone());
            when(entity.isDead()).thenAnswer(call -> removed);
            when(entity.isValid()).thenAnswer(call -> valid && !removed);
            doAnswer(call -> { removed = true; return null; }).when(entity).remove();
            when(entity.teleport(any(Location.class))).thenAnswer(call -> { this.location = ((Location) call.getArgument(0)).clone(); return true; });
            entities.add(entity);
        }
    }

    private static final class SavedStyle {
        final String small = Cache.trackItemSmall, medium = Cache.trackItemMedium, large = Cache.trackItemLarge;
        final String appliedSmall = Cache.appliedTrackItemSmall, appliedMedium = Cache.appliedTrackItemMedium,
                appliedLarge = Cache.appliedTrackItemLarge, switchItem = Cache.trackSwitchItem;
        final double offset = Cache.trackDisplayYOffset, appliedOffset = Cache.appliedTrackDisplayYOffset;
        final int budget = Cache.trackResyncChunksPerTick;
        void restore() {
            Cache.trackItemSmall = small; Cache.trackItemMedium = medium; Cache.trackItemLarge = large;
            Cache.appliedTrackItemSmall = appliedSmall; Cache.appliedTrackItemMedium = appliedMedium;
            Cache.appliedTrackItemLarge = appliedLarge; Cache.trackSwitchItem = switchItem;
            Cache.trackDisplayYOffset = offset; Cache.appliedTrackDisplayYOffset = appliedOffset;
            Cache.trackResyncChunksPerTick = budget;
        }
    }
}
