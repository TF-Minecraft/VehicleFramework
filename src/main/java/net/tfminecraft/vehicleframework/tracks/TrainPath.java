package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The rails a train will run over, from its trailing coupler forwards. At the start the
 * train covers the first {@link #trainLength()} blocks; moving d blocks covers d to
 * d + trainLength, until its leading end reaches the end of the path. The head is the
 * part that explodes on contact: its locomotive, or a lone car.
 */
public final class TrainPath {

	/** Rail from {@code lo} to {@code hi} on one spline, run over towards {@code direction}. */
	public record Range(UUID splineId, double lo, double hi, int direction) {
		boolean overlaps(Range other) {
			return splineId.equals(other.splineId) && lo <= other.hi + 1e-9 && other.lo <= hi + 1e-9;
		}
	}

	private final List<TrainRoute.Piece> pieces;
	private final double trainLength;
	private final double length;
	private final double headFrom;
	private final double headTo;
	private final Set<UUID> splines = new HashSet<>();

	/** A train that is all head, such as a lone locomotive. */
	public TrainPath(List<TrainRoute.Piece> pieces, double trainLength) {
		this(pieces, trainLength, 0, trainLength);
	}

	/**
	 * A train whose head spans {@code headFrom} to {@code headTo} blocks from its trailing
	 * end; a train with no head, such as cars whose locomotive is elsewhere, has
	 * {@code headFrom > headTo}.
	 */
	public TrainPath(List<TrainRoute.Piece> pieces, double trainLength, double headFrom, double headTo) {
		this.pieces = List.copyOf(pieces);
		double end = 0;
		for (TrainRoute.Piece piece : this.pieces) {
			end = Math.max(end, piece.start() + piece.length());
			splines.add(piece.splineId());
		}
		this.length = end;
		this.trainLength = Math.min(Math.max(0, trainLength), end);
		this.headFrom = Math.max(0, headFrom);
		this.headTo = Math.min(this.trainLength, headTo);
	}

	/** A lone car standing at {@code s}, covering {@code halfLength} either side, cut short at track ends. */
	public static TrainPath standing(TrackSpline spline, double s, double halfLength) {
		double half = Math.max(0, halfLength);
		double from = s - half;
		double to = s + half;
		if (!spline.isLoop()) {
			from = Math.max(0, from);
			to = Math.min(spline.length(), to);
		}
		TrainRoute.Piece piece = new TrainRoute.Piece(spline.getId(), from, 1, Math.max(0, to - from), 0,
				spline.isLoop(), spline.length());
		return new TrainPath(List.of(piece), to - from);
	}

	public double trainLength() {
		return trainLength;
	}

	public double length() {
		return length;
	}

	public boolean hasHead() {
		return headFrom <= headTo;
	}

	public double headFrom() {
		return headFrom;
	}

	public double headTo() {
		return headTo;
	}

	public boolean sharesSpline(TrainPath other) {
		for (UUID id : other.splines) {
			if (splines.contains(id)) {
				return true;
			}
		}
		return false;
	}

	/** Where the trailing end is after moving {@code distance}; trains stop at the end of the path. */
	public double rearAfter(double distance) {
		return Math.min(Math.max(0, distance), length - trainLength);
	}

	/** The rail between {@code from} and {@code to} blocks along the path. */
	public List<Range> ranges(double from, double to) {
		double a0 = Math.max(0, from);
		double b0 = Math.min(length, to);
		List<Range> out = new ArrayList<>();
		for (TrainRoute.Piece piece : pieces) {
			double a = Math.max(a0, piece.start());
			double b = Math.min(b0, piece.start() + piece.length());
			if (b < a) {
				continue;
			}
			double s0 = piece.s() + piece.direction() * (a - piece.start());
			double s1 = piece.s() + piece.direction() * (b - piece.start());
			add(out, piece, Math.min(s0, s1), Math.max(s0, s1));
		}
		return out;
	}

	/** The first pair of ranges, one from each list, that share rail; null when none do. */
	public static Range[] overlap(List<Range> a, List<Range> b) {
		for (Range x : a) {
			for (Range y : b) {
				if (x.overlaps(y)) {
					return new Range[] {x, y};
				}
			}
		}
		return null;
	}

	// A loop's arc length wraps, so a stretch across its seam becomes two ranges.
	private static void add(List<Range> out, TrainRoute.Piece piece, double lo, double hi) {
		if (!piece.loop() || piece.splineLength() <= 1e-9) {
			out.add(new Range(piece.splineId(), lo, hi, piece.direction()));
			return;
		}
		double length = piece.splineLength();
		double shift = Math.floor(lo / length) * length;
		double from = lo - shift;
		double to = hi - shift;
		if (to <= length) {
			out.add(new Range(piece.splineId(), from, to, piece.direction()));
			return;
		}
		out.add(new Range(piece.splineId(), from, length, piece.direction()));
		out.add(new Range(piece.splineId(), 0, Math.min(length, to - length), piece.direction()));
	}
}
