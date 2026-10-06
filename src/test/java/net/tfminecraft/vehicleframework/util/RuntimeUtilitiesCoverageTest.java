package net.tfminecraft.vehicleframework.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.tfminecraft.tlibs.enums.NSEW;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.LogWriter;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.events.*;
import net.tfminecraft.vehicleframework.loaders.VehicleLoader;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockito.*;

class RuntimeUtilitiesCoverageTest {
  Rig r;
  final Map<String, Block> blocks = new HashMap<>();
  final List<FallingBlock> debris = new ArrayList<>();
  final List<Player> online = new ArrayList<>();
  final List<World> otherWorlds = new ArrayList<>();
  Consumer<Event> onEvent = e -> {};
  MockedStatic<LogWriter> logs;
  List<Material> oldExplode, oldLands, oldGround;
  Map<Material, Material> oldConvert;
  Set<Location> oldLights;
  boolean oldDamage;
  int oldDistance;

  @BeforeEach
  void setup() {
    r = new Rig();
    logs = mockStatic(LogWriter.class);
    oldExplode = new ArrayList<>(Cache.ignoreExplode);
    oldLands = new ArrayList<>(Cache.ignoreLands);
    oldGround = new ArrayList<>(Cache.ignoreGround);
    oldConvert = new HashMap<>(Cache.convertExplode);
    oldLights = new HashSet<>(Cache.lightLocations);
    oldDamage = Cache.blockDamage;
    oldDistance = Cache.despawnDistance;
    Cache.ignoreExplode.clear();
    Cache.ignoreLands.clear();
    Cache.ignoreGround.clear();
    Cache.convertExplode.clear();
    Cache.lightLocations.clear();
    Cache.blockDamage = false;
    PluginManager plugins = mock(PluginManager.class);
    r.bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
    doAnswer(
            c -> {
              onEvent.accept(c.getArgument(0));
              return null;
            })
        .when(plugins)
        .callEvent(any(Event.class));
    r.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(online);
    r.bukkit
        .when(() -> Bukkit.createBlockData(any(Material.class)))
        .thenAnswer(c -> data(c.getArgument(0)));
    doAnswer(c -> block(c.getArgument(0), c.getArgument(1), c.getArgument(2)))
        .when(r.world)
        .getBlockAt(anyInt(), anyInt(), anyInt());
    doAnswer(
            c -> {
              Location l = c.getArgument(0);
              return block(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            })
        .when(r.world)
        .getBlockAt(any(Location.class));
    when(r.world.spawnFallingBlock(any(Location.class), any(BlockData.class)))
        .thenAnswer(
            c -> {
              FallingBlock falling = r.entity(FallingBlock.class, c.getArgument(0));
              BlockData material = c.getArgument(1);
              when(falling.getBlockData()).thenReturn(material);
              debris.add(falling);
              return falling;
            });
  }

  @AfterEach
  void cleanup() {
    Cache.ignoreExplode.clear();
    Cache.ignoreExplode.addAll(oldExplode);
    Cache.ignoreLands.clear();
    Cache.ignoreLands.addAll(oldLands);
    Cache.ignoreGround.clear();
    Cache.ignoreGround.addAll(oldGround);
    Cache.convertExplode.clear();
    Cache.convertExplode.putAll(oldConvert);
    Cache.lightLocations.clear();
    Cache.lightLocations.addAll(oldLights);
    Cache.blockDamage = oldDamage;
    Cache.despawnDistance = oldDistance;
    logs.close();
    r.close();
  }

  @Test
  void lavaDoesNotProvideWaterBuoyancy() {
    Block lava = block(0, 64, 0, Material.LAVA);
    assertTrue(lava.isLiquid());
    assertFalse(
        LocationChecker.isWaterBlock(lava),
        "Lava must not satisfy water-only flotation or wading checks");
  }

  @Test
  void temporaryLightPreservesReplacementAndReleasesItsReservation() {
    Location location = new Location(r.world, 0, 64, 0);
    new LightEffect().createTemporaryLight(location, 12);
    block(0, 64, 0).setType(Material.STONE);
    r.ticks(2);
    assertEquals(Material.STONE, location.getBlock().getType());
    assertFalse(Cache.lightLocations.contains(location));
  }

  @Test
  void missingSeatCannotFulfilOccupiedSeatCondition() {
    ActiveVehicle vehicle = r.vehicle();
    when(vehicle.getAccessPanel().getSeat("missing")).thenReturn(null);
    assertFalse(ConditionChecker.checkCondition(vehicle, "seat_filled", "missing"));
  }

  @Test
  void invalidConditionsFailClosedWithoutCrashingStateUpdates() {
    ActiveVehicle vehicle = r.vehicle();
    assertFalse(ConditionChecker.checkCondition(vehicle, "unknown", "true"));
    assertFalse(ConditionChecker.checkConditions(vehicle, List.of("passengers")));
    assertFalse(ConditionChecker.checkCondition(vehicle, "OR", "has_fuel"));
    assertFalse(ConditionChecker.checkCondition(vehicle, "AND", "has_fuel"));
    assertFalse(ConditionChecker.checkConditions(vehicle, Arrays.asList((String) null)));
    for (String condition : List.of("(true)", "has_fuel(true", "passengers(maybe)"))
      assertFalse(ConditionChecker.checkConditions(vehicle, List.of(condition)));
    assertFalse(ConditionChecker.checkCondition(vehicle, null, "true"));
    assertFalse(ConditionChecker.checkCondition(vehicle, "has_fuel", null));
    assertFalse(ConditionChecker.checkCondition(vehicle, "has_fuel", "sometimes"));
    assertFalse(ConditionChecker.checkCondition(vehicle, "throttle_less_than", "many"));
    assertFalse(ConditionChecker.checkCondition(vehicle, "throttle_more_than", "many"));
    when(vehicle.getAccessPanel().getSeat("missing")).thenReturn(null);
    assertFalse(ConditionChecker.checkCondition(vehicle, "seat_empty", "missing"));
  }

  @Test
  void healthConditionIdentifiersAreIndependentOfServerLocale() {
    ActiveVehicle vehicle = r.vehicle();
    when(vehicle.hasComponent(Component.WINGS)).thenReturn(true);
    when(vehicle.getComponent(Component.WINGS).getHealthData().getHealthPercentage())
        .thenReturn(50);
    Locale saved = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertTrue(ConditionChecker.checkCondition(vehicle, "health_percent", "wings;at_least=50"));
    } finally {
      Locale.setDefault(saved);
    }
  }

