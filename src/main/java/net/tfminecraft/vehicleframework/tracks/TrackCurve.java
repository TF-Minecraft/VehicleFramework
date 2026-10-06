package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TrackCurve {
	public static final double STRAIGHT_EPS = 0.02;
	public static final double DEFAULT_DESIRED_GRADE = 10;
	public static final double DEFAULT_MAX_GRADE = 15;
	/** Block centres line up along these headings: the four axes and the diagonals. */
	static final double GRID_DEGREES = 45;
	/** An extension whose plain arc would end this close to a grid heading ends on it instead. */
	static final double SNAP_END_DEGREES = 10;
	/** A track end this close to a grid heading is treated as on it. */
	static final double SNAP_START_DEGREES = 1.5;
	/** A click whose direction from the track end is this close to a grid heading is along a row. */
	static final double ROW_CHORD_DEGREES = 2;
	/** A click this far off the heading's line, still inside its block, continues straight. */
	static final double PIN_OFFSET = 0.5;
	/**
	 * A click at most this far to the side of a track on a grid heading, and
	 * within {@link #SHIFT_CHORD_DEGREES} of it, moves the track over rather than turning it.
	 */
	static final double SHIFT_OFFSET = 3;
	static final double SHIFT_CHORD_DEGREES = 8;
	/** Radius a long stroke curves at, so a bend or a sideways shift stays local instead of spanning the stroke. */
	public static final double DEFAULT_CURVE_RADIUS = 32;
	/**
	 * Turning onto the end heading straight away and moving across near B
	 * takes two more curves than one corner, so it is only laid when it keeps
	 * this many curve radii more track on the grid headings.
	 */
	static final double EXTRA_CURVES_RADII = 2;
	/** Each smaller radius tried when the curve radius does not fit. */
	private static final double RADIUS_STEP = 0.85;

	private TrackCurve() {
	}

	public static List<double[]> between(
			double ax, double ay, double az,
			double bx, double by, double bz,
			double minDistance,
			double desiredGradeDegrees,
			double maxGradeDegrees,
			double step) throws TrackLayException {
		ensureMinDistance(ax, ay, az, bx, by, bz, minDistance);
		double horiz = Math.hypot(bx - ax, bz - az);
		List<double[]> points;
		if (horiz < 1e-6) {
			points = new ArrayList<>();
			points.add(new double[] {ax, ay, az});
			points.add(new double[] {bx, ay, bz});
		} else {
			points = TrackGenerate.densify(ax, ay, az, bx, ay, bz, step);
		}
		TrackGrade.apply(points, ay, by, desiredGradeDegrees, maxGradeDegrees);
		return points;
	}

	public static List<double[]> lay(
			double ax, double ay, double az,
			float startYaw,
			double bx, double by, double bz,
			double minDistance,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		return lay(ax, ay, az, startYaw, bx, by, bz,
				minDistance, maxTurnDegrees, DEFAULT_DESIRED_GRADE, DEFAULT_MAX_GRADE, step);
	}

	public static List<double[]> lay(
			double ax, double ay, double az,
			float startYaw,
			double bx, double by, double bz,
			double minDistance,
			double maxTurnDegrees,
			double desiredGradeDegrees,
			double maxGradeDegrees,
			double step) throws TrackLayException {
		ensureMinDistance(ax, ay, az, bx, by, bz, minDistance);
		double dx = bx - ax;
		double dz = bz - az;
		double yawRad = Math.toRadians(startYaw);
		double tx = -Math.sin(yawRad);
		double tz = Math.cos(yawRad);
		double horiz = Math.hypot(dx, dz);
		if (horiz < 1e-6) {
			List<double[]> vertical = new ArrayList<>();
			vertical.add(new double[] {ax, ay, az});
			vertical.add(new double[] {bx, ay, bz});
			TrackGrade.apply(vertical, ay, by, desiredGradeDegrees, maxGradeDegrees);
			return vertical;
		}
		List<double[]> points;
		double along = dx * tx + dz * tz;
		double cross = tx * dz - tz * dx;
		if (Math.abs(cross) <= STRAIGHT_EPS * horiz) {
			if (along < 0) {
				throw new TrackLayException(
						"Turn is too sharp: the end is behind the track heading. Pick an end ahead of the current track.");
			}
			points = TrackGenerate.densify(ax, ay, az, bx, ay, bz, step);
		} else {
			points = arcXz(ax, ay, az, bx, bz, tx, tz, cross, horiz, maxTurnDegrees, step);
		}
		TrackGrade.apply(points, ay, by, desiredGradeDegrees, maxGradeDegrees);
		return points;
	}

	/**
	 * Extends track from a heading to a clicked block. A single arc from a fixed
	 * heading to a block centre ends off-grid whenever the heading is slightly
	 * off, and the next click then bends it the other way, so a row of clicks
	 * lays a wave. Instead, an end near a grid heading, a click along a row, or
	 * a click just beside the row the rail is on, is fitted onto the grid
	 * heading (a curve at the corner, or a sideways shift just before the click),
	 * or as close to it as the turn limits allow.
	 * A click just beside the current line continues it straight. Anything else
	 * is a plain {@link #lay} arc.
	 */
	public static List<double[]> layAligned(
			double ax, double ay, double az,
			float startYaw,
			double bx, double by, double bz,
			double minDistance,
			double maxTurnDegrees,
			double desiredGradeDegrees,
			double maxGradeDegrees,
			double step) throws TrackLayException {
		return layAligned(ax, ay, az, startYaw, bx, by, bz, minDistance, maxTurnDegrees,
				DEFAULT_CURVE_RADIUS, desiredGradeDegrees, maxGradeDegrees, step);
	}

	/**
	 * As {@link #layAligned(double, double, double, float, double, double, double, double, double, double, double, double)},
	 * curving at up to {@code curveRadius}: a longer stroke keeps its bends
	 * that size and runs straight for the rest.
	 */
	public static List<double[]> layAligned(
			double ax, double ay, double az,
			float startYaw,
			double bx, double by, double bz,
			double minDistance,
			double maxTurnDegrees,
			double curveRadius,
			double desiredGradeDegrees,
			double maxGradeDegrees,
			double step) throws TrackLayException {
		ensureMinDistance(ax, ay, az, bx, by, bz, minDistance);
		List<double[]> points = alignedXz(ax, ay, az, startYaw, bx, bz,
				minRadius(minDistance, maxTurnDegrees), curveRadius, maxTurnDegrees, step);
		if (points == null) {
			return lay(ax, ay, az, startYaw, bx, by, bz,
					minDistance, maxTurnDegrees, desiredGradeDegrees, maxGradeDegrees, step);
		}
		TrackGrade.apply(points, ay, by, desiredGradeDegrees, maxGradeDegrees);
		return points;
	}

	/**
	 * Track from A on heading {@code startYaw} that reaches B travelling on
	 * {@code endYaw}, so it runs into the track already at B without a kink.
	 * When no curve within the turn limits does that, it is a plain {@link #lay} arc.
	 */
	public static List<double[]> join(
			double ax, double ay, double az,
			float startYaw,
			double bx, double by, double bz,
			float endYaw,
			double minDistance,
			double maxTurnDegrees,
			double curveRadius,
			double desiredGradeDegrees,
			double maxGradeDegrees,
			double step) throws TrackLayException {
		ensureMinDistance(ax, ay, az, bx, by, bz, minDistance);
		List<double[]> points = null;
		if (Math.hypot(bx - ax, bz - az) >= 1e-6) {
			points = fit(ax, ay, az, startYaw, bx, bz, endYaw,
					minRadius(minDistance, maxTurnDegrees), curveRadius, maxTurnDegrees, step);
		}
		if (points == null) {
			return lay(ax, ay, az, startYaw, bx, by, bz,
					minDistance, maxTurnDegrees, desiredGradeDegrees, maxGradeDegrees, step);
		}
		TrackGrade.apply(points, ay, by, desiredGradeDegrees, maxGradeDegrees);
		return points;
	}

	/** Heading of travel off the end of a polyline, {@code p2} being the end, from the circle through the last three points. */
	public static float endYaw(double[] p0, double[] p1, double[] p2) {
		double chord = yawOf(p2[0] - p1[0], p2[2] - p1[2]);
		if (p0 == null) {
			return (float) chord;
		}
		// The tangent at p2 turns past the chord p1->p2 by the inscribed angle at p0.
		double inscribed = wrap(yawOf(p2[0] - p0[0], p2[2] - p0[2]) - yawOf(p1[0] - p0[0], p1[2] - p0[2]));
		double yaw = wrap(chord + inscribed);
		double grid = Math.round(yaw / GRID_DEGREES) * GRID_DEGREES;
		if (Math.abs(wrap(yaw - grid)) <= SNAP_START_DEGREES) {
			yaw = wrap(grid);
		}
		return (float) yaw;
	}

	/** The tightest radius a plain lay already allows: the full turn over the shortest stroke. */
	static double minRadius(double minDistance, double maxTurnDegrees) {
		double half = Math.toRadians(Math.min(179, maxTurnDegrees)) / 2.0;
		return Math.max(1.0, minDistance) / (2.0 * Math.sin(half));
	}

	private static List<double[]> alignedXz(
			double ax, double ay, double az,
			float startYaw,
			double bx, double bz,
			double minRadius,
			double curveRadius,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		double dx = bx - ax;
		double dz = bz - az;
		if (Math.hypot(dx, dz) < 1e-6) {
			return null;
		}
		double yawRad = Math.toRadians(startYaw);
		double tx = -Math.sin(yawRad);
		double tz = Math.cos(yawRad);
		double along = dx * tx + dz * tz;
		double cross = tx * dz - tz * dx;
		if (along <= 0) {
			return null;
		}
		double chord = yawOf(dx, dz);
		double arcEnd = startYaw + 2.0 * wrap(chord - startYaw);
		double arcGrid = nearestGrid(arcEnd);
		double chordGrid = nearestGrid(chord);
		List<Double> targets = new ArrayList<>();
		// A click within a track's width of a row the rail is on shifts across onto the clicked row.
		if (Math.abs(cross) <= SHIFT_OFFSET && Math.abs(wrap(chord - startYaw)) <= SHIFT_CHORD_DEGREES
				&& Math.abs(wrap(startYaw - nearestGrid(startYaw))) <= 1e-3) {
			targets.add(nearestGrid(startYaw));
		}
		if (Math.abs(wrap(arcEnd - arcGrid)) <= SNAP_END_DEGREES && !targets.contains(arcGrid)) {
			targets.add(arcGrid);
		}
		// Clicking along a row: aim for the row's heading however far off the rail arrives.
		if (Math.abs(wrap(chord - chordGrid)) <= ROW_CHORD_DEGREES && !targets.contains(chordGrid)) {
			targets.add(chordGrid);
		}
		for (double grid : targets) {
			// Too sharp to reach the grid heading here: end as close to it as fits,
			// so each click shrinks the error instead of flipping it.
			double miss = wrap(arcEnd - grid);
			for (double off = 0; off < Math.abs(miss); off += 1.0) {
				double end = grid + Math.signum(miss) * off;
				List<double[]> fitted = fit(
						ax, ay, az, startYaw, bx, bz, end, minRadius, curveRadius, maxTurnDegrees, step);
				if (fitted != null) {
					return fitted;
				}
			}
		}
		if (Math.abs(cross) <= PIN_OFFSET) {
			return TrackGenerate.densify(ax, ay, az, ax + tx * along, ay, az + tz * along, step);
		}
		return null;
	}

	private static double nearestGrid(double yaw) {
		return Math.round(yaw / GRID_DEGREES) * GRID_DEGREES;
	}

	/**
	 * Track from A on heading {@code startYaw} to B on heading {@code endYaw},
	 * or null if it would be too sharp. Curves are {@code curveRadius} where
	 * they fit and tighter, down to {@code minRadius}, where they do not; the
	 * rest is straight. A sideways shift is made just before B, and a turn at
	 * the corner where the two headings' lines cross, unless that would leave
	 * a long run off the grid; then it turns at A and shifts across before B.
	 */
	private static List<double[]> fit(
			double ax, double ay, double az,
			double startYaw,
			double bx, double bz,
			double endYaw,
			double minRadius,
			double curveRadius,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		double turn = wrap(endYaw - startYaw);
		if (Math.abs(turn) > maxTurnDegrees + 1e-6) {
			return null;
		}
		double radius = Math.max(minRadius, curveRadius);
		double sx = -Math.sin(Math.toRadians(startYaw));
		double sz = Math.cos(Math.toRadians(startYaw));
		double ex = -Math.sin(Math.toRadians(endYaw));
		double ez = Math.cos(Math.toRadians(endYaw));
		double vx = bx - ax;
		double vz = bz - az;
		double horiz = Math.hypot(vx, vz);
		if (Math.abs(turn) < 1e-3) {
			if (Math.abs(sx * vz - sz * vx) < 1e-6 * horiz) {
				if (sx * vx + sz * vz <= 0) {
					return null;
				}
				return TrackGenerate.densify(ax, ay, az, bx, ay, bz, step);
			}
			for (double r = radius; r > 0; r = nextRadius(r, minRadius)) {
				List<double[]> shifted = shift(ax, ay, az, startYaw, bx, bz, r, maxTurnDegrees, step);
				if (shifted != null) {
					return shifted;
				}
			}
			return sBend(ax, ay, az, sx, sz, bx, bz, ex, ez, minRadius, maxTurnDegrees, step);
		}
		List<double[]> corner = null;
		// Where the start and end headings' lines cross: A + t*s = B - u*e.
		double det = sx * ez - ex * sz;
		double t = (vx * ez - ex * vz) / det;
		double u = (sx * vz - vx * sz) / det;
		double reach = Math.min(t, u);
		double half = Math.tan(Math.toRadians(Math.abs(turn)) / 2.0);
		if (reach > 1e-6 && reach / half >= minRadius - 1e-6) {
			double tangent = Math.min(reach, radius * half);
			corner = new ArrayList<>();
			double qx = ax + sx * (t - tangent);
			double qz = az + sz * (t - tangent);
			double rx = bx - ex * (u - tangent);
			double rz = bz - ez * (u - tangent);
			append(corner, piece(ax, ay, az, qx, qz, sx, sz, step));
			append(corner, piece(qx, ay, qz, rx, rz, sx, sz, step));
			append(corner, piece(rx, ay, rz, bx, bz, ex, ez, step));
		}
		List<double[]> turned = null;
		for (double r = radius; r > 0 && turned == null; r = nextRadius(r, minRadius)) {
			turned = turnThenShift(ax, ay, az, startYaw, bx, bz, endYaw, r, maxTurnDegrees, step);
		}
		if (corner != null && (turned == null
				|| offGrid(turned) + EXTRA_CURVES_RADII * radius >= offGrid(corner))) {
			return corner;
		}
		if (turned != null) {
			return turned;
		}
		return sBend(ax, ay, az, sx, sz, bx, bz, ex, ez, minRadius, maxTurnDegrees, step);
	}

	/** The next smaller radius to try after {@code radius}, ending on {@code minRadius}; 0 once that has been tried. */
	private static double nextRadius(double radius, double minRadius) {
		if (radius <= minRadius + 1e-9) {
			return 0;
		}
		return Math.max(minRadius, radius * RADIUS_STEP);
	}

	/** Length of the polyline heading off the grid headings. */
	private static double offGrid(List<double[]> points) {
		double length = 0;
		for (int i = 1; i < points.size(); i++) {
			double dx = points.get(i)[0] - points.get(i - 1)[0];
			double dz = points.get(i)[2] - points.get(i - 1)[2];
			double yaw = yawOf(dx, dz);
			if (Math.abs(wrap(yaw - nearestGrid(yaw))) > 0.01) {
				length += Math.hypot(dx, dz);
			}
		}
		return length;
	}

	/** An arc of {@code radius} at A from {@code startYaw} round to {@code endYaw}, then on to B as {@link #shift} does. */
	private static List<double[]> turnThenShift(
			double ax, double ay, double az,
			double startYaw,
			double bx, double bz,
			double endYaw,
			double radius,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		double turn = Math.toRadians(wrap(endYaw - startYaw));
		double sx = -Math.sin(Math.toRadians(startYaw));
		double sz = Math.cos(Math.toRadians(startYaw));
		// Sideways towards increasing yaw, the way a positive turn bends.
		double nx = -Math.cos(Math.toRadians(startYaw));
		double nz = -Math.sin(Math.toRadians(startYaw));
		double forward = radius * Math.sin(Math.abs(turn));
		double aside = Math.signum(turn) * radius * (1.0 - Math.cos(Math.abs(turn)));
		double px = ax + sx * forward + nx * aside;
		double pz = az + sz * forward + nz * aside;
		double ex = -Math.sin(Math.toRadians(endYaw));
		double ez = Math.cos(Math.toRadians(endYaw));
		double along = (bx - px) * ex + (bz - pz) * ez;
		if (along <= 1e-6) {
			return null;
		}
		List<double[]> rest;
		if (Math.abs(ex * (bz - pz) - ez * (bx - px)) < 1e-6) {
			rest = TrackGenerate.densify(px, ay, pz, bx, ay, bz, step);
		} else {
			rest = shift(px, ay, pz, endYaw, bx, bz, radius, maxTurnDegrees, step);
		}
		if (rest == null) {
			return null;
		}
		List<double[]> points = new ArrayList<>();
		append(points, piece(ax, ay, az, px, pz, sx, sz, step));
		append(points, rest);
		return points;
	}

	/**
	 * Straight on from A along {@code yaw}, then a reverse curve of
	 * {@code radius} that ends at B on the same heading: the track keeps its
	 * line and moves across only at the end. A shift wider than the turn limit
	 * allows crosses over on a straight between the two curves. Null if the
	 * reverse curve is longer than the way to B.
	 */
	private static List<double[]> shift(
			double ax, double ay, double az,
			double yaw,
			double bx, double bz,
			double radius,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		double dx = -Math.sin(Math.toRadians(yaw));
		double dz = Math.cos(Math.toRadians(yaw));
		double nx = -Math.cos(Math.toRadians(yaw));
		double nz = -Math.sin(Math.toRadians(yaw));
		double along = (bx - ax) * dx + (bz - az) * dz;
		double across = (bx - ax) * nx + (bz - az) * nz;
		double side = Math.signum(across);
		double wide = Math.abs(across);
		double cap = Math.toRadians(Math.min(maxTurnDegrees, 89));
		double angle;
		double middle = 0;
		if (wide <= 2.0 * radius * (1.0 - Math.cos(cap))) {
			angle = Math.acos(1.0 - wide / (2.0 * radius));
		} else {
			angle = cap;
			middle = (wide - 2.0 * radius * (1.0 - Math.cos(cap))) / Math.sin(cap);
		}
		double length = 2.0 * radius * Math.sin(angle) + middle * Math.cos(angle);
		if (along <= 0 || length > along + 1e-9) {
			return null;
		}
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double mx = dx * cos + side * nx * sin;
		double mz = dz * cos + side * nz * sin;
		double j0x = ax + dx * (along - length);
		double j0z = az + dz * (along - length);
		double j1x = j0x + dx * radius * sin + side * nx * radius * (1.0 - cos);
		double j1z = j0z + dz * radius * sin + side * nz * radius * (1.0 - cos);
		double j2x = j1x + mx * middle;
		double j2z = j1z + mz * middle;
		List<double[]> points = new ArrayList<>();
		append(points, piece(ax, ay, az, j0x, j0z, dx, dz, step));
		append(points, piece(j0x, ay, j0z, j1x, j1z, dx, dz, step));
		append(points, piece(j1x, ay, j1z, j2x, j2z, mx, mz, step));
		append(points, piece(j2x, ay, j2z, bx, bz, mx, mz, step));
		return points;
	}

	/** Two arcs meeting halfway between equal-length tangents from A and B (a biarc). */
	private static List<double[]> sBend(
			double ax, double ay, double az,
			double sx, double sz,
			double bx, double bz,
			double ex, double ez,
			double minRadius,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		double vx = bx - ax;
		double vz = bz - az;
		double wx = sx + ex;
		double wz = sz + ez;
		double vw = vx * wx + vz * wz;
		double vv = vx * vx + vz * vz;
		double a = wx * wx + wz * wz - 4.0;
		double d;
		if (Math.abs(a) < 1e-9) {
			d = vv / (2.0 * vw);
		} else {
			d = (vw - Math.sqrt(vw * vw - a * vv)) / a;
		}
		if (!(d > 1e-6)) {
			return null;
		}
		double p1x = ax + sx * d;
		double p1z = az + sz * d;
		double p2x = bx - ex * d;
		double p2z = bz - ez * d;
		double mx = p2x - p1x;
		double mz = p2z - p1z;
		double ml = Math.hypot(mx, mz);
		if (ml < 1e-9) {
			return null;
		}
		mx /= ml;
		mz /= ml;
		double jx = (p1x + p2x) / 2.0;
		double jz = (p1z + p2z) / 2.0;
		if (!gentle(sx, sz, mx, mz, ax, az, jx, jz, minRadius, maxTurnDegrees)
				|| !gentle(mx, mz, ex, ez, jx, jz, bx, bz, minRadius, maxTurnDegrees)) {
			return null;
		}
		List<double[]> points = new ArrayList<>();
		append(points, piece(ax, ay, az, jx, jz, sx, sz, step));
		append(points, piece(jx, ay, jz, bx, bz, mx, mz, step));
		return points;
	}

	private static boolean gentle(
			double fromX, double fromZ, double toX, double toZ,
			double ax, double az, double bx, double bz,
			double minRadius, double maxTurnDegrees) {
		double turn = Math.abs(wrap(yawOf(toX, toZ) - yawOf(fromX, fromZ)));
		if (turn > maxTurnDegrees + 1e-6) {
			return false;
		}
		if (turn < 1e-3) {
			return true;
		}
		double chord = Math.hypot(bx - ax, bz - az);
		return chord / (2.0 * Math.sin(Math.toRadians(turn) / 2.0)) >= minRadius - 1e-6;
	}

	/** A straight or single arc from A, leaving on (tx, tz), to B. Empty when A and B meet. */
	private static List<double[]> piece(
			double ax, double ay, double az,
			double bx, double bz,
			double tx, double tz,
			double step) throws TrackLayException {
		double dx = bx - ax;
		double dz = bz - az;
		double horiz = Math.hypot(dx, dz);
		if (horiz < 1e-6) {
			return List.of();
		}
		double cross = tx * dz - tz * dx;
		if (Math.abs(cross) <= 1e-6 * horiz) {
			return TrackGenerate.densify(ax, ay, az, bx, ay, bz, step);
		}
		return arcXz(ax, ay, az, bx, bz, tx, tz, cross, horiz, 180, step);
	}

	private static void append(List<double[]> points, List<double[]> piece) {
		int from = points.isEmpty() ? 0 : 1;
		for (int i = from; i < piece.size(); i++) {
			points.add(piece.get(i));
		}
	}

	private static double yawOf(double dx, double dz) {
		return Math.toDegrees(Math.atan2(-dx, dz));
	}

	private static double wrap(double degrees) {
		double d = degrees % 360.0;
		if (d > 180.0) {
			d -= 360.0;
		} else if (d <= -180.0) {
			d += 360.0;
		}
		return d;
	}

	private static List<double[]> arcXz(
			double ax, double ay, double az,
			double bx, double bz,
			double tx, double tz,
			double cross, double horiz,
			double maxTurnDegrees,
			double step) throws TrackLayException {
		double vDotN = cross;
		double radius = (horiz * horiz) / (2.0 * vDotN);
		double nx = -tz;
		double nz = tx;
		if (radius < 0) {
			radius = -radius;
			nx = -nx;
			nz = -nz;
		}
		double cx = ax + nx * radius;
		double cz = az + nz * radius;
		double pox = ax - cx;
		double poz = az - cz;
		double qox = bx - cx;
		double qoz = bz - cz;
		double angle = Math.atan2(pox * qoz - poz * qox, pox * qox + poz * qoz);
		double absDeg = Math.abs(Math.toDegrees(angle));
		if (absDeg > maxTurnDegrees + 1e-6) {
			throw new TrackLayException("Turn is too sharp (heading change "
					+ format(absDeg)
					+ " degrees, max "
					+ format(maxTurnDegrees)
					+ "). Lay a longer or gentler curve.");
		}
		double arcLen = radius * Math.abs(angle);
		double increment = Math.max(1e-6, step);
		int n = Math.max(1, (int) Math.floor(arcLen / increment));
		List<double[]> points = new ArrayList<>();
		for (int i = 0; i <= n; i++) {
			double t = (i * increment) / arcLen;
			if (t > 1) {
				t = 1;
			}
			double a = angle * t;
			double cos = Math.cos(a);
			double sin = Math.sin(a);
			double rx = pox * cos - poz * sin;
			double rz = pox * sin + poz * cos;
			points.add(new double[] {cx + rx, ay, cz + rz});
		}
		double[] last = points.get(points.size() - 1);
		if (Math.hypot(last[0] - bx, last[2] - bz) > 1e-4) {
			points.add(new double[] {bx, ay, bz});
		}
		return points;
	}

	private static void ensureMinDistance(
			double ax, double ay, double az,
			double bx, double by, double bz,
			double minDistance) throws TrackLayException {
		double dist = Math.sqrt(
				(bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
		if (dist < minDistance) {
			double remain = minDistance - dist;
			throw new TrackLayException("Need at least "
					+ format(minDistance)
					+ " blocks (you are "
					+ format(dist)
					+ ", "
					+ format(remain)
					+ " more).");
		}
	}

	private static String format(double value) {
		return String.format(Locale.US, "%.1f", value);
	}
}
