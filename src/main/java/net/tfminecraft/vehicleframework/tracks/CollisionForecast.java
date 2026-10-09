package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs trains forward along their switch settings and finds when two would collide.
 * As in {@link TrainCollision}, a collision is a head (a locomotive, or a lone car)
 * touching any part of another train; cars alone pass through each other. A train keeps
 * its speed, or keeps accelerating up to its top speed while its driver opens the
 * throttle; when either might, both cases are tried and the sooner contact counts.
 */
public final class CollisionForecast {
	// Short enough that two trains closing at full overdrive cannot pass through each other between checks.
	public static final double STEP_SECONDS = 0.1;

	public enum Kind {
		ONCOMING,
		STOPPED,
		AHEAD,
		BEHIND
	}

	/**
	 * A train on its path at {@code speed} blocks a second (0 when standing), gaining
	 * {@code acceleration} blocks a second each second until it reaches {@code topSpeed}.
	 */
	public record Mover(TrainPath path, double speed, double acceleration, double topSpeed) {
		public Mover(TrainPath path, double speed) {
			this(path, speed, 0, speed);
		}

		public boolean standing() {
			return speed <= 0 && acceleration <= 0;
		}

		/** Blocks covered after {@code seconds}. */
		public double distance(double seconds) {
			double top = Math.max(speed, topSpeed);
			if (acceleration <= 0 || speed >= top) {
				return speed * seconds;
			}
			double rising = Math.min(seconds, (top - speed) / acceleration);
			return speed * rising + acceleration * rising * rising / 2 + top * (seconds - rising);
		}

		double speedAt(double seconds) {
			return acceleration <= 0 ? speed : Math.min(Math.max(speed, topSpeed), speed + acceleration * seconds);
		}

		List<Mover> cases() {
			return acceleration > 0 ? List.of(this, new Mover(path, speed)) : List.of(this);
		}
	}

	public record Contact(double seconds, Kind kind) {
	}

	/**
	 * One train's rails over the look-ahead, worked out once and shared by every train it
	 * is checked against. Each step holds where its head and whole body reach, including
	 * the margin ahead of it.
	 */
	public static final class Sweep {
		private final Mover mover;
		private final double horizon;
		private final double margin;
		private final List<TrainPath.Range> reach;
		private final List<List<TrainPath.Range>> heads = new ArrayList<>();
		private final List<List<TrainPath.Range>> bodies = new ArrayList<>();

		Sweep(Mover mover, double horizon, double margin) {
			this.mover = mover;
			this.horizon = Math.max(0, horizon);
			this.margin = margin;
			this.reach = mover.path().ranges(0, mover.path().length() + margin);
		}

		private int steps() {
			return (int) Math.ceil(horizon / STEP_SECONDS);
		}

		private double time(int step) {
			return Math.min(horizon, step * STEP_SECONDS);
		}

		private List<TrainPath.Range> head(int step) {
			fill(step);
			return heads.get(step);
		}

		private List<TrainPath.Range> body(int step) {
			fill(step);
			return bodies.get(step);
		}

		private void fill(int step) {
			TrainPath path = mover.path();
			while (heads.size() <= step) {
				double rear = path.rearAfter(mover.distance(time(heads.size())));
				heads.add(path.hasHead()
						? path.ranges(rear + path.headFrom(), rear + path.headTo() + margin) : List.of());
				bodies.add(path.ranges(rear, rear + path.trainLength() + margin));
			}
		}

		/** When this train first collides with {@code other}, or null within the look-ahead. */
		public Contact first(Mover other) {
			return first(other, Double.POSITIVE_INFINITY);
		}

		/**
		 * As {@link #first(Mover)}, looking only for contacts sooner than {@code before}
		 * seconds; checking many trains, only the soonest contact matters.
		 */
		public Contact first(Mover other, double before) {
			if (mover.standing() || TrainPath.overlap(reach, other.path().ranges(0, other.path().length())) == null) {
				return null;
			}
			Contact first = null;
			double limit = before;
			for (Mover them : other.cases()) {
				Contact contact = firstAgainst(them, limit);
				if (contact != null) {
					first = contact;
					limit = contact.seconds();
				}
			}
			return first;
		}

		private Contact firstAgainst(Mover other, double before) {
			TrainPath path = other.path();
			List<TrainPath.Range> body = null;
			List<TrainPath.Range> head = null;
			for (int i = 0; i <= steps() && time(i) < before; i++) {
				if (body == null || !other.standing()) {
					double rear = path.rearAfter(other.distance(time(i)));
					body = path.ranges(rear, rear + path.trainLength());
					head = path.hasHead() ? path.ranges(rear + path.headFrom(), rear + path.headTo()) : List.of();
				}
				TrainPath.Range[] hit = TrainPath.overlap(head(i), body);
				if (hit == null) {
					hit = TrainPath.overlap(body(i), head);
				}
				if (hit != null) {
					return new Contact(time(i), kind(mover, other, hit, time(i)));
				}
			}
			return null;
		}
	}

	private CollisionForecast() {
	}

	public static Sweep sweep(Mover mover, double horizon, double margin) {
		return new Sweep(mover, horizon, margin);
	}

	/** When {@code a} first collides with {@code b} within {@code horizon} seconds, or null. */
	public static Contact first(Mover a, Mover b, double horizon, double margin) {
		Contact first = null;
		for (Mover us : a.cases()) {
			Contact contact = sweep(us, horizon, margin).first(b);
			if (contact != null && (first == null || contact.seconds() < first.seconds())) {
				first = contact;
			}
		}
		return first;
	}

	private static Kind kind(Mover a, Mover b, TrainPath.Range[] hit, double seconds) {
		if (b.standing()) {
			return Kind.STOPPED;
		}
		if (hit[0].direction() != hit[1].direction()) {
			return Kind.ONCOMING;
		}
		return a.speedAt(seconds) >= b.speedAt(seconds) ? Kind.AHEAD : Kind.BEHIND;
	}
}
