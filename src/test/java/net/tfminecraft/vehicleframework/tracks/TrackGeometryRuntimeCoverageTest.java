package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.json.simple.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrackGeometryRuntimeCoverageTest {
  @BeforeAll
  static void registries() {
    RegistryFixture.initialize();
  }

  @Test
  void reverseTravelStopsAtTheFirstBrokenEdgeBeforeWrapping() {
    TrackSpline loop = square();
    loop =
        new TrackSpline(
            loop.getId(),
            "world",
            true,
            loop.getSamples(),
            List.of(
                new TrackSegment(0, true, 0),
                new TrackSegment(1, false, 1),
                new TrackSegment(2, false, 1),
                new TrackSegment(3, true, 0)));
    TrackAdvance stop = loop.advance(25, -35);
    assertTrue(stop.stoppedAtBreak);
    assertEquals(
        10,
        stop.s,
        1e-9,
        "Travelling backwards reaches edge zero before wrapping to the final edge");
  }

  @Test
  void junctionSideIdentifiersAreIndependentOfLocale() {
    JSONObject json =
        new TrackJunction(
                UUID.randomUUID(), UUID.randomUUID(), 2, 1, TrackJunction.Side.RIGHT, null)
            .toJson();
    json.put("side", "right");
    Locale old = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals(TrackJunction.Side.RIGHT, TrackJunction.fromJson(json).side);
    } finally {
      Locale.setDefault(old);
    }
  }

  @Test
  void splineConfigurationValidatesSamplesAndRecoversLegacyJsonValues() {
    assertThrows(
        IllegalArgumentException.class, () -> new TrackSpline(null, null, false, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new TrackSpline(null, null, false, List.of(), null));
    assertThrows(IllegalArgumentException.class, () -> TrackSpline.fromJson(null));
    assertThrows(IllegalArgumentException.class, () -> TrackSpline.fromJson(new JSONObject()));
    assertFalse(TrackSpline.shouldLoop(null, 1));
    assertFalse(TrackSpline.shouldLoop(List.of(), 1));
    assertFalse(TrackSpline.shouldLoop(points(0, 0, 1, 0, 0, 0), -1));
    TrackSpline stored = TrackSpline.fromPoints(null, null, false, points(0, 0, 10, 0, 20, 0));
    assertEquals("", stored.getWorld());
    TrackSpline trimmed =
        new TrackSpline(
            stored.getId(),
            "world",
            false,
            stored.getSamples(),
            List.of(
                new TrackSegment(0, false, 1),
                new TrackSegment(1, false, 1),
                new TrackSegment(2, true, 0)));
    assertEquals(2, trimmed.getSegments().size());
    assertFalse(trimmed.segment(-1).broken);
    assertEquals(-1, trimmed.segment(-1).fromIndex);
    assertFalse(trimmed.segment(100).broken);
    JSONObject json = stored.toJson();
    json.remove("id");
    JSONArray rows = (JSONArray) json.get("samples");
    ((JSONObject) rows.get(0)).remove("s");
    ((JSONObject) rows.get(0)).remove("x");
    ((JSONObject) rows.get(1)).put("x", "10.0");
    TrackSpline loaded = TrackSpline.fromJson(json);
    assertNotNull(loaded.getId());
    assertEquals(10, loaded.getSamples().get(1).s);
    assertEquals(20, loaded.length());
    json.put("id", "invalid uuid");
    assertNotNull(TrackSpline.fromJson(json).getId());
    TrackSpline point = TrackSpline.fromPoints(null, "world", false, points(2, 3, 2, 3));
    TrackPose pose = point.sampleAt(999);
    assertEquals(2, pose.x);
    assertEquals(3, pose.z);
    assertEquals(0, point.advance(99, 10).s);
    assertEquals(0, point.nearestS(4, 64, 5));
  }

  @Test
  void junctionJsonHandlesOptionalBranchesAndRejectsMissingIdentity() {
    UUID id = UUID.randomUUID(), stem = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrackJunction(null, stem, 0, 1, TrackJunction.Side.LEFT, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrackJunction(id, null, 0, 1, TrackJunction.Side.LEFT, null));
    assertThrows(
        IllegalArgumentException.class, () -> new TrackJunction(id, stem, 0, 1, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrackJunction(id, stem, 0, 1, TrackJunction.Side.LEFT, null, false, -1));
    assertThrows(IllegalArgumentException.class, () -> TrackJunction.fromJson(null));
    JSONObject json = new TrackJunction(id, stem, 2, 1, TrackJunction.Side.LEFT, null).toJson();
    json.put("s", "3.5");
    json.put("facing", "-1");
    json.put("thrown", "true");
    assertEquals(3.5, TrackJunction.fromJson(json).s);
    assertEquals(-1, TrackJunction.fromJson(json).facingSign);
    assertTrue(TrackJunction.fromJson(json).thrown);
    for (String branch : List.of("", "null", "bad uuid")) {
      json.put("branch", branch);
      assertTrue(TrackJunction.fromJson(json).branchSplineId().isEmpty());
    }
    json.put("side", "unknown");
    assertThrows(IllegalArgumentException.class, () -> TrackJunction.fromJson(json));
    json.remove("side");
    assertThrows(IllegalArgumentException.class, () -> TrackJunction.fromJson(json));
    json.put("side", "LEFT");
    json.remove("id");
    assertThrows(IllegalArgumentException.class, () -> TrackJunction.fromJson(json));
    json.put("id", "bad uuid");
    assertThrows(IllegalArgumentException.class, () -> TrackJunction.fromJson(json));
    assertEquals(0, TrackJunction.wrapS(10, 0, true));
  }

  @Test
  void junctionTravelHandlesReverseLoopsAndSpacingAcrossBranchEnds() {
    assertFalse(TrackJunctionTravel.diverge(null, 1f));
    assertTrue(TrackJunctionTravel.diverge(TrackJunction.Side.LEFT, 1f));
    assertEquals(
        TrackJunctionTravel.Choice.THROUGH,
        TrackJunctionTravel.choice(TrackJunction.Side.LEFT, -1f));
    assertEquals(Double.POSITIVE_INFINITY, TrackJunctionTravel.ahead(0, 0, 1, false, 0));
    assertEquals(5, TrackJunctionTravel.ahead(5, 10, 1, true, 40));
    assertEquals(5, TrackJunctionTravel.ahead(10, 5, -1, true, 40));
    assertEquals(10, TrackJunctionTravel.ahead(5, 35, -1, true, 40));
    assertFalse(TrackJunctionTravel.crosses(0, 1, 0, 1, false, 0));
    assertTrue(TrackJunctionTravel.crosses(20, 10, 15, -1, true, 40));
    assertTrue(TrackJunctionTravel.crosses(5, 35, 39, -1, true, 40));
    assertFalse(TrackJunctionTravel.crosses(5, 35, 20, -1, true, 40));
    UUID stem = UUID.randomUUID(), branch = UUID.randomUUID();
    TrackJunctionTravel.Pose none =
        TrackJunctionTravel.rewind(null, 3, 1, 2, true, stem, branch, 10, 1, 40, false, 10);
    assertNull(none.splineId);
    assertEquals(3, none.s);
    assertEquals(
        3, TrackJunctionTravel.rewind(stem, 3, 1, 0, true, stem, branch, 10, 1, 40, false, 10).s);
    TrackJunctionTravel.Pose reverse =
        TrackJunctionTravel.rewind(branch, 8, -1, 5, true, stem, branch, 10, 1, 40, false, 10);
    assertEquals(stem, reverse.splineId);
    assertEquals(13, reverse.s);
    assertEquals(0, reverse.missingSpacing);
    TrackJunctionTravel.Pose branchPose =
        TrackJunctionTravel.rewind(branch, 3, -1, 2, true, stem, branch, 10, 1, 40, false, 10);
    assertEquals(branch, branchPose.splineId);
    assertEquals(5, branchPose.s);
    TrackJunctionTravel.Pose beyond =
        TrackJunctionTravel.rewind(stem, 2, 1, 5, false, null, null, 10, 1, 40, false, 10);
    assertEquals(stem, beyond.splineId);
    assertEquals(0, beyond.s);
    assertEquals(3, beyond.missingSpacing);
  }

  @Test
  void gradesValidateEmptyVerticalAndRequiredHeightStrokes() throws Exception {
    assertThrows(TrackLayException.class, () -> TrackGrade.apply(null, 0, 1, 10, 15));
    assertThrows(TrackLayException.class, () -> TrackGrade.apply(List.of(), 0, 1, 10, 15));
    assertThrows(
        TrackLayException.class, () -> TrackGrade.apply(points(0, 0, 0, 0), 64, 65, 10, 15));
    List<double[]> flat = points(0, 0, 4, 0);
    TrackGrade.apply(flat, 64, 64, 10, 15);
    assertEquals(64, flat.getLast()[1]);
    TrackGrade.applyRequiredHeights(null, new double[] {1, 2}, 15);
    TrackGrade.applyRequiredHeights(flat, null, 15);
    TrackGrade.applyRequiredHeights(flat, new double[] {1}, 15);
    TrackGrade.applyRequiredHeights(List.of(), new double[] {}, 15);
    assertThrows(
        TrackLayException.class,
        () -> TrackGrade.applyRequiredHeights(points(0, 0, 1, 0), new double[] {64, 66}, 15));
    List<double[]> falling = points(0, 0, 4, 0, 8, 0);
    falling.get(1)[1] = 60;
    falling.get(2)[1] = 50;
    TrackGrade.applyRequiredHeights(falling, new double[] {64, 60, 50}, 15);
    assertTrue(falling.get(1)[1] >= 64 - 4 * Math.tan(Math.toRadians(15)) - 1e-9);
    assertTrue(falling.get(2)[1] >= 64 - 8 * Math.tan(Math.toRadians(15)) - 1e-9);
  }

  @Test
  void curvesRejectVerticalOrBackwardRequestsAndRetainStraightEndpoints() throws Exception {
    assertThrows(
        TrackLayException.class, () -> TrackCurve.between(0, 64, 0, 0, 70, 0, 1, 10, 15, 1));
    assertThrows(TrackLayException.class, () -> TrackCurve.lay(0, 64, 0, 0, 0, 70, 0, 1, 35, 1));
    assertThrows(
        TrackLayException.class,
        () -> TrackCurve.layAligned(0, 64, 0, 0, 0, 70, 0, 1, 35, 10, 15, 1));
    assertThrows(
        TrackLayException.class,
        () -> TrackCurve.join(0, 64, 0, 0, 0, 70, 0, 0, 1, 35, 32, 10, 15, 1));
    assertThrows(TrackLayException.class, () -> TrackCurve.lay(0, 64, 0, 0, 0, 64, -10, 1, 35, 1));
    assertEquals(
        0, TrackCurve.endYaw(null, new double[] {0, 64, 0}, new double[] {0, 64, 1}), 1e-9);
    List<double[]> forward = TrackCurve.join(0, 64, 0, 0, 0, 64, 10, 0, 1, 35, 32, 10, 15, 1);
    assertArrayEquals(new double[] {0, 64, 10}, forward.getLast(), 1e-9);
    assertArrayEquals(new double[] {0, 64, 0}, forward.getFirst(), 1e-9);
  }

  @Test
  void visualBakingHandlesMissingShortAndAppendedTracks() {
    assertTrue(TrackVisualBake.bake(null).isEmpty());
    assertTrue(TrackVisualBake.bakeAppended(null, 0, null).isEmpty());
    assertTrue(TrackVisualBake.bakeAppended(List.of(), 0, List.of()).isEmpty());
    TrackSpline tiny = TrackSpline.fromPoints(null, "world", false, points(0, 0, 1, 0));
    assertFalse(TrackVisualBake.bake(tiny).isEmpty());
    List<double[]> expanded = new ArrayList<>();
    for (int z = 0; z < 20; z++) expanded.add(new double[] {0, 64, z});
    List<TrackVisual> before =
        TrackVisualBake.bake(TrackSpline.fromPoints(null, "world", false, expanded.subList(0, 10)));
    List<TrackVisual> after = TrackVisualBake.bakeAppended(before, 10, expanded);
    List<TrackVisual> whole =
        TrackVisualBake.bake(TrackSpline.fromPoints(null, "world", false, expanded));
    assertEquals(whole.size(), after.size());
    for (int i = 0; i < whole.size(); i++) {
      assertEquals(whole.get(i).startIndex, after.get(i).startIndex);
      assertEquals(whole.get(i).length, after.get(i).length);
      assertEquals(whole.get(i).z, after.get(i).z);
    }
  }

  @Test
  void routeQueriesHandleMissingInputsDisconnectedJunctionsAndVerticalRuns() {
    TrackSpline track = TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 0));
    for (double radius : List.of(-1d, Double.NaN))
      assertTrue(
          TrackRouteQuery.shortestRouteLength(
                  List.of(track), List.of(), "world", 0, 0, radius, 10, 0, 0)
              .isEmpty());
    assertTrue(
        TrackRouteQuery.shortestRouteLength(null, List.of(), "world", 0, 0, 1, 10, 0, 1).isEmpty());
    assertTrue(
        TrackRouteQuery.shortestRouteLength(List.of(track), null, "world", 0, 0, 1, 10, 0, 1)
            .isEmpty());
    assertTrue(
        TrackRouteQuery.shortestRouteLength(List.of(track), List.of(), null, 0, 0, 1, 10, 0, 1)
            .isEmpty());
    TrackJunction
        self =
            new TrackJunction(
                UUID.randomUUID(), track.getId(), 5, 1, TrackJunction.Side.LEFT, track.getId()),
        missing =
            new TrackJunction(
                UUID.randomUUID(), UUID.randomUUID(), 5, 1, TrackJunction.Side.LEFT, track.getId());
    assertEquals(
        10,
        TrackRouteQuery.shortestRouteLength(
                List.of(track), Arrays.asList(null, self, missing), "world", 0, 0, 0, 10, 0, 0)
            .orElseThrow());
    TrackSpline vertical =
        TrackSpline.fromPoints(
            null,
            "world",
            false,
            List.of(new double[] {0, 64, 0}, new double[] {0, 68, 0}, new double[] {10, 68, 0}));
    assertEquals(
        10,
        TrackRouteQuery.shortestRouteLength(
                List.of(vertical), List.of(), "world", 0, 0, 0, 10, 0, 0)
            .orElseThrow(),
        1e-8);
  }

  @Test
  void clearanceAllowsEmptySpaceAndRaisesSingleStepsWithinGradeLimits(
      @TempDir java.nio.file.Path folder) throws Exception {
    Terrain terrain = new Terrain();
    double grade = Cache.trackMaxGradeDegrees,
        width = Cache.trainClearanceWidth,
        height = Cache.trainClearanceHeight,
        offset = Cache.trackVehicleYOffset;
    try {
      Cache.trackMaxGradeDegrees = 15;
      Cache.trainClearanceWidth = 3;
      Cache.trainClearanceHeight = 3;
      Cache.trackVehicleYOffset = .5;
      assertDoesNotThrow(() -> TrackClearance.check(null, points(0, 0, 10, 0), null, null));
      assertDoesNotThrow(() -> TrackClearance.check(terrain.world, null, null, null));
      assertDoesNotThrow(() -> TrackClearance.check(terrain.world, List.of(), null, null));
      TrackClearance.liftOneBlockSteps(null, null);
      List<double[]> stroke = new ArrayList<>();
      for (int z = 0; z <= 10; z++) stroke.add(new double[] {.5, 64, z + .5});
      TrackClearance.check(terrain.world, stroke, null, null);
      terrain.solid.add(new Pos(0, 64, 8));
      TrackClearance.check(terrain.world, stroke, null, null);
      assertTrue(stroke.get(8)[1] >= 65);
      assertEquals(64, stroke.getFirst()[1]);
      TrackRegistry registry = new TrackRegistry(folder.toFile());
      try {
        TrackSpline existing = TrackSpline.fromPoints(null, "world", false, points(20, 0, 30, 0));
        registry.replace(existing);
        TrackClearance.check(
            terrain.world, points(20, 0, 30, 0), registry, Set.of(existing.getId()));
        assertThrows(
            TrackLayException.class,
            () -> TrackClearance.check(terrain.world, points(20, 0, 30, 0), registry, null));
      } finally {
        registry.close();
      }
    } finally {
      Cache.trackMaxGradeDegrees = grade;
      Cache.trainClearanceWidth = width;
      Cache.trainClearanceHeight = height;
      Cache.trackVehicleYOffset = offset;
    }
  }

  @Test
  void clearanceReportsHighStepsAndRailHeadroomBeyondNarrowTrainBounds() {
    Terrain terrain = new Terrain();
    double width = Cache.trainClearanceWidth,
        height = Cache.trainClearanceHeight,
        offset = Cache.trackVehicleYOffset;
    try {
      Cache.trainClearanceWidth = 3;
      Cache.trainClearanceHeight = 3;
      Cache.trackVehicleYOffset = 0;
      terrain.solid.addAll(List.of(new Pos(0, 64, 4), new Pos(0, 65, 4), new Pos(0, 66, 7)));
      List<double[]> stroke = new ArrayList<>();
      for (int z = 0; z <= 10; z++) stroke.add(new double[] {.5, 64, z + .5});
      TrackLayException rejected =
          assertThrows(
              TrackLayException.class,
              () -> TrackClearance.check(terrain.world, stroke, null, null));
      assertTrue(rejected.hasBlock());
      assertTrue(rejected.inTrainSpace.size() >= 2);
      assertTrue(rejected.getMessage().contains("more blocks"));
      TrackLayException generic = new TrackLayException("generic");
      assertSame(generic, TrackClearance.withTrainSpace(terrain.world, stroke, generic));
      assertEquals("3", TrackClearance.format(3));
      assertEquals("3.5", TrackClearance.format(3.5));
      terrain.solid.clear();
      terrain.solid.add(new Pos(0, 66, 4));
      Cache.trainClearanceWidth = .5;
      Cache.trainClearanceHeight = 1;
      TrackLayException overhead =
          assertThrows(
              TrackLayException.class,
              () -> TrackClearance.check(terrain.world, stroke, null, null));
      assertEquals(66, overhead.blockY);
      assertTrue(overhead.getMessage().contains("stone in the way"));
    } finally {
      Cache.trainClearanceWidth = width;
      Cache.trainClearanceHeight = height;
      Cache.trackVehicleYOffset = offset;
    }
  }

  @Test
  void collisionScansHandleNoWorldTinyIntervalsAndLoopSeams() {
    Terrain terrain = new Terrain();
    TrackSpline loop = square();
    Entity entity = mock(Entity.class);
    when(entity.getWorld()).thenReturn(terrain.world);
    assertFalse(TrainBlockCollision.blocked(entity, loop, 39.9, loop, .1, 2));
    assertTrue(TrainBlockCollision.obstructions(null, loop, 0, 1).isEmpty());
    assertTrue(TrainBlockCollision.obstructions(terrain.world, null, 0, 1).isEmpty());
    assertTrue(TrainBlockCollision.obstructions(terrain.world, loop, 1, 0).isEmpty());
    assertTrue(TrainBlockCollision.obstructions(terrain.world, loop, 0, 1e-8).isEmpty());
  }

  @Test
  void longLoopAdvancesStillStopAtTheFirstBrokenEdge() {
    TrackSpline loop = square();
    loop =
        loop.withSegment(0, new TrackSegment(0, true, 0))
            .withSegment(3, new TrackSegment(3, true, 0));
    TrackAdvance forward = loop.advance(25, 40);
    assertTrue(forward.stoppedAtBreak);
    assertEquals(30, forward.s, 1e-9);
    TrackAdvance reverse = loop.advance(25, -40);
    assertTrue(reverse.stoppedAtBreak);
    assertEquals(10, reverse.s, 1e-9);
  }

  @Test
  void joiningStraightBehindTheHeadingRejectsAnInstantReversal() {
    assertThrows(
        TrackLayException.class,
        () -> TrackCurve.join(0, 64, 0, 0, 0, 64, -30, 0, 1, 35, 32, 10, 15, 1));
  }

  @Test
  void curveFallbacksPreserveEndpointsAndRejectOvertightBends() throws Exception {
    for (double[] c :
        List.of(
            new double[] {-.1, 10, -160, 1, 35},
            new double[] {-2, 30, -35, 1, 35},
            new double[] {0, 2, -20, 1, 35},
            new double[] {-30, 30, -90, 1, 90},
            new double[] {10, 30, -90, 16, 90})) {
      List<double[]> curve =
          TrackCurve.join(0, 64, 0, 0, c[0], 64, c[1], (float) c[2], c[3], c[4], 32, 10, 15, 1);
      assertArrayEquals(new double[] {0, 64, 0}, curve.getFirst(), 1e-9);
      assertArrayEquals(new double[] {c[0], 64, c[1]}, curve.getLast(), 1e-9);
      for (double[] p : curve) {
        assertTrue(Double.isFinite(p[0]) && Double.isFinite(p[2]));
        assertEquals(64, p[1], 1e-9);
      }
      assertTrue(TrackGrade.horizontalLength(curve) >= Math.hypot(c[0], c[1]) - 1e-8);
    }
    for (double[] c :
        List.of(
            new double[] {-30, -30, -35, 35},
            new double[] {-30, 30, -35, 35},
            new double[] {-30, -30, 0, 35},
            new double[] {2, 0, 90, 120}))
      assertThrows(
          TrackLayException.class,
          () -> TrackCurve.join(0, 64, 0, 0, c[0], 64, c[1], (float) c[2], 1, c[3], 32, 10, 15, 1));
    assertThrows(
        TrackLayException.class,
        () -> TrackCurve.layAligned(0, 64, 0, 0, 0, 64, -10, 1, 35, 10, 15, 1));
    List<double[]> degenerate = TrackCurve.lay(0, 64, 0, 0, 0, 64, 0, 0, 35, 1);
    assertEquals(2, degenerate.size());
    assertArrayEquals(degenerate.getFirst(), degenerate.getLast());
    assertEquals(2, TrackCurve.between(0, 64, 0, 0, 64, 0, 0, 10, 15, 1).size());
    double angle = Math.toRadians(35),
        x = -32 * (1 - Math.cos(angle)) - 10 * Math.sin(angle),
        z = 32 * Math.sin(angle) + 10 * Math.cos(angle);
    List<double[]> tangent = TrackCurve.join(0, 64, 0, 0, x, 64, z, 35, 1, 35, 32, 10, 15, 1);
    assertArrayEquals(new double[] {x, 64, z}, tangent.getLast(), 1e-9);
    assertEquals(
        35,
        TrackCurve.endYaw(
            tangent.get(tangent.size() - 3), tangent.get(tangent.size() - 2), tangent.getLast()),
        .1);
  }

  @Test
  void gradeRejectsSmallOverslopeEvenWithinStartPointTolerance() {
    double target = 64 + Math.tan(Math.toRadians(15)) + .0005;
    TrackLayException steep =
        assertThrows(
            TrackLayException.class,
            () ->
                TrackGrade.applyRequiredHeights(points(0, 0, 1, 0), new double[] {64, target}, 15));
    assertTrue(steep.getMessage().contains("Slope is too steep"));
  }

  @Test
  void splineCopiesCoordinatesAndPromotesOnlyNearbyEndpoints() {
    List<double[]> xyz = points(0, 0, 1, 0, 0, .1);
    assertTrue(TrackSpline.shouldLoop(xyz, .2));
    assertFalse(TrackSpline.shouldLoop(xyz, .05));
    TrackSpline open = TrackSpline.fromPoints(null, "world", false, xyz);
    assertSame(open, open.promotedLoop(.05));
    TrackSpline loop = open.promotedLoop(.2);
    assertTrue(loop.isLoop());
    assertSame(loop, loop.promotedLoop(.2));
    List<double[]> copied = loop.xyz();
    copied.getFirst()[0] = 999;
    assertEquals(0, loop.first().x);
    assertEquals(.1, loop.last().z);
    assertSame(loop.visuals(), loop.visuals());
    TrackSpline broken =
        square()
            .withSegment(0, new TrackSegment(0, true, 0))
            .withSegment(3, new TrackSegment(3, true, 0));
    assertEquals(30, broken.advance(25, 20).s);
    assertEquals(30, broken.advance(25, 10).s);
    assertFalse(broken.advance(25, -2).stoppedAtBreak);
    TrackSpline legacy =
        new TrackSpline(
            open.getId(),
            "world",
            false,
            open.getSamples(),
            List.of(new TrackSegment(-2, true, .3), new TrackSegment(1, false, 1)));
    assertEquals(.3, legacy.segment(-2).health);
    assertFalse(legacy.segment(0).broken);
  }

  @Test
  void junctionChangesPreserveOtherPersistedFields() {
    UUID id = UUID.randomUUID(),
        stem = UUID.randomUUID(),
        other = UUID.randomUUID(),
        branch = UUID.randomUUID();
    TrackJunction start =
        new TrackJunction(id, stem, 2, 1, TrackJunction.Side.LEFT, branch, true, 5);
    TrackJunction changed =
        start
            .withS(3)
            .withStem(other, 4)
            .withSide(TrackJunction.Side.RIGHT)
            .withBranch(stem)
            .withFacing(-1)
            .withThrown(false)
            .withTurnoutEndS(6);
    assertEquals(id, changed.id);
    assertEquals(other, changed.stemSplineId);
    assertEquals(4, changed.s);
    assertEquals(TrackJunction.Side.RIGHT, changed.side);
    assertEquals(stem, changed.branchSplineId().orElseThrow());
    assertEquals(-1, changed.facingSign);
    assertFalse(changed.thrown);
    assertEquals(6, changed.turnoutEndS);
    assertEquals(2, start.s);
    assertEquals(TrackJunction.Side.LEFT, TrackJunction.sideFrom(0, 1, 0));
    assertEquals(TrackJunction.Side.RIGHT, TrackJunction.sideFrom(0, -1, 0));
    assertEquals(10, TrackJunctionTravel.ahead(35, 5, 1, true, 40));
    assertTrue(TrackJunctionTravel.crosses(35, 5, 0, 1, true, 40));
  }

  @Test
  void alternatingGentleBendsAreNotMergedIntoAnOverturnedVisual() {
    List<double[]> xyz = new ArrayList<>();
    double x = 0, z = 0;
    xyz.add(new double[] {x, 64, z});
    for (double yaw : List.of(0d, 7d, -7d, -7d)) {
      x -= Math.sin(Math.toRadians(yaw));
      z += Math.cos(Math.toRadians(yaw));
      xyz.add(new double[] {x, 64, z});
    }
    TrackSpline track = TrackSpline.fromPoints(null, "world", false, xyz);
    List<TrackVisual> visuals = TrackVisualBake.bake(track);
    assertEquals(2, visuals.getFirst().length);
    assertEquals(xyz.size(), visuals.stream().mapToInt(v -> v.length).sum());
    assertFalse(TrackVisualBake.bake(square()).isEmpty());
  }

  @Test
  void routesChooseEitherDirectionAroundALoopAndIgnoreZeroLengthBrokenEdges() {
    TrackSpline loop = square(),
        left = TrackSpline.fromPoints(null, "world", false, points(0, 0, -10, 0)),
        right = TrackSpline.fromPoints(null, "world", false, points(10, 10, 10, 20));
    List<TrackJunction> junctions =
        List.of(
            new TrackJunction(
                UUID.randomUUID(), loop.getId(), 0, 1, TrackJunction.Side.LEFT, left.getId()),
            new TrackJunction(
                UUID.randomUUID(), loop.getId(), 20, 1, TrackJunction.Side.RIGHT, right.getId()));
    List<TrackSpline> tracks = List.of(loop, left, right);
    assertEquals(
        40,
        TrackRouteQuery.shortestRouteLength(tracks, junctions, "world", -10, 0, 0, 10, 20, 0)
            .orElseThrow(),
        1e-8);
    assertEquals(
        10,
        TrackRouteQuery.shortestRouteLength(tracks, junctions, "world", 0, 0, 0, -10, 0, 0)
            .orElseThrow(),
        1e-8);
    assertEquals(
        13,
        TrackRouteQuery.shortestRouteLength(tracks, junctions, "world", 0, 3, 0, -10, 0, 0)
            .orElseThrow(),
        1e-8);
    TrackSpline repeated = TrackSpline.fromPoints(null, "world", false, points(0, 0, 0, 0, 10, 0));
    repeated = repeated.withSegment(0, new TrackSegment(0, true, 0));
    assertEquals(
        10,
        TrackRouteQuery.shortestRouteLength(
                List.of(repeated), List.of(), "world", 0, 0, 0, 10, 0, 0)
            .orElseThrow(),
        1e-8);
    TrackSpline closed = square();
    for (int edge = 0; edge < 4; edge++)
      closed = closed.withSegment(edge, new TrackSegment(edge, true, 0));
    assertTrue(
        TrackRouteQuery.shortestRouteLength(List.of(closed), List.of(), "world", 0, 0, 0, 10, 10, 0)
            .isEmpty());
    TrackJunction missing =
        new TrackJunction(
            UUID.randomUUID(), loop.getId(), 3, 1, TrackJunction.Side.RIGHT, UUID.randomUUID());
    assertEquals(
        10,
        TrackRouteQuery.shortestRouteLength(
                List.of(loop), List.of(missing), "world", 0, 0, 0, 10, 0, 0)
            .orElseThrow());
  }

  @Test
  void clearanceIgnoresTracksAtDifferentHeightsAndEmptyTrainVolume(
      @TempDir java.nio.file.Path folder) throws Exception {
    Terrain terrain = new Terrain();
    TrackRegistry registry = new TrackRegistry(folder.toFile());
    try {
      registry.replace(
          TrackSpline.fromPoints(
              null, "world", false, List.of(new double[] {0, 70, 0}, new double[] {10, 70, 0})));
      TrackClearance.checkOverlap("world", points(0, 0, 10, 0), registry, null, null);
      TrackClearance.checkOverlap(null, points(0, 0, 10, 0), registry, null, null);
      TrackClearance.checkOverlap("world", null, registry, null, null);
      TrackClearance.checkOverlap("world", points(0, 0, 10, 0), null, null, null);
    } finally {
      registry.close();
    }
    double offset = Cache.trackVehicleYOffset, height = Cache.trainClearanceHeight;
    try {
      Cache.trackVehicleYOffset = .5;
      Cache.trainClearanceHeight = .5;
      terrain.solid.add(new Pos(0, 64, 2));
      assertTrue(TrainBlockCollision.obstructions(terrain.world, square(), 0, 10).isEmpty());
    } finally {
      Cache.trackVehicleYOffset = offset;
      Cache.trainClearanceHeight = height;
    }
  }

  @Test
  void alignedRowsRoundTowardGridHeadingAndNumericSamplingNeverOvershoots() throws Exception {
    List<double[]> track = TrackCurve.layAligned(0, 64, 0, 20, 0, 64, 30, 1, 35, 10, 15, 1);
    assertArrayEquals(new double[] {0, 64, 30}, track.getLast(), 1e-9);
    assertTrue(
        Math.abs(
                TrackCurve.endYaw(
                    track.get(track.size() - 3), track.get(track.size() - 2), track.getLast()))
            < 20);
    List<double[]> dense = TrackGenerate.densify(0, 64, 0, 3.9, 64, 0, 1.3);
    assertEquals(4, dense.size());
    assertEquals(3.9, dense.getLast()[0]);
    assertTrue(dense.stream().allMatch(p -> p[0] >= 0 && p[0] <= 3.9));
    assertEquals(0, TrackLap.wrapDelta(4, 3, 0));
    assertEquals(2, TrackLap.wrapDelta(1, 9, 10));
    assertEquals(new org.bukkit.util.Vector(0, 0, 1), TrackSplineMotion.tangentFromPose(null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> TrackSwitchPose.of(null, null, 0, 0, 0, 0, 0));
  }

  @Test
  void halfBlockSupportAlreadyUnderTheRailNeedsNoLift() throws Exception {
    Terrain terrain = new Terrain();
    Pos support = new Pos(0, 64, 0);
    terrain.solid.add(support);
    Block block = terrain.block(support);
    org.bukkit.block.data.type.Slab slab = mock(org.bukkit.block.data.type.Slab.class);
    when(slab.getType()).thenReturn(org.bukkit.block.data.type.Slab.Type.BOTTOM);
    when(block.getBlockData()).thenReturn(slab);
    List<double[]> stroke =
        new ArrayList<>(List.of(new double[] {.5, 64.5, .5}, new double[] {.5, 64.5, 1.5}));
    TrackClearance.liftOneBlockSteps(terrain.world, stroke);
    assertEquals(64.5, stroke.getFirst()[1]);
    assertEquals(64.5, stroke.getLast()[1]);
  }

  @Test
  void layResultsKeepOnlyExistingTrackAndVisualDiffFindsNewChunks() {
    TrackSpline track =
        TrackSpline.fromPoints(null, "world", false, points(0, 0, 1, 0, 2, 0, 3, 0));
    TrackLayResult append =
        TrackLayResult.of(TrackLayResult.Kind.APPEND, track, points(2, 0, 3, 0), 3);
    assertEquals(3, append.keepPoints().size());
    assertEquals(2, append.keepPoints().getLast()[0]);
    assertTrue(
        TrackLayResult.of(TrackLayResult.Kind.APPEND, track, null, 1).keepPoints().isEmpty());
    TrackLayResult prepend =
        TrackLayResult.of(TrackLayResult.Kind.PREPEND, track, points(0, 0, 1, 0), 3);
    assertEquals(1, prepend.keepPoints().getFirst()[0]);
    assertEquals(3, prepend.keepPoints().size());
    assertTrue(
        TrackLayResult.of(TrackLayResult.Kind.PREPEND, track, track.xyz(), 2)
            .keepPoints()
            .isEmpty());
    assertTrue(
        TrackLayResult.of(TrackLayResult.Kind.CONNECT, track, track.xyz(), 2)
            .keepPoints()
            .isEmpty());
    List<double[]> keep = append.keepPoints();
    keep.getFirst()[0] = 99;
    assertEquals(0, track.first().x);
    assertEquals(0, append.keepPoints().getFirst()[0]);
    TrackLayResult withBefore = append.withBefore(track);
    assertSame(track, withBefore.before);
    assertSame(track, withBefore.spline());
    assertTrue(TrackLayResult.of(TrackLayResult.Kind.NEW, track).sequential());
    assertFalse(TrackLayResult.of(TrackLayResult.Kind.CONNECT, track).sequential());
    assertTrue(TrackLayResult.of(TrackLayResult.Kind.NEW, null).keepPoints().isEmpty());
    assertEquals(
        Set.of(TrackChunks.key(0, 0)), TrackVisualDiff.changedChunks(null, track.visuals()));
    assertEquals(
        Set.of(TrackChunks.key(0, 0)), TrackVisualDiff.changedChunks(track.visuals(), null));
    assertTrue(TrackVisualDiff.changedChunks(null, null).isEmpty());
    assertEquals(0, TrackVisualDiff.firstChange(null, track.visuals()));
    assertEquals(
        track.visuals().size(), TrackVisualDiff.firstChange(track.visuals(), track.visuals()));
    assertTrue(TrackVisualDiff.same(track.visuals().getFirst(), track.visuals().getFirst()));
    assertFalse(TrackVisualDiff.same(null, track.visuals().getFirst()));
    assertFalse(TrackVisualDiff.same(track.visuals().getFirst(), null));
  }

  @Test
  void routeQueriesHandleRoundoffAtCircleTangentsAndRepeatedGraphRelaxation() {
    TrackSpline line = TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 0));
    assertEquals(
        5,
        TrackRouteQuery.shortestRouteLength(
                List.of(line), List.of(), "world", 5, 1 + 1e-10, 1, 10, 0, 0)
            .orElseThrow(),
        1e-6);
    TrackSpline loop = square();
    assertEquals(
        0,
        TrackRouteQuery.shortestRouteLength(List.of(loop), List.of(), "world", 0, 0, 0, 0, 0, 0)
            .orElseThrow());
    TrackSpline shortcut = TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 10)),
        branch = TrackSpline.fromPoints(null, "world", false, points(10, 10, 10, 20)),
        isolated = TrackSpline.fromPoints(null, "world", false, points(100, 100, 110, 100));
    List<TrackJunction> junctions =
        List.of(
            new TrackJunction(
                UUID.randomUUID(), loop.getId(), 0, 1, TrackJunction.Side.LEFT, shortcut.getId()),
            new TrackJunction(
                UUID.randomUUID(), loop.getId(), 20, 1, TrackJunction.Side.RIGHT, branch.getId()),
            new TrackJunction(
                UUID.randomUUID(),
                shortcut.getId(),
                shortcut.length(),
                1,
                TrackJunction.Side.LEFT,
                branch.getId()));
    List<TrackSpline> network = List.of(loop, shortcut, branch, isolated);
    assertEquals(
        Math.sqrt(200) + 10,
        TrackRouteQuery.shortestRouteLength(network, junctions, "world", 0, 0, 0, 10, 20, 0)
            .orElseThrow(),
        1e-8);
    assertTrue(
        TrackRouteQuery.shortestRouteLength(network, junctions, "world", 0, 0, 0, 110, 100, 0)
            .isEmpty());
    assertEquals(
        27,
        TrackRouteQuery.shortestRouteLength(network, junctions, "world", 10, 20, 0, 0, 3, 0)
            .orElseThrow(),
        1e-8);
    TrackSpline broken =
        TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 0, 20, 0, 30, 0));
    broken = broken.withSegment(1, new TrackSegment(1, true, 0));
    TrackSpline spur = TrackSpline.fromPoints(null, "world", false, points(25, 0, 25, 10));
    TrackJunction far =
        new TrackJunction(
            UUID.randomUUID(), broken.getId(), 25, 1, TrackJunction.Side.LEFT, spur.getId());
    assertEquals(
        5,
        TrackRouteQuery.shortestRouteLength(
                List.of(broken, spur), List.of(far), "world", 0, 0, 0, 5, 0, 0)
            .orElseThrow(),
        1e-8);
    assertTrue(TrackJunctionTravel.crosses(5, 15, 10, 1, true, 40));
    assertEquals(
        8,
        TrackRouteQuery.shortestRouteLength(List.of(loop), List.of(), "world", 5, 0, 1, 10, 5, 1)
            .orElseThrow(),
        1e-8);
  }

  @Test
  void tapeDefaultsLegacyJsonAndCapacityRetainRecordedSemantics() {
    UUID origin = UUID.randomUUID();
    ThrottleTape empty = new ThrottleTape(null, null);
    assertEquals("", empty.getSplineId());
    assertFalse(empty.matchesSpline(null));
    assertFalse(empty.takesJunction(null));
    assertEquals(0, ThrottleTape.lookup(null, 1, 1));
    assertNull(ThrottleTape.fromJson(null));
    assertNull(ThrottleTape.fromJson(new JSONObject()));
    JSONObject json = new JSONObject();
    json.put("splineId", " ");
    assertNull(ThrottleTape.fromJson(json));
    json.put("splineId", origin.toString());
    JSONArray samples = new JSONArray();
    samples.add("ignored legacy marker");
    JSONObject sample = new JSONObject();
    sample.put("s", "2.5");
    sample.put("sign", "-1");
    sample.put("throttle", "bad value");
    sample.put("hold", "3");
    sample.put("junction", "null");
    samples.add(sample);
    json.put("samples", samples);
    ThrottleTape loaded = ThrottleTape.fromJson(json);
    assertTrue(loaded.matchesSpline(origin));
    assertEquals(1, loaded.getSamples().size());
    assertEquals(0, loaded.lookup(2.5, -1, (UUID) null));
    assertEquals(3, loaded.holdAt(2.5, -1, (UUID) null));
    assertEquals(1, loaded.holdAt(2.5, 1, origin));
    assertEquals(0, loaded.targetWithDwell(2.5, -1, new ThrottleTape.DwellState(), (UUID) null));
    assertEquals(0, loaded.lookup(2.5, -1, " "));
    sample.remove("s");
    assertEquals(0, ThrottleTape.fromJson(json).getSamples().getFirst().s);
    assertEquals(0, loaded.lookup(Double.NaN, -1));
    ThrottleTape defaults =
        new ThrottleTape(origin.toString(), List.of(new ThrottleTape.Sample(3, 1, 25, 4)));
    assertEquals(4, defaults.holdAt(3, 1));
    assertEquals(ThrottleTape.AppendResult.ADDED, defaults.tryAppend(5, 1, 25, " ", null));
    ThrottleTape blank = new ThrottleTape("");
    assertEquals(ThrottleTape.AppendResult.ADDED, blank.tryAppend(0, 1, 0, (String) null, null));
    assertEquals(ThrottleTape.AppendResult.HELD, blank.tryAppend(.1, 1, 0, "null", null));
    ThrottleTape capacity = new ThrottleTape(origin.toString());
    for (int i = 0; i < ThrottleTape.MAX_SAMPLES - 1; i++)
      assertEquals(ThrottleTape.AppendResult.ADDED, capacity.tryAppend(i, 1, 50));
    assertEquals(
        ThrottleTape.AppendResult.CAPPED, capacity.tryAppend(ThrottleTape.MAX_SAMPLES - 1, 1, 50));
    assertEquals(
        ThrottleTape.AppendResult.CAPPED, capacity.tryAppend(ThrottleTape.MAX_SAMPLES, 1, 60));
    assertEquals(ThrottleTape.MAX_SAMPLES, capacity.getSamples().size());
    assertEquals(
        ThrottleTape.AppendResult.HELD, capacity.tryAppend(ThrottleTape.MAX_SAMPLES - 1, 1, 50));
    assertEquals(2, capacity.getSamples().getLast().holdTicks);
  }

  @Test
  void trainRouteStopsAtBreaksAndHandlesMissingAndZeroLengthTracks(
      @TempDir java.nio.file.Path folder) throws Exception {
    TrackRegistry registry = new TrackRegistry(folder.toFile());
    try {
      TrackSpline line =
          TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 0, 20, 0))
              .withSegment(1, new TrackSegment(1, true, 0));
      registry.replace(line);
      TrainRoute route = new TrainRoute(registry, Map.of());
      TrainRoute.Walk stopped =
          route.walk(new TrainRoute.Position(line.getId(), 5, 1), 10, false, true);
      assertTrue(stopped.broken());
      assertEquals(10, stopped.position().s());
      assertEquals(5, stopped.missing());
      UUID absent = UUID.randomUUID();
      TrainRoute.Walk missing = route.walk(new TrainRoute.Position(absent, 2, 1), 3);
      assertEquals(3, missing.missing());
      assertNull(route.rail(new TrainRoute.Position(absent, 0, 1), 2));
      TrackSpline zero = TrackSpline.fromPoints(null, "world", false, points(40, 0, 40, 0));
      registry.replace(zero);
      assertEquals(2, route.walk(new TrainRoute.Position(zero.getId(), 0, 1), 2).missing());
      TrackSpline longStem = TrackSpline.fromPoints(null, "world", false, points(0, 100, 100, 100));
      registry.replace(longStem);
      TrackJunction incomplete =
          registry.putJunction(
              new TrackJunction(
                  UUID.randomUUID(), longStem.getId(), 50, 1, TrackJunction.Side.LEFT, null));
      assertEquals(
          60, route.walk(new TrainRoute.Position(longStem.getId(), 40, 1), 20).position().s());
      registry.putJunction(incomplete.withBranch(UUID.randomUUID()));
      assertEquals(
          60, route.walk(new TrainRoute.Position(longStem.getId(), 40, 1), 20).position().s());
      TrackPose beyond = route.rail(new TrainRoute.Position(longStem.getId(), 95, 1), 10);
      assertEquals(105, beyond.x, 1e-9);
      assertEquals(100, beyond.z, 1e-9);
      TrackPose reverse = route.rail(new TrainRoute.Position(longStem.getId(), 5, -1), 10);
      assertEquals(-5, reverse.x, 1e-9);
      assertEquals(100, reverse.z, 1e-9);
      assertEquals(90, reverse.yaw, 1e-6);
    } finally {
      registry.close();
    }
  }

  @Test
  void constructorNormalizesStoredDistancesJustLikeJsonReload() {
    for (double[] stored :
        List.of(
            new double[] {0, 10, 5, 30},
            new double[] {5, 15, 25, 35},
            new double[] {0, Double.NaN, 20, 30},
            new double[] {0, 10, Double.POSITIVE_INFINITY, 30},
            new double[] {0, 10, 20, Double.POSITIVE_INFINITY})) {
      List<TrackSample> samples = new ArrayList<>();
      for (int i = 0; i < stored.length; i++)
        samples.add(new TrackSample(0, 64, i * 10, 0, 0, stored[i]));
      TrackSpline direct = new TrackSpline(UUID.randomUUID(), "world", false, samples, null),
          loaded = TrackSpline.fromJson(direct.toJson());
      assertEquals(30, direct.length(), 1e-9);
      for (double at : List.of(0d, 7d, 12d, 17d, 25d, 30d)) {
        assertEquals(
            loaded.sampleAt(at).z,
            direct.sampleAt(at).z,
            1e-9,
            "Constructor and JSON loader must agree at s=" + at);
        assertEquals(at, direct.sampleAt(at).z, 1e-9);
      }
    }
    TrackSpline duplicate =
        new TrackSpline(
            null,
            "world",
            false,
            List.of(
                new TrackSample(0, 64, 0, 0, 0, 0),
                new TrackSample(0, 64, 0, 0, 0, 0),
                new TrackSample(0, 64, 10, 0, 0, 10)),
            null);
    assertEquals(5, duplicate.sampleAt(5).z, 1e-9);
  }

  @Test
  void nonfiniteRouteCoordinatesDoNotConnectToRealTracks() {
    TrackSpline line = TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 0));
    for (double coordinate :
        List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertTrue(
          TrackRouteQuery.shortestRouteLength(
                  List.of(square()), List.of(), "world", coordinate, 0, 1, 10, 0, 1)
              .isEmpty());
      assertTrue(
          TrackRouteQuery.shortestRouteLength(
                  List.of(line), List.of(), "world", coordinate, 0, 1, 10, 0, 1)
              .isEmpty());
      assertTrue(
          TrackRouteQuery.shortestRouteLength(
                  List.of(line), List.of(), "world", 0, 0, 1, coordinate, 0, 1)
              .isEmpty());
    }
  }

  @Test
  void invalidSampleDistancesReturnFinitePointsOnTheTrack() {
    for (TrackSpline track :
        List.of(square(), TrackSpline.fromPoints(null, "world", false, points(0, 0, 10, 0))))
      for (double at : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        TrackPose p = track.sampleAt(at);
        assertTrue(Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z));
        assertTrue(p.x >= 0 && p.x <= 10 && p.z >= 0 && p.z <= 10);
        assertEquals(64, p.y);
      }
  }

  @Test
  void finiteLargeCoordinateCancellationFallsBackWithoutDividingByZero() throws Exception {
    // Beyond normal world bounds, finite doubles can still be supplied through this public geometry
    // API.
    // At this magnitude the biarc tangent points round to the same coordinate; its fallback must
    // stay finite.
    double origin = 1e12, end = origin + Math.ulp(origin);
    List<double[]> curve =
        TrackCurve.join(origin, 64, origin, 135, end, 64, origin, -135, 0, 179, 32, 10, 15, 1);
    assertEquals(2, curve.size());
    assertArrayEquals(new double[] {origin, 64, origin}, curve.getFirst(), 0);
    assertArrayEquals(new double[] {end, 64, origin}, curve.getLast(), 0);
    for (double[] p : curve) for (double coordinate : p) assertTrue(Double.isFinite(coordinate));
  }

  private static List<double[]> points(double... xz) {
    List<double[]> p = new ArrayList<>();
    for (int i = 0; i < xz.length; i += 2) p.add(new double[] {xz[i], 64, xz[i + 1]});
    return p;
  }

  private static TrackSpline square() {
    return TrackSpline.fromPoints(
        UUID.randomUUID(), "world", true, points(0, 0, 10, 0, 10, 10, 0, 10));
  }

  private record Pos(int x, int y, int z) {}

  private static final class Terrain {
    final World world = mock(World.class);
    final Set<Pos> solid = new HashSet<>();
    final Map<Pos, Block> blocks = new HashMap<>();

    Terrain() {
      when(world.getName()).thenReturn("world");
      when(world.getUID()).thenReturn(UUID.randomUUID());
      when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
      when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
          .thenAnswer(c -> block(new Pos(c.getArgument(0), c.getArgument(1), c.getArgument(2))));
    }

    Block block(Pos pos) {
      return blocks.computeIfAbsent(
          pos,
          key -> {
            Block b = mock(Block.class);
            when(b.getX()).thenReturn(pos.x);
            when(b.getY()).thenReturn(pos.y);
            when(b.getZ()).thenReturn(pos.z);
            when(b.getWorld()).thenReturn(world);
            when(b.getType()).thenAnswer(c -> solid.contains(pos) ? Material.STONE : Material.AIR);
            when(b.isPassable()).thenAnswer(c -> !solid.contains(pos));
            BlockData data = mock(BlockData.class);
            when(b.getBlockData()).thenReturn(data);
            VoxelShape shape = mock(VoxelShape.class);
            when(shape.getBoundingBoxes())
                .thenAnswer(
                    c ->
                        solid.contains(pos)
                            ? List.of(new BoundingBox(0, 0, 0, 1, 1, 1))
                            : List.of());
            when(b.getCollisionShape()).thenReturn(shape);
            return b;
          });
    }
  }
}
