package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Shortest along-track distance between two horizontal areas.
 * The splines and junctions are read only; nothing here loads chunks or
 * touches entities. A broken segment blocks a route.
 */
public final class TrackRouteQuery {
	private static final int SRC = 0;
	private static final int DST = 1;
	private static final double EPS = 1e-9;

	private TrackRouteQuery() {
	}

	/**
	 * Length in blocks of the shortest route along track from any point within
	 * {@code radiusA} of {@code (ax, az)} to any point within {@code radiusB}
	 * of {@code (bx, bz)}, in {@code world}. Empty when the areas are not
	 * joined by track. Height is ignored. A loop is travelled the shorter way.
	 * A junction joins its stem at {@code s} to the branch at {@code s = 0};
	 * which way the switch is thrown, and which way the turnout faces, are
	 * ignored. A junction with no branch joins nothing. Broken segments block
	 * routes: that edge cannot be crossed, on a straight spline or either way
	 * round a loop.
	 */
	public static OptionalDouble shortestRouteLength(
			Collection<TrackSpline> splines,
			Collection<TrackJunction> junctions,
			String world,
			double ax, double az, double radiusA,
			double bx, double bz, double radiusB) {
		Optional<Path> path = findRoute(splines, junctions, world, ax, az, radiusA, bx, bz, radiusB);
		return path.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(path.get().length);
	}

	/**
	 * The shortest route measured by {@link #shortestRouteLength}, with points
	 * ordered from A to B and sampled by arc length. Each spline piece's exit
	 * is included. Overlapping areas give one point and length 0. Invalid
	 * spacing defaults to 4 blocks, with a minimum of 0.5 blocks.
	 * The splines and junctions are read only; no chunks or entities are touched.
	 */
	public static Optional<TrackRoute> shortestRoute(
			Collection<TrackSpline> splines,
			Collection<TrackJunction> junctions,
			String world,
			double ax, double az, double radiusA,
			double bx, double bz, double radiusB,
			double spacing) {
		if (!Double.isFinite(spacing) || spacing <= 0) {
			spacing = 4;
		} else {
			spacing = Math.max(spacing, 0.5);
		}
		Optional<Path> found = findRoute(splines, junctions, world, ax, az, radiusA, bx, bz, radiusB);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Path path = found.get();
		List<TrackSamplePoint> points = new ArrayList<>();
		for (Leg leg : path.legs) {
			TrackSamplePoint start = samplePoint(leg.spline, leg.from);
			if (points.isEmpty() || !points.getLast().equals(start)) {
				points.add(start);
			}
			if (path.length == 0) {
				break;
			}
			double span = Math.abs(leg.to - leg.from);
			double sign = Math.signum(leg.to - leg.from);
			for (int i = 1; i * spacing < span - EPS; i++) {
				points.add(samplePoint(leg.spline, leg.from + sign * i * spacing));
			}
			if (span > 0) {
				points.add(samplePoint(leg.spline, leg.to));
			}
		}
		return Optional.of(new TrackRoute(path.length, points));
	}

	private static TrackSamplePoint samplePoint(TrackSpline spline, double s) {
		TrackPose pose = spline.sampleAt(s);
		return new TrackSamplePoint(spline.getId(), pose.x, pose.y, pose.z);
	}

