package net.tfminecraft.vehicleframework.weapons;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.bones.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.loaders.AmmunitionLoader;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import net.tfminecraft.vehicleframework.util.ExplosionCreator;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;
import net.tfminecraft.vehicleframework.weapons.ammunition.*;
import net.tfminecraft.vehicleframework.weapons.ammunition.data.AmmunitionData;
import net.tfminecraft.vehicleframework.weapons.ammunition.data.projectile.*;
import net.tfminecraft.vehicleframework.weapons.controller.WeaponMovementController;
import net.tfminecraft.vehicleframework.weapons.data.WeaponData;
import net.tfminecraft.vehicleframework.weapons.handlers.AmmunitionHandler;
import net.tfminecraft.vehicleframework.weapons.shooter.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockito.*;

class WeaponsCoverageTest {
  Rig r;
  ActiveModel model;
  Map<String, ModelBone> bones;
  ActiveVehicle vehicle;
  Player player;
  AccessPanel panel;
  boolean oldDebug;
  double oldMultiplier;
  HashMap<String, Ammunition> oldAmmo;
  MockedConstruction<ItemStack> stacks;

  @BeforeEach
  void setup() {
    r = new Rig();
    model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
    bones = new HashMap<>();
    when(model.getBone(anyString()))
        .thenAnswer(c -> Optional.ofNullable(bones.get(c.getArgument(0))));
    when(model.getBlueprint().getAnimations()).thenReturn(new HashMap<>());
    bone("muzzle", new Location(r.world, 0, 64, 0));
    bone("aim", new Location(r.world, 0, 64, 1));
    vehicle = r.vehicle();
    panel = new AccessPanel();
    when(vehicle.getAccessPanel()).thenReturn(panel);
    when(vehicle.getCurrentState().getType()).thenReturn(State.GROUND);
    player = r.entity(Player.class, new Location(r.world, 0, 64, 0));
    ItemStack air = item(Material.AIR, 1);
    when(player.getInventory().getItemInMainHand()).thenReturn(air);
    oldDebug = Cache.weaponAimDebug;
    oldMultiplier = Cache.weaponDegradedReloadMultiplier;
    oldAmmo = AmmunitionLoader.map;
    AmmunitionLoader.map = new HashMap<>();
    Cache.weaponAimDebug = false;
    Cache.weaponDegradedReloadMultiplier = 2;
    // ItemStack construction belongs to the unavailable CraftBukkit item factory.
    stacks =
        mockConstruction(
            ItemStack.class,
            (stack, context) -> {
              ItemMeta meta = mock(ItemMeta.class, RETURNS_DEEP_STUBS);
              when(stack.getItemMeta()).thenReturn(meta);
              when(stack.setItemMeta(any())).thenReturn(true);
              if (!context.arguments().isEmpty()
                  && context.arguments().getFirst() instanceof Material material)
                when(stack.getType()).thenReturn(material);
            });
  }

  @AfterEach
  void cleanup() {
    stacks.close();
    Cache.weaponAimDebug = oldDebug;
    Cache.weaponDegradedReloadMultiplier = oldMultiplier;
    AmmunitionLoader.map = oldAmmo;
    r.close();
  }

  @Test
  void configuredDelayDefersTheLastRoundAndPreservesItsAmmunition() throws Exception {
    ActiveWeapon active = active("fixed: true\ndelay: 3\nbones: [muzzle.aim]\n");
    Bullet bullet = r.bullet(false);
    active.getAmmunitionHandler().setAmmo(bullet, 1);
    active.getAmmunitionHandler().shoot(player, List.of());
    assertEquals(0, active.getAmmunitionHandler().getCount());
    assertFalse(active.getAmmunitionHandler().hasAmmo());
    assertEquals(
        Material.AIR,
        r.block(0, 64, 0).getType(),
        "Muzzle flash and projectile must wait for the configured delay");
    r.ticks(2);
    assertEquals(Material.AIR, r.block(0, 64, 0).getType());
    assertDoesNotThrow(r::tick);
    assertEquals(Material.LIGHT, r.block(0, 64, 0).getType());
  }

  @Test
  void fixedWeaponsIgnoreManualTurnInputs() throws Exception {
    Weapon stored = new Weapon("fixed", Rig.yaml("fixed: true\n"));
    ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
    WeaponMovementController controls =
        new WeaponMovementController(vehicle, model, active, stored, stored.getLimits());
    for (Input input :
        List.of(Input.WEAPON_UP, Input.WEAPON_DOWN, Input.WEAPON_LEFT, Input.WEAPON_RIGHT))
      assertDoesNotThrow(() -> controls.input(List.of(), input, player));
  }

  @Test
  void trailingDotInExitBoneIsRejectedWithoutCrashingWeaponCreation() throws Exception {
    assertDoesNotThrow(() -> active("fixed: true\nbones: [muzzle.]\n"));
  }

  @Test
  void unconfiguredMuzzlesCannotConsumeAmmunitionOrScheduleReload() throws Exception {
    ActiveWeapon active = active("fixed: true\nbones: []\n");
    assertDoesNotThrow(() -> active.getAmmunitionHandler().reloadStart(player, r.bullet(false)));
    assertFalse(active.getAmmunitionHandler().hasAmmo());
    assertEquals(0, r.pending());
  }

