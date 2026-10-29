package net.tfminecraft.vehicleframework.vehicles.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.tracks.TrackRegistry;
import net.tfminecraft.vehicleframework.tracks.TrackSpline;
import net.tfminecraft.vehicleframework.tracks.TrackStore;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Connector;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Deck;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.DeckBody;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

/**
 * A train standing on its track is posed every tick. A car already still at its pose is not
 * teleported or stopped again; anything that moved it is still put back.
 */
class TrainStandingPoseTest {
	@TempDir Path directory;
	private Field registryField;
	private Object previousRegistry;
	private TrackRegistry registry;
	private TrackStore store;
	private MockedStatic<Bukkit> bukkit;
	private TrackSpline track;

	@BeforeEach
	void setUp() throws Exception {
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(mock(BlockData.class));
		registryField = VehicleFramework.class.getDeclaredField("trackRegistry");
		registryField.setAccessible(true);
		previousRegistry = registryField.get(null);
		registry = new TrackRegistry(directory.toFile());
		store = new TrackStore(directory.toFile());
		registryField.set(null, registry);
		track = TrackSpline.fromPoints(UUID.randomUUID(), "world", false,
				List.of(new double[]{0, 64, 0}, new double[]{0, 64, 100}));
		store.save(track);
		registry.loadFromDisk();
	}

	@AfterEach
	void restore() throws Exception {
		registryField.set(null, previousRegistry);
		bukkit.close();
	}

	@Test
	void standingCarsArePlacedOnceThenLeftWhereTheyStand() {
		List<Car> cars = consist(60);
		cars.get(0).handler.placeLoadedCars();
		List<Location> placed = locations(cars);
		for (int tick = 0; tick < 5; tick++) {
			cars.get(0).handler.placeLoadedCars();
		}
		for (Car car : cars) {
			verify(car.entity, times(1)).teleport(any(Location.class));
			verify(car.entity, times(1)).setVelocity(any(Vector.class));
			// Gravity is still switched off and the body still turned every tick.
			verify(car.entity, times(6)).setGravity(false);
			verify(car.rotator, times(6)).rotateToTarget(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
					anyBoolean(), anyBoolean(), anyBoolean());
		}
		assertEquals(placed, locations(cars));
	}

	@Test
	void theFirstPlacementAppliesEvenToACarAlreadyStandingThere() {
		List<Location> targets = targets(60);
		List<Car> cars = consist(60);
		for (int i = 0; i < cars.size(); i++) {
			cars.get(i).location[0] = targets.get(i).clone();
		}
		cars.get(0).handler.placeLoadedCars();
		for (Car car : cars) {
			verify(car.entity, times(1)).teleport(any(Location.class));
			verify(car.entity, times(1)).setVelocity(any(Vector.class));
		}
		assertEquals(targets, locations(cars));
	}

	@Test
	void aPushedCarSnapsBackToItsPose() {
		List<Car> cars = consist(60);
		cars.get(0).handler.placeLoadedCars();
		List<Location> placed = locations(cars);
		Car pushed = cars.get(1);
		pushed.location[0].add(0.3, 0.1, -0.2);

		cars.get(0).handler.placeLoadedCars();

		verify(pushed.entity, times(2)).teleport(any(Location.class));
		verify(cars.get(0).entity, times(1)).teleport(any(Location.class));
		assertEquals(placed, locations(cars));
		cars.get(0).handler.placeLoadedCars();
		verify(pushed.entity, times(2)).teleport(any(Location.class));
	}

	@Test
	void evenATinyPushIsPutBack() {
		List<Car> cars = consist(60);
		cars.get(0).handler.placeLoadedCars();
		Location placed = cars.get(0).location[0].clone();
		cars.get(0).location[0].add(1e-4, 0, 0);

		cars.get(0).handler.placeLoadedCars();

		verify(cars.get(0).entity, times(2)).teleport(any(Location.class));
		assertEquals(placed, cars.get(0).location[0]);
	}