	private static Optional<Path> findRoute(
			Collection<TrackSpline> splines,
			Collection<TrackJunction> junctions,
			String world,
			double ax, double az, double radiusA,
			double bx, double bz, double radiusB) {
		if (world == null || splines == null || junctions == null) {
			return Optional.empty();
		}
		if (!(radiusA >= 0) || !(radiusB >= 0)) {
			return Optional.empty();
		}
		Map<UUID, TrackSpline> byId = new HashMap<>();
		for (TrackSpline spline : splines) {
			if (spline != null && world.equals(spline.getWorld())) {
				byId.put(spline.getId(), spline);
			}
		}
		if (byId.isEmpty()) {
			return Optional.empty();
		}

		List<Touch> touches = new ArrayList<>();
		boolean sawA = false;
		boolean sawB = false;
		for (TrackSpline spline : byId.values()) {
			List<Interval> nearA = cover(spline, ax, az, radiusA);
			List<Interval> nearB = cover(spline, bx, bz, radiusB);
			if (!nearA.isEmpty()) {
				sawA = true;
			}
			if (!nearB.isEmpty()) {
				sawB = true;
			}
			touches.add(new Touch(spline, nearA, nearB));
		}
		if (!sawA || !sawB) {
			return Optional.empty();
		}

		List<TrackJunction> linked = new ArrayList<>();
		for (TrackJunction junction : junctions) {
			if (junction == null || junction.branchSplineId == null) {
				continue;
			}
			if (junction.stemSplineId.equals(junction.branchSplineId)) {
				continue;
			}
			if (byId.get(junction.stemSplineId) == null || byId.get(junction.branchSplineId) == null) {
				continue;
			}
			linked.add(junction);
		}

		// Each junction is one point: a port on the stem at junction.s and a
		// port on the branch at s = 0, joined by an edge of length 0. Ports on
		// one spline are joined in s order. On a loop the last port also joins
		// the first the other way round.
		int nodes = 2 + linked.size() * 2;
		List<List<Edge>> adj = new ArrayList<>(nodes);
		for (int i = 0; i < nodes; i++) {
			adj.add(new ArrayList<>());
		}
		Map<UUID, List<Anchor>> anchors = new HashMap<>();
		for (int i = 0; i < linked.size(); i++) {
			TrackJunction junction = linked.get(i);
			TrackSpline stem = byId.get(junction.stemSplineId);
			TrackSpline branch = byId.get(junction.branchSplineId);
			int stemNode = 2 + i * 2;
			int branchNode = stemNode + 1;
			double stemS = TrackJunction.wrapS(junction.s, stem.length(), stem.isLoop());
			anchor(anchors, stem.getId(), stemS, stemNode);
			anchor(anchors, branch.getId(), 0, branchNode);
			link(adj, stemNode, branchNode, 0, null);
		}

		for (Touch touch : touches) {
			linkSpline(adj, touch.spline, touch.nearA, touch.nearB, anchors.get(touch.spline.getId()));
		}

		return dijkstra(adj, SRC, DST);
	}

	private static void anchor(Map<UUID, List<Anchor>> anchors, UUID splineId, double s, int node) {
		anchors.computeIfAbsent(splineId, id -> new ArrayList<>()).add(new Anchor(s, node));
	}

	private static void link(List<List<Edge>> adj, int a, int b, double weight, Leg leg) {
		oneWay(adj, a, b, weight, leg);
		oneWay(adj, b, a, weight, leg == null ? null : leg.reverse());
	}

	private static void oneWay(List<List<Edge>> adj, int from, int to, double weight, Leg leg) {
		if (from == to || !Double.isFinite(weight)) {
			return;
		}
		if (weight < 0) {
			weight = 0;
		}
		adj.get(from).add(new Edge(to, weight, leg));
	}

	private static Optional<Path> dijkstra(List<List<Edge>> adj, int src, int dst) {
		double[] dist = new double[adj.size()];
		Arrays.fill(dist, Double.POSITIVE_INFINITY);
		dist[src] = 0;
		boolean[] done = new boolean[adj.size()];
		int[] previous = new int[adj.size()];
		Edge[] incoming = new Edge[adj.size()];
		PriorityQueue<Walk> queue = new PriorityQueue<>(Comparator.comparingDouble(step -> step.dist));
		queue.add(new Walk(src, 0));
		while (!queue.isEmpty()) {
			Walk step = queue.poll();
			if (done[step.node]) {
				continue;
			}
			done[step.node] = true;
			if (step.node == dst) {
				List<Leg> legs = new ArrayList<>();
				for (int at = dst; at != src; at = previous[at]) {
					if (incoming[at].leg != null) {
						legs.add(incoming[at].leg);
					}
				}
				Collections.reverse(legs);
				return Optional.of(new Path(step.dist, legs));
			}
			for (Edge edge : adj.get(step.node)) {
				double next = step.dist + edge.weight;
				if (next < dist[edge.to]) {
					dist[edge.to] = next;
					previous[edge.to] = step.node;
					incoming[edge.to] = edge;
					queue.add(new Walk(edge.to, next));
				}
			}
		}
		return Optional.empty();
	}

