package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.vehicleframework.bones.ConvertedAngle;

/**
 * A surface on a car that players can walk on, such as a flat car's deck. It is laid out in the
 * model's own blocks from its origin: x runs across the car, z along it with +z facing
 * +s, and {@code top} is the height of the surface.
 *
 * <p>Minecraft only lets players stand on a few entities, and their boxes never turn.
 * The deck is covered with square boxes instead, each as wide as a shulker can grow,
 * that follow the car and stay upright on bends.
 */
public final class Deck {
	// Shulkers cannot be scaled past this.
	public static final double MAX_BOX = 3.0;
	// How far past the deck's edge a player can stand, as their feet overhang it.
	static final double EDGE = 0.3;
	// Feet this far under the surface still count as on it, as the train climbs.
	static final double BELOW = 0.25;
	// A jump from the deck rises about 1.25 blocks; carry players through it.
	static final double ABOVE = 1.5;

	private final double minX;
	private final double maxX;
	private final double minZ;
	private final double maxZ;
	private final double top;
	private final double boxSize;

	public Deck(double minX, double maxX, double minZ, double maxZ, double top, double boxSize) {
		this.minX = Math.min(minX, maxX);
		this.maxX = Math.max(minX, maxX);
		this.minZ = Math.min(minZ, maxZ);
		this.maxZ = Math.max(minZ, maxZ);
		this.top = top;
		double widest = Math.min(this.maxX - this.minX, this.maxZ - this.minZ);
		this.boxSize = Math.max(0.25, Math.min(Math.min(MAX_BOX, boxSize), widest));
	}

	/** The deck in a car's {@code walkable} section, or null if it has none or it is invalid. */
	public static Deck fromConfig(ConfigurationSection config) {
		if (config == null) {
			return null;
		}
		List<Double> x = config.getDoubleList("x");
		List<Double> z = config.getDoubleList("z");
		if (x.size() != 2 || z.size() != 2 || !config.contains("top")) {
			return null;
		}
		if (Math.abs(x.get(0) - x.get(1)) < 0.25 || Math.abs(z.get(0) - z.get(1)) < 0.25) {
			return null;
		}
		return new Deck(x.get(0), x.get(1), z.get(0), z.get(1), config.getDouble("top"),
				config.getDouble("box-size", MAX_BOX));
	}

	public double top() {
		return top;
	}

	public double boxSize() {
		return boxSize;
	}

	/** Where a car's model sits: its origin, which way it faces and how far it tilts. */
	public record Frame(double x, double y, double z, float yaw, float pitch) {
		double fx() {
			return -Math.sin(Math.toRadians(yaw));
		}

		double fz() {
			return Math.cos(Math.toRadians(yaw));
		}

		double slope() {
			return -Math.tan(Math.toRadians(pitch));
		}

		/** A point on the car, in its model's blocks, as a world position. */
		double[] toWorld(double localX, double height, double localZ) {
			double fx = fx();
			double fz = fz();
			// +x on the model is world +x when the car faces +z.
			return new double[] {
					x + fz * localX + fx * localZ,
					y + height + slope() * localZ,
					z + fx * -localX + fz * localZ };
		}

		/** A world position as a point on the car: across, height above the origin, along. */
		double[] toLocal(double worldX, double worldY, double worldZ) {
			double dx = worldX - x;
			double dz = worldZ - z;
			double fx = fx();
			double fz = fz();
			double along = dx * fx + dz * fz;
			double across = dx * fz - dz * fx;
			return new double[] { across, worldY - y - slope() * along, along };
		}

		/** Whether this frame is in the same place as another, near enough to not move. */
		boolean same(Frame other) {
			if (other == null) {
				return false;
			}
			double dx = x - other.x;
			double dy = y - other.y;
			double dz = z - other.z;
			return dx * dx + dy * dy + dz * dz < 1e-8
					&& Math.abs(ConvertedAngle.wrapDegrees(yaw - other.yaw)) < 1e-3
					&& Math.abs(pitch - other.pitch) < 1e-3;
		}

		double distance(Frame other) {
			double dx = x - other.x;
			double dy = y - other.y;
			double dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/** A solid cube, by the centre of its base in the world and its width. */
	public record Box(double x, double y, double z, double size) {
	}

	/** Box centres across and along the car, in its model's blocks. */
	List<double[]> layout() {
		List<double[]> centres = new ArrayList<>();
		for (double across : spread(minX, maxX)) {
			for (double along : spread(minZ, maxZ)) {
				centres.add(new double[] { across, along });
			}
		}
		return centres;
	}

	// Box centres covering from min to max, overlapping where the span does not divide evenly.
	private List<Double> spread(double min, double max) {
		List<Double> centres = new ArrayList<>();
		double span = max - min;
		int count = (int) Math.ceil(span / boxSize - 1e-9);
		if (count <= 1) {
			centres.add((min + max) / 2);
			return centres;
		}
		double first = min + boxSize / 2;
		double step = (span - boxSize) / (count - 1);
		for (int i = 0; i < count; i++) {
			centres.add(first + i * step);
		}
		return centres;
	}

	/** The cubes that make up the deck with the car in this frame, tops level with the deck. */
	public List<Box> boxes(Frame frame) {
		List<Box> boxes = new ArrayList<>();
		for (double[] centre : layout()) {
			double[] at = frame.toWorld(centre[0], top - boxSize, centre[1]);
			boxes.add(new Box(at[0], at[1], at[2], boxSize));
		}
		return boxes;
	}

	/** Whether feet at this world position are on the deck, or jumping from it. */
	public boolean carries(Frame frame, double worldX, double worldY, double worldZ) {
		double[] local = frame.toLocal(worldX, worldY, worldZ);
		double height = local[1] - top;
		return local[0] >= minX - EDGE && local[0] <= maxX + EDGE
				&& local[2] >= minZ - EDGE && local[2] <= maxZ + EDGE
				&& height >= -BELOW && height <= ABOVE;
	}

	/** Where a point on the car in {@code from} is once the car has moved to {@code to}. */
	public static double[] carry(Frame from, Frame to, double worldX, double worldY, double worldZ) {
		double[] local = from.toLocal(worldX, worldY, worldZ);
		return to.toWorld(local[0], local[1], local[2]);
	}

	/** How far the car turned between two frames, in degrees. */
	public static float turn(Frame from, Frame to) {
		return ConvertedAngle.wrapDegrees(to.yaw - from.yaw);
	}
}