  @Test
  void waterDepthWadingAirAndGroundUseActualNeighbourBlocks() {
    assertFalse(LocationChecker.isWaterBlock(null));
    assertFalse(LocationChecker.isMostlyShallowWadableWater(null, .5));
    assertFalse(LocationChecker.isMostlyShallowWadableWater(Set.of(), .5));
    Block floor = block(0, 63, 0, Material.STONE),
        water = block(0, 64, 0, Material.WATER),
        above = block(0, 65, 0, Material.WATER);
    assertTrue(LocationChecker.isInWater(water.getLocation()));
    assertTrue(LocationChecker.hasDeepWaterAtCentre(floor));
    assertTrue(LocationChecker.hasDeepWaterAtCentre(water));
    assertTrue(LocationChecker.isShallowWadableBlock(water));
    assertFalse(LocationChecker.isShallowWadableBlock(above));
    assertFalse(LocationChecker.isShallowWadableBlock(floor));
    assertTrue(LocationChecker.isMostlyShallowWadableWater(Set.of(floor, water, above), .5));
    assertFalse(LocationChecker.isMostlyShallowWadableWater(Set.of(water, above), .75));
    assertFalse(LocationChecker.isMostlyShallowWadableWater(Set.of(floor), .5));
    above.setType(Material.AIR);
    assertFalse(LocationChecker.hasDeepWaterAtCentre(floor));
    assertFalse(LocationChecker.hasDeepWaterAtCentre(water));
    assertFalse(LocationChecker.hasDeepWaterAtCentre(above));
    assertTrue(LocationChecker.isOnGround(floor.getLocation()));
    assertFalse(LocationChecker.isOnGround(water.getLocation()));
    assertTrue(LocationChecker.isInAir(above.getLocation()));
    assertFalse(LocationChecker.isOnGround(above.getLocation()));
    above.setType(Material.LIGHT);
    assertTrue(LocationChecker.isInAir(above.getLocation()));
    assertFalse(LocationChecker.isInAir(water.getLocation()));
    Waterlogged logged = mock(Waterlogged.class);
    Block slab = block(1, 64, 0, Material.OAK_SLAB);
    slab.setBlockData(logged, false);
    when(logged.isWaterlogged()).thenReturn(true);
    assertTrue(LocationChecker.isWaterBlock(slab));
    when(logged.isWaterlogged()).thenReturn(false);
    assertFalse(LocationChecker.isWaterBlock(slab));
    for (Material material :
        List.of(Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS)) {
      water.setType(material);
      assertTrue(LocationChecker.isWaterBlock(water));
    }
  }

  @Test
  void railStepsFollowStraightSlopesAndEachCurveExit() {
    record Route(Rail.Shape shape, NSEW direction, float yaw, int dx, int dy, int dz) {}
    List<Route> routes = new ArrayList<>();
    routes.addAll(
        List.of(
            new Route(Rail.Shape.NORTH_SOUTH, NSEW.NORTH, 0, 0, 0, -1),
            new Route(Rail.Shape.NORTH_SOUTH, NSEW.SOUTH, 0, 0, 0, 1),
            new Route(Rail.Shape.NORTH_SOUTH, NSEW.EAST, 0, 0, 0, 1),
            new Route(Rail.Shape.NORTH_SOUTH, NSEW.EAST, -90, 0, 0, -1),
            new Route(Rail.Shape.NORTH_SOUTH, NSEW.WEST, 0, 0, 0, 1),
            new Route(Rail.Shape.NORTH_SOUTH, NSEW.WEST, 90, 0, 0, -1),
            new Route(Rail.Shape.EAST_WEST, NSEW.EAST, 0, 1, 0, 0),
            new Route(Rail.Shape.EAST_WEST, NSEW.WEST, 0, -1, 0, 0),
            new Route(Rail.Shape.EAST_WEST, NSEW.NORTH, 90, -1, 0, 0),
            new Route(Rail.Shape.EAST_WEST, NSEW.NORTH, 0, 1, 0, 0),
            new Route(Rail.Shape.EAST_WEST, NSEW.SOUTH, 90, -1, 0, 0),
            new Route(Rail.Shape.EAST_WEST, NSEW.SOUTH, 0, 1, 0, 0)));
    routes.addAll(
        List.of(
            new Route(Rail.Shape.ASCENDING_NORTH, NSEW.NORTH, 0, 0, 1, -1),
            new Route(Rail.Shape.ASCENDING_NORTH, NSEW.SOUTH, 0, 0, 0, 1),
            new Route(Rail.Shape.ASCENDING_SOUTH, NSEW.NORTH, 0, 0, 0, -1),
            new Route(Rail.Shape.ASCENDING_SOUTH, NSEW.SOUTH, 0, 0, 1, 1),
            new Route(Rail.Shape.ASCENDING_EAST, NSEW.EAST, 0, 1, 1, 0),
            new Route(Rail.Shape.ASCENDING_EAST, NSEW.WEST, 0, -1, 0, 0),
            new Route(Rail.Shape.ASCENDING_WEST, NSEW.EAST, 0, 1, 0, 0),
            new Route(Rail.Shape.ASCENDING_WEST, NSEW.WEST, 0, -1, 1, 0)));
    for (Rail.Shape shape :
        List.of(
            Rail.Shape.NORTH_EAST,
            Rail.Shape.NORTH_WEST,
            Rail.Shape.SOUTH_EAST,
            Rail.Shape.SOUTH_WEST)) {
      int x = shape == Rail.Shape.NORTH_EAST || shape == Rail.Shape.SOUTH_EAST ? 1 : -1,
          z = shape == Rail.Shape.NORTH_EAST || shape == Rail.Shape.NORTH_WEST ? -1 : 1;
      for (NSEW dir : List.of(NSEW.NORTH, NSEW.SOUTH, NSEW.EAST, NSEW.WEST)) {
        boolean alongZ =
            (z < 0 && dir == NSEW.NORTH)
                || (z > 0 && dir == NSEW.SOUTH)
                || (x > 0 && dir == NSEW.WEST)
                || (x < 0 && dir == NSEW.EAST);
        routes.add(new Route(shape, dir, 0, alongZ ? 0 : x, 0, alongZ ? z : 0));
      }
    }
    for (Route route : routes) {
      blocks.clear();
      rail(block(0, 64, 0, Material.RAIL), route.shape());
      Rail.Shape next = route.dx() != 0 ? Rail.Shape.EAST_WEST : Rail.Shape.NORTH_SOUTH;
      // The first step selects the configured exit, then three aligned straight rails continue it.
      for (int i = 1; i < 4; i++)
        rail(block(route.dx() * i, 64 + route.dy(), route.dz() * i, Material.RAIL), next);
      float yaw = route.yaw();
      // For a turn the heading preference is chosen from the actual exit; test all curve arms
      // separately below.
      if (route.shape().name().contains("ASCENDING")
          || route.shape() == Rail.Shape.NORTH_SOUTH
          || route.shape() == Rail.Shape.EAST_WEST) {
        Location result =
            LocationChecker.getNextTrackedLocation(
                new Location(r.world, 0.2, 64, 0.2), List.of(route.direction()), yaw);
        assertNotNull(result, route.toString());
        assertEquals(64 + route.dy(), result.getY(), route.toString());
        assertEquals(route.dx() * 4 + .5, result.getX(), route.toString());
        assertEquals(route.dz() * 4 + .5, result.getZ(), route.toString());
      } else {
        // A single curve is followed by no rail: the observable result is a stopped route, with the
        // exit sampled.
        blocks.clear();
        rail(block(0, 64, 0, Material.RAIL), route.shape());
        assertNull(
            LocationChecker.getNextTrackedLocation(
                new Location(r.world, 0, 64, 0), List.of(route.direction()), 0));
        verify(block(route.dx(), 64, route.dz()), atLeastOnce()).getBlockData();
      }
    }
    blocks.clear();
    assertNull(
        LocationChecker.getNextTrackedLocation(
            new Location(r.world, 0, 64, 0), List.of(NSEW.NORTH), 0));
    for (int z = 0; z > -4; z--) rail(block(0, 63, z, Material.RAIL), Rail.Shape.NORTH_SOUTH);
    assertEquals(
        63,
        LocationChecker.getNextTrackedLocation(
                new Location(r.world, 0, 64, 0), List.of(NSEW.NORTH), 0)
            .getY());
    for (Rail.Shape shape : Rail.Shape.values()) {
      blocks.clear();
      rail(block(0, 64, 0, Material.RAIL), shape);
      assertEquals(
          new Location(r.world, .5, 64, .5),
          LocationChecker.getNextTrackedLocation(
              new Location(r.world, 0, 64, 0), List.of(NSEW.NORTH_EAST), 0),
          "Unsupported diagonal preference must not invent rail movement");
    }
  }