	private static List<Interval> cover(TrackSpline spline, double x, double z, double radius) {
		List<TrackSample> samples = spline.getSamples();
		int n = samples.size();
		int edges = spline.edgeCount();
		double r2 = radius * radius;
		List<Interval> raw = new ArrayList<>();
		for (int i = 0; i < edges; i++) {
			TrackSample a = samples.get(Math.min(i, n - 1));
			TrackSample b;
			double s0;
			double s1;
			if (i < n - 1) {
				b = samples.get(i + 1);
				s0 = a.s;
				s1 = b.s;
			} else {
				b = samples.get(0);
				s0 = samples.get(n - 1).s;
				s1 = spline.length();
			}
			double ux = b.x - a.x;
			double uz = b.z - a.z;
			double fx = a.x - x;
			double fz = a.z - z;
			double quadA = ux * ux + uz * uz;
			if (quadA < 1e-18) {
				if (fx * fx + fz * fz <= r2 + 1e-8) {
					addInterval(raw, s0, s1);
				}
				continue;
			}
			double quadB = 2 * (fx * ux + fz * uz);
			double quadC = fx * fx + fz * fz - r2;
			double disc = quadB * quadB - 4 * quadA * quadC;
			double discTol = 1e-9 * (quadA * quadA + Math.abs(quadB) + 1);
			if (disc < -discTol) {
				continue;
			}
			if (disc < 0) {
				disc = 0;
			}
			double root = Math.sqrt(disc);
			double t0 = (-quadB - root) / (2 * quadA);
			double t1 = (-quadB + root) / (2 * quadA);
			double lo = Math.max(0, Math.min(t0, t1));
			double hi = Math.min(1, Math.max(t0, t1));
			if (lo > hi) {
				continue;
			}
			double span = s1 - s0;
			addInterval(raw, s0 + lo * span, s0 + hi * span);
		}
		return merge(raw);
	}

	private static void addInterval(List<Interval> raw, double s0, double s1) {
		if (s1 < s0) {
			double swap = s0;
			s0 = s1;
			s1 = swap;
		}
		raw.add(new Interval(s0, s1));
	}

	private static List<Interval> merge(List<Interval> raw) {
		if (raw.isEmpty()) {
			return List.of();
		}
		raw.sort(Comparator.comparingDouble(interval -> interval.lo));
		List<Interval> out = new ArrayList<>();
		double lo = raw.get(0).lo;
		double hi = raw.get(0).hi;
		for (int i = 1; i < raw.size(); i++) {
			Interval next = raw.get(i);
			if (next.lo <= hi + EPS) {
				hi = Math.max(hi, next.hi);
			} else {
				out.add(new Interval(lo, hi));
				lo = next.lo;
				hi = next.hi;
			}
		}
		out.add(new Interval(lo, hi));
		return out;
	}

	private static Span separation(List<Interval> a, List<Interval> b, double length, boolean loop) {
		Span best = null;
		for (Interval left : a) {
			for (Interval right : b) {
				Span next = separation(left, right, length, loop);
				if (best == null || next.length < best.length) {
					best = next;
				}
			}
		}
		return best;
	}

	private static Span separation(Interval a, Interval b, double length, boolean loop) {
		if (overlaps(a, b, length, loop)) {
			if (a.hi + EPS >= b.lo && b.hi + EPS >= a.lo) {
				double s = Math.max(a.lo, b.lo);
				return new Span(s, s, 0);
			}
			return new Span(0, 0, 0);
		}
		if (!loop) {
			if (a.hi < b.lo) {
				return new Span(a.hi, b.lo, b.lo - a.hi);
			}
			return new Span(a.lo, b.hi, a.lo - b.hi);
		}
		Span best = arc(a.lo, b.lo, length);
		for (Span next : List.of(arc(a.lo, b.hi, length), arc(a.hi, b.lo, length), arc(a.hi, b.hi, length))) {
			if (next.length < best.length) {
				best = next;
			}
		}
		return best;
	}

	private static Span arc(double from, double to, double length) {
		double delta = to - from;
		double distance = TrackJunction.arcDistance(from, to, length, true);
		if (length > 0 && Math.abs(delta) > length / 2) {
			delta += delta > 0 ? -length : length;
		}
		return new Span(from, from + delta, distance);
	}

	private static boolean overlaps(Interval a, Interval b, double length, boolean loop) {
		if (a.hi + EPS >= b.lo && b.hi + EPS >= a.lo) {
			return true;
		}
		if (!loop || length <= 0) {
			return false;
		}
		return (a.hi >= length - EPS && b.lo <= EPS) || (b.hi >= length - EPS && a.lo <= EPS);
	}