	@Test
	void aCarKnockedIntoMotionIsStoppedBeforeItMoves() {
		List<Car> cars = consist(60);
		cars.get(0).handler.placeLoadedCars();
		Car hit = cars.get(0);
		hit.velocity[0] = new Vector(0.6, 0.3, 0);

		hit.handler.placeLoadedCars();

		verify(hit.entity, times(2)).teleport(any(Location.class));
		verify(hit.entity, times(2)).setVelocity(any(Vector.class));
		assertEquals(0, hit.velocity[0].lengthSquared());
	}

	@Test
	void aRespawnedEntityIsPlacedInFull() {
		List<Car> cars = consist(60);
		cars.get(0).handler.placeLoadedCars();
		Car car = cars.get(1);
		Entity respawned = entity(car.location, car.velocity);
		when(car.vehicle.getEntity()).thenReturn(respawned);

		cars.get(0).handler.placeLoadedCars();

		verify(respawned, times(1)).teleport(any(Location.class));
		verify(respawned, times(1)).setVelocity(any(Vector.class));
	}

	@Test
	void rebindingOrLoadingTheConsistPlacesEveryCarInFull() {
		List<Car> cars = consist(60);
		TrainHandler loco = cars.get(0).handler;
		loco.placeLoadedCars();
		loco.applyConsist(new ConsistData(null, null, track.getId().toString(), 60.0, 1));
		loco.placeLoadedCars();
		verify(cars.get(0).entity, times(2)).teleport(any(Location.class));

		loco.unbind();
		loco.setSplineId(track.getId());
		loco.setS(60);
		loco.placeLoadedCars();

		for (Car car : cars) {
			verify(car.entity, times(car == cars.get(0) ? 3 : 2)).teleport(any(Location.class));
		}
	}

	@Test
	void aMobCarIsAlwaysTeleported() {
		Car car = car(mock(Mob.class));
		car.handler.setSplineId(track.getId());
		car.handler.setS(60);
		for (int tick = 0; tick < 3; tick++) {
			car.handler.placeLoadedCars();
		}
		verify(car.entity, times(3)).teleport(any(Location.class));
	}

	@Test
	void theDeckIsStillPlacedEveryTick() throws Exception {
		List<Car> cars = consist(60);
		DeckBody deck = mock(DeckBody.class);
		Field field = TrainHandler.class.getDeclaredField("deckBody");
		field.setAccessible(true);
		field.set(cars.get(1).handler, deck);
		for (int tick = 0; tick < 4; tick++) {
			cars.get(0).handler.placeLoadedCars();
		}
		verify(deck, times(4)).place(any(Deck.Frame.class));
		verify(cars.get(1).entity, times(1)).teleport(any(Location.class));
	}

	@Test
	void aMovingTrainIsTeleportedEveryTick() {
		List<Car> cars = consist(60);
		TrainHandler loco = cars.get(0).handler;
		loco.placeLoadedCars();
		loco.v.getAccessPanel().setSpeed(0.4);
		List<Location> before = locations(cars);
		for (int tick = 0; tick < 3; tick++) {
			loco.splineTick();
		}
		for (Car car : cars) {
			verify(car.entity, times(4)).teleport(any(Location.class));
		}
		assertNotEquals(before, locations(cars));
		loco.v.getAccessPanel().setSpeed(0);
		loco.splineTick();
		loco.splineTick();
		for (Car car : cars) {
			verify(car.entity, times(4)).teleport(any(Location.class));
		}
	}

	@Test
	void aCarWhoseVelocityIsUnknownIsAlwaysPlaced() {
		Car car = car(null);
		when(car.entity.getVelocity()).thenReturn(null);
		car.handler.setSplineId(track.getId());
		car.handler.setS(60);
		car.handler.placeLoadedCars();
		car.handler.placeLoadedCars();
		verify(car.entity, times(2)).teleport(any(Location.class));
	}

	private List<Location> targets(double s) {
		List<Car> probe = consist(s);
		probe.get(0).handler.placeLoadedCars();
		return locations(probe);
	}

