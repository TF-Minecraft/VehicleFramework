package net.tfminecraft.vehicleframework.effects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.property.SimpleProperty;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.model.bone.SimpleManualAnimator;
import com.ticxo.modelengine.api.utils.config.ConfigCache;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class EffectsCoverageTest {
  Rig r;
  ActiveVehicle vehicle;
  ActiveModel model;
  Player viewer;
  final Map<String, ModelBone> bones = new HashMap<>();
  final Map<String, BlueprintAnimation> animations = new HashMap<>();
  MockedStatic<ModelEngineAPI> api;

  @BeforeEach
  void setup() {
    r = new Rig();
    vehicle = r.vehicle();
    model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
    viewer = r.entity(Player.class, new Location(r.world, 0, 64, 0));
    when(vehicle.getModel()).thenReturn(model);
    when(model.getBone(anyString()))
        .thenAnswer(c -> Optional.ofNullable(bones.get(c.getArgument(0))));
    when(model.getBlueprint().getAnimations()).thenReturn(animations);
    api = mockStatic(ModelEngineAPI.class);
    ConfigCache config = mock(ConfigCache.class);
    api.when(ModelEngineAPI::getConfigCache).thenReturn(config);
    bone("base", new Location(r.world, 0, 64, 0));
    bone("aim", new Location(r.world, 0, 64, 1));
  }

  @AfterEach
  void cleanup() {
    api.close();
    r.close();
  }

  @Test
  void effectIdentifiersDoNotDependOnTheServerLocale() {
    when(vehicle.hasComponent(Component.WINGS)).thenReturn(true);
    Locale saved = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      CustomEffect effect =
          new CustomEffect(
              List.of("particle(crit;base.aim;1;0;0.2)", "start_fire(wings)", "death(sink)"));
      assertDoesNotThrow(() -> effect.play(List.of(viewer), vehicle));
      verify(vehicle).kill(VehicleDeath.SINK);
      verify(vehicle.getComponent(Component.WINGS)).startFire();
    } finally {
      Locale.setDefault(saved);
    }
  }

  @Test
  void emptyEffectsFinishImmediatelyAndExposeConfiguredCommands() {
    CustomEffect empty = new CustomEffect(List.of());
    assertTrue(empty.isEmpty());
    assertTrue(empty.isFinished());
    empty.play(List.of(), vehicle);
    assertTrue(empty.isFinished());
    assertEquals(List.of(), empty.getCommands());
    CustomEffect effect = new CustomEffect(List.of("delay(2)", "sound(base;test.horn;2;0.5)"));
    assertFalse(effect.isEmpty());
    assertEquals(2, effect.getCommands().size());
    effect.play(List.of(viewer), vehicle);
    assertFalse(effect.isFinished());
    r.tick();
    verify(r.world, never()).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
    r.tick();
    assertTrue(effect.isFinished());
    verify(r.world).playSound(new Location(r.world, 0, 64, 0), "test.horn", 2f, .5f);
  }

  @Test
  void particleEffectsAimAlongBonesAndEmitForEachViewer() {
    CustomEffect effect = new CustomEffect(List.of("particle(flame;base.aim;3;0;0.4)"));
    effect.play(List.of(viewer), vehicle);
    verify(viewer, times(3))
        .spawnParticle(Particle.FLAME, new Location(r.world, 0, 64, 0), 0, 0, 0, 1, .4);
    assertTrue(effect.isFinished());
  }

  @Test
  void conditionsStopFollowingCommandsAndPreserveCompositeValues() {
    for (String condition :
        List.of(
            "condition()",
            "condition( ;true)",
            "condition(has_fuel)",
            "condition(has_fuel;true)",
            "condition(AND;has_fuel=true;passengers=true)")) {
      CustomEffect effect = new CustomEffect(List.of(condition, "death(explode)"));
      effect.play(List.of(), vehicle);
      assertTrue(effect.isFinished());
      verify(vehicle, never()).kill(any());
    }
    when(vehicle.hasFuel()).thenReturn(true);
    when(vehicle.getSeatHandler().hasPassengers()).thenReturn(true);
    CustomEffect passes =
        new CustomEffect(List.of("condition(AND;has_fuel=true;passengers=true)", "death(explode)"));
    passes.play(List.of(), vehicle);
    verify(vehicle).kill(VehicleDeath.EXPLODE);
  }

  @Test
  void deathAndFireEffectsRespectExistingDestructionAndBurning() {
    when(vehicle.isDestroyed()).thenReturn(true);
    new CustomEffect(List.of("death(crash)")).play(List.of(), vehicle);
    verify(vehicle, never()).kill(any());
    new CustomEffect(List.of("start_fire(hull)")).play(List.of(), vehicle);
    verify(vehicle.getComponent(Component.HULL), never()).startFire();
    when(vehicle.hasComponent(Component.HULL)).thenReturn(true);
    when(vehicle.getComponent(Component.HULL).isOnFire()).thenReturn(true);
    new CustomEffect(List.of("start_fire(hull)")).play(List.of(), vehicle);
    verify(vehicle.getComponent(Component.HULL), never()).startFire();
    when(vehicle.getComponent(Component.HULL).isOnFire()).thenReturn(false);
    new CustomEffect(List.of("start_fire(hull)")).play(List.of(), vehicle);
    verify(vehicle.getComponent(Component.HULL)).startFire();
  }

  @Test
  void animationsIgnoreMissingAndAlreadyPlayingEntries() {
    new CustomEffect(List.of("animation(missing)")).play(List.of(), vehicle);
    verify(model.getAnimationHandler(), never())
        .playAnimation(any(SimpleProperty.class), anyBoolean());
    BlueprintAnimation animation = new BlueprintAnimation(model.getBlueprint(), "fly");
    animation.setLength(1);
    animation.setLoopMode(BlueprintAnimation.LoopMode.LOOP);
    animations.put("fly", animation);
    when(model.getAnimationHandler().isPlayingAnimation("fly")).thenReturn(true);
    new CustomEffect(List.of("animation(fly)")).play(List.of(), vehicle);
    verify(model.getAnimationHandler(), never())
        .playAnimation(any(SimpleProperty.class), anyBoolean());
    when(model.getAnimationHandler().isPlayingAnimation("fly")).thenReturn(false);
    new CustomEffect(List.of("animation(fly)")).play(List.of(), vehicle);
    verify(model.getAnimationHandler()).playAnimation(any(SimpleProperty.class), eq(true));
  }

  @Test
  void boneFreezingCreatesAndReleasesTheAnimatorIdempotently() {
    ModelBone bone = bones.get("base");
    new CustomEffect(
            List.of(
                "freeze_bone(missing;true)",
                "freeze_bone(base;false)",
                "freeze_bone(base;true)",
                "freeze_bone(base;true)"))
        .play(List.of(), vehicle);
    assertNotNull(bone.getManualAnimator());
    verify(bone, times(1)).setManualAnimator(any(SimpleManualAnimator.class));
    new CustomEffect(List.of("freeze_bone(base;false)")).play(List.of(), vehicle);
    assertNull(bone.getManualAnimator());
    verify(bone).setManualAnimator(null);
  }

  private ModelBone bone(String id, Location location) {
    ModelBone bone = mock(ModelBone.class, RETURNS_DEEP_STUBS);
    when(bone.getBoneId()).thenReturn(id);
    when(bone.getActiveModel()).thenReturn(model);
    when(bone.getLocation()).thenAnswer(c -> location.clone());
    when(bone.getLocalTransform().getSafeLeftQuaternion()).thenReturn(new org.joml.Quaternionf());
    when(bone.getLocalTransform().getSafePosition()).thenReturn(new org.joml.Vector3f());
    when(bone.getLocalTransform().getSafeScale()).thenReturn(new org.joml.Vector3f(1));
    AtomicReference<com.ticxo.modelengine.api.model.bone.ManualAnimator> animator =
        new AtomicReference<>();
    when(bone.getManualAnimator()).thenAnswer(c -> animator.get());
    doAnswer(
            c -> {
              animator.set(c.getArgument(0));
              return null;
            })
        .when(bone)
        .setManualAnimator(any());
    bones.put(id, bone);
    return bone;
  }
}