	private static Span distanceTo(double s, List<Interval> covered, double length, boolean loop) {
		Span best = null;
		for (Interval interval : covered) {
			Span next;
			if (s >= interval.lo - EPS && s <= interval.hi + EPS) {
				next = new Span(s, s, 0);
			} else if (!loop) {
				double to = s < interval.lo ? interval.lo : interval.hi;
				next = new Span(s, to, Math.abs(to - s));
			} else {
				Span lo = arc(s, interval.lo, length);
				Span hi = arc(s, interval.hi, length);
				next = lo.length <= hi.length ? lo : hi;
			}
			if (best == null || next.length < best.length) {
				best = next;
			}
		}
		return best;
	}

	private static void oneWay(
			List<List<Edge>> adj, int from, int to,
			TrackSpline spline, Piece piece, Span span) {
		if (span != null) {
			double origin = piece.circular ? 0 : piece.start;
			oneWay(adj, from, to, span.length, new Leg(spline, origin + span.from, origin + span.to));
		}
	}

	private static void linkSpline(
			List<List<Edge>> adj,
			TrackSpline spline,
			List<Interval> nearA,
			List<Interval> nearB,
			List<Anchor> onSpline) {
		double length = spline.length();
		for (Piece piece : pieces(spline)) {
			if (!nearA.isEmpty() && !nearB.isEmpty()) {
				oneWay(adj, SRC, DST, spline, piece, pieceSeparation(piece, nearA, nearB, length));
			}
			if (onSpline == null || onSpline.isEmpty()) {
				continue;
			}
			List<Anchor> onPiece = new ArrayList<>();
			for (Anchor at : onSpline) {
				if (holds(piece, at.s)) {
					onPiece.add(at);
				}
			}
			onPiece.sort(Comparator.comparingDouble(at -> along(piece, at.s, length)));
			for (int i = 1; i < onPiece.size(); i++) {
				Anchor prev = onPiece.get(i - 1);
				Anchor next = onPiece.get(i);
				link(adj, prev.node, next.node,
						along(piece, next.s, length) - along(piece, prev.s, length),
						new Leg(spline, piece.start + along(piece, prev.s, length), piece.start + along(piece, next.s, length)));
			}
			if (piece.circular && onPiece.size() >= 2) {
				Anchor first = onPiece.get(0);
				Anchor last = onPiece.get(onPiece.size() - 1);
				link(adj, last.node, first.node, length - last.s + first.s,
						new Leg(spline, last.s, length + first.s));
			}
			List<Interval> localA = piece.circular ? nearA : clip(nearA, piece, length);
			List<Interval> localB = piece.circular ? nearB : clip(nearB, piece, length);
			for (Anchor at : onPiece) {
				double atS = along(piece, at.s, length);
				if (!nearA.isEmpty()) {
					Span span = piece.circular
							? distanceTo(at.s, nearA, length, true)
							: distanceTo(atS, localA, piece.span, false);
					oneWay(adj, SRC, at.node, spline, piece, span == null ? null : span.reverse());
				}
				if (!nearB.isEmpty()) {
					Span span = piece.circular
							? distanceTo(at.s, nearB, length, true)
							: distanceTo(atS, localB, piece.span, false);
					oneWay(adj, at.node, DST, spline, piece, span);
				}
			}
		}
	}

	// fromIndex is the edge index. Edge i joins sample i to sample i + 1.
	// On a loop the closing edge is index samples.size() - 1: last sample back
	// to the first, with s running from the last sample up to the spline length.
	// That is the same split TrackSpline uses for advance.
	private static List<Piece> pieces(TrackSpline spline) {
		double length = spline.length();
		boolean loop = spline.isLoop();
		List<Interval> breaks = brokenSpans(spline);
		if (breaks.isEmpty()) {
			if (loop) {
				return List.of(Piece.circular(length));
			}
			return List.of(Piece.linear(0, length));
		}
		List<Piece> out = new ArrayList<>();
		if (!loop) {
			double cursor = 0;
			for (Interval br : breaks) {
				if (br.lo > cursor) {
					out.add(Piece.linear(cursor, br.lo));
				}
				if (br.hi > cursor) {
					cursor = br.hi;
				}
			}
			if (cursor < length) {
				out.add(Piece.linear(cursor, length));
			}
			return out;
		}
		int n = breaks.size();
		for (int i = 0; i < n; i++) {
			double start = seam(breaks.get(i).hi, length);
			double end = seam(breaks.get((i + 1) % n).lo, length);
			double forward = end >= start ? end - start : (length - start) + end;
			if (forward <= EPS) {
				continue;
			}
			if (end >= start) {
				out.add(Piece.linear(start, end));
			} else {
				out.add(Piece.wrapping(start, end, length));
			}
		}
		return out;
	}

