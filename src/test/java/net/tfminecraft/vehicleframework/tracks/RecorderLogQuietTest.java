package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.vehicleframework.bones.ConvertedAngle;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;

/** Trains log every tick. With the log off nothing is looked at; with it on, the lines are as before. */
class RecorderLogQuietTest {
	@TempDir Path folder;

	@AfterEach
	void off() {
		RecorderLog.configure(false, folder.toFile());
	}

	@Test
	void anOffLogDoesNotLookAtTheTrain() {
		RecorderLog.configure(false, folder.toFile());
		ActiveVehicle train = mock(ActiveVehicle.class);
		Location from = mock(Location.class);
		Location to = mock(Location.class);
		TrackPose pose = new TrackPose(0, 64, 3, 90, 5);
		ThrottleTape.DwellState dwell = new ThrottleTape.DwellState();
		TrackJunction frog = new TrackJunction(UUID.randomUUID(), UUID.randomUUID(), 5, 1, TrackJunction.Side.LEFT, null);

		RecorderLog.pose(train, from, to, pose, new ConvertedAngle(90, 5, 0));
		RecorderLog.sample(train, ThrottleTape.AppendResult.ADDED, 35, 1);
		RecorderLog.playback(train, "ok", 35, dwell);
		RecorderLog.interact("save", "samples=1");
		RecorderLog.arm(train, "armed", TrackJunction.Side.LEFT, frog, 5);
		RecorderLog.junction(train, true, frog.id);
		RecorderLog.junction(train, false, frog.id, "held left");

		verifyNoInteractions(train, from, to);
		assertFalse(Files.exists(folder.resolve("logs/recorder.log")));
	}

	@Test
	void anOnLogWritesTheSameLinesAsBefore() throws Exception {
		RecorderLog.configure(true, folder.toFile());
		ActiveVehicle train = train("golden-recorder-" + UUID.randomUUID());
		World world = mock(World.class);
		Location from = new Location(world, 0, 64, 0);
		Location to = new Location(world, 0, 64, 3);
		ThrottleTape.DwellState dwell = new ThrottleTape.DwellState();
		dwell.left = 3;
		dwell.atS = 12.5;
		TrackJunction frog = new TrackJunction(UUID.fromString("00000000-0000-0000-0000-00000000000f"),
				UUID.randomUUID(), 5, 1, TrackJunction.Side.LEFT, null);

		RecorderLog.pose(train, from, to, new TrackPose(0, 64, 3, 90, 5), new ConvertedAngle(90, 5, 0));
		RecorderLog.pose(train, from, to, null, null);
		RecorderLog.sample(train, ThrottleTape.AppendResult.HELD, 35, 20);
		RecorderLog.playback(train, "waiting", 0, dwell);
		RecorderLog.interact("save", "samples=1");
		RecorderLog.arm(train, "armed", TrackJunction.Side.LEFT, frog, 5);
		RecorderLog.junction(train, true, frog.id, "held left");

		String state = "uuid=" + train.getUUID() + " id=engine s=12.500 sign=-1 thr=35 eyaw=45.000 dyaw=-"
				+ " bound=true spline=null tape=0 recording=true recSamples=3 captain=false";
		assertEquals(List.of(
				"POSE gap=3.000 myaw=90.000 mpitch=5.000 pyaw=90.000 ppitch=5.000 " + state,
				"SAMPLE result=HELD thr=35 hold=20 " + state,
				"PLAY reason=waiting target=0 dwellLeft=3 dwellS=12.500 " + state,
				"INTERACT save samples=1",
				"ARM status=armed hold=LEFT frogSide=LEFT frogS=5.000 ahead=5.000 facing=1 " + state,
				"JUNCTION take=diverge id=00000000-0000-0000-0000-00000000000f reason=held left " + state),
				lines());
	}

	private ActiveVehicle train(String uuid) {
		ActiveVehicle train = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
		TrainHandler handler = mock(TrainHandler.class);
		Entity entity = mock(Entity.class);
		when(entity.getLocation()).thenReturn(new Location(null, 0, 0, 0, 45, 0));
		when(train.getUUID()).thenReturn(uuid);
		when(train.getId()).thenReturn("engine");
		when(train.isTrain()).thenReturn(true);
		when(train.getTrainHandler()).thenReturn(handler);
		when(train.getEntity()).thenReturn(entity);
		when(train.getThrottle().getCurrent()).thenReturn(35);
		when(train.getBehaviourHandler()).thenReturn(null);
		when(train.getSeatHandler()).thenReturn(null);
		when(handler.getS()).thenReturn(12.5);
		when(handler.getTravelSign()).thenReturn(-1);
		when(handler.isBound()).thenReturn(true);
		when(handler.isRecording()).thenReturn(true);
		when(handler.recordingSampleCount()).thenReturn(3);
		return train;
	}

	// Every line but the SESSION header, without its timestamp.
	private List<String> lines() throws Exception {
		return Files.readAllLines(folder.resolve("logs/recorder.log")).stream()
				.map(line -> line.substring("yyyy-MM-dd HH:mm:ss ".length()))
				.filter(line -> !line.startsWith("SESSION "))
				.toList();
	}
}
