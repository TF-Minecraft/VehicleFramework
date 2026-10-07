package net.tfminecraft.vehicleframework.tracks;

import java.util.Optional;
import java.util.UUID;

import org.json.simple.JSONObject;

public final class TrackJunction {
	public enum Side {
		LEFT,
		RIGHT
	}

	public final UUID id;
	public final UUID stemSplineId;
	public final double s;
	public final int facingSign;
	public final Side side;
	public final UUID branchSplineId;
	public final boolean thrown;
	public final double turnoutEndS;
	/** The branch meets the frog at its last sample: a turnout that rejoins this stem. */
	public final boolean atEnd;

	public TrackJunction(
			UUID id,
			UUID stemSplineId,
			double s,
			int facingSign,
			Side side,
			UUID branchSplineId) {
		this(id, stemSplineId, s, facingSign, side, branchSplineId, false);
	}

	public TrackJunction(
			UUID id,
			UUID stemSplineId,
			double s,
			int facingSign,
			Side side,
			UUID branchSplineId,
			boolean thrown) {
		this(id, stemSplineId, s, facingSign, side, branchSplineId, thrown, 0);
	}

	public TrackJunction(
			UUID id,
			UUID stemSplineId,
			double s,
			int facingSign,
			Side side,
			UUID branchSplineId,
			boolean thrown,
			double turnoutEndS) {
		this(id, stemSplineId, s, facingSign, side, branchSplineId, thrown, turnoutEndS, false);
	}

	public TrackJunction(
			UUID id,
			UUID stemSplineId,
			double s,
			int facingSign,
			Side side,
			UUID branchSplineId,
			boolean thrown,
			double turnoutEndS,
			boolean atEnd) {
		if (id == null || stemSplineId == null) {
			throw new IllegalArgumentException("junction needs id and stem");
		}
		if (side == null) {
			throw new IllegalArgumentException("junction needs side");
		}
		if (turnoutEndS < 0) {
			throw new IllegalArgumentException("junction turnoutEndS must be >= 0");
		}
		this.id = id;
		this.stemSplineId = stemSplineId;
		this.s = s;
		this.facingSign = facingSign < 0 ? -1 : 1;
		this.side = side;
		this.branchSplineId = branchSplineId;
		this.thrown = thrown;
		this.turnoutEndS = turnoutEndS;
		this.atEnd = atEnd;
	}

	public Optional<UUID> branchSplineId() {
		return Optional.ofNullable(branchSplineId);
	}

	public TrackJunction withBranch(UUID branchId) {
		return new TrackJunction(id, stemSplineId, s, facingSign, side, branchId, thrown, turnoutEndS, atEnd);
	}

	public TrackJunction withS(double nextS) {
		return new TrackJunction(id, stemSplineId, nextS, facingSign, side, branchSplineId, thrown, turnoutEndS, atEnd);
	}

	public TrackJunction withStem(UUID stemId, double nextS) {
		return new TrackJunction(id, stemId, nextS, facingSign, side, branchSplineId, thrown, turnoutEndS, atEnd);
	}

	public TrackJunction withSide(Side nextSide) {
		return new TrackJunction(id, stemSplineId, s, facingSign, nextSide, branchSplineId, thrown, turnoutEndS, atEnd);
	}

	public TrackJunction withFacing(int sign) {
		return new TrackJunction(id, stemSplineId, s, sign, side, branchSplineId, thrown, turnoutEndS, atEnd);
	}

	public TrackJunction withThrown(boolean nextThrown) {
		return new TrackJunction(id, stemSplineId, s, facingSign, side, branchSplineId, nextThrown, turnoutEndS, atEnd);
	}

	public TrackJunction withTurnoutEndS(double nextTurnoutEndS) {
		return new TrackJunction(id, stemSplineId, s, facingSign, side, branchSplineId, thrown, nextTurnoutEndS, atEnd);
	}

	public TrackJunction withAtEnd(boolean nextAtEnd) {
		return new TrackJunction(id, stemSplineId, s, facingSign, side, branchSplineId, thrown, turnoutEndS, nextAtEnd);
	}

