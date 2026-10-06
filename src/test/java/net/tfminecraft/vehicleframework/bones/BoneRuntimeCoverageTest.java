package net.tfminecraft.vehicleframework.bones;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.reflect.StructureModifier;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import java.util.*;
import net.tfminecraft.vehicleframework.enums.Keybind;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import net.tfminecraft.vehicleframework.protocol.PacketConverter;
import net.tfminecraft.vehicleframework.protocol.VehiclePacketListener;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class BoneRuntimeCoverageTest {
  Rig r;
  ActiveVehicle vehicle;
  ActiveModel model;
  AccessPanel panel;
  Map<String, ModelBone> bones;

  @BeforeEach
  void setup() {
    r = new Rig();
    vehicle = r.vehicle();
    panel = new AccessPanel();
    when(vehicle.getAccessPanel()).thenReturn(panel);
    model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
    bones = new HashMap<>();
    when(model.getBone(anyString()))
        .thenAnswer(c -> Optional.ofNullable(bones.get(c.getArgument(0))));
  }

  @AfterEach
  void cleanup() {
    r.close();
  }

  @Test
  void normalizationPreservesUnselectedAxes() {
    BoneRotator rotator = rotator("body", new RotationLimits());
    rotator.setRotation(30, 0, 0, true, true, true);
    rotator.normalize(true, false, false);
    assertEquals(
        30, rotator.getConvertedAngles().getYaw(), .001, "Normalizing X must preserve Y rotation");
    rotator.normalize(false, true, false);
    assertEquals(0, rotator.getConvertedAngles().getYaw(), .001);
  }

  @Test
  void normalizationSnapsToNeutralWithoutOvershooting() {
    BoneRotator rotator = rotator("body", new RotationLimits());
    rotator.setRotation(40, 0, 0, true, true, true);
    rotator.normalize(true, true, true);
    assertEquals(
        0,
        rotator.getConvertedAngles().getYaw(),
        .001,
        "Interpolation past 1 mirrors the angle instead of resetting it");
    assertEquals(0, rotator.getDriveYaw(), .001);
  }

  @Test
  void directionAnglesWrapAndKeepInputVectorsIntact() {
    ConvertedAngle explicit = new ConvertedAngle(10, 20, 30);
    assertEquals(10, explicit.getYaw());
    assertEquals(20, explicit.getPitch());
    assertEquals(30, explicit.getRoll());
    assertEquals(
        90, new ConvertedAngle(new Quaternionf().rotateY((float) Math.PI / 2)).getYaw(), .001);
    assertEquals(
        30, new ConvertedAngle(new Quaternionf().rotateX((float) Math.PI / 6)).getPitch(), .001);
    assertEquals(
        45, new ConvertedAngle(new Quaternionf().rotateZ((float) Math.PI / 4)).getRoll(), .001);
    assertEquals(0, ConvertedAngle.fromDirection(null).getYaw());
    assertEquals(0, ConvertedAngle.fromDirection(new Vector()).getPitch());
    Vector direction = new Vector(2, 0, 0);
    assertEquals(-90, ConvertedAngle.fromDirection(direction).getYaw(), .001);
    assertEquals(new Vector(2, 0, 0), direction);
    assertEquals(-90, ConvertedAngle.fromDirection(new Vector(0, 1, 0)).getPitch(), .001);
    assertEquals(-90, ConvertedAngle.wrapDegrees(270));
    assertEquals(90, ConvertedAngle.wrapDegrees(-270));
    assertEquals(180, ConvertedAngle.wrapDegrees(-180));
    assertEquals(20, ConvertedAngle.shortestDelta(170, -170));
  }

  @Test
  void rotationLimitsApplyOptionalAxesDefaultsWrappingAndClamps() throws Exception {
    RotationLimits none = new RotationLimits();
    assertTrue(none.withinAll(300, 100, 200));
    assertEquals(300, none.clampYaw(300));
    assertEquals(100, none.clampPitch(100));
    assertEquals(200, none.clampRoll(200));
    RotationLimits configured =
        new RotationLimits(
            Rig.yaml(
                "min-yaw: -30\n"
                    + "max-yaw: 30\n"
                    + "min-pitch: -45\n"
                    + "max-pitch: 45\n"
                    + "min-roll: -20\n"
                    + "max-roll: 20\n"));
    assertEquals(-30, configured.getMinYaw());
    assertEquals(30, configured.getMaxYaw());
    assertEquals(-45, configured.getMinPitch());
    assertEquals(45, configured.getMaxPitch());
    assertEquals(-20, configured.getMinRoll());
    assertEquals(20, configured.getMaxRoll());
    assertTrue(configured.withinAll(30, 45, 20));
    assertTrue(configured.withinYaw(350));
    assertTrue(configured.withinYaw(-350));
    assertFalse(configured.withinYaw(40));
    assertFalse(configured.withinYaw(-40));
    assertFalse(configured.withinAll(0, 46, 0));
    assertFalse(configured.withinAll(0, 0, 21));
    assertEquals(30, configured.clampYaw(90));
    assertEquals(-45, configured.clampPitch(-90));
    assertEquals(20, configured.clampRoll(50));
    assertEquals(10, configured.clampYaw(10));
    RotationLimits partial =
        new RotationLimits(Rig.yaml("max-yaw: 30\nmin-pitch: -40\nmax-roll: 10\n"));
    assertEquals(-180, partial.getMinYaw());
    assertEquals(90, partial.getMaxPitch());
    assertEquals(-180, partial.getMinRoll());
    assertTrue(new RotationLimits(Rig.yaml("")).withinAll(400, 200, 300));
    assertFalse(RotationLimits.yawOnly(-1, 1).withinYaw(2));
    assertTrue(RotationLimits.yawOnly(-1, 1).withinPitch(200));
    assertFalse(RotationLimits.pitchOnly(-1, 1).withinPitch(2));
    assertTrue(RotationLimits.pitchOnly(-1, 1).withinRoll(200));
    assertFalse(RotationLimits.rollOnly(-1, 1).withinRoll(2));
    assertTrue(RotationLimits.rollOnly(-1, 1).withinYaw(200));
  }

  @Test
  void boneRotationsMaintainDriveHeadingLimitsSmoothingAndEntityFacing() throws Exception {
    BoneRotator rotator = rotator("body", RotationLimits.yawOnly(-5, 5));
    assertEquals("body", rotator.getId());
    assertSame(bones.get("body"), rotator.getBone());
    assertSame(rotator, panel.getRotator("body"));
    rotator.rotate(0, 2, 0);
    assertEquals(0, rotator.getDriveYaw(), .001);
    rotator.rotate(0, .1, 0);
    assertEquals(Math.toDegrees(.01), rotator.getDriveYaw(), .001);
    rotator.rotate(0, 0, 0);
    rotator.reset();
    assertEquals(0, rotator.getDriveYaw(), .001);
    rotator.rotateSmoothed(0, 10, 0);
    assertEquals(Math.toDegrees(.05), rotator.getDriveYaw(), .001);
    rotator.resetSmoothing();
    rotator.reset();
    rotator.rotateSmoothed(0, 10, 0);
    assertEquals(Math.toDegrees(.05), rotator.getDriveYaw(), .001);
    rotator.rotateEntity(25, -10);
    assertEquals(25, vehicle.getEntity().getLocation().getYaw());
    assertEquals(-10, vehicle.getEntity().getLocation().getPitch());
    assertEquals(64, vehicle.getEntity().getLocation().getY());
    assertTrue(Double.isFinite(rotator.getAngles().angle));
    Quaternionf heading = new Quaternionf().rotateY((float) Math.toRadians(20));
    rotator.rawSet(heading.x, heading.y, heading.z, heading.w);
    assertEquals(20, rotator.getDriveYaw(), .001);
    ModelBone replacement = bone("body", new Location(r.world, 0, 64, 0));
    rotator.updateModel(model);
    assertSame(replacement, rotator.getBone());
    verify(replacement).setManualAnimator(rotator.getAnimator());
    assertEquals(20, rotator.getConvertedAngles().getYaw(), .001);
    assertTrue(rotator.rotateToTarget(20, 0, 0, 1, true, true, true));
    assertFalse(rotator.rotateToTarget(0, 10, 0, .5f, false, true, false));
    assertTrue(rotator.getConvertedAngles().getPitch() > 0);
  }

  @Test
  void vectorBonesTrackReplacementModelsAndCoincidentFallbacks() {
    ModelBone base = bone("base", new Location(r.world, 1, 64, 1)),
        align = bone("aim", new Location(r.world, 1, 64, 3));
    VectorBone vector = new VectorBone(base, align);
    assertEquals("base", vector.getId());
    assertSame(base, vector.getBase());
    assertSame(align, vector.getAlign());
    assertSame(model, vector.getModel());
    assertEquals(new Location(r.world, 1, 64, 1), vector.getBaseLocation());
    assertEquals(new Location(r.world, 1, 64, 3), vector.getAlignLocation());
    assertEquals(new Vector(0, 0, 1), vector.getVector());
    ModelBone replacement = bone("base", new Location(r.world, 1, 64, 3));
    vector.updateModel(model);
    assertSame(replacement, vector.getBase());
    assertEquals(new Vector(1, 0, 0), vector.getVector());
  }

  @Test
  void rotationTargetsBecomeActiveAtTheTargetAndReactivateAfterConditionsChange() throws Exception {
    BoneRotator base = rotator("base", new RotationLimits());
    RotationTarget stored =
        new RotationTarget(
            Rig.yaml("yaw: 30\npitch: 0\nroll: 0\ninterval: 1\nconditions: [has_fuel(true)]\n"));
    assertEquals("none", stored.getRotatorOverride());
    assertEquals(List.of("has_fuel(true)"), stored.getConditions());
    assertEquals(30, stored.getYaw());
    assertEquals(0, stored.getPitch());
    assertEquals(0, stored.getRoll());
    assertTrue(stored.shouldYaw());
    assertTrue(stored.shouldPitch());
    assertTrue(stored.shouldRoll());
    assertEquals(1, stored.getInterval());
    RotationTarget active = new RotationTarget(vehicle, stored, base);
    assertSame(base, active.getRotator());
    assertFalse(active.isActive());
    active.run(vehicle);
    assertEquals(0, base.getDriveYaw());
    when(vehicle.hasFuel()).thenReturn(true);
    active.run(vehicle);
    assertFalse(active.isActive());
    active.run(vehicle);
    assertTrue(active.isActive());
    active.run(vehicle);
    assertEquals(30, base.getDriveYaw(), .001);
    active.updateModel();
    when(vehicle.hasFuel()).thenReturn(false);
    active.run(vehicle);
    assertFalse(active.isActive());
    when(vehicle.hasFuel()).thenReturn(true);
    active.run(vehicle);
    assertTrue(active.isActive());
    BoneRotator override = rotator("override", new RotationLimits());
    RotationTarget selected =
        new RotationTarget(vehicle, new RotationTarget(Rig.yaml("rotator: override\n")), base);
    assertSame(override, selected.getRotator());
    assertFalse(selected.shouldYaw());
    assertFalse(selected.shouldPitch());
    assertFalse(selected.shouldRoll());
    RotationTarget missing =
        new RotationTarget(vehicle, new RotationTarget(Rig.yaml("rotator: missing\n")), base);
    assertSame(base, missing.getRotator());
    selected.setRotator(base);
    assertSame(base, selected.getRotator());
  }

  @Test
  void packetConversionDistinguishesHeldJumpSneakAndOppositeAxes() {
    PacketConverter converter = new PacketConverter();
    assertEquals(List.of(Keybind.A, Keybind.W), converter.convert(1, 1, false, false));
    assertEquals(List.of(Keybind.D, Keybind.S), converter.convert(-1, -1, false, false));
    assertEquals(List.of(), converter.convert(0, 0, false, false));
    assertEquals(
        List.of(Keybind.SPACE_A, Keybind.SPACE_W, Keybind.SPACE),
        converter.convert(1, 1, true, false));
    assertEquals(
        List.of(Keybind.SPACE_D, Keybind.SPACE_S, Keybind.SPACE),
        converter.convert(-1, -1, true, false));
    assertEquals(
        List.of(Keybind.SHIFT_A, Keybind.SHIFT_W, Keybind.SHIFT, Keybind.SPACE),
        converter.convert(1, 1, true, true));
    assertEquals(
        List.of(Keybind.SHIFT_D, Keybind.SHIFT_S, Keybind.SHIFT),
        converter.convert(-1, -1, false, true));
    assertEquals(List.of(Keybind.SHIFT), converter.convert(0, 0, false, true));
  }

  @Test
  void packetListenerReplaysHeldInputsUntilQuitAndCancelsOppositeDirections() {
    Server server = mock(Server.class);
    when(server.getVersion()).thenReturn("Paper (MC: 1.21.10)");
    when(server.getBukkitVersion()).thenReturn("1.21.10-R0.1-SNAPSHOT");
    r.bukkit.when(Bukkit::getServer).thenReturn(server);
    r.bukkit.when(Bukkit::getVersion).thenReturn("Paper (MC: 1.21.10)");
    r.bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.10-R0.1-SNAPSHOT");
    r.bukkit.when(Bukkit::getLogger).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    ProtocolManager protocol = mock(ProtocolManager.class);
    try (var library = mockStatic(ProtocolLibrary.class);
        var reflection = mockStatic(com.comphenix.protocol.utility.MinecraftReflection.class);
        var nativeStacks = mockConstruction(org.bukkit.inventory.ItemStack.class)) {
      reflection
          .when(com.comphenix.protocol.utility.MinecraftReflection::getItemStackClass)
          .thenReturn(org.bukkit.inventory.ItemStack.class);
      reflection
          .when(com.comphenix.protocol.utility.MinecraftReflection::getBlockClass)
          .thenReturn(org.bukkit.block.Block.class);
      library.when(ProtocolLibrary::getProtocolManager).thenReturn(protocol);
      VehiclePacketListener listener = new VehiclePacketListener(r.manager);
      listener.register();
      ArgumentCaptor<PacketListener> capture = ArgumentCaptor.forClass(PacketListener.class);
      verify(protocol).addPacketListener(capture.capture());
      PacketListener adapter = capture.getValue();
      Player driver = r.entity(Player.class, new Location(r.world, 0, 64, 0)),
          other = r.entity(Player.class, new Location(r.world, 1, 64, 0));
      r.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(driver, other));
      PacketEvent event = mock(PacketEvent.class, RETURNS_DEEP_STUBS);
      when(event.getPlayer()).thenReturn(driver);
      StructureModifier<InternalStructure> structures = mock(StructureModifier.class);
      when(event.getPacket().getStructures()).thenReturn(structures);
      when(structures.size()).thenReturn(0);
      adapter.onPacketReceiving(event);
      r.tick();
      verify(r.manager, never())
          .inputPacket(any(), anyFloat(), anyFloat(), anyBoolean(), anyBoolean());
      InternalStructure input = mock(InternalStructure.class, RETURNS_DEEP_STUBS);
      when(structures.size()).thenReturn(1);
      when(structures.read(0)).thenReturn(input);
      boolean[] flags = {true, false, true, false, true, false};
      when(input.getBooleans().read(anyInt())).thenAnswer(c -> flags[c.getArgument(0)]);
      adapter.onPacketReceiving(event);
      r.ticks(2);
      verify(r.manager, times(2)).inputPacket(driver, 1, 1, true, false);
      Arrays.fill(flags, true);
      adapter.onPacketReceiving(event);
      r.tick();
      verify(r.manager).inputPacket(driver, 0, 0, true, true);
      Arrays.fill(flags, false);
      flags[1] = true;
      flags[3] = true;
      adapter.onPacketReceiving(event);
      r.tick();
      verify(r.manager).inputPacket(driver, -1, -1, false, false);
      listener.unregisterPlayer(driver);
      clearInvocations(r.manager);
      r.tick();
      verifyNoInteractions(r.manager);
    }
  }

  private BoneRotator rotator(String id, RotationLimits limits) {
    return new BoneRotator(
        vehicle, vehicle.getEntity(), bone(id, new Location(r.world, 0, 64, 0)), limits);
  }

  private ModelBone bone(String id, Location location) {
    ModelBone bone = mock(ModelBone.class, RETURNS_DEEP_STUBS);
    when(bone.getBoneId()).thenReturn(id);
    when(bone.getActiveModel()).thenReturn(model);
    when(bone.getLocation()).thenAnswer(c -> location.clone());
    when(bone.getLocalTransform().getSafeLeftQuaternion()).thenReturn(new Quaternionf());
    when(bone.getLocalTransform().getSafePosition()).thenReturn(new org.joml.Vector3f());
    when(bone.getLocalTransform().getSafeScale()).thenReturn(new org.joml.Vector3f(1));
    bones.put(id, bone);
    return bone;
  }
}
