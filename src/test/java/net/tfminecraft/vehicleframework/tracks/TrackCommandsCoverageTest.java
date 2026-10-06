package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import net.kyori.adventure.text.Component;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class TrackCommandsCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final TrackRegistry registry = mock(TrackRegistry.class);
    private final TrackDisplayManager displays = mock(TrackDisplayManager.class);
    private final VehicleManager vehicles = mock(VehicleManager.class);
    private final HashMap<Entity, ActiveVehicle> fleet = new HashMap<>();
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final Map<UUID, TrackSpline> tracks = new LinkedHashMap<>();
    private final List<String> messages = new ArrayList<>();
    private MockedStatic<VehicleFramework> framework;
    private MockedStatic<Permissions> permissions;
    private MockedStatic<TrackSupport> support;
    private MockedStatic<TrackPieces> pieces;
    private MockedStatic<TrackBuildAnimator> animation;
    private MockedStatic<TrackFx> fx;
    private MockedStatic<TrackLog> log;
    private MockedStatic<TrackTools> tools;
    private MockedStatic<TrainSpaceHighlight> highlights;
    private MockedStatic<TrainBlockCollision> collision;
    private boolean oldDebug, oldSwing;
    private long oldRetry, oldRemove;

    @BeforeEach
    void setup() {
        oldDebug = Cache.debugLogging;
        oldSwing = Cache.trackBuildSwing;
        oldRetry = Cache.trackLayRetryMs;
        oldRemove = Cache.trackRemoveCooldownMs;
        Cache.debugLogging = false;
        Cache.trackBuildSwing = true;
        Cache.trackLayRetryMs = 60_000;
        Cache.trackRemoveCooldownMs = 60_000;
        framework = keep(mockStatic(VehicleFramework.class));
        permissions = keep(mockStatic(Permissions.class));
        support = keep(mockStatic(TrackSupport.class));
        pieces = keep(mockStatic(TrackPieces.class));
        animation = keep(mockStatic(TrackBuildAnimator.class));
        fx = keep(mockStatic(TrackFx.class));
        log = keep(mockStatic(TrackLog.class));
        tools = keep(mockStatic(TrackTools.class));
        highlights = keep(mockStatic(TrainSpaceHighlight.class));
        collision = keep(mockStatic(TrainBlockCollision.class));
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
        framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(displays);
        framework.when(VehicleFramework::getVehicleManager).thenReturn(vehicles);
        permissions.when(() -> Permissions.isAdmin(any(CommandSender.class))).thenReturn(true);
        support.when(() -> TrackSupport.snapY(any(World.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(64.25);
        support.when(() -> TrackSupport.firstSitY(any(World.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(64d);
        pieces.when(() -> TrackPieces.canAffordFirst(player)).thenReturn(true);
        pieces.when(() -> TrackPieces.pays(player)).thenReturn(true);
        pieces.when(() -> TrackPieces.count(player)).thenReturn(10_000);
        pieces.when(() -> TrackPieces.cost(anyList())).thenCallRealMethod();
        pieces.when(() -> TrackPieces.consumeUpTo(eq(player), anyInt())).thenAnswer(call -> call.getArgument(1));
        pieces.when(() -> TrackPieces.persistPoints(anyList(), anyList(), anyBoolean(), anyInt())).thenCallRealMethod();
        when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Engineer");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(call -> start());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        doAnswer(call -> { messages.add(call.getArgument(0)); return null; }).when(player).sendMessage(anyString());
        when(registry.get(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(tracks.get(call.getArgument(0))));
        when(registry.inWorld(anyString())).thenAnswer(call -> tracks.values().stream()
                .filter(s -> s.getWorld().equals(call.getArgument(0))).toList());
        when(registry.nearest(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        when(registry.digTarget(anyString(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        when(vehicles.get()).thenReturn(fleet);
    }

    @AfterEach
    void teardown() throws Exception {
        TrackAnchorSession.clear(player);
        TrackJunctionSession.clear(player);
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
        Cache.debugLogging = oldDebug;
        Cache.trackBuildSwing = oldSwing;
        Cache.trackLayRetryMs = oldRetry;
        Cache.trackRemoveCooldownMs = oldRemove;
    }

    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private Location start() { return new Location(world, 8, 64, 8); }
    private Location end() { return new Location(world, 8, 64, 20); }
    private TrackSpline rail() { return rail("world"); }
    private TrackSpline rail(String worldName) {
        TrackSpline rail = TrackSpline.fromPoints(UUID.randomUUID(), worldName, false,
                List.of(new double[] {8, 64.25, 8}, new double[] {8, 64.25, 14}, new double[] {8, 64.25, 20}));
        tracks.put(rail.getId(), rail);
        return rail;
    }
    private void nearby(TrackSpline rail) {
        when(registry.nearest(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.of(rail));
    }
    private boolean command(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "track";
        System.arraycopy(args, 0, full, 1, args.length);
        return TrackCommands.handle(player, full);
    }
    private boolean said(String part) { return messages.stream().anyMatch(message -> message.contains(part)); }
    private void lays(TrackLayResult result) throws TrackLayException {
        when(registry.lay(anyString(), any(World.class), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(result);
    }
    private void refuses(TrackLayException error) throws TrackLayException {
        when(registry.lay(anyString(), any(World.class), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenThrow(error);
    }
    private TrackRegistry.DigTarget digTarget(TrackSpline rail, DigResult result) {
        TrackRegistry.DigTarget target = new TrackRegistry.DigTarget(rail, 1,
                List.of(new TrackRegistry.Span(rail.getId(), 6, 1)));
        when(registry.digTarget("world", 8, 64, 8)).thenReturn(Optional.of(target));
        when(registry.digAt(rail, 1, world)).thenReturn(result);
        return target;
    }
    private ActiveVehicle train(double x, World in) {
        ActiveVehicle train = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
        when(train.isTrain()).thenReturn(true);
        Entity entity = train.getEntity();
        when(entity.getWorld()).thenReturn(in);
        when(entity.getLocation()).thenReturn(new Location(in, x, 64, 8));
        fleet.put(entity, train);
        return train;
    }
    private void pending(TrackSpline stem) {
        TrackJunctionSession.set(player, new TrackJunctionSession.Pending(stem.getId(), 6, 1));
    }
    private AtomicInteger inventory(int amount) {
        AtomicInteger available = new AtomicInteger(amount);
        pieces.when(() -> TrackPieces.count(player)).thenAnswer(call -> available.get());
        pieces.when(() -> TrackPieces.consumeUpTo(eq(player), anyInt())).thenAnswer(call -> {
            int consumed = Math.min(available.get(), call.<Integer>getArgument(1));
            available.addAndGet(-consumed);
            return consumed;
        });
        return available;
    }
    private TrackSpline openRacetrack() {
        List<double[]> points = new ArrayList<>();
        for (int z = 10; z <= 40; z++) points.add(new double[] {0, 64, z});
        semicircle(points, 20, 40, Math.PI);
        for (int z = 39; z >= 0; z--) points.add(new double[] {40, 64, z});
        semicircle(points, 20, 0, 0);
        return TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points);
    }
    private void semicircle(List<double[]> points, double cx, double cz, double from) {
        for (int i = 1; i < 63; i++) {
            double angle = from - Math.PI * i / 63;
            points.add(new double[] {cx + 20 * Math.cos(angle), 64, cz + 20 * Math.sin(angle)});
        }
    }

    @Test
    void checksPermissionAndPlayerRequirementBeforeDispatch() {
        permissions.when(() -> Permissions.isAdmin(player)).thenReturn(false);
        assertTrue(command("list"));
        assertTrue(said("No permission"));
        verifyNoInteractions(registry);
        CommandSender console = mock(CommandSender.class);
        assertTrue(TrackCommands.handle(console, new String[] {"track", "list"}));
        verify(console).sendMessage("§cOnly players can use track commands.");
    }

    @Test
    void givesUsageAndSupportsConsoleResyncWithOrWithoutDisplayService() {
        assertTrue(command());
        assertTrue(command("unrecognized"));
        assertEquals(2, messages.stream().filter(m -> m.contains("/vf track start")).count());
        CommandSender console = mock(CommandSender.class);
        assertTrue(TrackCommands.handle(console, new String[] {"track", "ReSyNc"}));
        verify(displays).startRailResync(console);
        framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(null);
        assertTrue(command("resync"));
        assertTrue(said("displays are not running"));
    }

    @Test
    void listsTracksAndReportsExplicitOrNearbyInformation() {
        assertTrue(command("list"));
        assertTrue(said("No tracks"));
        TrackSpline rail = rail();
        assertTrue(command("list"));
        assertTrue(said("§bTracks:"));
        assertTrue(said(rail.getId() + " §7samples=3 length=12.0"));
        assertTrue(command("info", rail.getId().toString()));
        assertTrue(said("§bTrack " + rail.getId()));
        assertTrue(said("samples=3 length=12.0 loop=false"));
        nearby(rail);
        assertTrue(command("info"));
        verify(registry).nearest("world", 8, 64, 8, 8);
    }

    @Test
    void unknownAndMalformedTrackIdsHaveActionableMessages() {
        assertTrue(command("info"));
        assertTrue(command("info", "not-a-uuid"));
        assertTrue(command("particles", UUID.randomUUID().toString()));
        assertTrue(command("clearance", "not-a-uuid"));
        assertEquals(4, messages.stream().filter(m -> m.contains("No track nearby (8 blocks) or unknown id")).count());
    }

    @Test
    void particleCommandShowsEverySampleSlightlyAboveRail() {
        TrackSpline rail = rail();
        assertTrue(command("particles", rail.getId().toString()));
        for (TrackSample sample : rail.getSamples()) {
            verify(player).spawnParticle(Particle.END_ROD, new Location(world, sample.x, sample.y + .2, sample.z), 1, 0, 0, 0, 0);
        }
        assertTrue(said("Showed 3 samples"));
    }

    @Test
    void clearanceRejectsDifferentWorldAndReportsClearLoadedSpace() {
        TrackSpline elsewhere = rail("other-world");
        assertTrue(command("clearance", elsewhere.getId().toString()));
        assertTrue(said("That track is in other-world"));
        collision.verifyNoInteractions();
        TrackSpline rail = rail();
        collision.when(() -> TrainBlockCollision.scanLoaded(world, rail)).thenReturn(new TrainBlockCollision.Scan(List.of(), 12));
        assertTrue(command("clearance", rail.getId().toString()));
        assertTrue(said("Nothing in the way of trains"));
        assertTrue(said("Did not check 12 blocks of track in unloaded chunks"));
    }

    @Test
    void clearanceGroupsObstructionsShowsLargestFiveAndCountsRemainingStretches() {
        TrackSpline rail = rail();
        List<TrainBlockCollision.Obstruction> obstructions = new ArrayList<>();
        obstructions.add(new TrainBlockCollision.Obstruction(1, 65, 1, 0));
        obstructions.add(new TrainBlockCollision.Obstruction(2, 65, 1, 2));
        for (int i = 1; i <= 5; i++) obstructions.add(new TrainBlockCollision.Obstruction(i * 10, 65, 1, i * 10));
        collision.when(() -> TrainBlockCollision.scanLoaded(world, rail)).thenReturn(new TrainBlockCollision.Scan(obstructions, 0));
        highlights.when(() -> TrainSpaceHighlight.show(player, obstructions)).thenReturn(4);
        assertTrue(command("clearance", rail.getId().toString()));
        assertTrue(said("7 blocks in the way"));
        assertTrue(said("Outlined the nearest 4"));
        assertEquals(5, messages.stream().filter(m -> m.contains("blocks from")).count());
        assertTrue(messages.get(1).contains("2 blocks from 1, 65, 1 (0 to 2 along the track)"));
        assertTrue(said("and 1 smaller stretches"));
    }

    @Test
    void dumpRequiresDebugLogging() {
        assertTrue(command("dump"));
        assertTrue(said("Enable debug-logging"));
        verify(registry, never()).dumpToLog();
        Cache.debugLogging = true;
        assertTrue(command("dump"));
        verify(registry).dumpToLog();
        assertTrue(said("Wrote spline dump"));
    }

    @Test
    void startCommandSnapsToGroundReplacesJunctionAndEndCommandUsesSavedAnchor() throws Exception {
        TrackSpline rail = rail();
        pending(rail);
        assertFalse(TrackCommands.skipDuplicateToolUse(player));
        assertTrue(command("StArT"));
        assertNull(TrackJunctionSession.get(player));
        Location snapped = new Location(world, 8, 64.25, 8);
        assertEquals(snapped, TrackAnchorSession.getStart(player));
        log.verify(() -> TrackLog.start("Engineer", 8, 64.25, 8));
        assertTrue(said("Start location set"));
        lays(TrackLayResult.of(TrackLayResult.Kind.NEW, rail, rail.xyz(), 0));
        when(player.getLocation()).thenReturn(end());
        assertTrue(command("end"));
        verify(registry).lay("world", world, 8, 64.25, 8, 8, 64.25, 20, 10_000);
        assertNull(TrackAnchorSession.getStart(player));
        verify(displays).refreshSpline(null, rail.getId());
        assertTrue(said("Laid track from start to end. Length 12.0"));
        fx.verify(() -> TrackFx.hitNear(new Location(world, 8, 64.25, 20)));
    }

    @Test
    void startRejectsMissingClickAndUnsuitableGroundWithoutSettingAnchor() {
        assertFalse(TrackCommands.markStart(player, null));
        support.when(() -> TrackSupport.firstSitY(world, 8, 64, 8)).thenReturn(null);
        assertFalse(TrackCommands.markStart(player, start()));
        assertNull(TrackAnchorSession.getStart(player));
        assertTrue(said("Click solid ground"));
    }

    @Test
    void endRequiresStartAndSkipsInvalidClickedGround() {
        assertTrue(command("end"));
        assertTrue(said("Set a start location first"));
        TrackAnchorSession.setStart(player, start());
        support.when(() -> TrackSupport.firstSitY(world, 8, 64, 20)).thenReturn(null);
        TrackCommands.markEnd(player, end());
        assertEquals(start(), TrackAnchorSession.getStart(player));
        fx.verifyNoInteractions();
        verifyNoInteractions(registry);
    }

    @Test
    void endUsesClickedBlockForFeedbackWhenSupplied() throws Exception {
        TrackSpline rail = rail();
        TrackAnchorSession.setStart(player, start());
        lays(TrackLayResult.of(TrackLayResult.Kind.NEW, rail));
        Block block = mock(Block.class);
        TrackCommands.markEnd(player, end(), block);
        fx.verify(() -> TrackFx.hit(block));
        fx.verify(() -> TrackFx.place(eq(world), argThat(p -> p.x == 8 && p.y == 64.25 && p.z == 20 && p.yaw == 0)));
    }

    @Test
    void finishRejectsDifferentWorldsAndMissingInventoryWithoutMutatingTracks() {
        TrackCommands.finish(player, start(), new Location(mock(World.class), 8, 64, 20));
        assertTrue(said("Start and end must be in the same world"));
        log.verify(() -> TrackLog.append("LAY_FAIL player=Engineer different worlds"));
        pieces.when(() -> TrackPieces.canAffordFirst(player)).thenReturn(false);
        TrackCommands.finish(player, start(), end());
        assertTrue(said("You need track in your inventory"));
        verifyNoInteractions(registry);
    }

    @Test
    void refusedLayHighlightsBlockingCoordinateAndPreventsImmediateRetry() throws Exception {
        refuses(new TrackLayException("Wall in the way", 8, 65, 12));
        TrackAnchorSession.setStart(player, start());
        TrackCommands.markEnd(player, end());
        assertTrue(said("Wall in the way"));
        highlights.verify(() -> TrainSpaceHighlight.show(player, List.of(new TrainBlockCollision.Obstruction(8, 65, 12, 0))));
        TrackCommands.markEnd(player, end());
        TrackCommands.startJunction(player, start());
        verify(player, times(2)).sendActionBar(any(Component.class));
        verify(registry, times(1)).lay(anyString(), any(World.class), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
        assertNotNull(TrackAnchorSession.getStart(player));
    }

    @Test
    void refusedLayWithoutCoordinateStillReportsError() throws Exception {
        refuses(new TrackLayException("Too tight a curve"));
        TrackCommands.finish(player, start(), end());
        assertTrue(said("Too tight a curve"));
        highlights.verifyNoInteractions();
    }

    @Test
    void junctionStartRequiresExistingTrackAndClearsOrdinaryAnchorOnSuccess() throws Exception {
        TrackCommands.startJunction(player, null);
        TrackCommands.startJunction(player, new Location(null, 0, 0, 0));
        TrackCommands.startJunction(player, start());
        assertEquals(3, messages.size());
        TrackSpline rail = rail();
        nearby(rail);
        TrackAnchorSession.setStart(player, start());
        TrackCommands.startJunction(player, new Location(world, 8, 64.25, 14, 180, 0));
        TrackJunctionSession.Pending pending = TrackJunctionSession.get(player);
        assertNotNull(pending);
        assertEquals(rail.getId(), pending.stemId);
        assertEquals(6, pending.s);
        assertEquals(-1, pending.facingSign);
        assertNull(TrackAnchorSession.getStart(player));
        verify(registry).dropIncompleteJunctions();
        verify(registry).ensureFrogClear(rail, 6, null);
        log.verify(() -> TrackLog.junctionStart("Engineer", rail.getId(), 6));
    }

    @Test
    void junctionRefusalHighlightsAllTrainSpaceObstructionsAndLogsFailure() throws Exception {
        TrackSpline rail = rail();
        nearby(rail);
        List<TrainBlockCollision.Obstruction> blocked = List.of(new TrainBlockCollision.Obstruction(8, 65, 14, 6));
        TrackLayException error = new TrackLayException("Train space blocked", blocked);
        doThrow(error).when(registry).ensureFrogClear(eq(rail), anyDouble(), isNull());
        TrackCommands.startJunction(player, start());
        assertNull(TrackJunctionSession.get(player));
        assertTrue(said("Train space blocked"));
        highlights.verify(() -> TrainSpaceHighlight.show(player, blocked));
        log.verify(() -> TrackLog.layFail("Train space blocked", error));
    }

    @Test
    void branchEndRetainsPendingJunctionUntilAffordableAndSuccessful() throws Exception {
        TrackSpline stem = rail(), branch = rail();
        pending(stem);
        pieces.when(() -> TrackPieces.canAffordFirst(player)).thenReturn(false);
        TrackCommands.markEnd(player, end());
        assertNotNull(TrackJunctionSession.get(player));
        assertTrue(said("You need track"));
        pieces.when(() -> TrackPieces.canAffordFirst(player)).thenReturn(true);
        when(registry.layBranch(stem.getId(), 6, 1, "world", world, 8, 64.25, 20)).thenReturn(branch);
        TrackCommands.markEnd(player, end());
        assertNull(TrackJunctionSession.get(player));
        verify(displays).refreshSpline(null, branch.getId());
        assertTrue(said("Laid branch. Length 12.0"));
    }

    @Test
    void branchEndRefusesUnsuitableGroundAndReportsLayFailure() throws Exception {
        TrackSpline stem = rail();
        pending(stem);
        support.when(() -> TrackSupport.firstSitY(world, 8, 64, 20)).thenReturn(null);
        TrackCommands.markEnd(player, end());
        assertNotNull(TrackJunctionSession.get(player));
        verifyNoInteractions(displays);
        support.when(() -> TrackSupport.firstSitY(world, 8, 64, 20)).thenReturn(64d);
        when(registry.layBranch(stem.getId(), 6, 1, "world", world, 8, 64.25, 20))
                .thenThrow(new TrackLayException("Branch too long"));
        TrackCommands.markEnd(player, end());
        assertNotNull(TrackJunctionSession.get(player));
        assertTrue(said("Branch too long"));
    }

    @Test
    void branchEndWithoutWorldKeepsPendingJunctionAndDoesNotLayRail() {
        TrackSpline stem = rail();
        pending(stem);
        TrackCommands.markEnd(player, new Location(null, 8, 64, 20));
        assertNotNull(TrackJunctionSession.get(player));
        verifyNoInteractions(registry, displays);
    }

    @Test
    void sequentialLayStartsAnimatorWithoutImmediateConsumptionOrBurst() throws Exception {
        TrackSpline rail = rail();
        TrackLayResult result = TrackLayResult.of(TrackLayResult.Kind.APPEND, rail, rail.xyz(), 2);
        lays(result);
        animation.when(() -> TrackBuildAnimator.sequential(player)).thenReturn(true);
        animation.when(() -> TrackBuildAnimator.start(eq(player), eq(rail), anyList(), eq(result.stroke))).thenReturn(true);
        TrackCommands.finish(player, start(), end());
        ArgumentCaptor<List<double[]>> kept = ArgumentCaptor.forClass(List.class);
        animation.verify(() -> TrackBuildAnimator.start(eq(player), eq(rail), kept.capture(), eq(result.stroke)));
        assertEquals(2, kept.getValue().size());
        assertArrayEquals(new double[] {8, 64.25, 8}, kept.getValue().getFirst());
        assertArrayEquals(new double[] {8, 64.25, 14}, kept.getValue().getLast());
        pieces.verify(() -> TrackPieces.consumeUpTo(any(), anyInt()), never());
        verifyNoInteractions(displays);
        fx.verifyNoInteractions();
        assertTrue(said("Laid track"));
    }

    @Test
    void failedSequentialStartDoesNotClaimMissingTrackWasLaid() throws Exception {
        TrackSpline rail = rail();
        lays(TrackLayResult.of(TrackLayResult.Kind.NEW, rail, rail.xyz(), 0));
        animation.when(() -> TrackBuildAnimator.sequential(player)).thenReturn(true);
        animation.when(() -> TrackBuildAnimator.start(eq(player), eq(rail), anyList(), anyList()))
                .thenAnswer(call -> { tracks.remove(rail.getId()); return false; });
        TrackCommands.finish(player, start(), end());
        assertFalse(said("Laid track"));
        verifyNoInteractions(displays);
    }

    @Test
    void fullyPaidConnectionAnnouncesJoinAndPreservesBothOriginalTrackSections() throws Exception {
        TrackSpline rail = rail();
        lays(TrackLayResult.of(TrackLayResult.Kind.CONNECT, rail, rail.xyz(), 0));
        TrackCommands.finish(player, start(), end());
        assertTrue(said("Connected two tracks. Length 12.0"));
        assertFalse(said("Not enough track to finish"));
        verify(registry, never()).persistPoints(any(), anyList());
    }

    @Test
    void connectingTracksRequiresFullPaymentBeforeChangingEitherSpline(@TempDir java.nio.file.Path directory) throws Exception {
        TrackRegistry actual = new TrackRegistry(directory.toFile());
        keep(mockStatic(TrackClearance.class)); // Geometry is real; block clearance is independent of payment.
        TrackSpline first = actual.lay("world", 0, 64, 0, 0, 64, 12).spline();
        TrackSpline second = actual.lay("world", 0, 64, 24, 0, 64, 36).spline();
        assertEquals(2, actual.inWorld("world").size());
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(actual);
        AtomicInteger available = inventory(1);
        TrackCommands.finish(player, new Location(world, 0, 64, 12), new Location(world, 0, 64, 24));
        TrackRegistry reloaded = new TrackRegistry(directory.toFile());
        reloaded.loadFromDisk();
        assertAll("An unaffordable connection must preserve existing track and inventory",
                () -> assertEquals(2, actual.inWorld("world").size()),
                () -> assertSame(first, actual.get(first.getId()).orElse(null)),
                () -> assertSame(second, actual.get(second.getId()).orElse(null)),
                () -> assertEquals(first.toJson(), reloaded.get(first.getId()).map(TrackSpline::toJson).orElse(null)),
                () -> assertEquals(second.toJson(), reloaded.get(second.getId()).map(TrackSpline::toJson).orElse(null)),
                () -> assertEquals(1, available.get()),
                () -> assertFalse(said("Connected two tracks")));
    }

    @Test
    void affordableConnectionPaysForEveryNewEdgeAndSavesJoinedTrack(@TempDir java.nio.file.Path directory) throws Exception {
        TrackRegistry actual = new TrackRegistry(directory.toFile());
        keep(mockStatic(TrackClearance.class));
        TrackSpline first = actual.lay("world", 0, 64, 0, 0, 64, 12).spline();
        TrackSpline second = actual.lay("world", 0, 64, 24, 0, 64, 36).spline();
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(actual);
        AtomicInteger available = inventory(1000);
        TrackCommands.finish(player, new Location(world, 0, 64, 12), new Location(world, 0, 64, 24));
        TrackSpline joined = actual.get(first.getId()).orElseThrow();
        assertEquals(1, actual.inWorld("world").size());
        assertTrue(actual.get(second.getId()).isEmpty());
        assertEquals(0, joined.first().z);
        assertEquals(36, joined.last().z);
        int newEdges = joined.edgeCount() - first.edgeCount() - second.edgeCount();
        assertTrue(newEdges > 1);
        assertEquals(1000 - newEdges, available.get());
        assertTrue(said("Connected two tracks"));
        TrackRegistry reloaded = new TrackRegistry(directory.toFile());
        reloaded.loadFromDisk();
        assertEquals(joined.toJson(), reloaded.get(joined.getId()).orElseThrow().toJson());
        assertTrue(reloaded.get(second.getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void closingLoopRequiresFullPaymentBeforeChangingSavedTrack(int amount, @TempDir java.nio.file.Path directory) {
        TrackRegistry actual = new TrackRegistry(directory.toFile());
        keep(mockStatic(TrackClearance.class));
        TrackSpline open = actual.replace(openRacetrack());
        assertFalse(open.isLoop());
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(actual);
        AtomicInteger available = inventory(amount);
        TrackSample first = open.first(), last = open.last();
        TrackCommands.finish(player, new Location(world, first.x, first.y, first.z),
                new Location(world, last.x, last.y, last.z));
        TrackSpline after = actual.get(open.getId()).orElseThrow();
        if (amount == 1) {
            assertSame(open, after);
            assertFalse(after.isLoop());
            assertEquals(1, available.get());
            assertTrue(said("complete this connection"));
        } else {
            assertTrue(after.isLoop());
            int newEdges = after.edgeCount() - open.edgeCount();
            assertTrue(newEdges > 1);
            assertEquals(amount - newEdges, available.get());
        }
        TrackRegistry reloaded = new TrackRegistry(directory.toFile());
        reloaded.loadFromDisk();
        assertEquals(after.toJson(), reloaded.get(open.getId()).orElseThrow().toJson());
    }

    @Test
    void partiallyPaidAppendPersistsAffordablePointsAndReportsIncompleteTrack() throws Exception {
        TrackSpline rail = rail();
        TrackLayResult result = TrackLayResult.of(TrackLayResult.Kind.APPEND, rail, rail.xyz(), 2).withBefore(rail);
        lays(result);
        pieces.when(() -> TrackPieces.consumeUpTo(player, 2)).thenReturn(1);
        List<double[]> kept = TrackPieces.persistPoints(result.keepPoints(), result.stroke, true, 1);
        TrackCommands.finish(player, start(), end());
        ArgumentCaptor<List<double[]>> saved = ArgumentCaptor.forClass(List.class);
        verify(registry).persistPoints(eq(rail.getId()), saved.capture());
        assertEquals(kept.size(), saved.getValue().size());
        for (int i = 0; i < kept.size(); i++) assertArrayEquals(kept.get(i), saved.getValue().get(i));
        verify(displays).refreshSpline(rail, rail.getId());
        assertTrue(said("Not enough track to finish"));
    }

    @Test
    void depletedNewLayThatLeavesNoRailAsksForTrackInsteadOfAnnouncing() throws Exception {
        TrackSpline rail = rail();
        lays(TrackLayResult.of(TrackLayResult.Kind.NEW, rail, rail.xyz(), 0));
        pieces.when(() -> TrackPieces.consumeUpTo(player, 2)).thenReturn(0);
        doAnswer(call -> { tracks.remove(rail.getId()); return null; }).when(registry).persistPoints(eq(rail.getId()), anyList());
        TrackCommands.finish(player, start(), end());
        assertTrue(said("You need track in your inventory"));
        assertFalse(said("Laid track"));
    }

    @Test
    void unchangedAppendCannotAnnounceSuccessWhenInventoryRunsOut() throws Exception {
        TrackSpline rail = rail();
        lays(TrackLayResult.of(TrackLayResult.Kind.APPEND, rail, rail.xyz(), 3));
        pieces.when(() -> TrackPieces.consumeUpTo(player, 2)).thenReturn(0);
        TrackCommands.finish(player, start(), end());
        assertTrue(said("You need track in your inventory"));
        assertFalse(said("Laid track"));
    }

    @Test
    void singlePointBurstHasNeutralYawAndDisplayServiceCanBeAbsent() throws Exception {
        TrackSpline rail = rail();
        lays(TrackLayResult.of(TrackLayResult.Kind.CONNECT, rail, List.of(new double[] {9, 64, 20}), 0));
        framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(null);
        pieces.when(() -> TrackPieces.pays(player)).thenReturn(false);
        TrackCommands.finish(player, start(), end());
        fx.verify(() -> TrackFx.place(eq(world), argThat(p -> p.x == 9 && p.y == 64 && p.z == 20 && p.yaw == 0)));
        assertTrue(said("Connected two tracks"));
    }

    @Test
    void trainBindingAndUnbindingRequireNearbyTrain() {
        assertTrue(command("bind"));
        assertTrue(command("unbind"));
        assertEquals(2, messages.stream().filter(m -> m.contains("No train")).count());
    }

    @Test
    void bindingChoosesNearestEligibleTrainAndUsesTrackNearLocomotiveIfNeeded() {
        TrackSpline rail = rail();
        ActiveVehicle notTrain = mock(ActiveVehicle.class);
        fleet.put(mock(Entity.class), notTrain);
        ActiveVehicle absentEntity = mock(ActiveVehicle.class);
        when(absentEntity.isTrain()).thenReturn(true);
        fleet.put(mock(Entity.class), absentEntity);
        train(8, mock(World.class));
        train(30, world);
        ActiveVehicle farther = train(14, world);
        ActiveVehicle nearest = train(10, world);
        when(vehicles.getByPassenger(player)).thenReturn(notTrain);
        when(registry.nearest("world", 10, 64, 8, 8)).thenReturn(Optional.of(rail));
        when(nearest.getTrainHandler().bind(rail)).thenReturn(true);
        assertTrue(command("bind", "bad-id"));
        verify(nearest.getTrainHandler()).bind(rail);
        verify(farther.getTrainHandler(), never()).bind(any());
        assertTrue(said("Bound to track " + rail.getId()));
        assertTrue(command("unbind"));
        verify(nearest.getTrainHandler()).unbind();
        assertTrue(said("Unbound from track"));
    }

    @Test
    void passengerBindingFollowsParentsAndReportsBindingFailureOrMissingRail() {
        ActiveVehicle car = train(8, world), middle = train(12, world), loco = train(16, world);
        when(vehicles.getByPassenger(player)).thenReturn(car);
        when(car.hasParent()).thenReturn(true);
        when(car.getParent()).thenReturn(middle);
        when(middle.hasParent()).thenReturn(true);
        when(middle.getParent()).thenReturn(loco);
        assertTrue(command("bind"));
        assertTrue(said("No track nearby or unknown id"));
        TrackSpline rail = rail();
        assertTrue(command("bind", rail.getId().toString()));
        verify(loco.getTrainHandler()).bind(rail);
        assertTrue(said("Could not bind to track"));
        assertTrue(command("unbind"));
        verify(loco.getTrainHandler()).unbind();
    }

    @Test
    void deletionValidatesUuidCancelsAnimationAndReportsStorageResult() {
        assertTrue(command("delete"));
        assertTrue(said("Usage: /vf track delete <uuid>"));
        assertTrue(command("delete", "broken"));
        assertTrue(said("Invalid uuid"));
        UUID known = UUID.randomUUID(), missing = UUID.randomUUID();
        when(registry.delete(known)).thenReturn(true);
        assertTrue(command("delete", known.toString()));
        animation.verify(() -> TrackBuildAnimator.cancel(known));
        verify(displays).despawnSpline(known);
        log.verify(() -> TrackLog.delete("Engineer", known, true));
        assertTrue(said("Deleted track " + known));
        framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(null);
        assertTrue(command("delete", missing.toString()));
        log.verify(() -> TrackLog.delete("Engineer", missing, false));
        assertTrue(said("Unknown track"));
    }

    @Test
    void diggingWithoutWorldOrTargetDoesNotChangeAnything() {
        TrackCommands.digAt(player, new Location(null, 1, 2, 3));
        verifyNoInteractions(registry);
        TrackCommands.digAt(player, start());
        log.verify(() -> TrackLog.dig(eq("Engineer"), argThat(r -> r.kind == DigResult.Kind.NONE)));
        verifyNoInteractions(displays);
        fx.verifyNoInteractions();
    }

    @Test
    void trainOnAnyAffectedSpanBlocksDiggingBeforeRegistryMutation() {
        TrackSpline rail = rail();
        TrackRegistry.DigTarget target = digTarget(rail, DigResult.deleted(rail.getId()));
        ActiveVehicle train = train(8, world);
        when(train.getTrainHandler().occupies(target.spans())).thenReturn(true);
        TrackCommands.digAt(player, start());
        verify(registry, never()).digAt(any(), anyInt(), any());
        assertTrue(said("A train is on this track"));
        verifyNoInteractions(displays);
    }

    @Test
    void digUpdatesAffectedSplineStartsSurvivalCooldownAndBlocksRepeat() {
        TrackSpline rail = rail();
        TrackRegistry.DigTarget target = digTarget(rail, DigResult.updated(rail));
        ActiveVehicle child = train(8, world);
        when(child.hasParent()).thenReturn(true);
        ActiveVehicle clear = train(10, world);
        ActiveVehicle ordinary = mock(ActiveVehicle.class);
        fleet.put(mock(Entity.class), ordinary);
        TrackCommands.digAt(player, start());
        verify(clear.getTrainHandler()).occupies(target.spans());
        verify(child.getTrainHandler(), never()).occupies(anyList());
        verify(registry).digAt(rail, 1, world);
        verify(displays).refreshSpline(rail, rail.getId());
        tools.verify(() -> TrackTools.showRemoverCooldown(player, 60_000));
        TrackCommands.digAt(player, start());
        verify(registry, times(1)).digAt(rail, 1, world);
        verify(player).swingMainHand();
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"CREATIVE", "SPECTATOR"})
    void creativeAndSpectatorDiggingSkipCooldown(GameMode mode) {
        when(player.getGameMode()).thenReturn(mode);
        TrackSpline rail = rail();
        digTarget(rail, DigResult.updated(rail));
        framework.when(VehicleFramework::getVehicleManager).thenReturn(null);
        TrackCommands.digAt(player, start());
        TrackCommands.digAt(player, start());
        verify(registry, times(2)).digAt(rail, 1, world);
        tools.verifyNoInteractions();
    }

    @Test
    void unsuccessfulDigDoesNotStartCooldown() {
        TrackSpline rail = rail();
        digTarget(rail, DigResult.none());
        TrackCommands.digAt(player, start());
        TrackCommands.digAt(player, start());
        verify(registry, times(2)).digAt(rail, 1, world);
        tools.verifyNoInteractions();
    }

    @Test
    void deletingJunctionTurnoutCancelsItsAnimationAndRemovesDisplays() {
        UUID id = UUID.randomUUID();
        TrackCommands.applyDig(player, DigResult.deletedJunctionTurnout(id), start());
        assertTrue(said("Removed junction turnout."));
        animation.verify(() -> TrackBuildAnimator.cancel(id));
        verify(displays).despawnSpline(id);
        fx.verify(() -> TrackFx.place(eq(world), argThat(p -> p.x == 8 && p.y == 64 && p.z == 8)));
        verify(player).swingMainHand();
    }

    @Test
    void updatedTurnoutKeepsBranchAndAnimatedTrackDiscardsBeforeSnapshot() {
        TrackSpline rail = rail();
        animation.when(() -> TrackBuildAnimator.isBuilding(rail.getId())).thenReturn(true);
        Cache.trackBuildSwing = false;
        TrackCommands.applyDig(player, DigResult.removedJunctionTurnout(rail), start(), rail);
        assertTrue(said("branch track kept beyond the frog"));
        verify(displays).refreshSpline(null, rail.getId());
        verify(player, never()).swingMainHand();
        animation.verify(() -> TrackBuildAnimator.cancel(rail.getId()), times(2));
    }

    @Test
    void replacementDigRemovesPreviousIdentityAndKeepsOriginalSnapshot() {
        TrackSpline before = rail(), kept = rail();
        TrackCommands.applyDig(player, DigResult.updated(before.getId(), kept), null, before);
        verify(displays).despawnSpline(before.getId());
        verify(displays).refreshSpline(before, kept.getId());
        fx.verifyNoInteractions();
    }

    @Test
    void splitDigCancelsBothAnimationsAndReplacesBothDisplaySets() {
        TrackSpline kept = rail(), tail = rail();
        TrackCommands.applyDig(player, DigResult.split(kept, tail), start());
        animation.verify(() -> TrackBuildAnimator.cancel(kept.getId()));
        animation.verify(() -> TrackBuildAnimator.cancel(tail.getId()));
        InOrder order = inOrder(displays);
        order.verify(displays).despawnSpline(kept.getId());
        order.verify(displays).despawnSpline(tail.getId());
        order.verify(displays).spawnSpline(kept);
        order.verify(displays).spawnSpline(tail);
    }

    @Test
    void digStillRecordsMutationWhenDisplaysAreUnavailable() {
        framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(null);
        TrackSpline rail = rail();
        TrackCommands.applyDig(player, DigResult.deleted(rail.getId()), null);
        TrackCommands.applyDig(player, DigResult.updated(rail), new Location(null, 1, 2, 3));
        TrackCommands.applyDig(player, DigResult.split(rail, rail()), null);
        verifyNoInteractions(displays);
        fx.verifyNoInteractions();
    }
}