	/** Branch arc length at the frog. */
	public double branchFrogS(double branchLength) {
		return atEnd ? branchLength : 0;
	}

	/** Distance along the branch from the frog to {@code branchS}. */
	public double fromFrog(double branchS, double branchLength) {
		return atEnd ? branchLength - branchS : branchS;
	}

	/** Multiplies body orientation when crossing the frog between stem and branch, either way. */
	public int crossingSign() {
		return atEnd ? -facingSign : facingSign;
	}

	public static int facingSign(float playerYaw, float stemYaw) {
		float delta = net.tfminecraft.vehicleframework.bones.ConvertedAngle.shortestDelta(stemYaw, playerYaw);
		return Math.abs(delta) <= 90f ? 1 : -1;
	}

	public static Side sideFrom(float facingYaw, double dx, double dz) {
		double yawRad = Math.toRadians(facingYaw);
		double fx = -Math.sin(yawRad);
		double fz = Math.cos(yawRad);
		double cross = fx * dz - fz * dx;
		return cross > 0 ? Side.RIGHT : Side.LEFT;
	}

	public static double wrapS(double s, double length, boolean loop) {
		if (length <= 0) {
			return 0;
		}
		if (loop) {
			double wrapped = s % length;
			if (wrapped < 0) {
				wrapped += length;
			}
			return wrapped;
		}
		return Math.max(0, Math.min(length, s));
	}

	public static double arcDistance(double sA, double sB, double length, boolean loop) {
		double d = Math.abs(sA - sB);
		if (!loop || length <= 0) {
			return d;
		}
		return Math.min(d, length - d);
	}

	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		JSONObject root = new JSONObject();
		root.put("id", id.toString());
		root.put("stem", stemSplineId.toString());
		root.put("s", s);
		root.put("facing", (long) facingSign);
		root.put("side", side.name());
		root.put("thrown", thrown);
		if (branchSplineId != null) {
			root.put("branch", branchSplineId.toString());
		}
		if (turnoutEndS > 0) {
			root.put("turnoutS", turnoutEndS);
		}
		if (atEnd) {
			root.put("branchEnd", true);
		}
		return root;
	}

	public static TrackJunction fromJson(JSONObject root) {
		if (root == null) {
			throw new IllegalArgumentException("json is null");
		}
		UUID id = parseUuidRequired(root.get("id"));
		UUID stem = parseUuidRequired(root.get("stem"));
		double s = asDouble(root.get("s"));
		int facing = (int) asDouble(root.get("facing"));
		Side side = parseSide(root.get("side"));
		UUID branch = parseUuidOptional(root.get("branch"));
		boolean thrown = asBoolean(root.get("thrown"));
		double turnoutEndS = asDouble(root.get("turnoutS"));
		boolean atEnd = asBoolean(root.get("branchEnd"));
		return new TrackJunction(id, stem, s, facing, side, branch, thrown, turnoutEndS, atEnd);
	}

	private static Side parseSide(Object raw) {
		if (raw == null) {
			throw new IllegalArgumentException("junction needs side");
		}
		try {
			return Side.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("junction side must be LEFT or RIGHT");
		}
	}

	private static UUID parseUuidRequired(Object raw) {
		if (raw == null) {
			throw new IllegalArgumentException("junction json missing uuid");
		}
		try {
			return UUID.fromString(String.valueOf(raw));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("junction json has invalid uuid");
		}
	}

	private static UUID parseUuidOptional(Object raw) {
		if (raw == null) {
			return null;
		}
		String text = String.valueOf(raw).trim();
		if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
			return null;
		}
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean asBoolean(Object raw) {
		if (raw instanceof Boolean b) {
			return b;
		}
		if (raw == null) {
			return false;
		}
		return Boolean.parseBoolean(String.valueOf(raw).trim());
	}

	private static double asDouble(Object raw) {
		if (raw instanceof Number n) {
			return n.doubleValue();
		}
		if (raw == null) {
			return 0;
		}
		return Double.parseDouble(String.valueOf(raw));
	}
}