  @Test
  void conditionsGateStateLiftPassengersFuelSeatsAndThrottle() {
    ActiveVehicle v = r.vehicle();
    when(v.getStateHandler().getCurrentState().getType()).thenReturn(State.GROUND);
    assertTrue(ConditionChecker.checkConditions(v, List.of()));
    assertTrue(ConditionChecker.checkConditions(v, List.of("state(ground)", "passengers(false)")));
    assertFalse(ConditionChecker.checkConditions(v, List.of("state(flying)", "unknown()")));
    assertFalse(ConditionChecker.checkCondition(v, "lift", "true"));
    Wings wings = mock(Wings.class);
    Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
    when(v.hasComponent(Component.WINGS)).thenReturn(true);
    when(v.getComponent(Component.WINGS)).thenReturn(wings);
    assertFalse(ConditionChecker.checkCondition(v, "lift", "true"));
    when(v.hasComponent(Component.ENGINE)).thenReturn(true);
    when(v.getComponent(Component.ENGINE)).thenReturn(engine);
    when(wings.getLift()).thenReturn(1d);
    when(engine.getSpeed()).thenReturn(1d);
    when(engine.getThrottle().getCurrent()).thenReturn(100);
    assertTrue(ConditionChecker.checkCondition(v, "lift", "true"));
    assertFalse(ConditionChecker.checkCondition(v, "lift", "false"));
    when(engine.getThrottle().getCurrent()).thenReturn(-10);
    assertFalse(ConditionChecker.checkCondition(v, "lift", "true"));
    assertTrue(ConditionChecker.checkCondition(v, "lift", "false"));
    when(v.getSeatHandler().hasPassengers()).thenReturn(true);
    assertTrue(ConditionChecker.checkCondition(v, "passengers", "true"));
    assertFalse(ConditionChecker.checkCondition(v, "passengers", "false"));
    when(v.getAccessPanel().getSeat("pilot").isOccupied()).thenReturn(true);
    assertTrue(ConditionChecker.checkCondition(v, "seat_filled", "pilot"));
    assertFalse(ConditionChecker.checkCondition(v, "seat_empty", "pilot"));
    when(v.getAccessPanel().getSeat("pilot").isOccupied()).thenReturn(false);
    assertFalse(ConditionChecker.checkCondition(v, "seat_filled", "pilot"));
    assertTrue(ConditionChecker.checkCondition(v, "seat_empty", "pilot"));
    for (boolean flag : List.of(false, true)) {
      when(v.hasParent()).thenReturn(flag);
      when(v.hasFuel()).thenReturn(flag);
      for (String type : List.of("is_passenger", "has_fuel")) {
        assertTrue(ConditionChecker.checkCondition(v, type, Boolean.toString(flag)));
        assertFalse(ConditionChecker.checkCondition(v, type, Boolean.toString(!flag)));
      }
    }
    when(v.getThrottle()).thenReturn(null);
    assertFalse(ConditionChecker.checkCondition(v, "throttle_less_than", "50"));
    assertFalse(ConditionChecker.checkCondition(v, "throttle_more_than", "50"));
    Throttle throttle = mock(Throttle.class);
    when(v.getThrottle()).thenReturn(throttle);
    when(throttle.getCurrent()).thenReturn(50);
    assertTrue(ConditionChecker.checkCondition(v, "throttle_less_than", "50"));
    assertFalse(ConditionChecker.checkCondition(v, "throttle_less_than", "49"));
    assertTrue(ConditionChecker.checkCondition(v, "throttle_more_than", "50"));
    assertFalse(ConditionChecker.checkCondition(v, "throttle_more_than", "51"));
    assertTrue(
        ConditionChecker.checkCondition(v, "OR", "has_fuel=false;passengers=true;state=flying"));
    assertFalse(ConditionChecker.checkCondition(v, "OR", "has_fuel=false;passengers=false"));
    assertTrue(ConditionChecker.checkCondition(v, "AND", "has_fuel=true;passengers=true"));
    assertFalse(ConditionChecker.checkCondition(v, "AND", "has_fuel=false;passengers=true"));
  }

  @Test
  void healthComparisonsValidateConfigurationAndTheirBoundaries() {
    ActiveVehicle v = r.vehicle();
    when(v.hasComponent(Component.HULL)).thenReturn(true);
    when(v.getComponent(Component.HULL).getHealthData().getHealthPercentage()).thenReturn(50);
    for (String value :
        Arrays.asList(
            null,
            "",
            " ",
            "hull;wings;at_most=50",
            "hull;at_most=50;more_than=20",
            "hull;at_most=abc",
            "hull;bogus=20",
            "hull",
            "at_least=50",
            "absent;at_least=50",
            "engine;at_least=50"))
      assertFalse(
          ConditionChecker.checkCondition(v, "health_percent", value), String.valueOf(value));
    assertTrue(
        ConditionChecker.checkCondition(v, "health_percent", " ;component=hull;;less_than=51"));
    assertFalse(ConditionChecker.checkCondition(v, "health_percent", "hull;less_than=50"));
    assertTrue(ConditionChecker.checkCondition(v, "health_percent", "hull;more_than=49"));
    assertFalse(ConditionChecker.checkCondition(v, "health_percent", "hull;more_than=50"));
    assertTrue(ConditionChecker.checkCondition(v, "health_percent", "hull;at_most=50"));
    assertFalse(ConditionChecker.checkCondition(v, "health_percent", "hull;at_most=49"));
    assertTrue(ConditionChecker.checkCondition(v, "health_percent", "hull;at_least=50"));
    assertFalse(ConditionChecker.checkCondition(v, "health_percent", "hull;at_least=51"));
  }

