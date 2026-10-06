package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Function;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import com.ticxo.modelengine.api.model.ActiveModel;
import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.enums.Keybind;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.StateHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.*;
import net.tfminecraft.vehicleframework.vehicles.state.*;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;

class VehicleControllersCoverageTest {
    @BeforeAll static void registry() { net.tfminecraft.vehicleframework.test.RegistryFixture.initialize(); }
    private final List<AutoCloseable> scopes = new ArrayList<>();
    @AfterEach void closeScopes() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }

    @Test void floatingRequiresAValidLivingEntityInEnoughWater() {
        Water f = new Water(); Vector motion = new Vector(1, -.2, 3); FloatController controller = new FloatController();
        when(f.vehicle.shouldFloat()).thenReturn(false);
        assertSame(motion, controller.calculateFloat(f.vehicle, motion)); assertEquals(-.2, motion.getY());
        when(f.vehicle.shouldFloat()).thenReturn(true); when(f.vehicle.getEntity()).thenReturn(null);
        assertEquals(-.2, controller.calculateFloat(f.vehicle, motion).getY());
        when(f.vehicle.getEntity()).thenReturn(f.entity); when(f.entity.isValid()).thenReturn(false);
        assertEquals(-.2, controller.calculateFloat(f.vehicle, motion).getY());
        Entity other = mock(Entity.class); when(other.isValid()).thenReturn(true); when(f.vehicle.getEntity()).thenReturn(other);
        assertEquals(-.2, controller.calculateFloat(f.vehicle, motion).getY());
        when(f.vehicle.getEntity()).thenReturn(f.entity); when(f.entity.isValid()).thenReturn(true);
        assertEquals(-.2, controller.calculateFloat(f.vehicle, motion).getY());
        f.grid.fill = p -> p.y == 64 ? Material.WATER : Material.AIR; f.location.setY(64.5);
        assertEquals(-FloatController.BOB_SPEED / 2, controller.calculateFloat(f.vehicle, motion).getY(), 1e-9);
        assertEquals(1, motion.getX()); assertEquals(3, motion.getZ());
    }

    @Test void deeplySubmergedBoatsRiseEvenWhenTheSurfaceIsOutsideTheScan() {
        Water f = new Water(); f.grid.fill = p -> Material.WATER;
        assertEquals(FloatController.BOB_SPEED, new FloatController().calculateFloat(f.vehicle, new Vector()).getY());
    }

    @Test void partialEdgeWaterWithoutACentreSurfacePreservesVerticalMotion() {
        Water f = new Water(); when(f.entity.getBoundingBox()).thenReturn(new BoundingBox(0, 64, 0, 2, 65, 2));
        f.grid.fill = p -> p.x > 0 && p.y == 64 ? Material.WATER : Material.AIR;
        assertEquals(.25, new FloatController().calculateFloat(f.vehicle, new Vector(0, .25, 0)).getY());
    }

    @Test void buoyancyFlipsAtTheDepthBoundsAndCapsGravityDrivenDescent() {
        Water f = new Water(); f.grid.fill = p -> p.y <= 64 ? Material.WATER : Material.AIR;
        FloatController controller = new FloatController(); f.location.setY(64);
        assertEquals(FloatController.BOB_SPEED, controller.calculateFloat(f.vehicle, new Vector()).getY());
        f.location.setY(64.5); when(f.entity.hasGravity()).thenReturn(true); when(f.entity.getVelocity()).thenReturn(new Vector(0, -2, 0));
        assertEquals(-FloatController.BOB_SPEED / 2, controller.calculateFloat(f.vehicle, new Vector()).getY());
        f.location.setY(64.2); when(f.entity.getVelocity()).thenReturn(new Vector(0, .2, 0));
        assertEquals(0, controller.calculateFloat(f.vehicle, new Vector()).getY());
        when(f.entity.hasGravity()).thenReturn(false);
        assertEquals(-FloatController.BOB_SPEED / 2, controller.calculateFloat(f.vehicle, new Vector()).getY());
        f.location.setY(64); assertEquals(FloatController.BOB_SPEED, controller.calculateFloat(f.vehicle, new Vector()).getY());
        f.location.setY(64.2); assertEquals(FloatController.BOB_SPEED, controller.calculateFloat(f.vehicle, new Vector()).getY());
    }

    @Test void forwardWaterRiseAddsBoundedLiftButLevelAndMissingWaterDoNot() {
        Water f = new Water(); when(f.vehicle.getAccessPanel().getSpeed()).thenReturn(.2);
        f.grid.fill = p -> p.y <= (p.z >= 1 ? 65 : 64) ? Material.WATER : Material.AIR;
        FloatController c = new FloatController(); assertEquals(FloatController.BOB_SPEED + .3, c.calculateFloat(f.vehicle, new Vector()).getY());
        f.grid.fill = p -> p.y <= 64 ? Material.WATER : Material.AIR;
        assertEquals(FloatController.BOB_SPEED, c.calculateFloat(f.vehicle, new Vector()).getY());
        f.grid.fill = p -> p.z >= 1 ? Material.AIR : p.y <= 64 ? Material.WATER : Material.AIR;
        assertEquals(FloatController.BOB_SPEED, c.calculateFloat(f.vehicle, new Vector()).getY());
        f.grid.fill = p -> Material.WATER;
        assertEquals(FloatController.BOB_SPEED, c.calculateFloat(f.vehicle, new Vector()).getY());
        f.location.setPitch(90); assertEquals(FloatController.BOB_SPEED, c.calculateFloat(f.vehicle, new Vector()).getY());
    }

    @Test void destroyedAndFloodedHullsDescendWhileSubmergedOrDrainedHullsRecover() {
        Water f = new Water(); f.grid.fill = p -> p.y <= 64 ? Material.WATER : Material.AIR;
        FloatController c = new FloatController(); when(f.vehicle.isDestroyed()).thenReturn(true);
        assertEquals(-.03, c.calculateFloat(f.vehicle, new Vector()).getY());
        when(f.vehicle.isDestroyed()).thenReturn(false);
        SinkableHull hull = mock(SinkableHull.class); when(f.vehicle.getComponent(Component.HULL)).thenReturn(hull);
        when(hull.hasSinkProgress()).thenReturn(true); when(hull.isSinking()).thenReturn(true); when(hull.getSinkProgress()).thenReturn(40);
        assertEquals(-.004, c.calculateFloat(f.vehicle, new Vector()).getY());
        f.grid.fill = p -> Material.WATER; assertEquals(.006, c.calculateFloat(f.vehicle, new Vector()).getY());
        when(hull.isSinking()).thenReturn(false); assertEquals(.006, c.calculateFloat(f.vehicle, new Vector()).getY());
    }

    @Test void noGravityArmorStandUsesCollisionMovementAndBreaksNearbyLilyPads() {
        Water f = new Water(); ArmorStand stand = mock(ArmorStand.class); f.bind(stand);
        f.grid.fill = p -> p.equals(new Cell(1, 64, 0)) ? Material.LILY_PAD : p.y <= 64 ? Material.WATER : Material.AIR;
        var api = keep(mockStatic(ModelEngineAPI.class, RETURNS_DEEP_STUBS)); var handler = ModelEngineAPI.getEntityHandler();
        assertEquals(0, new FloatController().calculateFloat(f.vehicle, new Vector()).getY());
        verify(handler).move(stand, 0, FloatController.BOB_SPEED, 0);
        verify(f.grid.block(1, 64, 0)).breakNaturally();
        verify(f.grid.world).playSound(eq(new Location(f.grid.world, 1, 64, 0)), eq(Sound.BLOCK_GRASS_BREAK), eq(1f), eq(1f));
    }

    @Test void animationStopSkipsAbsentAndIdleEntriesAndStillStopsLaterPlayingEntries() {
        ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
        BlueprintAnimation idle = mock(BlueprintAnimation.class), active = mock(BlueprintAnimation.class);
        when(model.getBlueprint().getAnimations()).thenReturn(Map.of("idle", idle, "active", active));
        when(model.getAnimationHandler().isPlayingAnimation("active")).thenReturn(true);
        YamlConfiguration config = yaml(); config.set("forward", List.of("missing", "idle", "active"));
        AnimationHandler handler = new AnimationHandler(model, new AnimationHandler(config));
        handler.stop(Animation.FORWARD); verify(model.getAnimationHandler()).stopAnimation("active");
        verify(model.getAnimationHandler(), never()).stopAnimation("missing"); verify(model.getAnimationHandler(), never()).stopAnimation("idle");
    }

    @Test void animationCommandsHonorConfiguredListsIndicesCurrentPlaybackAndSpeed() {
        keep(mockStatic(ModelEngineAPI.class, RETURNS_DEEP_STUBS));
        ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS); ModelBlueprint blueprint = mock(ModelBlueprint.class);
        when(model.getBlueprint()).thenReturn(blueprint); Map<String, BlueprintAnimation> clips = new HashMap<>();
        Map<String, IAnimationProperty> playing = new HashMap<>(); when(blueprint.getAnimations()).thenReturn(clips);
        YamlConfiguration config = yaml();
        for (Animation animation : Animation.values()) {
            String name = animation.name().toLowerCase(); BlueprintAnimation clip = new BlueprintAnimation(blueprint, name); clip.setLength(1);
            clip.setLoopMode(BlueprintAnimation.LoopMode.LOOP); clips.put(name, clip); config.set(name, List.of(name));
        }
        var engine = model.getAnimationHandler();
        when(engine.isPlayingAnimation(anyString())).thenAnswer(c -> playing.containsKey(c.getArgument(0)));
        when(engine.getAnimation(anyString())).thenAnswer(c -> playing.get(c.getArgument(0)));
        when(engine.playAnimation(any(IAnimationProperty.class), eq(true))).thenAnswer(c -> { IAnimationProperty property = c.getArgument(0); playing.put(property.getName(), property); return true; });
        doAnswer(c -> { playing.remove(c.getArgument(0)); return null; }).when(engine).stopAnimation(anyString());
        AnimationHandler template = new AnimationHandler(config), handler = new AnimationHandler(model, template);
        assertEquals(List.of("forward"), handler.get(Animation.FORWARD)); assertTrue(handler.hasModel());
        for (Animation animation : Animation.values()) handler.animate(animation);
        assertTrue(playing.containsKey("die")); handler.animate("die"); handler.animate("missing");
        verify(engine, times(Animation.values().length)).playAnimation(any(IAnimationProperty.class), eq(true));
        handler.animate(Animation.FORWARD, 5); handler.animate(Animation.FORWARD, 0); assertTrue(playing.containsKey("forward"));
        IAnimationProperty forward = mock(IAnimationProperty.class); playing.put("forward", forward);
        handler.setSpeed(Animation.FORWARD, 2); verify(forward).setSpeed(2); playing.remove("forward"); handler.setSpeed(Animation.FORWARD, 3);
        handler.stopAllAnimations(); assertTrue(playing.isEmpty());
        AnimationHandler unbound = new AnimationHandler(); unbound.setSpeed(Animation.FORWARD, 1); unbound.animateWheels(Direction.FORWARD, 1);
        assertFalse(unbound.hasModel()); handler.updateModel(model); assertTrue(handler.hasModel());
    }

    @Test void wheelAnimationSkipsMissingClipsAndStartsTheRemainingConfiguredLoop() {
        keep(mockStatic(ModelEngineAPI.class, RETURNS_DEEP_STUBS));
        ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS); ModelBlueprint blueprint = mock(ModelBlueprint.class);
        when(model.getBlueprint()).thenReturn(blueprint); BlueprintAnimation clip = new BlueprintAnimation(blueprint, "wheels");
        clip.setLength(2); clip.setLoopMode(BlueprintAnimation.LoopMode.LOOP); when(blueprint.getAnimations()).thenReturn(Map.of("wheels", clip));
        when(model.getAnimationHandler().getAnimation(anyString())).thenReturn(null);
        YamlConfiguration config = yaml(); config.set("forward", List.of("missing", "wheels"));
        AnimationHandler handler = new AnimationHandler(model, new AnimationHandler(config)); handler.animateWheels(Direction.FORWARD, 1);
        ArgumentCaptor<IAnimationProperty> started = ArgumentCaptor.forClass(IAnimationProperty.class);
        verify(model.getAnimationHandler()).playAnimation(started.capture(), eq(true)); assertEquals("wheels", started.getValue().getName());
        assertEquals(2, started.getValue().getSpeed());
    }

    @Test void terrainSettingsUseDefaultsAndCopyConfiguredGroundProbes() {
        TerrainFollowConfig defaults = TerrainFollowConfig.from(null); assertFalse(defaults.isEnabled());
        assertEquals(1, defaults.getStepHeight()); assertEquals(.25, defaults.getSnapSpeed()); assertEquals(3, defaults.getClimbLeadTicks());
        assertEquals(1, defaults.getClimbLeadFactor()); assertEquals(.08, defaults.getAirGravity()); assertEquals(.98, defaults.getAirDrag());
        assertFalse(defaults.hasGroundProbes());
        List<String> probes = new ArrayList<>(List.of("front"));
        TerrainFollowConfig custom = new TerrainFollowConfig(true, 2, .4, 4, 2, .1, .9, probes); probes.add("back");
        assertEquals(List.of("front"), custom.getGroundProbes()); assertTrue(custom.hasGroundProbes());
        assertThrows(UnsupportedOperationException.class, () -> custom.getGroundProbes().add("side"));
        assertTrue(new TerrainFollowConfig(true, 1, 1, 1, 1, 1, 1, null).getGroundProbes().isEmpty());
    }

    @Test void configuredStatesRetainInputsAndBoundsAndFillMissingDefaults() {
        Water f = new Water(); YamlConfiguration config = yaml();
        config.set("ground.keybinds.W", "lights"); config.set("ground.animations.default", List.of());
        config.set("ground.climb", 1); config.set("ground.break", true); config.set("ground.terrain-follow", true);
        config.set("ground.ground-probes", List.of("left", "right"));
        config.set("ground.switch-parameters.velocity.x", "-1,1"); config.set("ground.switch-parameters.velocity.y", "-2,2");
        config.set("ground.switch-parameters.velocity.z", "-3,3"); config.set("ground.switch-parameters.rotations.yaw", "-90,90");
        config.set("ground.switch-parameters.rotations.pitch", "-45,45"); config.set("ground.switch-parameters.rotations.roll", "-30,30");
        StateHandler template = new StateHandler(config); StateHandler handler = new StateHandler(f.vehicle, template);
        assertFalse(template.hasState(State.FLOATING)); assertNull(template.getVehicleState(State.FLOATING));
        handler.setState(State.GROUND); VehicleState current = handler.getCurrentState();
        assertEquals(State.GROUND, current.getType()); assertFalse(current.isDefault()); assertTrue(current.isBreakState());
        assertTrue(current.getClimbHandler().canClimb()); assertTrue(current.getTerrainFollow().isEnabled());
        assertEquals(List.of("left", "right"), current.getTerrainFollow().getGroundProbes());
        assertEquals(6, current.getSwitchParameter().getParameters().size());
        Parameter bounds = current.getSwitchParameter().getParameters().get("vX");
        assertEquals(-1, bounds.getMin()); assertEquals(1, bounds.getMax()); assertTrue(bounds.isWithin(-1)); assertFalse(bounds.isWithin(2));
        assertFalse(new Parameter(-1, 1).isWithin(-2));
        Player pilot = mock(Player.class); handler.key(pilot, Keybind.W); verify(f.vehicle).toggleLights(pilot);
        assertSame(current.getAnimationHandler(), handler.getAnimationHandler()); assertSame(current.getMoveControls(), handler.getMoveControls());
        assertTrue(handler.getVehicleState(State.FLYING).isDefault()); assertFalse(handler.getVehicleState(State.FLYING).hasSwitchParameter());
        handler.updateModel(mock(ActiveModel.class)); assertTrue(current.getAnimationHandler().hasModel());
        when(f.vehicle.getParameterValue("vY")).thenReturn(3d); handler.setState(State.GROUND); verify(f.vehicle).kill(VehicleDeath.EXPLODE);
        when(f.vehicle.isDestroyed()).thenReturn(true); handler.setState(State.FLYING); assertSame(current, handler.getCurrentState());
        when(f.vehicle.isDestroyed()).thenReturn(false); handler.setState(null); assertSame(current, handler.getCurrentState());
    }

    @Test void stateTicksSelectDeepWaterAirAndGroundAndBrakeConfiguredGroundState() {
        Water f = new Water(); YamlConfiguration config = yaml(); config.createSection("floating"); config.createSection("flying"); config.set("ground.break", true);
        StateHandler handler = new StateHandler(f.vehicle, new StateHandler(config));
        when(f.vehicle.getStateHandler()).thenReturn(handler);
        f.grid.fill = p -> p.y <= 66 ? Material.WATER : Material.AIR;
        handler.tick(); assertEquals(State.FLOATING, handler.getCurrentState().getType());
        handler.tick(); assertEquals(State.FLOATING, handler.getCurrentState().getType());
        f.grid.fill = p -> Material.AIR; handler.tick(); assertEquals(State.FLYING, handler.getCurrentState().getType());
        f.grid.fill = p -> p.y < 64 ? Material.STONE : Material.AIR;
        handler.tick(); assertEquals(State.GROUND, handler.getCurrentState().getType()); verify(f.vehicle).applyBreakBraking();
        f.grid.fill = p -> p.y == 64 ? Material.WATER : p.y < 64 ? Material.STONE : Material.AIR;
        handler.tick(); assertEquals(State.GROUND, handler.getCurrentState().getType());
    }

    @Test void shallowWideWaterMayFloatWhenTheSampledWaterHasDepthAwayFromTheCentre() {
        Water f = new Water(); YamlConfiguration config = yaml(); config.createSection("floating");
        StateHandler handler = new StateHandler(f.vehicle, new StateHandler(config));
        f.grid.fill = p -> p.y <= 64 && (p.x != 0 || p.z != 0) ? Material.WATER : Material.AIR;
        handler.tick(); assertEquals(State.FLOATING, handler.getCurrentState().getType());
    }

    @Test void climbingRequiresMotionSupportAndHeadroomAndMayUseHarnessAnimals() {
        Water f = new Water(); YamlConfiguration config = yaml(); config.set("climb", 1); ClimbHandler climb = new ClimbHandler(config);
        assertFalse(new ClimbHandler().canClimb()); assertFalse(climb.shouldClimb(f.vehicle));
        when(f.vehicle.getAccessPanel().getSpeed()).thenReturn(.1); assertFalse(new ClimbHandler().shouldClimb(f.vehicle));
        assertFalse(climb.shouldClimb(f.vehicle));
        f.grid.fill = p -> p.y == 63 || p.equals(new Cell(1, 64, 0)) ? Material.STONE : Material.AIR;
        assertTrue(climb.shouldClimb(f.vehicle));
        f.grid.fill = p -> p.y == 63 || (p.x == 1 && p.z == 0 && p.y >= 64 && p.y <= 65) ? Material.STONE : Material.AIR;
        assertFalse(climb.shouldClimb(f.vehicle));
        LivingEntity horse = mock(LivingEntity.class); when(horse.getWorld()).thenReturn(f.grid.world);
        when(horse.getBoundingBox()).thenReturn(new BoundingBox(4, 64, 0, 5, 65, 1));
        Harness harness = mock(Harness.class); when(harness.getMountedEntities()).thenReturn(List.of(horse));
        when(f.vehicle.hasComponent(Component.HARNESS)).thenReturn(true); when(f.vehicle.getComponent(Component.HARNESS)).thenReturn(harness);
        f.grid.fill = p -> p.y == 63 || p.equals(new Cell(5, 65, 0)) ? Material.STONE : Material.AIR;
        assertTrue(climb.shouldClimb(f.vehicle));
    }

    @Test void scoreboardReportsComponentsWeaponAndOrderedLinesThenRemovesOnlyItsObjective() {
        ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS); Player viewer = mock(Player.class);
        when(vehicle.getName()).thenReturn("River boat"); when(vehicle.getId()).thenReturn("river_boat");
        Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS); Pump pump = mock(Pump.class, RETURNS_DEEP_STUBS);
        GearedEngine geared = mock(GearedEngine.class, RETURNS_DEEP_STUBS); SinkableHull hull = mock(SinkableHull.class, RETURNS_DEEP_STUBS);
        List<VehicleComponent> components = List.of(engine, pump, geared, hull); when(vehicle.getComponents()).thenReturn(components);
        for (VehicleComponent part : components) { when(part.getAlias()).thenReturn(part.getClass().getSimpleName()); when(part.getHealthData().getHealthPercentageString()).thenReturn("HP 75%"); }
        when(engine.isOnFire()).thenReturn(true); when(engine.getFire().getFireString()).thenReturn("Fire 10%");
        when(vehicle.getComponent(Component.ENGINE)).thenReturn(engine); when(vehicle.getComponent(Component.PUMP)).thenReturn(pump);
        when(vehicle.getComponent(Component.GEARED_ENGINE)).thenReturn(geared); when(vehicle.getComponent(Component.HULL)).thenReturn(hull);
        when(engine.getThrottle().getName()).thenReturn("Throttle"); when(engine.getThrottle().getCurrent()).thenReturn(25);
        when(engine.getFuelTank().useFuel()).thenReturn(true); when(engine.getFuelTank().getPercentage()).thenReturn(80);
        when(pump.getPower()).thenReturn(3); when(geared.getGear().getName()).thenReturn("First");
        when(geared.getGear().getThrottle().getName()).thenReturn("Power"); when(geared.getGear().getThrottle().getCurrent()).thenReturn(40);
        when(geared.getFuelTank().getPercentage()).thenReturn(60); when(vehicle.hasComponent(Component.HULL)).thenReturn(true);
        when(hull.hasSinkProgress()).thenReturn(true); when(hull.getSinkProgress()).thenReturn(15);
        when(vehicle.isPassenger(viewer, false)).thenReturn(true); when(vehicle.getSeat(viewer).hasWeapon()).thenReturn(true);
        ActiveWeapon gun = vehicle.getSeat(viewer).getWeapon(); when(gun.getName()).thenReturn("Cannon");
        when(gun.getAmmunitionHandler().hasAmmo()).thenReturn(true); when(gun.getAmmunitionHandler().getCount()).thenReturn(4);
        when(gun.getAmmunitionHandler().getAmmo().getName()).thenReturn("Shell");
        Scoreboard board = mock(Scoreboard.class); Objective objective = mock(Objective.class); ScoreboardManager manager = mock(ScoreboardManager.class);
        keep(mockStatic(Bukkit.class)).when(Bukkit::getScoreboardManager).thenReturn(manager);
        when(manager.getNewScoreboard()).thenReturn(board); when(board.registerNewObjective("vehicleDummy", Criteria.DUMMY, "River boat")).thenReturn(objective);
        Map<String, Integer> lines = new LinkedHashMap<>();
        when(objective.getScore(anyString())).thenAnswer(call -> { String line = call.getArgument(0); Score score = mock(Score.class);
            doAnswer(set -> { lines.put(line, set.getArgument(0)); return null; }).when(score).setScore(anyInt()); return score; });
        ScoreboardController controller = new ScoreboardController(vehicle); controller.scoreboard(viewer);
        assertTrue(lines.containsKey("§fType: §eRiver Boat")); assertEquals(0, lines.get("Shell §fx4"));
        assertTrue(lines.containsKey("§f- Fuel: §e80%")); assertTrue(lines.containsKey("§f- Power: §e3"));
        assertTrue(lines.containsKey("§f- Gear: §eFirst")); assertTrue(lines.containsKey("§9SINKING!! §b15% §1"));
        verify(objective).setDisplaySlot(DisplaySlot.SIDEBAR); verify(viewer).setScoreboard(board);
        when(vehicle.isLocomotive()).thenReturn(true);
        when(vehicle.getTrainHandler().getOverdrive().cooldownSeconds(anyLong())).thenReturn(9L); lines.clear(); controller.scoreboard(viewer);
        assertTrue(lines.containsKey("§f- Overdrive cooldown: §e9s"));
        when(vehicle.getTrainHandler().getOverdrive().cooldownSeconds(anyLong())).thenReturn(0L);
        when(vehicle.getTrainHandler().getOverdrive().remainingSeconds()).thenReturn(5L); lines.clear(); controller.scoreboard(viewer);
        assertTrue(lines.containsKey("§f- Overdrive left: §e5s"));
        when(vehicle.getTrainHandler().getOverdrive().remainingSeconds()).thenReturn(0L); lines.clear(); controller.scoreboard(viewer);
        assertTrue(lines.containsKey("§f- Overdrive: §aReady (W > 100%)"));
        when(viewer.getScoreboard()).thenReturn(board); controller.removeScoreboard(viewer);
        when(board.getObjective("vehicleDummy")).thenReturn(objective); controller.removeScoreboard(viewer); verify(objective).unregister();
    }

    private static YamlConfiguration yaml() { return new YamlConfiguration(); }
    private record Cell(int x, int y, int z) {}
    private static final class Grid {
        final World world = mock(World.class); final Map<Cell, Block> blocks = new HashMap<>();
        Function<Cell, Material> fill = p -> Material.AIR;
        Grid() {
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(c -> block(c.getArgument(0), c.getArgument(1), c.getArgument(2)));
            when(world.getBlockAt(any(Location.class))).thenAnswer(c -> { Location p = c.getArgument(0); return block(p.getBlockX(), p.getBlockY(), p.getBlockZ()); });
        }
        Block block(int x, int y, int z) {
            Cell cell = new Cell(x, y, z); return blocks.computeIfAbsent(cell, p -> {
                Block b = mock(Block.class); when(b.getLocation()).thenReturn(new Location(world, x, y, z));
                when(b.getWorld()).thenReturn(world); when(b.getType()).thenAnswer(c -> fill.apply(p));
                when(b.isLiquid()).thenAnswer(c -> fill.apply(p) == Material.WATER || fill.apply(p) == Material.LAVA);
                when(b.isPassable()).thenAnswer(c -> !fill.apply(p).isSolid());
                when(b.getRelative(any(BlockFace.class))).thenAnswer(c -> { BlockFace face = c.getArgument(0); return block(x + face.getModX(), y + face.getModY(), z + face.getModZ()); });
                when(b.getRelative(anyInt(), anyInt(), anyInt())).thenAnswer(c -> block(x + (int)c.getArgument(0), y + (int)c.getArgument(1), z + (int)c.getArgument(2)));
                return b;
            });
        }
    }
    private static final class Water {
        final Grid grid = new Grid(); final ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
        final LivingEntity entity = mock(LivingEntity.class); final Location location = new Location(grid.world, .5, 64, .5);
        Water() { when(vehicle.shouldFloat()).thenReturn(true); bind(entity); when(vehicle.getComponents()).thenReturn(List.of()); }
        void bind(LivingEntity body) {
            when(vehicle.getEntity()).thenReturn(body); when(body.isValid()).thenReturn(true); when(body.getWorld()).thenReturn(grid.world);
            when(body.getLocation()).thenAnswer(c -> location.clone()); when(body.getVelocity()).thenReturn(new Vector());
            when(body.getBoundingBox()).thenReturn(new BoundingBox(.2, 64, .2, .8, 65, .8));
        }
    }
}
