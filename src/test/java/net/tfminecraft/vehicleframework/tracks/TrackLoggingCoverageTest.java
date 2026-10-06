package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import net.tfminecraft.vehicleframework.bones.ConvertedAngle;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrackLoggingCoverageTest {
  @TempDir Path folder;

  @Test
  void trackLogStartsWithoutAFolderThenWritesEveryOperationAndWipesOnRequest() throws Exception {
    try (FreshLogger log = new FreshLogger(TrackLog.class)) {
      log.call("configure", true, true, null);
      log.call("append", "no destination");
      log.call("configure", false, false, folder.toFile());
      log.call("append", "disabled");
      assertFalse(Files.exists(folder.resolve("logs/track.log")));
      log.call("configure", true, false, folder.toFile());
      log.call("append", (Object) null);
      TrackSpline spline = line(), tail = line();
      UUID id = spline.getId();
      log.call("start", "alex", 1d, 64d, 2d);
      log.call("layAttempt", "world", 0d, 64d, 0d, 0d, 64d, 10d, true, false);
      log.call("layOk", null, "none");
      log.call("layOk", spline, "straight");
      log.call("layFail", "too short", null);
      log.call("layFail", "shape", new TrackLayException("shape"));
      log.call("layFail", "blocked", new TrackLayException("blocked", 1, 64, 2));
      log.call("dig", "alex", null);
      log.call("dig", "alex", DigResult.none());
      log.call("dig", "alex", DigResult.deleted(id));
      log.call("dig", "alex", DigResult.updated(spline));
      log.call("dig", "alex", DigResult.split(spline, tail));
      log.call("delete", "alex", id, true);
      log.call("spline", id, "world", false, 10d, 2, 0d, 64d, 0d, 0d, 64d, 10d);
      log.call("pt", id, 5d, 0d, 64d, 5d);
      log.call(
          "junction",
          id,
          id,
          2d,
          0d,
          64d,
          2d,
          true,
          "LEFT",
          1,
          tail.getId(),
          10d,
          1d,
          10d,
          true,
          false);
      log.call(
          "junction", id, id, 2d, 0d, 64d, 2d, false, "RIGHT", -1, null, 0d, 0d, 0d, false, true);
      log.call("junctionDrop", id, id, "missing");
      log.call("junctionStart", "alex", id, 2d);
      log.call("junctionBranch", null, id, id);
      log.call("junctionBranch", tail, id, id);
      String text = Files.readString(folder.resolve("logs/track.log"));
      for (String marker :
          List.of(
              "START player=alex 1.000,64.000,2.000",
              "LAY world=world",
              "LAY_OK how=straight",
              "LAY_FAIL blocked block=1,64,2",
              "kind=NONE",
              "kind=DELETED",
              "kind=UPDATED",
              "kind=SPLIT",
              "DELETE player=alex",
              "SPLINE id=",
              "PT spline=",
              "frog=-",
              "branchLen=-",
              "JUNCTION_DROP",
              "JUNCTION_START",
              "JUNCTION_BRANCH")) assertTrue(text.contains(marker), marker);
      assertFalse(text.contains("disabled"));
      log.call("configure", true, true, folder.toFile());
      assertFalse(Files.exists(folder.resolve("logs/track.log")));
      log.call("append", "after wipe");
      assertTrue(Files.readString(folder.resolve("logs/track.log")).contains("after wipe"));
    }
  }

  @Test
  void trackLogReportsWriteAndWipeFailuresWithoutThrowing() throws Exception {
    Path destination = folder.resolve("logs/track.log");
    Files.createDirectories(destination);
    Files.writeString(destination.resolve("occupied"), "retain");
    List<String> warnings = new ArrayList<>();
    Logger logger = Logger.getLogger(TrackLog.class.getName());
    Handler handler = capture(warnings);
    logger.addHandler(handler);
    try (FreshLogger log = new FreshLogger(TrackLog.class)) {
      log.call("configure", true, true, folder.toFile());
      log.call("append", "cannot write over a directory");
      assertTrue(Files.exists(destination.resolve("occupied")));
      assertTrue(warnings.stream().anyMatch(s -> s.startsWith("Failed to wipe track.log:")));
      assertTrue(warnings.stream().anyMatch(s -> s.startsWith("Failed to write track.log:")));
    } finally {
      logger.removeHandler(handler);
    }
  }

  @Test
  void recorderDescribesAvailableTrainStateAndThrottlesRepeatedEvents() throws Exception {
    try (FreshLogger log = new FreshLogger(RecorderLog.class);
        Rig r = new Rig()) {
      log.call("configure", true, null);
      log.call("append", "unconfigured");
      log.call("configure", false, folder.toFile());
      assertEquals(false, log.call("isEnabled"));
      log.call("append", "disabled");
      log.call("configure", true, folder.toFile());
      assertEquals(true, log.call("isEnabled"));
      log.call("append", (Object) null);
      assertEquals("train=null", log.call("train", (Object) null));
      ActiveVehicle vehicle = r.vehicle();
      assertEquals("train=null", log.call("train", vehicle));
      when(vehicle.isTrain()).thenReturn(true);
      when(vehicle.getId()).thenReturn("wagon");
      when(vehicle.getTrainHandler().getS()).thenReturn(12.5);
      when(vehicle.getTrainHandler().getTravelSign()).thenReturn(-1);
      when(vehicle.getTrainHandler().getInstalledTape()).thenReturn(null);
      when(vehicle.getThrottle()).thenReturn(null);
      when(vehicle.getBehaviourHandler()).thenReturn(null);
      when(vehicle.getSeatHandler()).thenReturn(null);
      String sparse = (String) log.call("train", vehicle);
      assertTrue(sparse.contains("thr=0"));
      assertTrue(sparse.contains("dyaw=-"));
      assertTrue(sparse.contains("tape=0"));
      assertTrue(sparse.contains("captain=false"));
      ActiveVehicle full = r.vehicle();
      when(full.isTrain()).thenReturn(true);
      when(full.getId()).thenReturn("engine");
      when(full.getThrottle().getCurrent()).thenReturn(35);
      when(full.getTrainHandler().getInstalledTape())
          .thenReturn(new ThrottleTape("line", List.of(new ThrottleTape.Sample(2, 1, 35))));
      when(full.getTrainHandler().recordingSampleCount()).thenReturn(3);
      when(full.getTrainHandler().isRecording()).thenReturn(true);
      when(full.getTrainHandler().isBound()).thenReturn(true);
      when(full.getBehaviourHandler().getRotator().getDriveYaw()).thenReturn(45f);
      when(full.getSeatHandler().hasCaptain()).thenReturn(true);
      String rich = (String) log.call("train", full);
      assertTrue(rich.contains("thr=35"));
      assertTrue(rich.contains("dyaw=45.000"));
      assertTrue(rich.contains("tape=1"));
      assertTrue(rich.contains("recSamples=3"));
      assertTrue(rich.contains("captain=true"));
      when(vehicle.getEntity()).thenReturn(null);
      assertTrue(((String) log.call("train", vehicle)).contains("eyaw=-"));
      Location from = new Location(r.world, 0, 64, 0), to = new Location(r.world, 0, 64, 3);
      log.call("pose", null, from, to, null, null);
      log.call(
          "pose", full, from, to, new TrackPose(0, 64, 3, 90, 5), new ConvertedAngle(90, 5, 0));
      log.call("pose", full, from, to, null, null);
      log.call("pose", vehicle, null, null, null, null);
      log.call("sample", full, ThrottleTape.AppendResult.HELD, 35, 2);
      log.call("sample", full, ThrottleTape.AppendResult.HELD, 35, 20);
      log.call("sample", full, ThrottleTape.AppendResult.ADDED, 35, 1);
      log.call("playback", full, "moving", 35, null);
      log.call("playback", full, "moving", 35, null);
      ThrottleTape.DwellState dwell = new ThrottleTape.DwellState();
      dwell.left = 3;
      dwell.atS = 12.5;
      log.call("playback", null, "waiting", 0, dwell);
      dwell.atS = null;
      log.call("playback", null, "no-position", 0, dwell);
      log.call("interact", "start", null);
      log.call("interact", "stop", " ");
      log.call("interact", "save", "samples=1");
      log.call("arm", full, "none", null, null, 0d);
      TrackJunction frog =
          new TrackJunction(
              UUID.randomUUID(), UUID.randomUUID(), 5, 1, TrackJunction.Side.LEFT, null);
      log.call("arm", full, "armed", TrackJunction.Side.LEFT, frog, 5d);
      log.call("junction", full, false, frog.id);
      log.call("junction", full, true, frog.id, "held left");
      log.call("junction", full, false, frog.id, (String) null);
      assertEquals(true, log.call("throttle", "probe", 100000L));
      assertEquals(false, log.call("throttle", "probe", 100000L));
      assertEquals(true, log.call("throttle", "probe", 0L));
      String text = Files.readString(folder.resolve("logs/recorder.log"));
      for (String marker :
          List.of(
              "SESSION plugin-folder=",
              "POSE gap=3.000 myaw=90.000",
              "POSE gap=0.000 myaw=-",
              "SAMPLE result=HELD thr=35 hold=20",
              "SAMPLE result=ADDED",
              "PLAY reason=moving",
              "dwellLeft=3 dwellS=12.500",
              "INTERACT save samples=1",
              "ARM status=armed hold=LEFT frogSide=LEFT",
              "JUNCTION take=through",
              "JUNCTION take=diverge",
              "reason=held left")) assertTrue(text.contains(marker), marker);
      assertFalse(text.contains("hold=2 "));
      assertFalse(text.contains("disabled"));
    }
  }

  @Test
  void recorderReportsFilesystemFailures() throws Exception {
    Files.writeString(folder.resolve("logs"), "not a directory");
    List<String> warnings = new ArrayList<>();
    Logger logger = Logger.getLogger(RecorderLog.class.getName());
    Handler handler = capture(warnings);
    logger.addHandler(handler);
    try (FreshLogger log = new FreshLogger(RecorderLog.class)) {
      log.call("configure", true, folder.toFile());
      assertTrue(warnings.stream().anyMatch(s -> s.startsWith("Failed to write recorder.log:")));
      assertEquals("not a directory", Files.readString(folder.resolve("logs")));
    } finally {
      logger.removeHandler(handler);
    }
  }

  private static TrackSpline line() {
    return TrackSpline.fromPoints(
        UUID.randomUUID(),
        "world",
        false,
        List.of(new double[] {0, 64, 0}, new double[] {0, 64, 10}));
  }

  private static Handler capture(List<String> messages) {
    return new Handler() {
      public void publish(LogRecord record) {
        messages.add(record.getMessage());
      }

      public void flush() {}

      public void close() {}
    };
  }

  /**
   * Each plugin classloader starts with unconfigured loggers; preserve that real startup state
   * without altering private fields.
   */
  private static final class FreshLogger implements AutoCloseable {
    private final java.net.URLClassLoader loader;
    private final Class<?> type;

    FreshLogger(Class<?> original) throws Exception {
      loader =
          new java.net.URLClassLoader(
              new java.net.URL[] {original.getProtectionDomain().getCodeSource().getLocation()},
              original.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
              if (name.equals(original.getName())) {
                synchronized (getClassLoadingLock(name)) {
                  Class<?> found = findLoadedClass(name);
                  if (found == null) found = findClass(name);
                  if (resolve) resolveClass(found);
                  return found;
                }
              }
              return super.loadClass(name, resolve);
            }
          };
      type = loader.loadClass(original.getName());
    }

    Object call(String name, Object... args) throws Exception {
      var method =
          Arrays.stream(type.getMethods())
              .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
              .findFirst()
              .orElseThrow();
      try {
        return method.invoke(null, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw new AssertionError("Logger API failed: " + name, e.getCause());
      }
    }

    public void close() throws Exception {
      loader.close();
    }
  }
}