  @Test
  void textAndControlLabelsStayReadable() {
    assertNull(Text.capitalize(null));
    assertEquals("", Text.capitalize(""));
    assertEquals("  Hello\tWorld\nAgain", Text.capitalize("  hello\tworld\nagain"));
    assertEquals("AlreadyUPPER", Text.capitalize("AlreadyUPPER"));
    String[] inputLabels = {
      "Speed Up",
      "Speed Down",
      "Turn Left",
      "Turn Right",
      "Turn Left",
      "Turn Right",
      "Junction Left",
      "Junction Right",
      "Seat Selection",
      "Move",
      "Forward",
      "Backward",
      "UP",
      "DOWN",
      "Pitch Down",
      "Pitch Up",
      "Roll Left",
      "Roll Right",
      "Weapon Aim Up",
      "Weapon Aim Down",
      "Weapon Aim Left",
      "Weapon Aim Right",
      "Weapon Reload",
      "Weapon Shoot",
      "Reload & Shoot",
      "Switch Weapon",
      "Toggle Lights",
      "Horn",
      "None"
    };
    for (int i = 0; i < Input.values().length; i++)
      assertEquals(inputLabels[i], EnumDisplayConverter.getInputDisplayName(Input.values()[i]));
    String[] keyLabels = {
      "W",
      "A",
      "S",
      "D",
      "SWAP",
      "Right Click",
      "Left Click",
      "Shift + Right Click",
      "Shift + Left Click",
      "Space",
      "Space + W",
      "Space + A",
      "Space + S",
      "Space + D",
      "Shift",
      "Shift + W",
      "Shift + A",
      "Shift + S",
      "Shift + D"
    };
    for (int i = 0; i < Keybind.values().length; i++)
      assertEquals(keyLabels[i], EnumDisplayConverter.getKeybindDisplayName(Keybind.values()[i]));
    new EnumDisplayConverter();
    new ConditionChecker();
    new LocationChecker();
    new SoundLoader();
    new ParticleLoader();
    new ExplosionCreator();
    new Damager();
  }

  @Test
  void legacyModelIdReplacesAllOldComponentChannels() {
    ItemMeta meta = mock(ItemMeta.class);
    CustomModelDataComponent component = mock(CustomModelDataComponent.class);
    when(meta.getCustomModelDataComponent()).thenReturn(component);
    when(component.getFloats()).thenReturn(List.of());
    assertFalse(LegacyModelData.has(meta));
    assertThrows(IllegalStateException.class, () -> LegacyModelData.get(meta));
    when(component.getFloats()).thenReturn(List.of(123f, 99f));
    assertTrue(LegacyModelData.has(meta));
    assertEquals(123, LegacyModelData.get(meta));
    LegacyModelData.set(meta, 42);
    verify(component).setFloats(List.of(42f));
    verify(component).setFlags(List.of());
    verify(component).setStrings(List.of());
    verify(component).setColors(List.of());
    verify(meta).setCustomModelDataComponent(component);
    LegacyModelData.set(meta, null);
    verify(meta).setCustomModelDataComponent(null);
  }

  @Test
  void mergingConfigMakesIndependentNestedCopiesAndWritableYaml() throws Exception {
    var source = Rig.yaml("engine:\n  speed: 2\n  sounds: [a,b]\nitems: [{id: stone}]\n");
    Map<String, Object> base = ConfigMerger.fromSection(source);
    Map<String, Object> overlay =
        Map.of("template", "base", "engine", Map.of("speed", 4), "new", true);
    Map<String, Object> merged = ConfigMerger.overlay(base, overlay);
    var yaml = ConfigMerger.toConfiguration(merged);
    assertEquals(4, yaml.getInt("engine.speed"));
    assertEquals(List.of("a", "b"), yaml.getStringList("engine.sounds"));
    assertFalse(yaml.contains("template"));
    assertTrue(yaml.getBoolean("new"));
    ((List<?>) ((Map<?, ?>) merged.get("engine")).get("sounds")).clear();
    assertEquals(List.of("a", "b"), source.getStringList("engine.sounds"));
    assertEquals(2, ((Map<?, ?>) base.get("engine")).get("speed"));
    assertTrue(ConfigMerger.fromSection(null).isEmpty());
    assertTrue(ConfigMerger.overlay(null, null).isEmpty());
    assertTrue(ConfigMerger.toConfiguration(null).getKeys(false).isEmpty());
    assertEquals(base, ConfigMerger.overlay(base, null));
  }

  @Test
  void soundAndParticleListsReadConfigurationAndSkipMalformedParticleRows() throws Exception {
    var sounds =
        SoundLoader.getSoundsFromConfig(
            Rig.yaml("one: {sound: test.horn, volume: 2, pitch: 0.8}\n"));
    assertEquals(1, sounds.size());
    assertEquals("test.horn", sounds.getFirst().getSound());
    assertEquals(2, sounds.getFirst().getVolume());
    var particles =
        ParticleLoader.getParticlesFromConfig(
            Rig.yaml("one: {particle: FLAME, amount: 3}\nbroken: scalar\n"));
    assertEquals(1, particles.size());
    assertEquals(Particle.FLAME, particles.getFirst().getParticle());
    assertEquals(3, particles.getFirst().getAmount());
  }

  @Test
  void temporaryLightsRespectSolidBlocksReservationsAndExpire() {
    LightEffect effect = new LightEffect();
    Location location = new Location(r.world, 0, 64, 0);
    block(0, 64, 0, Material.STONE);
    effect.createTemporaryLight(location, 12);
    assertEquals(0, r.pending());
    block(0, 64, 0).setType(Material.AIR);
    Cache.lightLocations.add(location);
    effect.createTemporaryLight(location, 12);
    assertEquals(0, r.pending());
    Cache.lightLocations.clear();
    effect.createTemporaryLight(location, 12);
    assertEquals(Material.LIGHT, location.getBlock().getType());
    verify((Levelled) location.getBlock().getBlockData()).setLevel(12);
    assertTrue(Cache.lightLocations.contains(location));
    r.ticks(2);
    assertEquals(Material.AIR, location.getBlock().getType());
    assertTrue(Cache.lightLocations.isEmpty());
  }

  @Test
  void spawnPresenceUsesSameWorldAndConfiguredSquaredDistance() {
    Chunk chunk = mock(Chunk.class);
    Location location = new Location(r.world, 0, 64, 0);
    SpawnLocation spawn = new SpawnLocation(chunk, location, "wagon.yml");
    assertSame(chunk, spawn.getChunk());
    assertSame(location, spawn.getLoc());
    assertEquals("wagon.yml", spawn.getFile());
    Cache.despawnDistance = 100;
    assertFalse(spawn.hasNearby());
    online.add(r.entity(Player.class, new Location(otherWorld(), 0, 64, 0)));
    online.add(r.entity(Player.class, new Location(r.world, 20, 64, 0)));
    assertFalse(spawn.hasNearby());
    online.add(r.entity(Player.class, new Location(r.world, 8, 64, 0)));
    assertTrue(spawn.hasNearby());
  }