	private static List<Interval> brokenSpans(TrackSpline spline) {
		int edges = spline.edgeCount();
		List<Interval> raw = new ArrayList<>();
		for (int edge = 0; edge < edges; edge++) {
			if (!spline.segment(edge).broken) {
				continue;
			}
			double s0 = edgeStartS(spline, edge);
			double s1 = edgeEndS(spline, edge);
			if (s1 < s0) {
				double swap = s0;
				s0 = s1;
				s1 = swap;
			}
			if (s1 <= s0 + EPS) {
				continue;
			}
			raw.add(new Interval(s0, s1));
		}
		return merge(raw);
	}

	private static double edgeStartS(TrackSpline spline, int edge) {
		List<TrackSample> samples = spline.getSamples();
		int n = samples.size();
		if (edge < n - 1 || !spline.isLoop()) {
			return samples.get(edge).s;
		}
		return samples.get(n - 1).s;
	}

	private static double edgeEndS(TrackSpline spline, int edge) {
		List<TrackSample> samples = spline.getSamples();
		int n = samples.size();
		if (edge < n - 1) {
			return samples.get(edge + 1).s;
		}
		if (spline.isLoop()) {
			return spline.length();
		}
		return samples.get(n - 1).s;
	}

	private static double seam(double s, double length) {
		if (length > 0 && s >= length - 1e-12) {
			return 0;
		}
		return s;
	}

	private static Span pieceSeparation(Piece piece, List<Interval> a, List<Interval> b, double length) {
		if (piece.circular) {
			return separation(a, b, length, true);
		}
		return separation(clip(a, piece, length), clip(b, piece, length), piece.span, false);
	}

	private static List<Interval> clip(List<Interval> intervals, Piece piece, double length) {
		List<Interval> raw = new ArrayList<>();
		for (Interval interval : intervals) {
			if (!piece.wraps) {
				addClipped(raw, interval.lo, interval.hi, piece.start, piece.end, piece.start);
			} else {
				addClipped(raw, interval.lo, interval.hi, piece.start, length, piece.start);
				addClipped(raw, interval.lo, interval.hi, 0, piece.end, piece.start - length);
			}
		}
		return merge(raw);
	}

	private static void addClipped(
			List<Interval> raw,
			double lo, double hi,
			double winLo, double winHi,
			double origin) {
		double a = Math.max(lo, winLo);
		double b = Math.min(hi, winHi);
		if (a <= b) {
			raw.add(new Interval(a - origin, b - origin));
		}
	}

	private static boolean holds(Piece piece, double s) {
		if (piece.circular) {
			return true;
		}
		if (!piece.wraps) {
			return s >= piece.start - EPS && s <= piece.end + EPS;
		}
		return s >= piece.start - EPS || s <= piece.end + EPS;
	}

	private static double along(Piece piece, double s, double length) {
		if (piece.circular) {
			return s;
		}
		if (!piece.wraps) {
			return s - piece.start;
		}
		if (s >= piece.start - EPS) {
			return s - piece.start;
		}
		return (length - piece.start) + s;
	}

	private record Touch(TrackSpline spline, List<Interval> nearA, List<Interval> nearB) {
	}

	private record Anchor(double s, int node) {
	}

	private record Edge(int to, double weight, Leg leg) {
	}

	private record Span(double from, double to, double length) {
		Span reverse() {
			return new Span(to, from, length);
		}
	}

	// Unwrapped s preserves travel direction through the seam and broken loops.
	private record Leg(TrackSpline spline, double from, double to) {
		Leg reverse() {
			return new Leg(spline, to, from);
		}
	}

	private record Path(double length, List<Leg> legs) {
	}

	private record Walk(int node, double dist) {
	}

	private record Interval(double lo, double hi) {
	}

	private static final class Piece {
		final double start;
		final double end;
		final boolean circular;
		final boolean wraps;
		final double span;

		private Piece(double start, double end, boolean circular, boolean wraps, double span) {
			this.start = start;
			this.end = end;
			this.circular = circular;
			this.wraps = wraps;
			this.span = span;
		}

		static Piece circular(double length) {
			return new Piece(0, length, true, false, length);
		}

		static Piece linear(double start, double end) {
			return new Piece(start, end, false, false, end - start);
		}

		static Piece wrapping(double start, double end, double length) {
			return new Piece(start, end, false, true, (length - start) + end);
		}
	}
}