	private List<Car> consist(double s) {
		Car loco = car(null);
		Car first = car(null);
		Car second = car(null);
		loco.handler.setChild(first.vehicle);
		first.handler.setChild(second.vehicle);
		loco.handler.setSplineId(track.getId());
		loco.handler.setS(s);
		return List.of(loco, first, second);
	}

	private static List<Location> locations(List<Car> cars) {
		List<Location> locations = new ArrayList<>();
		for (Car car : cars) {
			locations.add(car.location[0].clone());
		}
		return locations;
	}

	private record Car(TrainHandler handler, ActiveVehicle vehicle, Entity entity, BoneRotator rotator,
			Location[] location, Vector[] velocity) {
	}

	private Car car(Entity base) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class);
		Location[] location = {new Location(null, 0, 64, 0)};
		Vector[] velocity = {new Vector()};
		Entity entity = base == null ? entity(location, velocity) : track(base, location, velocity);
		when(vehicle.getEntity()).thenReturn(entity);
		when(vehicle.getUUID()).thenReturn(UUID.randomUUID().toString());
		when(vehicle.getAccessPanel()).thenReturn(new AccessPanel());
		when(vehicle.getThrottle()).thenReturn(new Throttle("Throttle", 100, -100, null));
		when(vehicle.getMoveControls()).thenReturn(mock(VehicleMovementController.class));
		ActiveModel model = mock(ActiveModel.class);
		when(model.getScale()).thenReturn(new Vector3f(1));
		when(vehicle.getModel()).thenReturn(model);
		Connector front = connector(vehicle, model, "front", 5);
		Connector back = connector(vehicle, model, "back", -5);
		BoneRotator rotator = mock(BoneRotator.class);
		BehaviourHandler behaviour = mock(BehaviourHandler.class);
		when(behaviour.getRotator()).thenReturn(rotator);
		when(vehicle.getBehaviourHandler()).thenReturn(behaviour);
		TrainHandler handler = new TrainHandler(new YamlConfiguration()) {
			@Override public boolean isAttachable() { return true; }
			@Override public boolean canHaveAttached() { return true; }
			@Override public Connector getFront() { return front; }
			@Override public Connector getBack() { return back; }
		};
		handler.v = vehicle;
		when(vehicle.getTrainHandler()).thenReturn(handler);
		return new Car(handler, vehicle, entity, rotator, location, velocity);
	}

	private static Entity entity(Location[] location, Vector[] velocity) {
		return track(mock(Entity.class), location, velocity);
	}

	// Paper keeps the entity where it was last put and as fast as it was last set.
	private static <T extends Entity> T track(T entity, Location[] location, Vector[] velocity) {
		when(entity.getLocation()).thenAnswer(call -> location[0].clone());
		when(entity.getBoundingBox()).thenAnswer(call -> new BoundingBox(
				location[0].getX() - 0.5, location[0].getY(), location[0].getZ() - 0.5,
				location[0].getX() + 0.5, location[0].getY() + 2, location[0].getZ() + 0.5));
		when(entity.teleport(any(Location.class))).thenAnswer(call -> {
			location[0] = call.<Location>getArgument(0).clone();
			return true;
		});
		when(entity.getVelocity()).thenAnswer(call -> velocity[0].clone());
		org.mockito.Mockito.doAnswer(call -> {
			velocity[0] = call.<Vector>getArgument(0).clone();
			return null;
		}).when(entity).setVelocity(any(Vector.class));
		return entity;
	}

	private static Connector connector(ActiveVehicle vehicle, ActiveModel model, String name, float z) {
		ModelBone bone = mock(ModelBone.class);
		BlueprintBone blueprint = new BlueprintBone();
		blueprint.setRotatedGlobalPosition(new Vector3f(0, 0, z));
		when(bone.getBlueprintBone()).thenReturn(blueprint);
		when(model.getBone(name)).thenReturn(Optional.of(bone));
		return new Connector(vehicle, new Connector(name));
	}
}