  @Test
  void disablingBlockDamageAlsoProtectsBlocksFromExplosionFire() {
    block(0, 63, 0, Material.STONE);
    var display = mock(net.tfminecraft.vehicleframework.tracks.TrackDisplayManager.class);
    r.framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(display);
    onEvent =
        e -> {
          if (e instanceof VFExplosionEvent explosion) explosion.setBlockDamage(false);
        };
    try (var random =
        mockConstruction(Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(0d))) {
      ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 2, 0, "test", true);
    }
    assertEquals(
        Material.AIR,
        block(0, 64, 0).getType(),
        "A protected explosion must not place damaging fire");
    verifyNoInteractions(display);
  }

  @Test
  void zeroRadiusExplosionNeverAppliesANonFiniteVelocity() {
    LivingEntity entity = r.entity(LivingEntity.class, new Location(r.world, 0, 64, 0));
    r.nearby.add(entity);
    ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 0, 4, "test");
    Vector velocity = entity.getVelocity();
    assertTrue(
        Double.isFinite(velocity.getX())
            && Double.isFinite(velocity.getY())
            && Double.isFinite(velocity.getZ()),
        "A zero-radius blast must not send NaN velocity to Bukkit");
    for (double radius : List.of(-1d, Double.NaN, Double.POSITIVE_INFINITY))
      assertDoesNotThrow(
          () ->
              ExplosionCreator.triggerExplosion(
                  new Location(r.world, 0, 64, 0), 1, radius, 4, "test"));
    verify(entity, never()).setVelocity(any());
  }

  @Test
  void entityAtBlastCentreGetsFiniteVerticalLift() {
    LivingEntity centered = r.entity(LivingEntity.class, new Location(r.world, 0, 64, 0));
    r.nearby.add(centered);
    ExplosionCreator.triggerExplosion(centered.getLocation(), 2, 4, 10, "test");
    assertEquals(new Vector(0, 1, 0), centered.getVelocity());
    verify(centered).damage(10);
  }

  @Test
  void explosionsRespectCancellationObserversDamageFalloffArmourAndVehicleKnockback() {
    Cache.blockDamage = true;
    Location center = new Location(r.world, 0, 64, 0);
    LivingEntity near = r.entity(LivingEntity.class, center.clone().add(0, 0, 1)),
        far = r.entity(LivingEntity.class, center.clone().add(0, 0, 3)),
        outside = r.entity(LivingEntity.class, center.clone().add(3.5, 0, 3.5)),
        deck = r.entity(LivingEntity.class, center.clone());
    Player creative = r.entity(Player.class, center.clone()),
        spectator = r.entity(Player.class, center.clone()),
        rider = r.entity(Player.class, center.clone()),
        survival = r.entity(Player.class, center.clone().add(0, 0, 1));
    when(creative.getGameMode()).thenReturn(GameMode.CREATIVE);
    when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
    when(rider.getGameMode()).thenReturn(GameMode.SURVIVAL);
    when(survival.getGameMode()).thenReturn(GameMode.SURVIVAL);
    when(survival.getAttribute(Attribute.ARMOR).getValue()).thenReturn(20d);
    Entity vehicleEntity = r.entity(Entity.class, center.clone());
    ActiveVehicle vehicle = r.vehicle();
    when(r.manager.get(vehicleEntity)).thenReturn(vehicle);
    when(vehicle.getSeatHandler().getPassengers()).thenReturn(List.of(rider));
    r.decks
        .when(() -> net.tfminecraft.vehicleframework.vehicles.handlers.train.DeckBody.isPart(deck))
        .thenReturn(true);
    r.nearby.addAll(
        List.of(near, far, outside, deck, creative, spectator, rider, survival, vehicleEntity));
    Player observer = r.entity(Player.class, center.clone().add(0, 0, 100)),
        distant = r.entity(Player.class, center.clone().add(0, 0, 321)),
        other = r.entity(Player.class, new Location(otherWorld(), 0, 64, 0));
    online.addAll(List.of(observer, distant, other));
    var display = mock(net.tfminecraft.vehicleframework.tracks.TrackDisplayManager.class);
    r.framework.when(VehicleFramework::getTrackDisplayManager).thenReturn(display);
    onEvent =
        e -> {
          if (e instanceof VFExplosionEvent explosion) explosion.setCancelled(true);
        };
    ExplosionCreator.triggerExplosion(center, 2, 4, 20, "test");
    verify(near, never()).damage(anyDouble());
    verifyNoInteractions(display);
    onEvent = e -> {};
    ExplosionCreator.triggerExplosion(center, 2, 4, 20, "test");
    verify(display).breakInRadius(center, 4);
    verify(near).damage(20);
    verify(far).damage(10);
    verify(survival).damage(15);
    verify(outside, never()).damage(anyDouble());
    verify(deck, never()).damage(anyDouble());
    verify(creative, never()).setVelocity(any());
    verify(spectator, never()).setVelocity(any());
    verify(rider, never()).setVelocity(any());
    assertEquals(new Vector(0, 1, 2.5), near.getVelocity());
    verify(observer).spawnParticle(Particle.EXPLOSION_EMITTER, center, 30, 0, 0, 0, 0);
    verify(distant, never())
        .spawnParticle(
            any(Particle.class),
            any(Location.class),
            anyInt(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            anyDouble());
    verify(other, never())
        .spawnParticle(
            any(Particle.class),
            any(Location.class),
            anyInt(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            anyDouble());
  }

  @Test
  void directDamageAllowsEventCancellationAndAlwaysClearsItsGuard() {
    Entity decoration = r.entity(Entity.class, new Location(r.world, 0, 64, 0));
    ExplosionCreator.applyDamage(decoration, 10, "test");
    LivingEntity target = r.entity(LivingEntity.class, new Location(r.world, 0, 64, 0));
    onEvent =
        e -> {
          if (e instanceof VFEntityDamageEvent damage) damage.setCancelled(true);
        };
    ExplosionCreator.applyDamage(target, 10, "test");
    verify(target, never()).damage(anyDouble());
    onEvent =
        e -> {
          if (e instanceof VFEntityDamageEvent damage) damage.setDamage(3);
        };
    ExplosionCreator.applyDamage(target, 10, "test");
    verify(target).damage(3);
    var order = inOrder(r.manager, target);
    order.verify(r.manager).setDamaged(target, true);
    order.verify(target).damage(3);
    order.verify(r.manager).setDamaged(target, false);
    doThrow(new IllegalStateException("external entity rejected damage")).when(target).damage(8);
    assertDoesNotThrow(() -> Damager.damage(target, 8));
    verify(r.manager, times(2)).setDamaged(target, false);
  }

  @Test
  void blastConvertsSoftGroundAndRespectfullySkipsProtectedBlocks() {
    Cache.blockDamage = true;
    Cache.ignoreExplode.add(Material.GOLD_BLOCK);
    Cache.ignoreLands.add(Material.DIRT);
    Cache.convertExplode.put(Material.DIRT, Material.COARSE_DIRT);
    List<Material> soils =
        List.of(
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.PODZOL,
            Material.COARSE_DIRT,
            Material.ROOTED_DIRT,
            Material.MYCELIUM,
            Material.FARMLAND,
            Material.SNOW,
            Material.SNOW_BLOCK);
    int i = 0;
    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) block(x, 64, z, soils.get(i++));
    Block bedrock = block(2, 64, 0, Material.BEDROCK),
        protectedBlock = block(-2, 64, 0, Material.GOLD_BLOCK),
        stone = block(0, 64, 2, Material.STONE);
    try (var random =
        mockConstruction(Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(.1d))) {
      ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 2, 4, 0, "artillery");
      assertEquals(Material.BEDROCK, bedrock.getType());
      assertEquals(Material.GOLD_BLOCK, protectedBlock.getType());
      assertEquals(Material.AIR, stone.getType());
      assertEquals(10, debris.size());
      for (FallingBlock falling : debris) {
        verify(falling).setDropItem(false);
        assertTrue(falling.getVelocity().length() > 0);
      }
      assertTrue(
          debris.stream()
              .anyMatch(falling -> falling.getBlockData().getMaterial() == Material.COARSE_DIRT));
      verify(debris.getFirst()).setCancelDrop(true);
      logs.verify(() -> LogWriter.logBreak(eq("artillery"), any(Block.class)), times(10));
      r.tick();
      verify(r.world, times(10))
          .spawnParticle(
              eq(Particle.BLOCK),
              any(Location.class),
              eq(8),
              eq(.1d),
              eq(.1d),
              eq(.1d),
              eq(0d),
              any(BlockData.class));
      for (FallingBlock falling : debris) when(falling.isDead()).thenReturn(true);
      r.ticks(2);
      assertEquals(0, r.pending());
    }
  }

  @Test
  void blastRandomnessCanLeaveHardBlocksAndSuppressDebris() {
    Cache.blockDamage = true;
    Block stone = block(0, 64, 0, Material.STONE), dirt = block(1, 64, 0, Material.DIRT);
    try (var random =
        mockConstruction(Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(.99d))) {
      ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 2, 0, "test");
      assertEquals(Material.STONE, stone.getType());
      assertEquals(Material.AIR, dirt.getType());
      assertTrue(debris.isEmpty());
    }
    dirt.setType(Material.AIR);
    try (var random =
        mockConstruction(
            Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(.1d, .9d))) {
      ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 2, 0, "test");
      assertEquals(Material.AIR, stone.getType());
      assertTrue(debris.isEmpty());
    }
  }

  @Test
  void debrisLandingRejectsInvalidGroundPillarsAndDistantUnluckyDebris() {
    Cache.blockDamage = true;
    Cache.ignoreGround.add(Material.WATER);
    for (String landing : List.of("water", "pillar", "supported", "far")) {
      blocks.clear();
      debris.clear();
      block(0, 64, 0, Material.DIRT);
      try (var random =
          mockConstruction(
              Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(.1d))) {
        ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 2, 0, "test");
        assertEquals(1, debris.size());
        FallingBlock falling = debris.getFirst();
        int x = landing.equals("far") ? 80 : 10;
        falling.teleport(new Location(r.world, x, 64, 0));
        Block floor = block(x, 63, 0, landing.equals("water") ? Material.WATER : Material.STONE);
        if (landing.equals("supported") || landing.equals("far"))
          block(x + 1, 63, 0, Material.STONE);
        when(falling.isOnGround()).thenReturn(true);
        if (landing.equals("far"))
          when(random.constructed().getFirst().nextDouble()).thenReturn(.99d);
        r.tick();
        assertEquals(0, r.pending());
        if (landing.equals("supported")) {
          verify(falling, never()).remove();
          Block landed = block(x, 64, 0);
          logs.verify(() -> LogWriter.logPlace("test", landed));
        } else verify(falling).remove();
      }
    }
  }

  @Test
  void incendiaryBlastFindsSupportedAirButHonoursRandomChanceAndRadius() {
    Cache.blockDamage = true;
    block(0, 63, 0, Material.BEDROCK);
    block(1, 63, 0, Material.BEDROCK);
    block(1, 64, 0, Material.WATER);
    Cache.ignoreExplode.add(Material.WATER);
    try (var random =
        mockConstruction(Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(0d))) {
      ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 2, 0, "test", true);
      assertEquals(Material.FIRE, block(0, 64, 0).getType());
      assertEquals(Material.WATER, block(1, 64, 0).getType());
      assertEquals(Material.AIR, block(2, 64, 0).getType());
    }
    block(0, 64, 0).setType(Material.AIR);
    try (var random =
        mockConstruction(Random.class, (rng, context) -> when(rng.nextDouble()).thenReturn(.99d))) {
      ExplosionCreator.triggerExplosion(new Location(r.world, 0, 64, 0), 1, 2, 0, "test", true);
      assertEquals(Material.AIR, block(0, 64, 0).getType());
    }
  }

  @Test
  void impactEffectsUseSurfaceNormalsAndOnlyNearbyViewers() {
    Location inside = new Location(r.world, 1, 64, 1);
    Vector direction = new Vector(0, 0, 1);
    assertNull(ImpactVfx.onBlockSurface(null, direction, null));
    Location nowhere = new Location(null, 0, 0, 0);
    assertSame(nowhere, ImpactVfx.onBlockSurface(nowhere, direction, null));
    assertSame(inside, ImpactVfx.onBlockSurface(inside, null, null));
    assertEquals(inside, ImpactVfx.onBlockSurface(inside, new Vector(), null));
    assertEquals(
        new Location(r.world, 1, 64, .94), ImpactVfx.onBlockSurface(inside, direction, null));
    assertEquals(
        new Location(r.world, 1, 64, .94),
        ImpactVfx.onBlockSurface(inside, direction, block(1, 64, 1)));
    when(r.world.rayTraceBlocks(
            any(Location.class), any(Vector.class), eq(3d), eq(FluidCollisionMode.NEVER), eq(true)))
        .thenReturn(new RayTraceResult(new Vector(1, 64, .5), BlockFace.NORTH));
    assertEquals(
        new Location(r.world, 1, 64, .44), ImpactVfx.onBlockSurface(inside, direction, null));
    when(r.world.rayTraceBlocks(
            any(Location.class), any(Vector.class), eq(3d), eq(FluidCollisionMode.NEVER), eq(true)))
        .thenReturn(new RayTraceResult(new Vector(1, 64, .5)));
    assertEquals(
        new Location(r.world, 1, 64, .44), ImpactVfx.onBlockSurface(inside, direction, null));
    assertEquals(new Vector(0, 0, -.06), ImpactVfx.surfaceOffset(direction, new Vector()));
    Player near = r.entity(Player.class, inside.clone()),
        far = r.entity(Player.class, inside.clone().add(321, 0, 0));
    when(r.world.getPlayers()).thenReturn(List.of(near, far));
    ImpactVfx.spawn(null, Particle.FLAME, 1, 0, 0, 0, 0, null);
    ImpactVfx.spawn(nowhere, Particle.FLAME, 1, 0, 0, 0, 0, null);
    ImpactVfx.spawn(inside, null, 1, 0, 0, 0, 0, null);
    ImpactVfx.spawn(inside, Particle.FLAME, 2, .1, .2, .3, .4, null);
    BlockData data = data(Material.STONE);
    ImpactVfx.spawn(inside, Particle.BLOCK, 3, .1, .2, .3, .4, data);
    verify(near).spawnParticle(Particle.FLAME, inside, 2, .1, .2, .3, .4);
    verify(near).spawnParticle(Particle.BLOCK, inside, 3, .1, .2, .3, .4, data);
    verify(far, never())
        .spawnParticle(
            any(Particle.class),
            any(Location.class),
            anyInt(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            anyDouble());
  }

  @Test
  void tabCompletionReflectsPermissionsVehiclesAndCurrentWorldTracks(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
    TabCompletion tabs = new TabCompletion();
    Command command = mock(Command.class);
    when(command.getName()).thenReturn("vf");
    Player player = r.entity(Player.class, new Location(r.world, 0, 64, 0));
    assertNull(tabs.onTabComplete(mock(CommandSender.class), command, "vf", new String[] {""}));
    assertEquals(
        List.of("findvehicles", "keybinds"),
        tabs.onTabComplete(player, command, "vf", new String[] {""}));
    assertEquals(List.of(), tabs.onTabComplete(player, command, "vf", new String[] {"spawn", ""}));
    when(player.hasPermission(Permissions.Permission_Spawn)).thenReturn(true);
    assertEquals(
        List.of("findvehicles", "keybinds", "spawn", "kill", "ammo"),
        tabs.onTabComplete(player, command, "vf", new String[] {""}));
    try (var loader = mockStatic(VehicleLoader.class)) {
      Vehicle template = mock(Vehicle.class);
      when(template.getId()).thenReturn("wagon");
      loader.when(VehicleLoader::get).thenReturn(new HashMap<>(Map.of("wagon", template)));
      assertEquals(
          List.of("wagon"), tabs.onTabComplete(player, command, "vf", new String[] {"spawn", ""}));
    }
    when(player.hasPermission(Permissions.Permission_Admin)).thenReturn(true);
    assertTrue(
        tabs.onTabComplete(player, command, "vf", new String[] {""})
            .containsAll(List.of("takeover", "reload", "track")));
    assertEquals(
        List.of(
            "start",
            "end",
            "list",
            "info",
            "particles",
            "clearance",
            "dump",
            "delete",
            "bind",
            "unbind",
            "resync"),
        tabs.onTabComplete(player, command, "vf", new String[] {"track", ""}));
    assertEquals(
        List.of(), tabs.onTabComplete(player, command, "vf", new String[] {"track", "delete", ""}));
    var registry = new net.tfminecraft.vehicleframework.tracks.TrackRegistry(folder.toFile());
    var local =
        net.tfminecraft.vehicleframework.tracks.TrackSpline.fromPoints(
            UUID.randomUUID(),
            r.world.getName(),
            false,
            List.of(new double[] {0, 64, 0}, new double[] {4, 64, 0}));
    var elsewhere =
        net.tfminecraft.vehicleframework.tracks.TrackSpline.fromPoints(
            UUID.randomUUID(),
            "elsewhere",
            false,
            List.of(new double[] {0, 64, 0}, new double[] {4, 64, 0}));
    registry.replace(local);
    registry.replace(elsewhere);
    r.framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
    try {
      for (String sub : List.of("delete", "clearance"))
        assertEquals(
            List.of(local.getId().toString()),
            tabs.onTabComplete(player, command, "vf", new String[] {"track", sub, ""}));
    } finally {
      registry.close();
    }
    assertEquals(
        List.of("10", "20", "50"),
        tabs.onTabComplete(player, command, "vf", new String[] {"kill", ""}));
    assertNull(tabs.onTabComplete(player, command, "vf", new String[] {"track", "info", ""}));
    assertNull(tabs.onTabComplete(player, command, "vf", new String[] {"unknown", ""}));
    when(command.getName()).thenReturn("other");
    assertNull(tabs.onTabComplete(player, command, "other", new String[] {""}));
  }

  @Test
  void mythicDespawnUnloadsVehiclesAndEntityCleanupAlwaysRemovesBase() {
    var event = mock(io.lumine.mythic.bukkit.events.MythicMobDespawnEvent.class);
    Entity entity = r.entity(Entity.class, new Location(r.world, 0, 64, 0));
    when(event.getEntity()).thenReturn(entity);
    MythicMobsIntegration listener = new MythicMobsIntegration();
    listener.despawnEvent(event);
    verify(r.manager, never()).unload(any());
    ActiveVehicle vehicle = r.vehicle();
    when(r.manager.get(entity)).thenReturn(vehicle);
    listener.despawnEvent(event);
    verify(r.manager).unload(vehicle);
    try (var api = mockStatic(com.ticxo.modelengine.api.ModelEngineAPI.class)) {
      VehicleEntityCleanup.remove(entity);
      verify(entity).remove();
      var modeled = mock(com.ticxo.modelengine.api.model.ModeledEntity.class);
      var engine = mock(com.ticxo.modelengine.api.ModelEngineAPI.class, RETURNS_DEEP_STUBS);
      api.when(com.ticxo.modelengine.api.ModelEngineAPI::getAPI).thenReturn(engine);
      api.when(() -> com.ticxo.modelengine.api.ModelEngineAPI.getModeledEntity(entity))
          .thenReturn(modeled);
      VehicleEntityCleanup.remove(entity);
      verify(engine.getModelUpdaters()).forceRemoveModeledEntity(modeled);
      verify(entity, times(2)).remove();
      var updaters = engine.getModelUpdaters();
      doThrow(new IllegalStateException("model callback failed"))
          .when(updaters)
          .forceRemoveModeledEntity(modeled);
      assertThrows(IllegalStateException.class, () -> VehicleEntityCleanup.remove(entity));
      verify(entity, times(3)).remove();
    }
  }

  @Test
  void relativeMoveSendsAllRelativeFieldsAndDisablesItselfAfterConnectionFailure(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
    try (var boundary = new MovementBoundary(folder)) {
      assertTrue(boundary.send(1.5, -2, 3, 45));
      Object packet = boundary.connection.getClass().getField("last").get(boundary.connection);
      assertEquals(-1, packet.getClass().getMethod("id").invoke(packet));
      Object change = packet.getClass().getMethod("change").invoke(packet),
          position = change.getClass().getMethod("position").invoke(change),
          velocity = change.getClass().getMethod("deltaMovement").invoke(change);
      assertEquals(1.5, position.getClass().getMethod("x").invoke(position));
      assertEquals(-2d, position.getClass().getMethod("y").invoke(position));
      assertEquals(3d, position.getClass().getMethod("z").invoke(position));
      assertEquals(0d, velocity.getClass().getMethod("x").invoke(velocity));
      assertEquals(45f, change.getClass().getMethod("yRot").invoke(change));
      assertEquals(0f, change.getClass().getMethod("xRot").invoke(change));
      assertEquals(9, ((Set<?>) packet.getClass().getMethod("relatives").invoke(packet)).size());
      assertTrue(boundary.send(0, 0, 0, 0));
      boundary.connection.getClass().getField("fail").setBoolean(boundary.connection, true);
      assertFalse(boundary.send(1, 1, 1, 0));
      boundary.connection.getClass().getField("fail").setBoolean(boundary.connection, false);
      assertFalse(
          boundary.send(1, 1, 1, 0),
          "Unsupported transport remains disabled instead of flooding logs every tick");
    }
  }

  @Test
  void relativeMoveUnsupportedServerFailsOnceWithoutTeleporting(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
    try (var boundary = new MovementBoundary(folder)) {
      Player unsupported = mock(Player.class);
      assertEquals(false, boundary.method.invoke(null, unsupported, 1d, 2d, 3d, 0f));
      assertFalse(boundary.send(1, 2, 3, 0));
      verifyNoInteractions(unsupported);
    }
  }

  @Test
  void simultaneousFirstMovesSafelyShareTheReflectiveTransport(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
    try (var boundary = new MovementBoundary(folder)) {
      boundary.loader.pause.set(true);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
      Runnable send =
          () -> {
            try {
              results.add(boundary.send(1, 0, 0, 0));
            } catch (Throwable e) {
              failure.compareAndSet(null, e);
            }
          };
      Thread first = new Thread(send, "relative-move-first"),
          second = new Thread(send, "relative-move-second");
      first.start();
      assertTrue(boundary.loader.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      second.start();
      try {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
          Thread.sleep(5);
        assertEquals(
            Thread.State.BLOCKED,
            second.getState(),
            "Second first-use request waits for the initializer");
      } finally {
        boundary.loader.release.countDown();
        first.join(5000);
        second.join(5000);
      }
      assertFalse(first.isAlive());
      assertFalse(second.isAlive());
      assertNull(failure.get());
      assertEquals(List.of(true, true), results);
    }
  }

  /**
   * Minimal external NMS transport with actual reflective class loading, constructors and packet
   * dispatch.
   */
  private static final class MovementBoundary implements AutoCloseable {
    final MovementLoader loader;
    final Object connection;
    final Player player;
    final java.lang.reflect.Method method;

    MovementBoundary(java.nio.file.Path directory) throws Exception {
      Map<String, String> sources =
          Map.of(
              "net.minecraft.world.phys.Vec3",
                  "package net.minecraft.world.phys; public record Vec3(double x,double y,double z)"
                      + " {}",
              "net.minecraft.world.entity.PositionMoveRotation",
                  "package net.minecraft.world.entity; public record"
                      + " PositionMoveRotation(net.minecraft.world.phys.Vec3"
                      + " position,net.minecraft.world.phys.Vec3 deltaMovement,float yRot,float"
                      + " xRot) {}",
              "net.minecraft.world.entity.Relative",
                  "package net.minecraft.world.entity; public final class Relative { public static"
                      + " final java.util.Set<String>"
                      + " ALL=java.util.Set.of(\"X\",\"Y\",\"Z\",\"Y_ROT\",\"X_ROT\",\"DELTA_X\",\"DELTA_Y\",\"DELTA_Z\",\"ROTATE_DELTA\");"
                      + " }",
              "net.minecraft.network.protocol.Packet",
                  "package net.minecraft.network.protocol; public interface Packet {}",
              "net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket",
                  "package net.minecraft.network.protocol.game; public record"
                      + " ClientboundPlayerPositionPacket(int"
                      + " id,net.minecraft.world.entity.PositionMoveRotation"
                      + " change,java.util.Set<?> relatives) implements"
                      + " net.minecraft.network.protocol.Packet {}",
              "fixture.Connection",
                  "package fixture; public class Connection { public Object last; public boolean"
                      + " fail; public void send(net.minecraft.network.protocol.Packet p){"
                      + " if(fail)throw new IllegalStateException(\"closed connection\"); last=p;}"
                      + " }",
              "fixture.ServerPlayer",
                  "package fixture; public class ServerPlayer { public final Connection"
                      + " connection=new Connection(); }",
              "fixture.CraftPlayer",
                  "package fixture; public interface CraftPlayer extends org.bukkit.entity.Player {"
                      + " ServerPlayer getHandle(); }");
      List<String> arguments =
          new ArrayList<>(
              List.of(
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  directory.toString(),
                  "-proc:none"));
      for (var entry : sources.entrySet()) {
        var path = directory.resolve(entry.getKey().replace('.', '/') + ".java");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, entry.getValue());
        arguments.add(path.toString());
      }
      assertEquals(
          0,
          javax.tools.ToolProvider.getSystemJavaCompiler()
              .run(null, null, null, arguments.toArray(String[]::new)));
      loader =
          new MovementLoader(
              new java.net.URL[] {
                directory.toUri().toURL(),
                RelativeMove.class.getProtectionDomain().getCodeSource().getLocation()
              });
      Class<?> server = loader.loadClass("fixture.ServerPlayer");
      Object handle = server.getConstructor().newInstance();
      connection = server.getField("connection").get(handle);
      Class<?> craft = loader.loadClass("fixture.CraftPlayer");
      player =
          (Player)
              mock(
                  craft,
                  invocation ->
                      invocation.getMethod().getName().equals("getHandle")
                          ? handle
                          : RETURNS_DEFAULTS.answer(invocation));
      method =
          loader
              .loadClass(RelativeMove.class.getName())
              .getMethod(
                  "send", Player.class, double.class, double.class, double.class, float.class);
    }

    boolean send(double x, double y, double z, float yaw) throws Exception {
      return (boolean) method.invoke(null, player, x, y, z, yaw);
    }

    public void close() throws Exception {
      loader.release.countDown();
      loader.close();
    }
  }

  private static final class MovementLoader extends java.net.URLClassLoader {
    final java.util.concurrent.atomic.AtomicBoolean pause =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1);

    MovementLoader(java.net.URL[] urls) {
      super(urls, RuntimeUtilitiesCoverageTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.equals("net.minecraft.world.phys.Vec3") && pause.compareAndSet(true, false)) {
        entered.countDown();
        try {
          if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw new ClassNotFoundException("Fixture initialization timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new ClassNotFoundException(name, interrupted);
        }
      }
      if (name.equals(RelativeMove.class.getName())) {
        synchronized (getClassLoadingLock(name)) {
          Class<?> type = findLoadedClass(name);
          if (type == null) type = findClass(name);
          if (resolve) resolveClass(type);
          return type;
        }
      }
      return super.loadClass(name, resolve);
    }
  }

  private World otherWorld() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    otherWorlds.add(world);
    return world;
  }

  private void rail(Block block, Rail.Shape shape) {
    Rail rail = mock(Rail.class);
    when(rail.getShape()).thenReturn(shape);
    block.setBlockData(rail, false);
  }

  private BlockData data(Material material) {
    BlockData d = material == Material.LIGHT ? mock(Levelled.class) : mock(BlockData.class);
    when(d.getMaterial()).thenReturn(material);
    return d;
  }

  private Block block(int x, int y, int z, Material type) {
    Block b = block(x, y, z);
    b.setType(type);
    return b;
  }

  private Block block(int x, int y, int z) {
    return blocks.computeIfAbsent(
        x + ":" + y + ":" + z,
        key -> {
          Block b = mock(Block.class);
          AtomicReference<Material> type = new AtomicReference<>(Material.AIR);
          AtomicReference<BlockData> state = new AtomicReference<>(data(Material.AIR));
          when(b.getType()).thenAnswer(c -> type.get());
          when(b.getBlockData()).thenAnswer(c -> state.get());
          doAnswer(
                  c -> {
                    Material m = c.getArgument(0);
                    type.set(m);
                    state.set(data(m));
                    return null;
                  })
              .when(b)
              .setType(any(Material.class));
          doAnswer(
                  c -> {
                    state.set(c.getArgument(0));
                    return null;
                  })
              .when(b)
              .setBlockData(any(), anyBoolean());
          when(b.getLocation()).thenAnswer(c -> new Location(r.world, x, y, z));
          when(b.getWorld()).thenReturn(r.world);
          when(b.getX()).thenReturn(x);
          when(b.getY()).thenReturn(y);
          when(b.getZ()).thenReturn(z);
          when(b.isLiquid())
              .thenAnswer(c -> type.get() == Material.WATER || type.get() == Material.LAVA);
          when(b.isPassable())
              .thenAnswer(
                  c ->
                      Set.of(
                              Material.AIR,
                              Material.LIGHT,
                              Material.WATER,
                              Material.LAVA,
                              Material.KELP,
                              Material.KELP_PLANT,
                              Material.SEAGRASS,
                              Material.TALL_SEAGRASS)
                          .contains(type.get()));
          when(b.getRelative(any(BlockFace.class)))
              .thenAnswer(
                  c -> {
                    BlockFace face = c.getArgument(0);
                    return block(x + face.getModX(), y + face.getModY(), z + face.getModZ());
                  });
          return b;
        });
  }
}
