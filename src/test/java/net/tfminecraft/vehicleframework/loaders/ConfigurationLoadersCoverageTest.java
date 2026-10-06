package net.tfminecraft.vehicleframework.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ticxo.modelengine.api.ModelEngineAPI;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.DamageData;
import net.tfminecraft.vehicleframework.util.ConfigMerger;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import net.tfminecraft.vehicleframework.weapons.ammunition.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class ConfigurationLoadersCoverageTest {
  @TempDir Path temp;
  private MockedStatic<VFLogger> logger;
  private MockedStatic<ModelEngineAPI> models;
  private final Map<String, Fuel> fuels = new HashMap<>(FuelLoader.get());
  private final Map<String, Ammunition> ammunition = new HashMap<>(AmmunitionLoader.get());
  private final Map<String, Vehicle> vehicles = new HashMap<>(VehicleLoader.get());
  private final List<Material> ignoredExplosions = new ArrayList<>(Cache.ignoreExplode);
  private final List<Material> ignoredLandings = new ArrayList<>(Cache.ignoreLands);
  private final List<Material> ignoredGround = new ArrayList<>(Cache.ignoreGround);
  private final Map<Material, Material> conversions = new HashMap<>(Cache.convertExplode);

  @BeforeEach
  void setup() {
    logger = mockStatic(VFLogger.class);
    models = mockStatic(ModelEngineAPI.class);
    FuelLoader.get().clear();
    AmmunitionLoader.get().clear();
    VehicleLoader.get().clear();
    new WeaponTemplateLoader().clear();
    new DeathTemplateLoader().clear();
    new ArmorTemplateLoader().clear();
    Cache.ignoreExplode.clear();
    Cache.ignoreLands.clear();
    Cache.ignoreGround.clear();
    Cache.convertExplode.clear();
  }

  @AfterEach
  void cleanup() throws Exception {
    File blank = yaml("reset.yml", "{}");
    new ConfigLoader().load(blank);
    new TrainsLoader().load(blank);
    FuelLoader.get().clear();
    FuelLoader.get().putAll(fuels);
    AmmunitionLoader.get().clear();
    AmmunitionLoader.get().putAll(ammunition);
    VehicleLoader.get().clear();
    VehicleLoader.get().putAll(vehicles);
    Cache.ignoreExplode.clear();
    Cache.ignoreExplode.addAll(ignoredExplosions);
    Cache.ignoreLands.clear();
    Cache.ignoreLands.addAll(ignoredLandings);
    Cache.ignoreGround.clear();
    Cache.ignoreGround.addAll(ignoredGround);
    Cache.convertExplode.clear();
    Cache.convertExplode.putAll(conversions);
    new WeaponTemplateLoader().clear();
    new DeathTemplateLoader().clear();
    new ArmorTemplateLoader().clear();
    models.close();
    logger.close();
  }

  @Test
  void configReloadReplacesRemovedMaterialRules() throws Exception {
    ConfigLoader loader = new ConfigLoader();
    loader.load(
        yaml(
            "first.yml",
            "ignore-explosion: [stone]\n"
                + "ignore-landing: [dirt]\n"
                + "ignore-ground: [gravel]\n"
                + "convert-explosion: [stone.dirt]\n"));
    loader.load(
        yaml(
            "second.yml",
            "ignore-explosion: [sand]\n"
                + "ignore-landing: []\n"
                + "ignore-ground: []\n"
                + "convert-explosion: []\n"));
    assertEquals(List.of(Material.SAND), Cache.ignoreExplode);
    assertTrue(Cache.ignoreLands.isEmpty());
    assertTrue(Cache.ignoreGround.isEmpty());
    assertTrue(Cache.convertExplode.isEmpty());
  }

  @Test
  void malformedExplosionConversionDoesNotPreventOtherConfigLoading() throws Exception {
    File file =
        yaml(
            "conversion.yml",
            "convert-explosion: [STONE, 'stone.', invalid.dirt, stone.dirt]\n"
                + "enable-logging: true\n");
    assertDoesNotThrow(() -> new ConfigLoader().load(file));
    assertEquals(Map.of(Material.STONE, Material.DIRT), Cache.convertExplode);
    assertTrue(Cache.enableLogging);
  }

  @Test
  void configLoadsValidMaterialsAndAllRuntimeOptions() throws Exception {
    new ConfigLoader()
        .load(
            yaml(
                "config.yml",
                """
                ignore-explosion: [stone, invalid]
                ignore-landing: [sand, invalid]
                ignore-ground: [gravel, invalid]
                convert-explosion: [stone.dirt, invalid.dirt]
                enable-logging: true
                block-damage: false
                despawn-distance: 17
                skin-item: custom.skin
                repair-item: custom.repair
                destroy-item: custom.destroy
                ticket-item: custom.ticket
                mythicmob: testmob
                allow-whitelist: true
                whitelisted-by-default: true
                weapon-degraded-reload-multiplier: 0.5
                weapon-aim-debug: true
                terrain-follow-debug: true
                ground-engine-logging: true
                persistence-logging: true
                wipe-log: true
                """));
    assertEquals(List.of(Material.STONE), Cache.ignoreExplode);
    assertEquals(List.of(Material.SAND), Cache.ignoreLands);
    assertEquals(List.of(Material.GRAVEL), Cache.ignoreGround);
    assertEquals(Material.DIRT, Cache.convertExplode.get(Material.STONE));
    assertTrue(Cache.enableLogging);
    assertFalse(Cache.blockDamage);
    assertEquals(289, Cache.despawnDistance);
    assertEquals("custom.skin", Cache.skinItem);
    assertEquals("custom.repair", Cache.repairItem);
    assertEquals("custom.destroy", Cache.destroyItem);
    assertEquals("custom.ticket", Cache.ticketItem);
    assertEquals("testmob", Cache.mythicMob);
    assertTrue(Cache.allowWhitelist);
    assertTrue(Cache.whitelistedByDefault);
    assertEquals(1, Cache.weaponDegradedReloadMultiplier);
    assertTrue(Cache.weaponAimDebug);
    assertTrue(Cache.terrainFollowDebug);
    assertTrue(Cache.groundEngineLogging);
    assertTrue(Cache.persistenceLogging);
    assertTrue(Cache.wipeLog);
    logger.verify(() -> VFLogger.log("invalid is not a material"), times(3));
  }

  @Test
  void trainsUseDefaultsWhenFxAndBuildSectionsAreAbsent() throws Exception {
    new TrainsLoader().load(yaml("trains.yml", "{}"));
    assertEquals("ia.tfmc:track_small", Cache.trackItemSmall);
    assertEquals("ia.tfmc:track_medium", Cache.trackItemMedium);
    assertEquals("ia.tfmc:track_large", Cache.trackItemLarge);
    assertEquals("v.iron_shovel", Cache.trackLayerItem);
    assertEquals(3, Cache.trackSnapDistance);
    assertEquals(2.5, Cache.trainClearanceHeight);
    assertEquals(Particle.BLOCK, Cache.trackFxParticle);
    assertEquals(Material.GRAVEL, Cache.trackFxBlock);
    assertEquals(3, Cache.trackFxCount);
    assertEquals(3, Cache.trackFxWidth);
    assertEquals(2.5, Cache.trackFxSoundInterval);
    assertEquals(4, Cache.trackBuildIntervalTicks);
    assertTrue(Cache.trackBuildSwing);
    assertEquals(6, Cache.trackBuildCount);
    assertEquals(1, Cache.trackBuildWidth);
    assertEquals(Particle.BLOCK, Cache.trackBuildParticle);
    assertEquals(Material.GRAVEL, Cache.trackBuildBlock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"BLOCK_CRACK", "BLOCK_DUST", "invalid", "none", " "})
  void trainsParseLegacyParticlesValidateBlocksAndClampUnsafeRanges(String particle)
      throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.set("fx.particle", particle);
    config.set("build.particle", particle);
    config.set("fx.particle-block", "not_a_block");
    config.set("build.particle-block", "not_a_block");
    config.set("fx.width", 0);
    config.set("fx.count", -1);
    config.set("fx.sound-interval-blocks", 0);
    config.set("build.interval-ticks", -1);
    config.set("build.count", -1);
    config.set("build.width", 0);
    config.set("clearance.width", 0);
    config.set("clearance.height", 0);
    config.set("vehicle-y-offset", 2);
    config.set("snap-distance", 0);
    config.set("max-grade-degrees", 3);
    config.set("desired-grade-degrees", 8);
    config.set("min-lay-distance", 10);
    config.set("max-junction-length", 4);
    config.set("lay-retry-seconds", -1);
    config.set("remove-cooldown-seconds", -1);
    config.set("resync-chunks-per-tick", 0);
    config.set("switch.throw-degrees-per-second", 0);
    File file = yaml("trains.yml", config.saveToString());
    new TrainsLoader().load(file);
    Particle expected = particle.isBlank() || particle.equals("none") ? null : Particle.BLOCK;
    assertEquals(expected, Cache.trackFxParticle);
    assertEquals(expected, Cache.trackBuildParticle);
    assertEquals(Material.GRAVEL, Cache.trackFxBlock);
    assertEquals(Material.GRAVEL, Cache.trackBuildBlock);
    assertEquals(1, Cache.trackFxWidth);
    assertEquals(0, Cache.trackFxCount);
    assertEquals(.25, Cache.trackFxSoundInterval);
    assertEquals(0, Cache.trackBuildIntervalTicks);
    assertEquals(0, Cache.trackBuildCount);
    assertEquals(1, Cache.trackBuildWidth);
    assertEquals(.5, Cache.trainClearanceWidth);
    assertEquals(2.5, Cache.trainClearanceHeight);
    assertEquals(.5, Cache.trackSnapDistance);
    assertEquals(3, Cache.trackDesiredGradeDegrees);
    assertEquals(10, Cache.trackMaxJunctionLength);
    assertEquals(0, Cache.trackLayRetryMs);
    assertEquals(0, Cache.trackRemoveCooldownMs);
    assertEquals(1, Cache.trackResyncChunksPerTick);
    assertEquals(1, Cache.trackSwitchThrowDegreesPerSecond);
  }

  @Test
  void unreadableOrMalformedConfigDoesNotReplaceTheLastWorkingSettings() throws Exception {
    File valid = yaml("valid.yml", "skin-item: custom.skin\nitem-small: custom.track\n");
    new ConfigLoader().load(valid);
    new TrainsLoader().load(valid);
    File missing = temp.resolve("missing.yml").toFile();
    File malformed = yaml("malformed.yml", "broken: [unterminated");
    new ConfigLoader().load(missing);
    new ConfigLoader().load(malformed);
    new TrainsLoader().load(missing);
    new TrainsLoader().load(malformed);
    assertEquals("custom.skin", Cache.skinItem);
    assertEquals("custom.track", Cache.trackItemSmall);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "malformed"})
  void firstInvalidConfigAndTrainLoadUsesTheSameDefaultsAsEmptyYaml(String kind) throws Exception {
    File empty = yaml("cold-empty.yml", "{}\n");
    File config = temp.resolve("cold-config.yml").toFile();
    File trains = temp.resolve("cold-trains.yml").toFile();
    if (kind.equals("malformed")) {
      config = yaml("cold-config.yml", "broken: [unterminated\n");
      trains = yaml("cold-trains.yml", "broken: [unterminated\n");
    }

    Map<String, Object> expected;
    try (ColdConfiguration baseline = new ColdConfiguration()) {
      baseline.load(empty, empty);
      expected = baseline.settings();
    }
    Map<String, Object> requiredDefaults =
        Map.ofEntries(
            Map.entry("despawnDistance", 4096),
            Map.entry("blockDamage", true),
            Map.entry("skinItem", "v.bucket"),
            Map.entry("repairItem", "v.iron_shovel"),
            Map.entry("destroyItem", "v.stone_axe"),
            Map.entry("ticketItem", "v.paper"),
            Map.entry("mythicMob", "none"),
            Map.entry("trackItemSmall", "ia.tfmc:track_small"),
            Map.entry("trackItemMedium", "ia.tfmc:track_medium"),
            Map.entry("trackItemLarge", "ia.tfmc:track_large"),
            Map.entry("trackLayerItem", "v.iron_shovel"),
            Map.entry("trackRemoverItem", "v.iron_pickaxe"),
            Map.entry("trackRecorderItem", "v.clock"),
            Map.entry("trackJunctionItem", "v.diamond_shovel"),
            Map.entry("trackSwitchItem", "ia.tfmc:railroad_switch"),
            Map.entry("trackItem", "ia.tfmc:train_track"),
            Map.entry("appliedTrackItemSmall", "ia.tfmc:track_small"),
            Map.entry("appliedTrackItemMedium", "ia.tfmc:track_medium"),
            Map.entry("appliedTrackItemLarge", "ia.tfmc:track_large"),
            Map.entry("trackFxParticle", Particle.BLOCK),
            Map.entry("trackBuildParticle", Particle.BLOCK),
            Map.entry("trackFxBlock", Material.GRAVEL),
            Map.entry("trackBuildBlock", Material.GRAVEL));
    assertAll(
        "Empty YAML supplies usable defaults",
        requiredDefaults.entrySet().stream()
            .map(
                entry ->
                    (org.junit.jupiter.api.function.Executable)
                        () ->
                            assertEquals(
                                entry.getValue(), expected.get(entry.getKey()), entry.getKey())));

    try (ColdConfiguration startup = new ColdConfiguration()) {
      assertNotSame(Cache.class, startup.loadClass(Cache.class.getName()));
      assertSame(VFLogger.class, startup.loadClass(VFLogger.class.getName()));
      assertSame(YamlConfiguration.class, startup.loadClass(YamlConfiguration.class.getName()));
      startup.load(config, trains);
      Map<String, Object> actual = startup.settings();
      assertEquals(expected.keySet(), actual.keySet());
      assertAll(
          "Cold startup with " + kind + " YAML",
          expected.entrySet().stream()
              .map(
                  entry ->
                      (org.junit.jupiter.api.function.Executable)
                          () ->
                              assertEquals(
                                  entry.getValue(), actual.get(entry.getKey()), entry.getKey())));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"weapon", "death", "armor", "role"})
  void templateFoldersFilterFilesPreserveFirstDefinitionAndSkipNonSections(String kind)
      throws Exception {
    java.util.function.Consumer<File> load = templateLoader(kind, false);
    java.util.function.Consumer<File> loadFolder = templateLoader(kind, true);
    Map<String, Map<String, Object>> target = templates(kind);
    File first =
        yaml(
            kind + "/first.YML",
            "base:\n"
                + "  damage: 5\n"
                + "  nested:\n"
                + "    radius: 2\n"
                + "  values: [one, two]\n"
                + "invalid: scalar\n");
    load.accept(first);
    assertEquals(5, target.get("base").get("damage"));
    assertFalse(target.containsKey("invalid"));
    load.accept(yaml(kind + "/duplicate.yaml", "base:\n  damage: 99\nsecond:\n  damage: 7\n"));
    assertEquals(5, target.get("base").get("damage"));
    assertEquals(7, target.get("second").get("damage"));
    yaml(kind + "/ignored.txt", "ignored: {damage: 8}");
    yaml(kind + "/nested/ignored.yml", "nested: {damage: 9}");
    loadFolder.accept(temp.resolve(kind).toFile());
    assertEquals(Set.of("base", "second"), target.keySet());
    loadFolder.accept(null);
    loadFolder.accept(first);
    loadFolder.accept(temp.resolve("missing").toFile());
    load.accept(temp.resolve("missing.yml").toFile());
    load.accept(yaml("bad.yml", "key: [unterminated"));
    assertEquals(Set.of("base", "second"), target.keySet());
    Path inaccessible = Files.createDirectory(temp.resolve("unreadable"));
    Assumptions.assumeTrue(
        Files.getFileStore(inaccessible).supportsFileAttributeView("posix"),
        "An unreadable-directory fixture requires POSIX file permissions");
    var permissions = Files.getPosixFilePermissions(inaccessible);
    try {
      Files.setPosixFilePermissions(inaccessible, Set.of());
      Assumptions.assumeTrue(
          inaccessible.toFile().listFiles() == null,
          "This filesystem or user cannot enforce unreadable-directory permissions");
      loadFolder.accept(inaccessible.toFile());
    } finally {
      Files.setPosixFilePermissions(inaccessible, permissions);
    }
    assertEquals(Set.of("base", "second"), target.keySet());
  }

  @ParameterizedTest
  @ValueSource(strings = {"weapon", "death"})
  void templatesResolveDeepInstanceOverridesWithoutMutatingTheTemplate(String kind)
      throws Exception {
    templateLoader(kind, false)
        .accept(
            yaml(
                "template.yml",
                "base:\n"
                    + "  damage: 5\n"
                    + "  nested:\n"
                    + "    radius: 2\n"
                    + "    count: 3\n"
                    + "  values: [one, two]\n"));
    java.util.function.BiFunction<String, ConfigurationSection, ConfigurationSection> resolve =
        kind.equals("weapon") ? WeaponTemplateLoader::resolve : DeathTemplateLoader::resolve;
    java.util.function.Function<String, Map<String, Object>> find =
        kind.equals("weapon")
            ? WeaponTemplateLoader::getByString
            : DeathTemplateLoader::getByString;
    assertNull(find.apply(null));
    assertNull(find.apply("missing"));
    assertNotNull(find.apply("base"));
    assertNull(resolve.apply("test", null));
    YamlConfiguration standalone = configuration("damage: 8\n");
    assertSame(standalone, resolve.apply("test", standalone));
    standalone.set("template", " ");
    assertSame(standalone, resolve.apply("test", standalone));
    standalone.set("template", "missing");
    assertNull(resolve.apply("test", standalone));
    ConfigurationSection merged =
        resolve.apply(
            "test", configuration("template: base\nnested:\n  radius: 9\nvalues: [three]\n"));
    assertEquals(5, merged.getInt("damage"));
    assertEquals(9, merged.getInt("nested.radius"));
    assertEquals(3, merged.getInt("nested.count"));
    assertEquals(List.of("three"), merged.getStringList("values"));
    assertFalse(merged.contains("template"));
    assertEquals(2, ((Map<?, ?>) find.apply("base").get("nested")).get("radius"));
    assertEquals(List.of("one", "two"), find.apply("base").get("values"));
  }

  @Test
  void armorRoleAndInlineDamageLayersResolveInOrderWithoutMutatingTemplates() throws Exception {
    ArmorTemplateLoader loader = new ArmorTemplateLoader();
    loader.load(
        yaml("armor.yml", "plated: {PROJECTILE: 0.5, FIRE: 0.25}\n"),
        ArmorTemplateLoader.armor,
        "armor");
    loader.load(
        yaml("roles.yml", "carrier: {PROJECTILE: 0.75, FALL: 0.5}\n"),
        ArmorTemplateLoader.roles,
        "role");
    DamageData damage =
        ArmorTemplateLoader.resolve(
            "hull", configuration("armor: plated\nrole: carrier\ndamage:\n  PROJECTILE: 0.9\n"));
    assertEquals(.9, damage.getModifier("PROJECTILE"));
    assertEquals(.25, damage.getModifier("FIRE"));
    assertEquals(.5, damage.getModifier("FALL"));
    assertEquals(.5, ArmorTemplateLoader.armor.get("plated").get("PROJECTILE"));
    damage =
        ArmorTemplateLoader.resolve(
            "hull", configuration("armor: missing\nrole: carrier\ndamage: [FALL(0.8)]\n"));
    assertEquals(.8, damage.getModifier("FALL"));
    assertEquals(.75, damage.getModifier("PROJECTILE"));
    damage = ArmorTemplateLoader.resolve("hull", configuration("role: missing\n"));
    assertTrue(damage.getModifiers().isEmpty());
    assertTrue(ArmorTemplateLoader.resolve("hull", null).getModifiers().isEmpty());
    assertEquals(
        .7,
        ArmorTemplateLoader.resolve("hull", configuration("damage: {fall: 0.7}"))
            .getModifier("FALL"));
    assertEquals(
        .4,
        ArmorTemplateLoader.resolve("hull", configuration("damage: [fall(0.4)]"))
            .getModifier("FALL"));
    assertNull(ArmorTemplateLoader.damageOverlay(null));
    assertNull(ArmorTemplateLoader.damageOverlay(new YamlConfiguration()));
    assertEquals(Map.of(), ArmorTemplateLoader.listToMap(null));
    assertEquals(Map.of(), ArmorTemplateLoader.mergeLayers(null, null, null));
    assertEquals(
        Map.of("FALL", .5), ArmorTemplateLoader.mergeLayers(null, Map.of("FALL", .5), null));
  }

  @Test
  void absentTemplateLayersAndNestedValuesAreCopiedWithoutAliasing() {
    assertEquals(Map.of(), ConfigMerger.fromSection(null));
    assertTrue(ConfigMerger.toConfiguration(null).getKeys(false).isEmpty());
    List<Object> nested = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("amount", 3))));
    Map<String, Object> base = new LinkedHashMap<>(Map.of("entries", nested));
    Map<String, Object> copy = ConfigMerger.overlay(base, null);
    assertEquals(base, copy);
    assertNotSame(base, copy);
    assertNotSame(nested, copy.get("entries"));
    nested.clear();
    assertEquals(1, ((List<?>) copy.get("entries")).size());
    assertEquals(Map.of("name", "test"), ConfigMerger.overlay(null, Map.of("name", "test")));
  }

  @Test
  void fuelEntriesExposeConfiguredNamesAmountsSoundsAndCaseInsensitiveItemLookup()
      throws Exception {
    new FuelLoader()
        .load(
            yaml(
                "fuel.yml",
                """
                coal:
                  name: Coal
                  item: v.coal
                  amount: 45
                  refuel-while-running: true
                  sound: minecraft:block.lava.extinguish
                  sound-volume: 0.4
                  sound-pitch: 1.2
                fallback: {}
                """));
    Fuel fuel = FuelLoader.getByString("coal");
    assertNotNull(fuel);
    assertSame(fuel, FuelLoader.getByInput("V.COAL"));
    assertTrue(FuelLoader.itemIsFuel("V.COAL"));
    assertFalse(FuelLoader.itemIsFuel("missing"));
    assertNull(FuelLoader.getByInput("missing"));
    assertNull(FuelLoader.getByString("missing"));
    assertEquals(2, FuelLoader.get().size());
    assertEquals("coal", fuel.getId());
    assertEquals("Coal", fuel.getName());
    assertEquals(45, fuel.getAmount());
    assertTrue(fuel.refuelWhileRunning());
    assertEquals("minecraft:block.lava.extinguish", fuel.getSound());
    assertEquals(.4f, fuel.getSoundVolume());
    assertEquals(1.2f, fuel.getSoundPitch());
    assertEquals("v.bedrock", FuelLoader.getByString("fallback").getItem());
    assertEquals(100, FuelLoader.getByString("fallback").getAmount());
  }

  @Test
  void ammunitionAndVehicleFilesRegisterRealConfiguredTemplates() throws Exception {
    new AmmunitionLoader()
        .load(
            yaml(
                "ammo.yml",
                "round: {type: BULLET, input: custom.round, damage: 12}\n"
                    + "shell: {input: custom.shell}\n"));
    assertInstanceOf(Bullet.class, AmmunitionLoader.getByString("round"));
    assertNotNull(AmmunitionLoader.getByString("shell"));
    assertSame(AmmunitionLoader.getByString("round"), AmmunitionLoader.getByInput("CUSTOM.ROUND"));
    assertNull(AmmunitionLoader.getByString("missing"));
    assertNull(AmmunitionLoader.getByInput("missing"));
    assertEquals(2, AmmunitionLoader.get().size());
    new VehicleLoader()
        .load(
            yaml(
                "vehicles.yml",
                "cart:\n"
                    + "  name: Cargo Cart\n"
                    + "  model: default\n"
                    + "  skins: {default: {model: cart_model}}\n"
                    + "  states: {}\n"
                    + "  components: {hull: {health: 100}}\n"
                    + "  seats: []\n"));
    Vehicle cart = VehicleLoader.getByString("cart");
    assertNotNull(cart);
    assertEquals("Cargo Cart", cart.getName());
    assertEquals("cart", cart.getId());
    assertEquals("default", cart.getModel());
    assertEquals("cart_model", cart.getSkinHandler().getCurrentSkin().getModel());
    assertEquals(1, cart.getComponentHandler().getComponents().size());
    assertNull(VehicleLoader.getByString("missing"));
  }

  @ParameterizedTest
  @CsvSource({
    "scalar, false",
    "empty-gears, false",
    "bad-seat, false",
    "scalar-engine, false",
    "scalar-hull, false",
    "scalar, true",
    "empty-gears, true",
    "bad-seat, true",
    "scalar-engine, true",
    "scalar-hull, true"
  })
  void invalidVehicleDefinitionsRetainExistingEntriesAndDoNotHideLaterValidVehicles(
      String kind, boolean reload) throws Exception {
    VehicleLoader loader = new VehicleLoader();
    YamlConfiguration initial = new YamlConfiguration();
    initial.set("existing", vehicleDefinition("Original vehicle"));
    loader.load(yaml("initial-vehicles.yml", initial.saveToString()));
    Vehicle previous = VehicleLoader.getByString("existing");
    assertNotNull(previous);

    Map<String, Object> invalidSection = vehicleDefinition("Invalid replacement");
    Object invalid;
    if (kind.equals("scalar")) {
      invalid = "not a vehicle section";
    } else {
      if (kind.equals("empty-gears")) {
        invalidSection.put("components", Map.of("geared_engine", Map.of("gears", Map.of())));
      } else if (kind.startsWith("scalar-")) {
        invalidSection.put("components", Map.of(kind.substring("scalar-".length()), "not a section"));
      } else {
        invalidSection.put("seats", List.of("captain_without_a_bone"));
      }
      invalid = invalidSection;
    }
    YamlConfiguration replacement = new YamlConfiguration();
    replacement.set("existing", invalid);
    replacement.set("new_invalid", invalid);
    replacement.set("later_valid", vehicleDefinition("Later valid vehicle"));
    File file = yaml("mixed-vehicles/definitions.yml", replacement.saveToString());
    logger.clearInvocations();

    assertDoesNotThrow(
        () -> {
          if (reload) loader.reload(file.getParentFile());
          else loader.load(file);
        });

    assertSame(previous, VehicleLoader.getByString("existing"));
    assertEquals("Original vehicle", previous.getName());
    assertNull(VehicleLoader.getByString("new_invalid"));
    assertNotNull(VehicleLoader.getByString("later_valid"));
    assertEquals("Later valid vehicle", VehicleLoader.getByString("later_valid").getName());
    assertEquals(Set.of("existing", "later_valid"), VehicleLoader.get().keySet());
    logger.verify(() -> VFLogger.log(contains("existing")), atLeastOnce());
    logger.verify(() -> VFLogger.log(contains("new_invalid")), atLeastOnce());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ammunition", "vehicles"})
  void folderReloadPublishesOnlyAfterAllFilesParseAndEmptySuccessRemovesOldEntries(String kind)
      throws Exception {
    registryLoader(kind).accept(registryFile(kind, "initial.yml", "old"));
    Object previous = registry(kind).get("old");
    assertNotNull(previous);
    File good = registryFile(kind, "replacement/first.yml", "new");
    File malformed = yaml("replacement/second.yml", "broken: [unterminated\n");
    File folder = spy(good.getParentFile());
    // The directory listing is the only mocked boundary; both YAML files are read normally.
    doReturn(new File[] {good, malformed}).when(folder).listFiles(any(java.io.FileFilter.class));

    registryReloader(kind).accept(folder);

    assertEquals(Set.of("old"), registry(kind).keySet());
    assertSame(previous, registry(kind).get("old"));
    Files.writeString(malformed.toPath(), "{}\n");
    registryReloader(kind).accept(folder);
    assertEquals(Set.of("new"), registry(kind).keySet());
    assertNotSame(previous, registry(kind).get("new"));
    if (kind.equals("vehicles")) {
      assertEquals("Vehicle new", VehicleLoader.getByString("new").getName());
    } else {
      assertEquals("custom.new", AmmunitionLoader.getByString("new").getData().getInput());
    }

    Path empty = Files.createDirectory(temp.resolve("empty"));
    Files.createDirectory(empty.resolve("ignored-subdirectory"));
    registryReloader(kind).accept(empty.toFile());
    assertTrue(registry(kind).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ammunition", "vehicles"})
  void missingOrUnlistableReloadFolderKeepsTheLastUsableRegistry(String kind) throws Exception {
    registryLoader(kind).accept(registryFile(kind, "initial.yml", "old"));
    Object previous = registry(kind).get("old");
    File missing = temp.resolve("missing-folder").toFile();
    File ordinaryFile = yaml("ordinary-file.yml", "{}\n");
    for (File folder : List.of(missing, ordinaryFile)) {
      assertDoesNotThrow(() -> registryReloader(kind).accept(folder));
      assertEquals(Set.of("old"), registry(kind).keySet());
      assertSame(previous, registry(kind).get("old"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ammunition", "vehicles"})
  void unreadableReloadFolderKeepsTheLastUsableRegistry(String kind) throws Exception {
    registryLoader(kind).accept(registryFile(kind, "initial.yml", "old"));
    Object previous = registry(kind).get("old");
    Path folder = Files.createDirectory(temp.resolve("unreadable-registry"));
    Assumptions.assumeTrue(
        Files.getFileStore(folder).supportsFileAttributeView("posix"),
        "An unreadable-directory fixture requires POSIX file permissions");
    var permissions = Files.getPosixFilePermissions(folder);
    try {
      Files.setPosixFilePermissions(folder, Set.of());
      Assumptions.assumeTrue(
          folder.toFile().listFiles() == null,
          "This filesystem or user cannot enforce unreadable-directory permissions");
      assertDoesNotThrow(() -> registryReloader(kind).accept(folder.toFile()));
      assertEquals(Set.of("old"), registry(kind).keySet());
      assertSame(previous, registry(kind).get("old"));
    } finally {
      Files.setPosixFilePermissions(folder, permissions);
    }
  }

  @Test
  void fuelReloadPreservesItsRegistryOnFileErrorsAndRemovesObsoleteEntriesOnSuccess()
      throws Exception {
    FuelLoader loader = new FuelLoader();
    loader.load(registryFile("fuel", "initial-fuel.yml", "old"));
    Fuel previous = FuelLoader.getByString("old");
    File malformed = yaml("malformed-fuel.yml", "broken: [unterminated\n");
    File scalar = yaml("scalar-fuel.yml", "new: {amount: 70}\nbad: not_a_section\n");
    File missing = temp.resolve("missing-fuel.yml").toFile();
    File directory = Files.createDirectory(temp.resolve("fuel-directory")).toFile();
    for (File bad : List.of(malformed, scalar, missing, directory)) {
      assertDoesNotThrow(() -> loader.reload(bad));
      assertEquals(Set.of("old"), FuelLoader.get().keySet());
      assertSame(previous, FuelLoader.getByString("old"));
    }
    loader.load(scalar);
    assertEquals(Set.of("old"), FuelLoader.get().keySet());
    assertSame(previous, FuelLoader.getByString("old"));
    loader.reload(registryFile("fuel", "replacement-fuel.yml", "new"));
    assertEquals(Set.of("new"), FuelLoader.get().keySet());
    assertEquals(42, FuelLoader.getByString("new").getAmount());
    loader.reload(yaml("empty-fuel.yml", "{}\n"));
    assertTrue(FuelLoader.get().isEmpty());
  }

  @Test
  void unreadableFuelFileKeepsItsPreviousDefinitions() throws Exception {
    FuelLoader loader = new FuelLoader();
    loader.load(registryFile("fuel", "initial-fuel.yml", "old"));
    Fuel previous = FuelLoader.getByString("old");
    Path unreadable = registryFile("fuel", "unreadable-fuel.yml", "new").toPath();
    Assumptions.assumeTrue(
        Files.getFileStore(unreadable).supportsFileAttributeView("posix"),
        "An unreadable-file fixture requires POSIX file permissions");
    var permissions = Files.getPosixFilePermissions(unreadable);
    try {
      Files.setPosixFilePermissions(unreadable, Set.of());
      Assumptions.assumeFalse(
          Files.isReadable(unreadable),
          "This filesystem or user cannot enforce unreadable-file permissions");
      assertDoesNotThrow(() -> loader.reload(unreadable.toFile()));
      assertEquals(Set.of("old"), FuelLoader.get().keySet());
      assertSame(previous, FuelLoader.getByString("old"));
    } finally {
      Files.setPosixFilePermissions(unreadable, permissions);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "scalar", "unknown-type", "missing-cluster", "item-without-material", "scalar-hit-sfx"
  })
  void invalidAmmunitionEntriesCannotPartiallyReplaceOrAddDefinitions(String kind)
      throws Exception {
    AmmunitionLoader loader = new AmmunitionLoader();
    loader.load(registryFile("ammunition", "initial-ammunition.yml", "old"));
    Ammunition previous = AmmunitionLoader.getByString("old");
    String bad = switch (kind) {
      case "scalar" -> "not_a_section";
      case "unknown-type" -> "{type: unknown_ammunition}";
      case "missing-cluster" -> "{type: cluster}";
      case "item-without-material" -> "{model: {type: item}}";
      case "scalar-hit-sfx" -> "{hit-sfx: scalar}";
      default -> throw new AssertionError("Unexpected fixture: " + kind);
    };
    File mixed =
        yaml("invalid-ammunition/definitions.yml", "new: {input: custom.new}\nbad: " + bad + "\n");
    assertAll("Failed additive loads and reloads must preserve the working ammunition registry",
        () -> assertDoesNotThrow(() -> loader.load(mixed)),
        () -> assertEquals(Set.of("old"), AmmunitionLoader.get().keySet()),
        () -> assertSame(previous, AmmunitionLoader.getByString("old")),
        () -> assertDoesNotThrow(() -> loader.reload(mixed.getParentFile())),
        () -> assertEquals(Set.of("old"), AmmunitionLoader.get().keySet()),
        () -> assertSame(previous, AmmunitionLoader.getByString("old")),
        () -> assertEquals("custom.old", previous.getData().getInput()),
        () -> logger.verify(() -> VFLogger.log(contains("definitions.yml")), times(2)));
  }

  private File registryFile(String kind, String filename, String id) throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    Object definition =
        switch (kind) {
          case "vehicles" -> vehicleDefinition("Vehicle " + id);
          case "ammunition" -> Map.of("input", "custom." + id);
          case "fuel" -> Map.of("amount", 42);
          default -> throw new IllegalArgumentException(kind);
        };
    config.set(id, definition);
    return yaml(filename, config.saveToString());
  }

  private static Map<String, ?> registry(String kind) {
    return switch (kind) {
      case "vehicles" -> VehicleLoader.get();
      case "ammunition" -> AmmunitionLoader.get();
      case "fuel" -> FuelLoader.get();
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static java.util.function.Consumer<File> registryLoader(String kind) {
    return switch (kind) {
      case "vehicles" -> new VehicleLoader()::load;
      case "ammunition" -> new AmmunitionLoader()::load;
      case "fuel" -> new FuelLoader()::load;
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static java.util.function.Consumer<File> registryReloader(String kind) {
    return switch (kind) {
      case "vehicles" -> new VehicleLoader()::reload;
      case "ammunition" -> new AmmunitionLoader()::reload;
      case "fuel" -> new FuelLoader()::reload;
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static Map<String, Object> vehicleDefinition(String name) {
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("name", name);
    definition.put("model", "default");
    definition.put("skins", Map.of("default", Map.of("model", "cart_model")));
    definition.put("states", Map.of());
    definition.put("components", Map.of("hull", Map.of("health", 100)));
    definition.put("seats", List.of());
    return definition;
  }

  @Test
  void basicLoadersIgnoreUnreadableAndMalformedFilesWithoutDiscardingExistingEntries()
      throws Exception {
    new FuelLoader().load(yaml("fuel.yml", "coal: {}"));
    new AmmunitionLoader().load(yaml("ammunition.yml", "shell: {}"));
    File absent = temp.resolve("absent.yml").toFile(),
        broken = yaml("broken.yml", "bad: [unterminated");
    for (File file : List.of(absent, broken)) {
      new FuelLoader().load(file);
      new AmmunitionLoader().load(file);
      new VehicleLoader().load(file);
    }
    assertNotNull(FuelLoader.getByString("coal"));
    assertNotNull(AmmunitionLoader.getByString("shell"));
    assertTrue(VehicleLoader.get().isEmpty());
  }

  /** Loads the original configuration classes with fresh static initialization. */
  private static final class ColdConfiguration extends java.net.URLClassLoader {
    private static final Set<String> ISOLATED_CLASSES =
        Set.of(Cache.class.getName(), ConfigLoader.class.getName(), TrainsLoader.class.getName());

    ColdConfiguration() {
      super(
          new java.net.URL[] {
            ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation()
          },
          ConfigLoader.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!ISOLATED_CLASSES.contains(name)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          loaded = findClass(name);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    void load(File config, File trains) throws ReflectiveOperationException {
      invokeLoader(ConfigLoader.class.getName(), config);
      invokeLoader(TrainsLoader.class.getName(), trains);
      // Startup applies the loaded item choices to the display style before creating tracks.
      loadClass(Cache.class.getName()).getMethod("applyTrackDisplayStyle").invoke(null);
    }

    private void invokeLoader(String className, File file) throws ReflectiveOperationException {
      Class<?> loaderClass = loadClass(className);
      Object loader = loaderClass.getConstructor().newInstance();
      loaderClass.getMethod("load", File.class).invoke(loader, file);
    }

    Map<String, Object> settings() throws ReflectiveOperationException {
      Map<String, Object> values = new TreeMap<>();
      for (java.lang.reflect.Field field : loadClass(Cache.class.getName()).getFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
          values.put(field.getName(), field.get(null));
        }
      }
      return values;
    }
  }

  private java.util.function.Consumer<File> templateLoader(String kind, boolean folder) {
    return switch (kind) {
      case "weapon" ->
          folder ? new WeaponTemplateLoader()::loadFolder : new WeaponTemplateLoader()::load;
      case "death" ->
          folder ? new DeathTemplateLoader()::loadFolder : new DeathTemplateLoader()::load;
      case "armor" ->
          folder
              ? new ArmorTemplateLoader()::loadArmorFolder
              : file -> new ArmorTemplateLoader().load(file, ArmorTemplateLoader.armor, "armor");
      case "role" ->
          folder
              ? new ArmorTemplateLoader()::loadRoleFolder
              : file -> new ArmorTemplateLoader().load(file, ArmorTemplateLoader.roles, "role");
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private Map<String, Map<String, Object>> templates(String kind) {
    return switch (kind) {
      case "weapon" -> WeaponTemplateLoader.map;
      case "death" -> DeathTemplateLoader.map;
      case "armor" -> ArmorTemplateLoader.armor;
      case "role" -> ArmorTemplateLoader.roles;
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private YamlConfiguration configuration(String content) throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(content);
    return config;
  }

  private File yaml(String name, String content) throws Exception {
    Path path = temp.resolve(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path.toFile();
  }
}