  @Test
  void configurationAndActiveCopiesPreserveAllWeaponFields() throws Exception {
    Weapon stored =
        new Weapon(
            "cannon",
            Rig.yaml(
                """
                name: Deck Cannon
                health: 250
                repair-time: 2
                body-bone: body
                head-bone: head
                head-axis: z
                seat: captain
                turn-rate: 3
                aim-mode: cursor
                cursor-range: 45
                aim-vector: muzzle.aim
                aim-offset: {body-yaw: 1, head-pitch: 2, head-yaw: 3, head-roll: 4}
                rotation-limits: {min-yaw: -90, max-yaw: 90}
                projectile-damage: 20
                projectile-damage-type: FIRE
                projectile-speed: 5
                projectile-yield: 3
                projectile-radius: 8
                projectile-explosive: true
                projectile-cluster-amount: 7
                damage: ['FIRE(0.5)']
                animations: {shoot: [left, right], reload: [load]}
                keybinds: {W: WEAPON_UP}
                bones: [muzzle.aim]
                data:
                  velocity: 4
                  reload-animation: reload
                  shoot-animation: shoot
                  reload-sounds: {done: {sound: test.done}}
                  reload-start-sounds: {begin: {sound: test.begin}}
                  shoot-sounds: {bang: {sound: test.bang}}
                  particles: {smoke: {particle: FLAME, amount: 1}}
                """));
    bone("body", new Location(r.world, 0, 64, 0));
    bone("head", new Location(r.world, 0, 64, 0));
    ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
    assertEquals("cannon", stored.getId());
    assertEquals("Deck Cannon", stored.getName());
    assertEquals(250, stored.getHealthData().getHealth());
    assertFalse(stored.isFixed());
    assertEquals("body", stored.getBodyBone());
    assertEquals("head", stored.getHeadBone());
    assertEquals("z", stored.getAxis());
    assertEquals("captain", stored.getSeat());
    assertEquals(3, stored.getTurnRate());
    assertEquals(WeaponAimMode.CURSOR, stored.getAimMode());
    assertEquals(45, stored.getCursorRange());
    assertEquals("muzzle.aim", stored.getAimVector());
    assertEquals(1, stored.getAimOffset().getBodyYaw());
    assertEquals(2, stored.getAimOffset().getHeadPitch());
    assertEquals(3, stored.getAimOffset().getHeadYaw());
    assertEquals(4, stored.getAimOffset().getHeadRoll());
    assertEquals(90, stored.getLimits().getMaxYaw());
    assertEquals(20, stored.getProjectileDamage());
    assertEquals("FIRE", stored.getProjectileDamageType());
    assertEquals(5, stored.getProjectileSpeed());
    assertEquals(3, stored.getProjectileYield());
    assertEquals(8, stored.getProjectileRadius());
    assertTrue(stored.getProjectileExplosive());
    assertEquals(7, stored.getProjectileClusterAmount());
    assertEquals(List.of("muzzle.aim"), stored.getBones());
    assertEquals("cannon", active.getId());
    assertEquals("Deck Cannon", active.getName());
    assertEquals("captain", active.getSeat());
    assertSame(stored.getWeaponData(), active.getWeaponData());
    assertSame(stored.getDamageData(), active.getDamageData());
    assertNotSame(stored.getHealthData(), active.getHealthData());
    assertEquals(Input.WEAPON_UP, active.getInputHandler().getInput(Keybind.W));
    assertTrue(active.getAnimationHandler().hasModel());
    assertEquals(4, active.getWeaponData().getVelocity());
    assertEquals("reload", active.getWeaponData().getReloadAnimation());
    assertEquals("shoot", active.getWeaponData().getShootAnimation());
    assertEquals(1, active.getWeaponData().getParticles().size());
    assertEquals(20, Weapon.effectiveDamage(active, r.bullet(false).getData()));
    assertEquals("FIRE", Weapon.effectiveDamageType(active, null));
    assertEquals(5, Weapon.effectiveProjectileSpeed(active, r.bullet(false)));
    assertEquals(5, Weapon.effectiveProjectileVelocity(active));
    assertEquals(3, Weapon.effectiveYield(active, null));
    assertEquals(8, Weapon.effectiveRadius(active, null));
    assertTrue(Weapon.effectiveExplosive(active, null));
    assertEquals(7, Weapon.effectiveClusterAmount(active, null));
    active.damage("FIRE", 20);
    active.damage("OTHER", 5);
    assertEquals(15, active.getHealthData().getDamage());
    assertEquals(0, stored.getHealthData().getDamage());
    active.getHealthData().startRepair();
    active.tick();
    active.tick();
    assertFalse(active.getHealthData().isUnderRepair());
    active.input(List.of(), Keybind.W);
    active.setController(player);
    assertTrue(active.isControlled());
    assertSame(player, active.getController());
    active.input(List.of(), Keybind.W);
    active.tick();
    active.updateModel(model);
    active.disconnect();
    assertFalse(active.isControlled());
  }

  @Test
  void defaultsAndFallbacksSupportEveryProjectileType() throws Exception {
    Weapon stored = new Weapon("default", new YamlConfiguration());
    assertEquals("default", stored.getName());
    assertEquals(WeaponAimMode.MANUAL, stored.getAimMode());
    assertEquals(80, stored.getCursorRange());
    assertNotNull(stored.getLimits());
    WeaponData none = new WeaponData(null), empty = new WeaponData(new YamlConfiguration());
    for (WeaponData data : List.of(none, empty)) {
      assertEquals(3, data.getVelocity());
      assertEquals("none", data.getReloadAnimation());
      assertEquals("none", data.getShootAnimation());
      for (SoundArg sound : List.of(SoundArg.SHOOT, SoundArg.RELOAD, SoundArg.RELOAD_START))
        assertTrue(data.getSounds(sound).isEmpty());
      assertTrue(data.getParticles().isEmpty());
    }
    for (net.tfminecraft.vehicleframework.enums.Projectile type :
        net.tfminecraft.vehicleframework.enums.Projectile.values()) {
      YamlConfiguration config = Rig.yaml("type: " + type + "\ncluster: {}\n");
      Ammunition ammo = Ammunition.create(type.name().toLowerCase(Locale.ROOT), config);
      assertEquals(type, ammo.getType());
      assertEquals(type.name().toLowerCase(Locale.ROOT), ammo.getId());
      assertEquals(ammo.getId(), ammo.getName());
      if (ammo instanceof Bullet b) {
        assertEquals(80, b.getRange());
        assertEquals(7, b.getSpeed());
        assertEquals(-.05, b.getGravity());
      }
      if (ammo instanceof FusedExplosive fused) assertEquals(60, fused.getFuse());
      if (ammo instanceof ClusterBomb cluster) {
        assertEquals(40, cluster.getFuse());
        assertEquals(4, cluster.getAmount());
        assertEquals(2, cluster.getSpread());
        assertNotNull(cluster.getClusterData());
      }
    }
    var ammo = r.bullet(false).getData();
    assertEquals(8, Weapon.effectiveDamage((ActiveWeapon) null, ammo));
    assertEquals(0, Weapon.effectiveDamage((Integer) null, null));
    assertEquals("PROJECTILE", Weapon.effectiveDamageType((ActiveWeapon) null, null));
    assertEquals("PROJECTILE", Weapon.effectiveDamageType(" ", ammo));
    assertEquals(7, Weapon.effectiveProjectileSpeed((ActiveWeapon) null, null));
    assertEquals(3, Weapon.effectiveProjectileVelocity(null));
    assertEquals(0, Weapon.effectiveYield((ActiveWeapon) null, null));
    assertEquals(1, Weapon.effectiveYield((Float) null, ammo));
    assertEquals(0, Weapon.effectiveRadius((ActiveWeapon) null, null));
    assertEquals(5, Weapon.effectiveRadius((Integer) null, ammo));
    assertFalse(Weapon.effectiveExplosive((ActiveWeapon) null, null));
    assertFalse(Weapon.effectiveExplosive((Boolean) null, ammo));
    assertEquals(0, Weapon.effectiveClusterAmount((ActiveWeapon) null, null));
    assertEquals(6, Weapon.effectiveClusterAmount(6, null));
    ActiveWeapon active = active("fixed: true\nprojectile-velocity: 9\n");
    assertEquals(9, active.effectiveProjectileVelocity());
    active.setController(player);
    active.tick();
    assertEquals(WeaponAimMode.MANUAL, WeaponAimMode.fromConfig("unknown"));
  }

