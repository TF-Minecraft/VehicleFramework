package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.PersistenceLog;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import net.tfminecraft.vehicleframework.util.ImpactVfx;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;

class TrackRuntimeCoverageTest {
    final List<AutoCloseable> scopes = new ArrayList<>();
    final Map<Field, Object> savedConfig = new HashMap<>();
    final Map<UUID, TrackSpline> tracks = new HashMap<>();
    final Map<String, ItemStack> templates = new HashMap<>();
    final World world = mock(World.class);
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final TrackRegistry registry = mock(TrackRegistry.class);
    final TrackDisplayManager displays = mock(TrackDisplayManager.class);
    final ItemAPI items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    ItemStack[] contents = new ItemStack[36]; ItemStack offhand, hand;
    MockedStatic<VehicleFramework> framework;
    MockedStatic<Bukkit> bukkit;
    MockedStatic<ImpactVfx> effects;
    VehicleFramework previousPlugin;

    @BeforeAll static void registries() {
        RegistryFixture.initialize();
        Map<NamespacedKey, DataComponentType> components = new HashMap<>();
        doAnswer(call -> components.computeIfAbsent(call.getArgument(0), key -> {
            Class<? extends DataComponentType> type = Set.of("unbreakable", "glider", "intangible_projectile", "creative_slot_lock").contains(key.getKey()) ? DataComponentType.NonValued.class : DataComponentType.Valued.class;
            DataComponentType component = mock(type); when(component.getKey()).thenReturn(key); return component;
        })).when(Registry.DATA_COMPONENT_TYPE).get(any(NamespacedKey.class));
        assertNotNull(DataComponentTypes.USE_COOLDOWN);
    }
    @BeforeEach void setup() throws Exception {
        // Snapshot only public configuration so every test restores the process-wide cache.
        for (Field field : Cache.class.getFields()) if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) savedConfig.put(field, field.get(null));
        previousPlugin = VehicleFramework.plugin; VehicleFramework.plugin = mock(VehicleFramework.class); when(VehicleFramework.plugin.getName()).thenReturn("VehicleFramework");
        when(VehicleFramework.plugin.namespace()).thenReturn("vehicleframework");
        framework = keep(mockStatic(VehicleFramework.class)); framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry); framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(displays);
        bukkit = keep(mockStatic(Bukkit.class)); bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        keep(mockStatic(VFLogger.class)); keep(mockStatic(TrackLog.class)); keep(mockStatic(RecorderLog.class)); keep(mockStatic(PersistenceLog.class)); effects = keep(mockStatic(ImpactVfx.class));
        var libs = keep(mockStatic(TLibs.class)); libs.when(TLibs::getItemAPI).thenReturn(items);
        when(items.getCreator().getItemFromPath(anyString())).thenAnswer(call -> templates.get(call.getArgument(0)));
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenAnswer(call -> {
            ItemStack item = call.getArgument(0); if (item == null) return false;
            return item.getType() == switch (call.<String>getArgument(1)) { case "track" -> Material.IRON_INGOT; case "recorder" -> Material.IRON_NUGGET; case "layer" -> Material.STICK; case "remover" -> Material.GOLD_NUGGET; case "junction" -> Material.REDSTONE; default -> Material.AIR; };
        });
        when(world.getName()).thenReturn("world"); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0, 15, 10));
        when(player.getName()).thenReturn("Engineer"); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory); when(inventory.getStorageContents()).thenAnswer(call -> contents);
        when(inventory.getItemInOffHand()).thenAnswer(call -> offhand); when(inventory.getItemInMainHand()).thenAnswer(call -> hand);
        doAnswer(call -> { contents[call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
        doAnswer(call -> { offhand = call.getArgument(0); return null; }).when(inventory).setItemInOffHand(any());
        doAnswer(call -> { hand = call.getArgument(0); return null; }).when(inventory).setItemInMainHand(any());
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
        when(registry.get(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(tracks.get(call.getArgument(0))));
        when(registry.inWorld("world")).thenAnswer(call -> new ArrayList<>(tracks.values()));
        Cache.trackItem = "track"; Cache.trackRecorderItem = "recorder"; Cache.trackLayerItem = "layer"; Cache.trackRemoverItem = "remover"; Cache.trackJunctionItem = "junction";
        Cache.trackBuildIntervalTicks = 2; Cache.trackBuildSwing = true; Cache.trackPlaceKeepoutRadius = 1;
    }
    @AfterEach void cleanup() throws Exception {
        TrackBuildAnimator.finishAll(); TrainSpaceHighlight.clearAll();
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        for (var entry : savedConfig.entrySet()) entry.getKey().set(null, entry.getValue()); VehicleFramework.plugin = previousPlugin;
    }
    <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    ItemStack stack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class); int[] count = {amount}; ItemMeta[] meta = {mock(ItemMeta.class)}; Map<NamespacedKey, String> tags = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class); when(meta[0].getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> tags.get(call.getArgument(0)));
        doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { tags.remove(call.getArgument(0)); return null; }).when(pdc).remove(any());
        when(stack.getType()).thenReturn(material); when(stack.getAmount()).thenAnswer(call -> count[0]); doAnswer(call -> { count[0] = call.getArgument(0); return null; }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(call -> stack(material, count[0])); when(stack.hasItemMeta()).thenAnswer(call -> meta[0] != null); when(stack.getItemMeta()).thenAnswer(call -> meta[0]);
        when(stack.setItemMeta(any())).thenAnswer(call -> { meta[0] = call.getArgument(0); return true; }); return stack;
    }
    List<double[]> points(double... z) { List<double[]> points = new ArrayList<>(); for (double value : z) points.add(new double[]{0, 64, value}); return points; }
    TrackSpline rail(List<double[]> points) { TrackSpline spline = TrackSpline.fromPoints(UUID.randomUUID(), "world", false, points); tracks.put(spline.getId(), spline); return spline; }
    Block block(Material material, int x, int y, int z, boolean passable, BlockData data) {
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world); when(block.getType()).thenReturn(material); when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenAnswer(call -> new Location(world, x, y, z)); when(block.isPassable()).thenReturn(passable); when(block.getBlockData()).thenReturn(data); return block;
    }

    @Test void trackPiecesCountAndConsumeStorageBeforeOffhandWithoutTouchingOtherItems() {
        contents[0] = stack(Material.STONE, 5); contents[1] = stack(Material.IRON_INGOT, 2); offhand = stack(Material.IRON_INGOT, 2);
        assertEquals(4, TrackPieces.count(player)); assertEquals(0, TrackPieces.count(null)); assertTrue(TrackPieces.canAffordFirst(player)); assertEquals(0, TrackPieces.consumeUpTo(player, 0));
        assertTrue(TrackPieces.consumeOne(player)); assertEquals(1, contents[1].getAmount()); assertTrue(TrackPieces.consumeOne(player)); assertNull(contents[1]);
        assertEquals(2, TrackPieces.consumeUpTo(player, 4)); assertNull(offhand); assertEquals(5, contents[0].getAmount()); assertFalse(TrackPieces.consumeOne(player)); assertFalse(TrackPieces.canAffordFirst(player));
        contents = null; assertFalse(TrackPieces.consumeOne(player)); contents = new ItemStack[0];
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE); assertTrue(TrackPieces.consumeOne(player)); assertEquals(10, TrackPieces.consumeUpTo(player, 10));
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR); assertFalse(TrackPieces.pays(player)); assertFalse(TrackPieces.pays(null));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); Cache.trackItem = null; assertFalse(TrackPieces.pays(player)); Cache.trackItem = " "; assertFalse(TrackPieces.pays(player));
    }

    @Test void reclaimedTrackDropsRequireALoadedChunkAndAUsableTemplate() {
        TrackPieces.dropAt(null, 0, 64, 0); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); TrackPieces.dropAt(world, -1, 64, 0); verify(world, never()).dropItemNaturally(any(), any());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); TrackPieces.dropAt(world, 0, 64, 0);
        templates.put("track", stack(Material.AIR, 1)); TrackPieces.dropAt(world, 0, 64, 0);
        Cache.trackItem = null; TrackPieces.dropAt(world, 0, 64, 0); Cache.trackItem = " "; TrackPieces.dropAt(world, 0, 64, 0); Cache.trackItem = "track";
        ItemStack track = stack(Material.IRON_INGOT, 1); templates.put("track", track); TrackPieces.dropAt(world, -1, 64, 0);
        verify(world).dropItemNaturally(argThat(at -> at.getX() == -1 && at.getY() == 64 + Cache.trackDisplayYOffset), same(track));
        when(items.getCreator().getItemFromPath("track")).thenThrow(new IllegalStateException("item plugin unavailable")); assertDoesNotThrow(() -> TrackPieces.dropAt(world, 0, 64, 0));
    }

    @Test void recorderMetadataRoundTripsAndRejectsMissingInvalidAndEmptyTapes() {
        assertNull(ThrottleTapeItems.read(null)); ItemStack blank = stack(Material.IRON_NUGGET, 1); when(blank.hasItemMeta()).thenReturn(false); assertNull(ThrottleTapeItems.read(blank));
        when(blank.hasItemMeta()).thenReturn(true); assertNull(ThrottleTapeItems.read(blank));
        NamespacedKey key = new NamespacedKey(VehicleFramework.plugin, "throttle_tape");
        for (String raw : List.of(" ", "bad json", "[]")) { blank.getItemMeta().getPersistentDataContainer().set(key, PersistentDataType.STRING, raw); assertNull(ThrottleTapeItems.read(blank)); }
        UUID spline = UUID.randomUUID(); ThrottleTape tape = new ThrottleTape(spline.toString(), List.of(new ThrottleTape.Sample(1, 1, 40)));
        ThrottleTapeItems.write(blank, tape); assertEquals(tape.toJson(), ThrottleTapeItems.read(blank).toJson());
        ThrottleTapeItems.write(blank, null); assertNull(ThrottleTapeItems.read(blank)); ThrottleTapeItems.write(blank, new ThrottleTape(spline.toString(), List.of())); assertNull(ThrottleTapeItems.read(blank));
        ThrottleTapeItems.write(null, tape); ItemStack noMeta = stack(Material.AIR, 1); when(noMeta.getItemMeta()).thenReturn(null); assertDoesNotThrow(() -> ThrottleTapeItems.write(noMeta, tape));
    }

    @Test void toolMatchingAndRemoverCooldownUseTheDedicatedGroup() {
        assertTrue(TrackTools.isLayer(stack(Material.STICK, 1))); assertTrue(TrackTools.isJunction(stack(Material.REDSTONE, 1))); assertTrue(TrackTools.isRecorder(stack(Material.IRON_NUGGET, 1))); assertFalse(TrackTools.isRemover(null));
        Cache.trackLayerItem = null; assertFalse(TrackTools.isLayer(stack(Material.STICK, 1))); Cache.trackLayerItem = " "; assertFalse(TrackTools.isLayer(stack(Material.STICK, 1)));
        hand = stack(Material.GOLD_NUGGET, 1); TrackTools.showRemoverCooldown(player, 0);
        UseCooldown.Builder builder = mock(UseCooldown.Builder.class, RETURNS_SELF); UseCooldown value = mock(UseCooldown.class); when(value.cooldownGroup()).thenReturn(TrackTools.REMOVER_COOLDOWN); when(builder.build()).thenReturn(value);
        try (var cooldown = mockStatic(UseCooldown.class)) {
            cooldown.when(() -> UseCooldown.useCooldown(anyFloat())).thenReturn(builder);
            TrackTools.showRemoverCooldown(player, 101); verify(player).setCooldown(TrackTools.REMOVER_COOLDOWN, 3); verify(hand).setData(DataComponentTypes.USE_COOLDOWN, value);
            when(hand.getData(DataComponentTypes.USE_COOLDOWN)).thenReturn(value); TrackTools.showRemoverCooldown(player, 150); verify(hand, times(1)).setData(DataComponentTypes.USE_COOLDOWN, value);
        }
        hand = stack(Material.STONE, 1); TrackTools.showRemoverCooldown(player, 100); verify(hand, never()).setData(eq(DataComponentTypes.USE_COOLDOWN), any(UseCooldown.class));
    }

    @Test void animatedTrackConstructionConsumesOnePiecePerRevealedEdgeAndReusesBakedChunks() {
        contents[0] = stack(Material.IRON_INGOT, 4); List<double[]> stroke = points(0, 1, 2, 3); TrackSpline spline = rail(stroke);
        assertFalse(TrackBuildAnimator.isBuilding(null)); assertFalse(TrackBuildAnimator.start(player, null, null, stroke)); assertFalse(TrackBuildAnimator.sequential(null));
        assertTrue(TrackBuildAnimator.sequential(player)); when(player.getGameMode()).thenReturn(GameMode.CREATIVE); assertFalse(TrackBuildAnimator.sequential(player)); when(player.getGameMode()).thenReturn(GameMode.SPECTATOR); assertFalse(TrackBuildAnimator.sequential(player)); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        assertTrue(TrackBuildAnimator.start(player, spline, null, stroke)); assertEquals(3, contents[0].getAmount()); assertTrue(TrackBuildAnimator.isBuilding(spline.getId()));
        Chunk chunk = mock(Chunk.class); when(chunk.getWorld()).thenReturn(world); TrackBuildAnimator.spawnIntoChunk(spline, chunk); verify(displays).spawnVisuals(eq(spline.getId()), anyList(), eq(world), eq(chunk));
        TrackBuildAnimator.tick(); assertEquals(3, contents[0].getAmount()); TrackBuildAnimator.tick(); assertEquals(2, contents[0].getAmount());
        TrackBuildAnimator.tick(); TrackBuildAnimator.tick(); assertFalse(TrackBuildAnimator.isBuilding(spline.getId())); assertEquals(1, contents[0].getAmount()); verify(player, times(3)).swingMainHand();
        TrackBuildAnimator.tick(); TrackBuildAnimator.spawnIntoChunk(spline, chunk); TrackBuildAnimator.cancel(null);
    }

    @Test void failedPaymentPersistsOnlyThePaidPrefixAndHandlesAbsentServices() {
        List<double[]> keep = points(-2, -1, 0), stroke = points(0, 1, 2, 3); TrackSpline spline = rail(stroke);
        assertFalse(TrackBuildAnimator.start(player, spline, keep, stroke)); verify(registry).persistPoints(eq(spline.getId()), argThat(p -> p.size() == keep.size()));
        contents[0] = stack(Material.IRON_INGOT, 1); assertTrue(TrackBuildAnimator.start(player, spline, keep, stroke)); TrackBuildAnimator.tick(); TrackBuildAnimator.tick();
        assertFalse(TrackBuildAnimator.isBuilding(spline.getId())); verify(player).sendMessage("§cNot enough track to finish."); verify(registry).persistPoints(eq(spline.getId()), argThat(p -> p.size() == 4 && p.getLast()[2] == 1));
        assertFalse(TrackBuildAnimator.start(null, spline, null, null)); verify(displays).rebakeSpline(spline.getId());
        framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(null); assertFalse(TrackBuildAnimator.start(null, spline, null, points(0))); assertTrue(TrackBuildAnimator.start(null, spline, points(10, 11), points(0, 1))); TrackBuildAnimator.tick(); TrackBuildAnimator.tick(); assertFalse(TrackBuildAnimator.isBuilding(spline.getId()));
        assertTrue(TrackBuildAnimator.start(null, spline, points(10, 11), stroke)); framework.when(VehicleFramework::getTrackRegistry).thenReturn(null); TrackBuildAnimator.tick(); TrackBuildAnimator.tick(); TrackBuildAnimator.finishAll(); assertFalse(TrackBuildAnimator.isBuilding(spline.getId()));
    }

    @Test void disconnectingDuringPaidConstructionCannotBuildTheRemainingTrackForFree() {
        contents[0] = stack(Material.IRON_INGOT, 1); TrackSpline spline = rail(points(0, 1, 2, 3));
        assertTrue(TrackBuildAnimator.start(player, spline, List.of(), points(0, 1, 2, 3))); assertNull(contents[0]);
        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(null); TrackBuildAnimator.tick(); TrackBuildAnimator.tick();
        assertFalse(TrackBuildAnimator.isBuilding(spline.getId()), "Offline builders must retain only their paid track");
        verify(registry).persistPoints(eq(spline.getId()), argThat(p -> p.size() == 2 && p.getLast()[2] == 1));
    }

    @Test void animationRetainsOnlyTheStrokeWhenExtendingFromTheOppositeEnd() {
        TrackSpline spline = rail(points(0, 1, 2, 3)); assertFalse(TrackBuildAnimator.start(player, spline, null, points(0, 1, 2, 3)));
        verify(registry).persistPoints(eq(spline.getId()), argThat(List::isEmpty));
        Cache.trackBuildSwing = false; Cache.trackBuildIntervalTicks = 1;
        assertTrue(TrackBuildAnimator.start(null, spline, points(10, 11), points(0, 1, 2, 3)));
        verify(displays).replaceFrom(eq(spline.getId()), anyList(), argThat(visuals -> !visuals.isEmpty() && visuals.stream().allMatch(v -> v.z < 2)), eq(world), isNull());
        verify(player, never()).swingMainHand(); tracks.remove(spline.getId()); TrackBuildAnimator.tick(); TrackBuildAnimator.tick(); assertFalse(TrackBuildAnimator.isBuilding(spline.getId()));
    }

    @Test void trackSupportRecognizesSolidSurfacesSlabsStairsTrapdoorsAndIgnoredVegetation() {
        assertNull(TrackSupport.sitY(null)); assertFalse(TrackSupport.isIgnored((Block)null));
        Slab slab = mock(Slab.class); when(slab.getType()).thenReturn(Slab.Type.BOTTOM); assertEquals(64.5, TrackSupport.sitY(64, slab, Material.STONE_SLAB, false, true));
        when(slab.getType()).thenReturn(Slab.Type.TOP); assertEquals(65, TrackSupport.sitY(64, slab, Material.STONE_SLAB, false, true));
        Stairs stairs = mock(Stairs.class); when(stairs.getHalf()).thenReturn(Bisected.Half.BOTTOM); assertEquals(64.5, TrackSupport.sitY(64, stairs, Material.STONE_STAIRS, false, true));
        when(stairs.getHalf()).thenReturn(Bisected.Half.TOP); assertEquals(65, TrackSupport.sitY(64, stairs, Material.STONE_STAIRS, false, true));
        TrapDoor trap = mock(TrapDoor.class); when(trap.getHalf()).thenReturn(Bisected.Half.BOTTOM); assertEquals(64.5, TrackSupport.sitY(64, trap, Material.OAK_TRAPDOOR, true, true));
        when(trap.getHalf()).thenReturn(Bisected.Half.TOP); assertEquals(65, TrackSupport.sitY(64, trap, Material.OAK_TRAPDOOR, false, true)); when(trap.isOpen()).thenReturn(true); assertNull(TrackSupport.sitY(64, trap, Material.OAK_TRAPDOOR, false, true));
        assertNull(TrackSupport.sitY(64, mock(Snow.class), Material.SNOW, false, true)); assertNull(TrackSupport.sitY(64, null, Material.STONE, false, false)); assertNull(TrackSupport.sitY(64, null, Material.STONE, true, true));
        for (Material ignored : List.of(Material.AIR, Material.CAVE_AIR, Material.WATER, Material.LAVA, Material.MOSS_CARPET, Material.RED_CARPET)) assertTrue(TrackSupport.isIgnored(null, ignored, false));
        for (String plant : List.of("OAK_SAPLING", "FLOWER_POT", "RED_TULIP", "BLUE_ORCHID", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH", "RED_MUSHROOM", "VINE", "LILY_PAD", "PINK_PETALS", "KELP", "SUGAR_CANE", "BAMBOO", "DANDELION", "POPPY", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER", "WITHER_ROSE", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "TORCHFLOWER", "PITCHER_PLANT", "WILDFLOWERS", "BUSH", "FIREFLY_BUSH", "LEAF_LITTER")) assertTrue(TrackSupport.isPlantName(plant), plant);
        assertFalse(TrackSupport.isPlantName("MUSHROOM_STEM")); assertFalse(TrackSupport.isPlantName("MUSHROOM_BLOCK")); assertFalse(TrackSupport.isPlantName("STONE"));
        Block stone = block(Material.STONE, 0, 63, 0, false, mock(BlockData.class)), air = block(Material.AIR, 0, 65, 0, true, mock(BlockData.class));
        assertTrue(TrackSupport.isValidClick(stone)); assertTrue(TrackSupport.isIgnored(air)); assertFalse(TrackSupport.blocksRail(air, 64)); assertTrue(TrackSupport.blocksRail(stone, 63.5)); assertFalse(TrackSupport.blocksRail(stone, 64));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> call.<Integer>getArgument(1) == 63 ? stone : air);
        assertEquals(64, TrackSupport.snapY(world, 0, 68, 0)); assertEquals(64, TrackSupport.floorY(world, 0, 68, 0)); assertNull(TrackSupport.firstSitY(null, 0, 64, 0)); assertEquals(80, TrackSupport.snapY(world, 0, 80, 0));
    }

    @Test void toolClicksRouteOnlySupportedHandsActionsAndSurfaces() {
        TrackToolListener listener = new TrackToolListener(); Block stone = block(Material.STONE, 3, 63, 7, false, mock(BlockData.class)); Block grass = block(Material.SHORT_GRASS, 3, 64, 7, true, mock(BlockData.class));
        try (var commands = mockStatic(TrackCommands.class); var effects = mockStatic(TrackFx.class)) {
            commands.when(() -> TrackCommands.markStart(eq(player), any())).thenReturn(true);
            listener.onInteract(click(Action.LEFT_CLICK_BLOCK, stack(Material.STICK, 1), stone, EquipmentSlot.OFF_HAND)); listener.onInteract(click(Action.LEFT_CLICK_BLOCK, stack(Material.STONE, 1), stone, EquipmentSlot.HAND));
            listener.onInteract(click(Action.RIGHT_CLICK_AIR, stack(Material.STICK, 1), null, EquipmentSlot.HAND)); listener.onInteract(click(Action.LEFT_CLICK_BLOCK, stack(Material.STICK, 1), null, EquipmentSlot.HAND)); commands.verifyNoInteractions();
            PlayerInteractEvent start = click(Action.LEFT_CLICK_BLOCK, stack(Material.STICK, 1), stone, EquipmentSlot.HAND); listener.onInteract(start); assertTrue(start.isCancelled());
            commands.verify(() -> TrackCommands.markStart(eq(player), argThat(at -> at.getX() == 3.5 && at.getY() == 64 && at.getZ() == 7.5 && at.getYaw() == 15))); effects.verify(() -> TrackFx.hit(stone));
            listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, stack(Material.STICK, 1), stone, EquipmentSlot.HAND)); commands.verify(() -> TrackCommands.markEnd(eq(player), any(), eq(stone)));
            listener.onInteract(click(Action.LEFT_CLICK_BLOCK, stack(Material.GOLD_NUGGET, 1), stone, EquipmentSlot.HAND)); commands.verify(() -> TrackCommands.digAt(eq(player), any()));
            listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, stack(Material.GOLD_NUGGET, 1), stone, EquipmentSlot.HAND));
            listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, stack(Material.REDSTONE, 1), stone, EquipmentSlot.HAND)); commands.verify(() -> TrackCommands.startJunction(eq(player), any()));
            listener.onInteract(click(Action.LEFT_CLICK_BLOCK, stack(Material.REDSTONE, 1), stone, EquipmentSlot.HAND));
            listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, stack(Material.REDSTONE, 1), grass, EquipmentSlot.HAND)); verify(player).sendMessage("§cClick existing track to start a junction.");
            listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, stack(Material.STICK, 1), grass, EquipmentSlot.HAND)); verify(player).sendMessage("§cClick solid ground, not grass or plants.");
            listener.onInteract(click(Action.LEFT_CLICK_BLOCK, stack(Material.GOLD_NUGGET, 1), grass, EquipmentSlot.HAND));
            commands.when(() -> TrackCommands.skipDuplicateToolUse(player)).thenReturn(true);
            for (Material tool : List.of(Material.STICK, Material.GOLD_NUGGET, Material.REDSTONE)) listener.onInteract(click(tool == Material.REDSTONE ? Action.RIGHT_CLICK_BLOCK : Action.LEFT_CLICK_BLOCK, stack(tool, 1), stone, EquipmentSlot.HAND));
            effects.verify(() -> TrackFx.hit(stone), times(1));
        }
    }
    PlayerInteractEvent click(Action action, ItemStack item, Block block, EquipmentSlot hand) { return new PlayerInteractEvent(player, action, item, block, BlockFace.UP, hand); }

    @Test void resettlingAndKeepoutUseRealBlockHeightsAndWorldTrackSamples() {
        Block stone = block(Material.STONE, 0, 63, 0, false, mock(BlockData.class)), air = block(Material.AIR, 0, 70, 0, true, mock(BlockData.class));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> call.<Integer>getArgument(1) == 63 ? stone : air);
        List<double[]> points = points(0, 2, 4, 6); points.getFirst()[1] = 70; points.getLast()[1] = 70;
        TrackResettle.resettle(world, points, true, true); assertEquals(64, points.getFirst()[1]); assertEquals(64, points.getLast()[1]);
        TrackResettle.resettle(world, points, true, true); TrackResettle.resettle(world, null, true, true); TrackResettle.resettle(world, points(0), true, true); TrackResettle.resettle(null, points(0, 1), false, false);
        assertFalse(TrackPlaceKeepout.blocked((Block)null)); assertFalse(TrackPlaceKeepout.blocked((List<TrackSample>)null, 0, 64, 0, 1)); assertFalse(TrackPlaceKeepout.blocked(0, 64, 0, 0, 64, 0, 0));
        TrackSpline spline = rail(points(0, 1, 2)); Block inside = block(Material.STONE, 0, 64, 0, false, mock(BlockData.class)); assertTrue(TrackPlaceKeepout.blocked(inside));
        assertFalse(TrackPlaceKeepout.blocked(spline.getSamples(), 20, 64, 20, 1));
        assertFalse(TrackPlaceKeepout.blocked(stone)); framework.when(VehicleFramework::getTrackRegistry).thenReturn(null); assertFalse(TrackPlaceKeepout.blocked(inside));
        assertEquals(0, TrackChunks.edgeIndexForSample(0, 0, false)); assertEquals(0, TrackChunks.edgeIndexForSample(-1, 4, false));
        TrackVisual visual = new TrackVisual(TrackVisual.Type.SMALL, 0, 1, -1, 1, 0, 64, 0, 0, 0); assertEquals(-1, visual.coveredEdge(0, 0)); assertEquals(4, visual.coveredEdge(0, 5)); assertEquals(0, visual.coveredEdge(-4, 5));
    }

    ActiveVehicle train(String id, UUID spline) {
        ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS); when(vehicle.isTrain()).thenReturn(true); when(vehicle.getUUID()).thenReturn(id);
        when(vehicle.getTrainHandler().isBound()).thenReturn(spline != null); when(vehicle.getTrainHandler().getSplineId()).thenReturn(spline);
        when(vehicle.getEntity().getWorld()).thenReturn(world); when(vehicle.getEntity().getBoundingBox()).thenReturn(new BoundingBox(-1, 64, -1, 1, 67, 1));
        when(vehicle.getOwnerData().getOwner()).thenReturn("player_Engineer"); return vehicle;
    }
    @Test void recordingRequiresOwnershipBoundCircuitAndTogglesOnlyForTheRecorder() {
        ActiveVehicle vehicle = train("loco", UUID.randomUUID()); TrainHandler train = vehicle.getTrainHandler();
        assertFalse(TrainTapeInteract.handle(null, vehicle)); assertFalse(TrainTapeInteract.handle(player, null)); when(vehicle.isTrain()).thenReturn(false); assertFalse(TrainTapeInteract.handle(player, vehicle)); when(vehicle.isTrain()).thenReturn(true);
        when(vehicle.hasParent()).thenReturn(true); assertFalse(TrainTapeInteract.handle(player, vehicle)); when(vehicle.hasParent()).thenReturn(false); hand = stack(Material.STONE, 1); assertFalse(TrainTapeInteract.handle(player, vehicle));
        hand = stack(Material.IRON_NUGGET, 1);
        try (var permissions = mockStatic(Permissions.class)) {
            when(vehicle.getOwnerData().getOwner()).thenReturn(null); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cYou do not own this vehicle");
            permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true); when(train.isBound()).thenReturn(false); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cThis locomotive is not on a track");
            when(train.isBound()).thenReturn(true); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cRecording only works on a circuit");
            when(train.canRecordCircuit()).thenReturn(true); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(train).startRecording(player); assertNull(ThrottleTapeItems.read(hand));
            when(train.isRecording()).thenReturn(true); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(train).stopRecording(hand); verify(player).sendMessage("§eRecording cancelled");
        }
    }

    @Test void installedTapesMustMatchTheTrackAndConsumeOnlyOneRecorder() {
        UUID spline = UUID.randomUUID(); ActiveVehicle vehicle = train("loco", spline); TrainHandler train = vehicle.getTrainHandler(); when(player.isSneaking()).thenReturn(true); hand = stack(Material.IRON_NUGGET, 2);
        try (var permissions = mockStatic(Permissions.class)) {
            assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cThis recorder has no tape");
            ThrottleTape wrong = new ThrottleTape(UUID.randomUUID().toString(), List.of(new ThrottleTape.Sample(1, 1, 40))); ThrottleTapeItems.write(hand, wrong);
            assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cThat tape does not match this track"); assertEquals(2, hand.getAmount());
            ThrottleTape tape = new ThrottleTape(spline.toString(), List.of(new ThrottleTape.Sample(1, 1, 40))); ThrottleTapeItems.write(hand, tape);
            when(train.hasInstalledTape()).thenReturn(true); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cRemove the current tape first"); assertEquals(2, hand.getAmount());
            when(train.hasInstalledTape()).thenReturn(false); assertTrue(TrainTapeInteract.handle(player, vehicle)); assertEquals(1, hand.getAmount()); verify(train).setInstalledTape(argThat(value -> value != null && value.toJson().equals(tape.toJson())));
            assertTrue(TrainTapeInteract.handle(player, vehicle)); assertNull(hand);
        }
    }

    @Test void ejectedTapesPreserveMetadataAndDropInventoryOverflow() {
        UUID spline = UUID.randomUUID(); ActiveVehicle vehicle = train("loco", spline); TrainHandler train = vehicle.getTrainHandler(); when(player.isSneaking()).thenReturn(true);
        ThrottleTape tape = new ThrottleTape(spline.toString(), List.of(new ThrottleTape.Sample(1, 1, 40))); when(train.getInstalledTape()).thenReturn(tape); when(train.hasInstalledTape()).thenReturn(true);
        try (var permissions = mockStatic(Permissions.class)) {
            assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(player).sendMessage("§cCould not create a recorder item"); verify(train, never()).setInstalledTape(null);
            ItemStack output = stack(Material.IRON_NUGGET, 4); templates.put("recorder", output); when(inventory.addItem(output)).thenReturn(new HashMap<>(Map.of(0, output)));
            assertTrue(TrainTapeInteract.handle(player, vehicle)); assertEquals(1, output.getAmount()); assertEquals(tape.toJson(), ThrottleTapeItems.read(output).toJson()); verify(train).setInstalledTape(null); verify(world).dropItemNaturally(any(), same(output));
            when(player.getWorld()).thenReturn(null); assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(world, times(1)).dropItemNaturally(any(), same(output));
        }
    }

    @Test void anAirRecorderTemplateCannotDestroyTheInstalledTape() {
        UUID spline = UUID.randomUUID(); ActiveVehicle vehicle = train("loco", spline); TrainHandler train = vehicle.getTrainHandler(); when(player.isSneaking()).thenReturn(true); hand = stack(Material.AIR, 1);
        when(train.hasInstalledTape()).thenReturn(true); when(train.getInstalledTape()).thenReturn(new ThrottleTape(spline.toString(), List.of(new ThrottleTape.Sample(1, 1, 40)))); templates.put("recorder", stack(Material.AIR, 1));
        try (var permissions = mockStatic(Permissions.class)) {
            assertTrue(TrainTapeInteract.handle(player, vehicle)); verify(train, never()).setInstalledTape(null); verify(inventory, never()).addItem(any(ItemStack.class));
        }
    }

    @Test void collisionsExplodeOverlappingDifferentConsistsOnlyOnce() {
        Cache.trainCollisionExplodeSpeed = 0; UUID spline = UUID.randomUUID(); ActiveVehicle first = train("a", spline), second = train("b", spline), third = train("c", spline);
        when(first.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true); when(second.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true); when(third.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true);
        TrainCollision.tick(null); TrainCollision.tick(List.of()); TrainCollision.tick(List.of(first, second, third)); verify(first).kill(VehicleDeath.EXPLODE); verify(second).kill(VehicleDeath.EXPLODE); verify(third, never()).kill(any());
        assertFalse(TrainCollision.sameConsist(null, first)); assertNull(TrainCollision.consistKey(null)); when(second.hasParent()).thenReturn(true); when(second.getParent()).thenReturn(first); assertTrue(TrainCollision.sameConsist(first, second));
        TrainCollision.tick(List.of(first, second)); verify(first, times(1)).kill(VehicleDeath.EXPLODE); verify(second, times(1)).kill(VehicleDeath.EXPLODE);
        when(first.getTrainHandler().getPendingParent()).thenReturn("unloaded-head"); assertEquals("unloaded-head", TrainCollision.consistKey(second));
    }

    @Test void collisionChecksIgnoreDifferentTracksWorldsUnboundAndUnavailableEntities() {
        Cache.trainCollisionExplodeSpeed = 0; UUID spline = UUID.randomUUID(); ActiveVehicle a = train("a", spline), b = train("b", UUID.randomUUID());
        TrainCollision.tick(List.of(a, b)); when(b.getTrainHandler().getSplineId()).thenReturn(spline); when(b.getEntity().getWorld()).thenReturn(mock(World.class)); TrainCollision.tick(List.of(a, b));
        when(b.getEntity().getWorld()).thenReturn(null); TrainCollision.tick(List.of(a, b)); when(b.getEntity().getWorld()).thenReturn(world); when(b.getEntity().getBoundingBox()).thenReturn(null); TrainCollision.tick(List.of(a, b));
        when(b.getEntity().getBoundingBox()).thenReturn(new BoundingBox(10, 64, 10, 11, 67, 11)); TrainCollision.tick(List.of(a, b));
        BoundingBox bounds = a.getEntity().getBoundingBox(); when(b.getEntity().getBoundingBox()).thenReturn(bounds); when(b.isDestroyed()).thenReturn(true); TrainCollision.tick(List.of(a, b));
        when(b.isDestroyed()).thenReturn(false); when(b.isTrain()).thenReturn(false); TrainCollision.tick(List.of(a, b)); when(b.isTrain()).thenReturn(true); when(b.getEntity()).thenReturn(null); TrainCollision.tick(List.of(a, b));
        verify(a, never()).kill(any()); verify(b, never()).kill(any());
        ActiveVehicle noDeath = train("no-death", spline); TrainCollision.tick(List.of(a, noDeath)); verify(noDeath, never()).kill(any());
    }

    @Test void trackEffectsUseConfiguredLanePositionsParticleDataAndSounds() {
        List<Location> particles = new ArrayList<>(); List<Object> payloads = new ArrayList<>();
        effects.when(() -> ImpactVfx.spawn(any(), any(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenAnswer(call -> { particles.add(call.getArgument(0)); payloads.add(call.getArgument(7)); return null; });
        BlockData data = mock(BlockData.class); bukkit.when(() -> Bukkit.createBlockData(Material.STONE)).thenReturn(data);
        Cache.trackFxParticle = Particle.BLOCK; Cache.trackFxBlock = Material.STONE; Cache.trackFxCount = 2; Cache.trackFxWidth = 3; Cache.trackFxYOffset = .2;
        TrackPose pose = new TrackPose(1, 64, 3, 0, 0); TrackFx.crumbs(world, pose); assertEquals(3, particles.size()); assertEquals(List.of(0., 1., 2.), particles.stream().map(Location::getX).toList()); assertTrue(particles.stream().allMatch(at -> at.getY() == 64.2)); assertEquals(List.of(data, data, data), payloads);
        TrackFx.crumbs(null, pose); TrackFx.crumbs(world, null); Cache.trackFxCount = 0; TrackFx.crumbs(world, pose); Cache.trackFxCount = 1; Cache.trackFxParticle = null; TrackFx.crumbs(world, pose); assertEquals(3, particles.size());
        Cache.trackFxParticle = Particle.FALLING_DUST; Cache.trackFxBlock = null; TrackFx.crumbs(world, pose); assertNull(payloads.getLast());
        Cache.trackFxBlock = Material.STONE; Cache.trackFxParticle = Particle.FLAME; TrackFx.crumbs(world, pose); assertNull(payloads.getLast());
        Cache.trackBuildParticle = Particle.FLAME; Cache.trackBuildCount = 1; Cache.trackBuildWidth = 1; Cache.trackBuildSound = "place-one"; Cache.trackBuildSound2 = "place-two";
        TrackFx.place(world, pose); verify(world).playSound(any(Location.class), eq("place-one"), eq(SoundCategory.BLOCKS), anyFloat(), anyFloat()); verify(world).playSound(any(Location.class), eq("place-two"), eq(SoundCategory.BLOCKS), anyFloat(), anyFloat());
        TrackFx.place(world, null); TrackFx.placeSound(null, 0, 64, 0); TrackFx.hit(null); Block orphan = mock(Block.class); TrackFx.hit(orphan); TrackFx.hitNear(null); TrackFx.hitNear(new Location(null, 0, 64, 0));
        Cache.trackBuildSound = null; Cache.trackBuildSound2 = " "; TrackFx.hit(block(Material.STONE, 1, 63, 3, false, data)); TrackFx.hitNear(new Location(world, 1, 64, 3));
        TrackFx.clack(null, pose); TrackFx.clack(world, null); Cache.trackFxSound = null; TrackFx.clack(world, pose); Cache.trackFxSound = " "; TrackFx.clack(world, pose); Cache.trackFxSound = "clack"; TrackFx.clack(world, pose);
        verify(world).playSound(argThat((Location at) -> at.getX() == 1 && at.getY() == 64.2), eq("clack"), eq(SoundCategory.BLOCKS), anyFloat(), anyFloat());
    }

    @Test void obstructionHighlightsRefreshAndExpireWithoutClearingAReplacementSet() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        List<Runnable> refresh = new ArrayList<>(), expiry = new ArrayList<>(); List<BukkitTask> tasks = new ArrayList<>(); List<BlockDisplay> outlined = new ArrayList<>();
        when(scheduler.runTaskTimer(eq(VehicleFramework.plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> { refresh.add(call.getArgument(1)); BukkitTask task = mock(BukkitTask.class); tasks.add(task); return task; });
        when(scheduler.runTaskLater(eq(VehicleFramework.plugin), any(Runnable.class), anyLong())).thenAnswer(call -> { expiry.add(call.getArgument(1)); return mock(BukkitTask.class); });
        BlockData stone = mock(BlockData.class), marker = mock(BlockData.class), changed = mock(BlockData.class); bukkit.when(() -> Bukkit.createBlockData(Material.RED_STAINED_GLASS)).thenReturn(marker);
        Map<Integer, Block> blocks = new HashMap<>(); blocks.put(0, block(Material.STONE, 0, 64, 0, false, stone)); blocks.put(1, block(Material.AIR, 1, 64, 0, true, marker)); blocks.put(2, block(Material.STONE, 2, 64, 0, false, stone));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> blocks.get(call.getArgument(0)));
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> blocks.get(call.<Location>getArgument(0).getBlockX()));
        when(world.spawn(any(Location.class), eq(BlockDisplay.class), any(Consumer.class))).thenAnswer(call -> {
            BlockDisplay display = mock(BlockDisplay.class); when(display.isValid()).thenReturn(true); Location location = call.getArgument(0); when(display.getLocation()).thenReturn(location);
            BlockData[] shape = {null}; when(display.getBlock()).thenAnswer(invocation -> shape[0]); doAnswer(invocation -> { shape[0] = invocation.getArgument(0); return null; }).when(display).setBlock(any());
            ((Consumer<BlockDisplay>)call.getArgument(2)).accept(display); outlined.add(display); return display;
        });
        List<TrainBlockCollision.Obstruction> obstructions = List.of(new TrainBlockCollision.Obstruction(2, 64, 0, 2), new TrainBlockCollision.Obstruction(0, 64, 0, 0), new TrainBlockCollision.Obstruction(1, 64, 0, 1));
        assertEquals(3, TrainSpaceHighlight.show(player, obstructions)); assertEquals(0, outlined.getFirst().getLocation().getBlockX()); verify(player, times(3)).showEntity(eq(VehicleFramework.plugin), any());
        when(blocks.get(0).getBlockData()).thenReturn(changed); refresh.getFirst().run(); assertSame(changed, outlined.getFirst().getBlock()); assertSame(marker, outlined.get(1).getBlock());
        when(blocks.get(0).isPassable()).thenReturn(true); when(outlined.get(2).isValid()).thenReturn(false); refresh.getFirst().run(); verify(outlined.getFirst()).remove(); verify(outlined.get(2), never()).remove();
        assertEquals(1, TrainSpaceHighlight.show(player, List.of(obstructions.get(2)))); verify(tasks.getFirst()).cancel(); expiry.getFirst().run(); verify(tasks.get(1), never()).cancel(); expiry.get(1).run(); verify(tasks.get(1)).cancel();
        assertEquals(0, TrainSpaceHighlight.show(player, List.of())); TrainSpaceHighlight.clear(player); TrainSpaceHighlight.clear(player); TrainSpaceHighlight.clearAll();
    }

    @Test void prependingPaidTrackPreservesPointOrderAndCopiesStoredCoordinates() {
        List<double[]> keep = points(0, 1, 2), stroke = points(0, -1, -2, -3);
        List<double[]> paid = TrackPieces.persistPoints(keep, stroke, false, 2); assertEquals(List.of(-2., -1., 0., 1., 2.), paid.stream().map(p -> p[2]).toList());
        paid.get(0)[2] = 100; paid.get(2)[2] = 200; assertEquals(-2, stroke.get(2)[2]); assertEquals(0, keep.getFirst()[2]);
        contents[0] = stack(Material.IRON_INGOT, 2); Cache.trackItem = null; assertEquals(0, TrackPieces.count(player)); Cache.trackItem = " "; assertEquals(0, TrackPieces.count(player));
    }

    @Test void resettlingLowersOrLiftsTrackOnlyAsFarAsGradeAndGroundPermit() {
        Cache.trackMaxGradeDegrees = 10;
        List<double[]> underGround = points(0, 1); TrackResettle.smoothInward(underGround, 0, 1, 1, "first", i -> 64.1); assertEquals(64.1, underGround.getLast()[1]);
        List<double[]> belowGrade = points(0, 1); belowGrade.getLast()[1] = 60; TrackResettle.smoothInward(belowGrade, 0, 1, 1, "first", null); assertEquals(64 - Math.tan(Math.toRadians(10)), belowGrade.getLast()[1], 1e-9);
        List<double[]> belowGround = points(0, 1); belowGround.getLast()[1] = 60; TrackResettle.smoothInward(belowGround, 0, 1, 1, "first", i -> 64.1); assertEquals(64.1, belowGround.getLast()[1]);
        assertFalse(TrackPlaceKeepout.blocked(List.of(new TrackSample(0, 64, 0, 0, 0, 0)), 20, 64, 20, 1));
    }

    @Test void resettlingCannotTurnAFlatSegmentIntoAGradeAboveTheConfiguredLimit() {
        Cache.trackMaxGradeDegrees = 10; List<double[]> points = points(0, 1);
        TrackResettle.smoothInward(points, 0, 1, 1, "first", i -> 65.);
        assertEquals(64, points.getLast()[1], "A supporting surface above the allowed grade must stop smoothing before changing the segment");
    }

    @Test void nearbyTrainsOnOtherTracksAreNotExplodedAndChainDestructionIsNotDuplicated() {
        Cache.trainCollisionExplodeSpeed = 0; ActiveVehicle first = train("a", UUID.randomUUID()), otherTrack = train("b", UUID.randomUUID());
        assertTrue(first.getTrainHandler().isBound()); assertNotEquals(first.getTrainHandler().getSplineId(), otherTrack.getTrainHandler().getSplineId()); TrainCollision.tick(List.of(first, otherTrack)); verify(first, never()).kill(any());
        UUID track = UUID.randomUUID(); ActiveVehicle left = train("c", track), right = train("d", track); when(left.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true); when(right.hasDeathData(VehicleDeath.EXPLODE)).thenReturn(true);
        doAnswer(call -> { when(right.isDestroyed()).thenReturn(true); return null; }).when(left).kill(VehicleDeath.EXPLODE);
        TrainCollision.tick(List.of(left, right)); verify(left).kill(VehicleDeath.EXPLODE); verify(right, never()).kill(any());
    }
}