  @Test
  void damageIdentifiersAreLocaleIndependent() throws Exception {
    Locale old = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals(
          "FIRE",
          new Weapon("gun", Rig.yaml("projectile-damage-type: fire\n")).getProjectileDamageType());
      assertEquals("FIRE", new AmmunitionData(Rig.yaml("damage-type: fire\n")).getDamageType());
    } finally {
      Locale.setDefault(old);
    }
  }

  @Test
  void ammunitionEffectsGettersAndTrailsPreserveConfiguredTiming() throws Exception {
    AmmunitionData data =
        new AmmunitionData(
            Rig.yaml(
                """
                type: BULLET
                input: test:round
                yield: 2.5
                radius: 7
                damage: 17
                rounds: 4
                damage-type: fire
                explosive: true
                fire: true
                model: {type: item, material: STONE, model-data: 4}
                sounds: {flight: {sound: test.flight, delay: 2}}
                particles: {trail: {particle: FLAME, amount: 1, spread: 0, y: 1}}
                hit-sfx: {impact: {sound: test.impact}}
                hit-vfx: {impact: {particle: FLAME, amount: 1, spread: 0, y: 1}}
                """));
    assertEquals("test:round", data.getInput());
    assertEquals(2.5f, data.getYield());
    assertEquals(7, data.getRadius());
    assertEquals(17, data.getDamage());
    assertEquals(4, data.getRounds());
    assertEquals("FIRE", data.getDamageType());
    assertTrue(data.isExplosive());
    assertTrue(data.isFire());
    assertEquals(1.5, data.getOffset());
    assertInstanceOf(ItemModel.class, data.getModel());
    assertInstanceOf(ArmorStand.class, data.spawn(new Location(r.world, 0, 64, 0)));
    Location from = new Location(r.world, 0, 64, 0), to = new Location(r.world, 0, 64, 2);
    data.fx(List.of(), from, 1, 0);
    verify(r.world, never())
        .playSound(any(Location.class), eq("test.flight"), anyFloat(), anyFloat());
    data.fx(List.of(), from, 1, 2);
    data.trailSegment(List.of(), from, to, 1, 3);
    verify(r.world, times(2)).playSound(any(Location.class), eq("test.flight"), eq(1f), eq(1f));
    data.hitFX(List.of(), to, 1);
    verify(r.world).playSound(eq(to), eq("test.impact"), eq(1f), eq(1f));
    assertEquals(new Location(r.world, 0, 64, 0), from);
    assertEquals(new Location(r.world, 0, 64, 2), to);
    AmmunitionData empty = new AmmunitionData(new YamlConfiguration());
    empty.trailSegment(List.of(), null, to, 1, 0);
    empty.trailSegment(List.of(), from, null, 1, 0);
    empty.trailSegment(List.of(), new Location(null, 0, 0, 0), to, 1, 0);
    empty.trailSegment(List.of(), from, new Location(mock(World.class), 0, 0, 0), 1, 0);
    empty.trailSegment(List.of(), from, from.clone().add(0, 0, .1), 1, 0);
    empty.trailSegment(List.of(), from, to, 1, 0);
    assertFalse(empty.isFire());
    assertTrue(empty.isExplosive());
  }

  @Test
  void lingeringCloudConfigurationParsesEffectsColorsDurationsAndMalformedFallbacks()
      throws Exception {
    var yaml = new YamlConfiguration();
    yaml.set(
        "potion-effects",
        List.of(
            "",
            "potion(speed,2,8) color(300,10,20) 12",
            "potion(unknown,1,2)",
            "cloud",
            "potion(poison,999999999999999,5)"));
    var data = new AmmunitionData(yaml);
    var clouds = data.getLingeringClouds();
    assertEquals(4, clouds.size());
    var first = clouds.getFirst();
    assertEquals(PotionEffectType.SPEED, first.getEffect().getType());
    assertEquals(2, first.getEffect().getAmplifier());
    assertEquals(160, first.getEffect().getDuration());
    assertEquals(Color.fromRGB(255, 10, 20), first.getColor());
    assertEquals(240, first.getCloudDurationTicks());
    assertEquals(PotionEffectType.POISON, clouds.get(1).getEffect().getType());
    assertEquals(600, clouds.get(2).getCloudDurationTicks());
    assertEquals(100, clouds.get(3).getEffect().getDuration());
    assertEquals(Color.fromRGB(0, 255, 0), clouds.get(3).getColor());
    try (var explosions = mockStatic(ExplosionCreator.class)) {
      new ProjectileShooter()
          .triggerExplosion(List.of(), new Location(r.world, 0, 64, 0), data, null);
      assertEquals(4, r.spawned.stream().filter(AreaEffectCloud.class::isInstance).count());
      AreaEffectCloud cloud = (AreaEffectCloud) r.spawned.getFirst();
      verify(cloud).setDuration(240);
      verify(cloud).setRadiusPerTick(-2.5f / 240);
      verify(cloud).addCustomEffect(first.getEffect(), true);
    }
  }

  @Test
  void itemModelsSpawnAtTheirOffsetsWithConfiguredEquipment() throws Exception {
    for (boolean small : List.of(false, true)) {
      ItemModel itemModel =
          new ItemModel(Rig.yaml("material: stone\nsmall: " + small + "\nmodel-data: 123\n"));
      Location origin = new Location(r.world, 2, 70, 4);
      ArmorStand stand = (ArmorStand) itemModel.spawn(origin);
      assertEquals(Material.STONE, itemModel.getMaterial());
      assertEquals(123, itemModel.getModel());
      assertEquals(small, itemModel.isSmall());
      assertEquals(small ? 1 : 1.5, itemModel.getOffset());
      assertEquals(70 - itemModel.getOffset(), stand.getLocation().getY());
      assertEquals(70, origin.getY());
      verify(stand).setInvisible(true);
      verify(stand).setSmall(small);
      verify(stand).setMarker(false);
      verify(stand).setGravity(true);
      var stack = stacks.constructed().getLast();
      verify(stand.getEquipment()).setHelmet(stack);
      verify(stack.getItemMeta().getCustomModelDataComponent()).setFloats(List.of(123f));
    }
    var disabled = new ItemModel(Rig.yaml("material: STONE\nmodel-data: -1\n"));
    disabled.spawn(new Location(r.world, 0, 64, 0));
    verify(stacks.constructed().getLast(), never()).getItemMeta();
  }

  @Test
  void modelEngineProjectilesHandleMissingMobModelAnimationAndInvalidAngles() throws Exception {
    MythicBukkit mythic = mock(MythicBukkit.class, RETURNS_DEEP_STUBS);
    MythicMob mob = mock(MythicMob.class);
    ActiveMob spawned = mock(ActiveMob.class, RETURNS_DEEP_STUBS);
    Entity entity = r.entity(Entity.class, new Location(r.world, 0, 64, 0));
    when(spawned.getEntity().getBukkitEntity()).thenReturn(entity);
    when(mob.spawn(any(), anyDouble())).thenReturn(spawned);
    ModeledEntity modeled = mock(ModeledEntity.class);
    try (var mythicScope = mockStatic(MythicBukkit.class);
        var engine = mockStatic(ModelEngineAPI.class)) {
      mythicScope.when(MythicBukkit::inst).thenReturn(mythic);
      when(mythic.getMobManager().getMythicMob("VehicleFrameworkDummy"))
          .thenReturn(Optional.empty());
      var empty = new MEGModel(new YamlConfiguration());
      assertEquals("none", empty.getModel());
      assertEquals(0, empty.getOffset());
      assertNull(empty.spawn(new Location(r.world, 0, 64, 0)));
      when(mythic.getMobManager().getMythicMob("VehicleFrameworkDummy"))
          .thenReturn(Optional.of(mob));
      var engineConfig = mock(com.ticxo.modelengine.api.utils.config.ConfigCache.class);
      engine.when(ModelEngineAPI::getConfigCache).thenReturn(engineConfig);
      engine.when(() -> ModelEngineAPI.createModeledEntity(entity)).thenReturn(modeled);
      engine.when(() -> ModelEngineAPI.createActiveModel(anyString())).thenReturn(model);
      var simple = new MEGModel(Rig.yaml("model: shell\n"));
      Location invalid = new Location(r.world, 0, 64, 0, Float.NaN, Float.POSITIVE_INFINITY);
      assertSame(entity, simple.spawn(invalid));
      verify(entity).setRotation(0, 0);
      verify(modeled).addModel(model, true);
      var animated = new MEGModel(Rig.yaml("model: shell\nanimation: fly\n"));
      when(model.getBlueprint().getAnimations()).thenReturn(new HashMap<>());
      assertSame(entity, animated.spawn(new Location(r.world, 0, 64, 0, 30, 15)));
      var animation = new BlueprintAnimation(model.getBlueprint(), "fly");
      animation.setLength(1);
      animation.setLoopMode(BlueprintAnimation.LoopMode.LOOP);
      when(model.getBlueprint().getAnimations()).thenReturn(Map.of("fly", animation));
      assertSame(entity, animated.spawn(new Location(r.world, 0, 64, 0)));
      verify(model.getAnimationHandler()).playAnimation(any(), eq(true));
      assertInstanceOf(
          MEGModel.class,
          new AmmunitionData(Rig.yaml("model: {type: meg, model: shell}\n")).getModel());
    }
  }

  @Test
  void reloadConsumesOneRoundPerMuzzleAndCompletesAfterConfiguredTime() throws Exception {
    ActiveWeapon active =
        active(
            "fixed: true\n"
                + "bones: [muzzle.aim, muzzle.aim]\n"
                + "accepted-ammunition: [bullet]\n"
                + "reload-time: 1\n"
                + "data:\n"
                + "  reload-start-sounds: {a: {sound: test.reload.begin}}\n"
                + "  reload-sounds: {a: {sound: test.reload.done}}\n");
    Bullet ammo = new Bullet("bullet", Rig.yaml("type: BULLET\ninput: test:round\nrounds: 3\n"));
    AmmunitionLoader.map.put(ammo.getId(), ammo);
    ItemStack held = item(Material.TNT, 5);
    ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    when(api.getChecker().getAsStringPath(any())).thenReturn("test:round");
    try (var libs = mockStatic(TLibs.class)) {
      libs.when(TLibs::getItemAPI).thenReturn(api);
      AmmunitionHandler handler = active.getAmmunitionHandler();
      assertEquals(2, handler.getAmmoAmount());
      assertEquals(1, handler.getBaseReloadTime());
      assertEquals(-1, handler.getReloadTime());
      handler.load(player, held, true);
      assertEquals(3, held.getAmount());
      assertEquals(1, handler.getReloadTime());
      handler.load(player, held, true);
      assertEquals(3, held.getAmount());
      verify(player).sendMessage("§eAlready reloading");
      r.tick();
      assertEquals(0, handler.getReloadTime());
      r.ticks(20);
      assertEquals(6, handler.getCount());
      assertSame(ammo, handler.getAmmo());
      assertEquals(-1, handler.getReloadTime());
      handler.load(player, held, true);
      verify(player).sendMessage("§eWeapon already loaded");
      handler.updateModel(model);
    }
  }

  @Test
  void reloadRejectsAirUnknownWrongStateUnsupportedAndInsufficientRounds() throws Exception {
    ActiveWeapon active =
        active(
            "fixed: true\n"
                + "bones: [muzzle.aim, invalid, absent.aim, muzzle.absent]\n"
                + "accepted-ammunition: [bullet]\n"
                + "reload-states: [ground, invalid]\n");
    var handler = active.getAmmunitionHandler();
    assertEquals(1, handler.getAmmoAmount());
    assertEquals(List.of(State.GROUND), handler.getReloadStates());
    assertEquals(List.of("bullet"), handler.getAcceptedAmmunition());
    assertEquals(10, handler.getCooldown());
    assertEquals(0, handler.getActiveCooldown());
    ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    when(api.getChecker().getAsStringPath(any())).thenReturn("test:round");
    ItemStack held = item(Material.TNT, 1);
    try (var libs = mockStatic(TLibs.class)) {
      libs.when(TLibs::getItemAPI).thenReturn(api);
      handler.load(player, item(Material.AIR, 1), true);
      handler.load(player, held, true);
      Bullet ammo = new Bullet("other", Rig.yaml("type: BULLET\ninput: test:round\n"));
      AmmunitionLoader.map.put("other", ammo);
      when(vehicle.getCurrentState().getType()).thenReturn(State.FLYING);
      handler.load(player, held, true);
      verify(player).sendMessage("§cCannot reload in the flying state");
      handler.load(player, held, false);
      when(vehicle.getCurrentState().getType()).thenReturn(State.GROUND);
      handler.load(player, held, true);
      verify(player).sendMessage("§eCannot use other for this weapon");
      AmmunitionLoader.map.clear();
      AmmunitionLoader.map.put(
          "bullet", new Bullet("bullet", Rig.yaml("type: BULLET\ninput: test:round\n")));
      held.setAmount(0);
      handler.load(player, held, true);
      verify(player).sendMessage("§eYou need 1 of that ammunition to reload this weapon");
      assertFalse(handler.hasAmmo());
      assertEquals(0, r.pending());
    }
  }

  @Test
  void firingAlternatesMuzzlesConsumesRoundsAndRespectsCooldown() throws Exception {
    ActiveWeapon active =
        active(
            "fixed: true\n"
                + "bones: [muzzle.aim, muzzle.aim]\n"
                + "cooldown: 0\n"
                + "animations: {shoot: [left,right]}\n"
                + "data:\n"
                + "  shoot-sounds: {shot: {sound: test.shot}}\n"
                + "  particles: {smoke: {particle: FLAME, amount: 1}}\n");
    var handler = active.getAmmunitionHandler();
    handler.shoot(player, List.of());
    verify(player).sendMessage("§cNo ammo");
    handler.setAmmo(r.bullet(false), 3);
    for (int i = 0; i < 3; i++) handler.shoot(player, List.of());
    assertFalse(handler.hasAmmo());
    assertEquals(0, handler.getCount());
    verify(vehicle, times(3)).updateBoard();
    verify(r.world, times(3)).playSound(any(Location.class), eq("test.shot"), eq(1f), eq(1f));
    ActiveWeapon cooling = active("fixed: true\nbones: [muzzle.aim]\ncooldown: 12000\n");
    cooling.getAmmunitionHandler().setAmmo(r.bullet(false), 2);
    long beforeShot = System.currentTimeMillis();
    cooling.getAmmunitionHandler().shoot(player, List.of());
    long until = cooling.getAmmunitionHandler().getActiveCooldown();
    cooling.getAmmunitionHandler().shoot(player, List.of());
    assertEquals(1, cooling.getAmmunitionHandler().getCount());
    assertTrue(until >= beforeShot + 600_000L);
    assertEquals(until, cooling.getAmmunitionHandler().getActiveCooldown());
  }

  @Test
  void manualControllersRotateAllAxesAndBrokenWeaponsNotifyOnlyOnce() throws Exception {
    bone("body", new Location(r.world, 0, 64, 0));
    bone("head", new Location(r.world, 0, 64, 0));
    for (String axis : List.of("x", "y", "z")) {
      Weapon stored =
          new Weapon(
              "turret",
              Rig.yaml(
                  "body-bone: body\nhead-bone: head\nhead-axis: "
                      + axis
                      + "\nbones: [muzzle.aim]\nturn-rate: 2\n"));
      ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
      WeaponMovementController controls =
          new WeaponMovementController(vehicle, model, active, stored, stored.getLimits());
      assertSame(bones.get("body"), controls.getBodyBone());
      assertSame(bones.get("head"), controls.getHeadBone());
      for (Input input :
          List.of(
              Input.WEAPON_UP,
              Input.WEAPON_DOWN,
              Input.WEAPON_LEFT,
              Input.WEAPON_RIGHT,
              Input.NONE)) {
        var bodyBefore =
            new org.joml.Quaternionf(panel.getRotator("body").getAnimator().getRotation());
        var headBefore =
            new org.joml.Quaternionf(panel.getRotator("head").getAnimator().getRotation());
        controls.input(List.of(), input, player);
        if (input == Input.WEAPON_UP || input == Input.WEAPON_DOWN)
          assertNotEquals(headBefore, panel.getRotator("head").getAnimator().getRotation());
        else if (input == Input.WEAPON_LEFT || input == Input.WEAPON_RIGHT)
          assertNotEquals(bodyBefore, panel.getRotator("body").getAnimator().getRotation());
        else {
          assertEquals(bodyBefore, panel.getRotator("body").getAnimator().getRotation());
          assertEquals(headBefore, panel.getRotator("head").getAnimator().getRotation());
        }
      }
      controls.move();
      controls.normalize();
      controls.updateModel(model);
      controls.trackCursor(player);
      assertTrue(Float.isFinite(panel.getRotator("body").getDriveYaw()));
      active.getHealthData().setDamage(100);
      controls.input(List.of(), Input.WEAPON_UP, player);
      controls.input(List.of(), Input.WEAPON_UP, player);
      controls.input(List.of(), Input.WEAPON_UP, null);
    }
    verify(player, times(3)).sendMessage(contains("is broken"));
  }

  @Test
  void cursorControllersTrackTargetsOffsetsAndDebugWithoutManualTurning() throws Exception {
    bone("body", new Location(r.world, 0, 64, 0));
    bone("head", new Location(r.world, 0, 64, 0));
    Cache.weaponAimDebug = true;
    for (String axis : List.of("x", "z", "y")) {
      Weapon stored =
          new Weapon(
              "cursor",
              Rig.yaml(
                  "body-bone: body\nhead-bone: head\nhead-axis: "
                      + axis
                      + "\naim-mode: cursor\naim-vector: muzzle.aim\nturn-rate: 2\n"));
      ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
      WeaponMovementController controls =
          new WeaponMovementController(vehicle, model, active, stored, stored.getLimits());
      controls.trackCursor(null);
      controls.trackCursor(player);
      controls.trackCursor(player);
      player.teleport(new Location(r.world, 0, 64, 0, 90, -30));
      var beforeTarget =
          new org.joml.Quaternionf(panel.getRotator("head").getAnimator().getRotation());
      controls.trackCursor(player);
      assertNotEquals(beforeTarget, panel.getRotator("head").getAnimator().getRotation());
      controls.move();
      for (Input input :
          List.of(Input.WEAPON_UP, Input.WEAPON_DOWN, Input.WEAPON_LEFT, Input.WEAPON_RIGHT))
        controls.input(List.of(), input, player);
      active.getHealthData().setDamage(100);
      controls.trackCursor(player);
      controls.updateModel(model);
      assertNotEquals(0, panel.getRotator("body").getDriveYaw());
      player.teleport(new Location(r.world, 0, 64, 0));
    }
    verify(player, atLeastOnce())
        .spawnParticle(
            eq(Particle.END_ROD), any(Location.class), eq(1), eq(0d), eq(0d), eq(0d), eq(0d));
  }

  @Test
  void aimingIgnoresMissingModelBonesAndInvalidVectorDefinitions() throws Exception {
    for (String spec : List.of("absent", "absent.aim", "muzzle.absent", "muzzle.aim")) {
      Weapon stored = new Weapon("bad", Rig.yaml("aim-mode: cursor\naim-vector: " + spec + "\n"));
      ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
      active.setController(player);
      active.tick();
      active.updateModel(model);
    }
    bone("weapon_body", new Location(r.world, 0, 64, 0));
    active("aim-mode: cursor\nbones: []\n").tick();
    bone("cannon_controller", new Location(r.world, 0, 64, 0));
    active("aim-mode: cursor\nbones: []\n").tick();
    active("aim-mode: cursor\nbones: [muzzle.aim]\n").tick();
    active("fixed: true\naim-mode: cursor\n").tick();
  }

  @Test
  void onlyCursorWeaponsWarnWhenFallingBackToTheFirstBone() throws Exception {
    active("bones: [muzzle.aim]\n");
    r.logger.verify(() -> VFLogger.log(contains("no aim-vector")), never());
    active("aim-mode: cursor\nbones: [muzzle.aim]\n");
    r.logger.verify(
        () ->
            VFLogger.log(
                "Weapon test has no aim-vector; falling back to first bones entry: muzzle.aim"));
  }

  @Test
  void controllerCommandsRouteReloadShootCombinedAndWeaponSwitch() throws Exception {
    Weapon stored = new Weapon("fixed", Rig.yaml("fixed: true\nbones: [muzzle.aim]\n"));
    ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
    WeaponMovementController controls =
        new WeaponMovementController(vehicle, model, active, stored, stored.getLimits());
    for (Input input :
        List.of(
            Input.WEAPON_RELOAD,
            Input.WEAPON_SHOOT,
            Input.WEAPON_RELOAD_AND_SHOOT,
            Input.WEAPON_SWITCH,
            Input.NONE)) controls.input(List.of(), input, player);
    verify(vehicle.getSeat(player)).changeWeapon();
    verify(vehicle).updateBoard();
    verify(player, times(2)).sendMessage("§cNo ammo");
  }

  @Test
  void targetResolverSelectsNearestSolidOrLivingHitAndFiltersPassengers() {
    assertNull(WeaponTargetResolver.resolveTarget(null, vehicle, 80));
    Location eye = player.getEyeLocation();
    Entity root = vehicle.getEntity();
    LivingEntity passenger = r.entity(LivingEntity.class, eye.clone().add(0, 0, 1));
    when(vehicle.getSeatHandler().getPassengers()).thenReturn(List.of(passenger));
    assertEquals(
        Set.of(player, root, passenger),
        WeaponEntityFilters.buildShooterIgnoreSet(player, root, vehicle));
    assertTrue(WeaponEntityFilters.buildShooterIgnoreSet(null, null, null).isEmpty());
    LivingEntity target = r.entity(LivingEntity.class, eye.clone().add(0, 0, 5));
    r.rayTargets.addAll(List.of(player, root, passenger, target));
    assertEquals(target.getLocation(), WeaponTargetResolver.resolveTarget(player, vehicle, 80));
    when(r.world.rayTraceBlocks(any(), any(), anyDouble(), any(), anyBoolean()))
        .thenReturn(new RayTraceResult(eye.clone().add(0, 0, 2).toVector()));
    assertEquals(eye.clone().add(0, 0, 2), WeaponTargetResolver.resolveTarget(player, vehicle, 80));
    r.rayTargets.clear();
    assertEquals(eye.clone().add(0, 0, 2), WeaponTargetResolver.resolveTarget(player, null, 80));
    when(r.world.rayTraceBlocks(any(), any(), anyDouble(), any(), anyBoolean())).thenReturn(null);
    assertEquals(eye.clone().add(0, 0, 80), WeaponTargetResolver.resolveTarget(player, null, -1));
    when(player.getEyeLocation()).thenReturn(new Location(null, 1, 2, 3));
    assertEquals(
        new Location(null, 1, 2, 13), WeaponTargetResolver.resolveTarget(player, null, 10));
  }

  @Test
  void aimMathHandlesMissingTargetsOffsetsHysteresisAndHealthScaling() {
    var offset = new WeaponAimOffset(10, 20, 30, 40);
    assertEquals(10, offset.applyToAngles(null, "x").getYaw());
    assertEquals(25, offset.applyToAngles(new ConvertedAngle(1, 5, 0), null).getPitch());
    assertEquals(45, offset.applyToAngles(new ConvertedAngle(1, 5, 0), "z").getPitch());
    assertEquals(41, offset.applyToAngles(new ConvertedAngle(1, 5, 0), "y").getYaw());
    assertEquals(0, WeaponAimAligner.yawError(null, new Vector(0, 0, 1)));
    assertEquals(0, WeaponAimAligner.yawError(new Vector(0, 0, 1), (ConvertedAngle) null));
    assertEquals(0, WeaponAimAligner.elevationError(null, new Vector(0, 0, 1), "x"));
    assertEquals(
        0, WeaponAimAligner.elevationError(new Vector(0, 0, 1), (ConvertedAngle) null, "x"));
    assertEquals(0, WeaponAimAligner.followStep(2, 0, false));
    assertEquals(0, WeaponAimAligner.followStep(2, 1, true));
    assertEquals(0, WeaponAimAligner.followStep(.5f, 1, false));
    assertTrue(WeaponAimAligner.followStep(-20, 2, false) < 0);
    assertTrue(WeaponAimAligner.updateSettled(.5f, false));
    assertFalse(WeaponAimAligner.updateSettled(20, false));
    assertTrue(WeaponAimAligner.updateSettled(2, true));
    assertFalse(WeaponAimAligner.updateSettled(8, true));
    assertEquals(-2, WeaponAimAligner.toBoneYawStep(2));
    assertEquals(-2, WeaponAimAligner.toBoneElevationStep(2));
    assertEquals(3, WeaponPerformance.effectiveTurnRate(3, null));
    assertEquals(1.5, WeaponPerformance.effectiveTurnRate(3, new HealthData(100, 50, 1)));
    assertEquals(1, WeaponPerformance.effectiveReloadSeconds(0, null, 2));
    assertEquals(2, WeaponPerformance.effectiveReloadSeconds(2, null, 2));
    assertEquals(4, WeaponPerformance.effectiveReloadSeconds(2, new HealthData(100, 100, 1), 2));
    var cooldown = new NoticeCooldown(10);
    UUID id = UUID.randomUUID();
    assertTrue(cooldown.tryAcquire(id, 0));
    assertFalse(cooldown.tryAcquire(id, 1));
    assertTrue(cooldown.tryAcquire(id, 10));
  }

  @Test
  void muzzleFlashDoesNotDeleteAReplacementBlock() {
    ProjectileShooter shooter = new ProjectileShooter();
    Location location = new Location(r.world, 0, 64, 0);
    shooter.lightEffect(location);
    r.block(0, 64, 0, Material.STONE);
    r.ticks(4);
    assertEquals(
        Material.STONE,
        location.getBlock().getType(),
        "The temporary muzzle light must not erase a subsequently placed block");
  }

  @Test
  void cursorTrackingHandlesAnUnavailableMuzzleCoincidentTargetAndBrokenElevation()
      throws Exception {
    bone("body", new Location(r.world, 0, 64, 0));
    bone("head", new Location(r.world, 0, 64, 0));
    Weapon stored =
        new Weapon(
            "cursor",
            Rig.yaml(
                "body-bone: body\nhead-bone: head\naim-mode: cursor\naim-vector: muzzle.aim\n"));
    ActiveWeapon active = new ActiveWeapon(model, vehicle, stored, null);
    WeaponMovementController controls =
        new WeaponMovementController(vehicle, model, active, stored, stored.getLimits());
    when(bones.get("muzzle").getLocation()).thenReturn(null);
    assertDoesNotThrow(() -> controls.trackCursor(player));
    when(bones.get("muzzle").getLocation()).thenAnswer(c -> new Location(r.world, 0, 64, 0));
    LivingEntity close = r.entity(LivingEntity.class, new Location(r.world, 0, 64, 0));
    r.rayTargets.add(close);
    controls.trackCursor(player);
    assertEquals(0, panel.getRotator("body").getDriveYaw());
    r.rayTargets.clear();
    active.getHealthData().setDamage(100);
    player.teleport(new Location(r.world, 0, 64, 0, 0, -45));
    controls.trackCursor(player);
    verify(player).sendMessage(contains("is broken"));
    Entity decoration = r.entity(Entity.class, new Location(r.world, 0, 64, 1));
    LivingEntity dead = r.entity(LivingEntity.class, new Location(r.world, 0, 64, 2));
    when(dead.isDead()).thenReturn(true);
    r.rayTargets.addAll(List.of(decoration, dead));
    assertNotNull(WeaponTargetResolver.resolveTarget(player, vehicle, 80));
  }

  @Test
  void muzzleLightAnimatesThenRemovesOnlyItsTemporaryLight() {
    ProjectileShooter shooter = new ProjectileShooter();
    Location location = new Location(r.world, 0, 64, 0);
    r.block(0, 64, 0, Material.STONE);
    shooter.lightEffect(location);
    assertEquals(0, r.pending());
    r.block(0, 64, 0, Material.AIR);
    shooter.lightEffect(location);
    var level = (org.bukkit.block.data.Levelled) location.getBlock().getBlockData();
    verify(level).setLevel(10);
    r.tick();
    r.tick();
    verify(level).setLevel(15);
    r.tick();
    verify(level).setLevel(11);
    r.tick();
    assertEquals(Material.AIR, location.getBlock().getType());
    assertEquals(0, r.pending());
  }

  @Test
  void cannonballMovesWithGravityAndIsRemovedOnImpact() throws Exception {
    Ammunition ammo = ammo("CANNONBALL", "explosive: false\n");
    ProjectileShooter shooter = new ProjectileShooter();
    shooter.shoot(
        List.of(),
        vehicle.getEntity(),
        new Location(r.world, 0, 64, 0),
        new Vector(0, 0, 1),
        ammo,
        null);
    Entity shot = r.spawned.getLast();
    assertTrue(Cache.projectiles.contains(shot));
    r.tick();
    assertEquals(3, shot.getLocation().getZ(), 1e-9);
    assertEquals(63.951, shot.getLocation().getY(), 1e-9);
    when(shot.isOnGround()).thenReturn(true);
    r.ticks(5);
    verify(shot).remove();
    assertFalse(Cache.projectiles.contains(shot));
    assertEquals(0, r.pending());
  }

  @Test
  void airborneCannonAndBombSoundsNeverDropBelowTheirPitchFloor() throws Exception {
    for (String type : List.of("CANNONBALL", "BOMB")) {
      Ammunition ammo =
          ammo(
              type,
              "explosive: false\n"
                  + "fuse: 80\n"
                  + "sounds: {flight: {sound: test.flight, pitched: true}}\n");
      new ProjectileShooter()
          .shoot(
              List.of(),
              vehicle.getEntity(),
              new Location(r.world, 0, 1000, 0),
              new Vector(0, 0, 1),
              ammo,
              null);
      Entity shot = r.spawned.getLast();
      r.ticks(85);
      ArgumentCaptor<Float> pitches = ArgumentCaptor.forClass(Float.class);
      verify(r.world, atLeast(85))
          .playSound(any(Location.class), eq("test.flight"), eq(1f), pitches.capture());
      assertTrue(pitches.getAllValues().stream().allMatch(p -> p >= 0.3f));
      assertTrue(pitches.getAllValues().contains(0.3f));
      when(shot.isDead()).thenReturn(true);
      r.tick();
    }
  }

  @Test
  void bombsInheritVehicleVelocityAndWaitForFuseBeforeGroundImpact() throws Exception {
    vehicle.getEntity().setVelocity(new Vector(2, .2, 0));
    Ammunition ammo = ammo("BOMB", "fuse: 1\nexplosive: false\n");
    new ProjectileShooter()
        .shoot(
            List.of(),
            vehicle.getEntity(),
            new Location(r.world, 0, 64, 0),
            new Vector(0, 0, 1),
            ammo,
            null);
    Entity shot = r.spawned.getLast();
    when(shot.isOnGround()).thenReturn(true);
    r.tick();
    assertEquals(2, shot.getLocation().getX(), 1e-9);
    assertEquals(64.151, shot.getLocation().getY(), 1e-9);
    verify(shot, never()).remove();
    r.tick();
    verify(shot).remove();
    assertFalse(Cache.projectiles.contains(shot));
  }

  @Test
  void clusterFuseCreatesConfiguredChildrenAndCleansThemOnImpact() throws Exception {
    ClusterBomb ammo =
        (ClusterBomb)
            ammo(
                "CLUSTER",
                "fuse: 2\n"
                    + "amount: 2\n"
                    + "spread: 0\n"
                    + "explosive: false\n"
                    + "cluster:\n"
                    + "  explosive: false\n"
                    + "  model: {type: item, material: STONE}\n");
    new ProjectileShooter()
        .shoot(
            List.of(player),
            vehicle.getEntity(),
            new Location(r.world, 0, 64, 0),
            new Vector(0, 0, 1),
            ammo,
            null);
    Entity shell = r.spawned.getFirst();
    r.ticks(2);
    verify(shell).remove();
    assertEquals(3, r.spawned.size());
    assertEquals(2, Cache.projectiles.size());
    for (Entity child : r.spawned.subList(1, 3)) {
      assertEquals(new Vector(0, -3.5, 0), child.getVelocity());
      when(child.isOnGround()).thenReturn(true);
    }
    r.ticks(7);
    assertTrue(Cache.projectiles.isEmpty());
    for (Entity child : r.spawned.subList(1, 3)) verify(child).remove();
    verify(player)
        .spawnParticle(
            eq(Particle.EXPLOSION_EMITTER),
            any(Location.class),
            eq(15),
            eq(0d),
            eq(0d),
            eq(0d),
            eq(0d));
  }

  @Test
  void clusterImpactAtFuseBoundaryDetonatesOnlyOnce() throws Exception {
    ClusterBomb ammo =
        (ClusterBomb)
            ammo(
                "CLUSTER", "fuse: 6\namount: 0\ncluster: {model: {type: item, material: STONE}}\n");
    try (var explosions = mockStatic(ExplosionCreator.class)) {
      new ProjectileShooter()
          .shoot(
              List.of(),
              vehicle.getEntity(),
              new Location(r.world, 0, 64, 0),
              new Vector(0, 0, 1),
              ammo,
              null);
      Entity shell = r.spawned.getFirst();
      when(shell.isOnGround()).thenReturn(true);
      r.ticks(6);
      explosions.verify(
          () ->
              ExplosionCreator.triggerExplosion(
                  any(Location.class), eq(1d), eq(5d), eq(8d), eq("PROJECTILE"), eq(false)),
          times(1));
      verify(shell, times(1)).remove();
    }
  }

  @Test
  void torpedoesLevelAccelerateInWaterAndExpireAfterTheirFuse() throws Exception {
    for (boolean waterlogged : List.of(false, true)) {
      Location launch = new Location(r.world, waterlogged ? 10 : 0, 64, 0);
      var block =
          r.block(launch.getBlockX(), 63, 0, waterlogged ? Material.OAK_SLAB : Material.WATER);
      if (waterlogged) {
        var data = mock(org.bukkit.block.data.Waterlogged.class);
        when(data.isWaterlogged()).thenReturn(true);
        when(block.getBlockData()).thenReturn(data);
      }
      Ammunition ammo =
          ammo(
              "TORPEDO",
              "fuse: 10\nexplosive: false\nmodel: {type: item, material: STONE, small: true}\n");
      new ProjectileShooter()
          .shoot(List.of(), vehicle.getEntity(), launch, new Vector(0, .1, 1), ammo, null);
      Entity torpedo = r.spawned.getLast();
      r.tick();
      assertEquals(0, torpedo.getVelocity().getY(), 1e-9);
      assertEquals(3.4, torpedo.getVelocity().getZ(), .003);
      r.ticks(10);
      verify(torpedo).remove();
      assertFalse(Cache.projectiles.contains(torpedo));
    }
  }

  @Test
  void bulletShooterStopsAtRangeOrCollisionAndProducesInterpolatedTrails() throws Exception {
    Bullet shortRange =
        new Bullet("short", Rig.yaml("type: BULLET\nrange: 1\nspeed: 2\ngravity: 0\n"));
    new ProjectileShooter()
        .shoot(
            List.of(),
            vehicle.getEntity(),
            new Location(r.world, 0, 64, 0),
            new Vector(0, 0, 1),
            shortRange,
            null);
    r.tick();
    r.ticks(3);
    assertEquals(0, r.pending());
    r.block(0, 64, 2, Material.STONE);
    new ProjectileShooter()
        .shoot(
            List.of(),
            vehicle.getEntity(),
            new Location(r.world, 0, 64, 0),
            new Vector(0, 0, 1),
            shortRange,
            null);
    r.tick();
    r.ticks(3);
    assertEquals(0, r.pending());
    verify(r.world).playSound(any(Location.class), eq(Sound.BLOCK_STONE_BREAK), eq(1f), eq(2f));
  }

  private ActiveWeapon active(String yaml) throws Exception {
    return new ActiveWeapon(model, vehicle, new Weapon("test", Rig.yaml(yaml)), null);
  }

  private Ammunition ammo(String type, String yaml) throws Exception {
    YamlConfiguration config = Rig.yaml("type: " + type + "\n" + yaml);
    if (!config.contains("model")) {
      config.set("model.type", "item");
      config.set("model.material", "STONE");
    }
    return Ammunition.create("round", config);
  }

  private ItemStack item(Material material, int amount) {
    ItemStack stack = mock(ItemStack.class);
    AtomicInteger count = new AtomicInteger(amount);
    when(stack.getType()).thenReturn(material);
    when(stack.getAmount()).thenAnswer(c -> count.get());
    doAnswer(
            c -> {
              count.set(c.getArgument(0));
              return null;
            })
        .when(stack)
        .setAmount(anyInt());
    return stack;
  }

  private ModelBone bone(String id, Location location) {
    ModelBone b = mock(ModelBone.class, RETURNS_DEEP_STUBS);
    when(b.getLocalTransform().getSafeLeftQuaternion()).thenReturn(new org.joml.Quaternionf());
    when(b.getLocalTransform().getSafePosition()).thenReturn(new org.joml.Vector3f());
    when(b.getLocalTransform().getSafeScale()).thenReturn(new org.joml.Vector3f(1));
    when(b.getBoneId()).thenReturn(id);
    when(b.getLocation()).thenAnswer(c -> location.clone());
    when(b.getActiveModel()).thenReturn(model);
    bones.put(id, b);
    return b;
  }
}
