package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TrackVisualDiff {
	private TrackVisualDiff() {
	}

	public static int firstChange(List<TrackVisual> previous, List<TrackVisual> next) {
		List<TrackVisual> old = previous == null ? List.of() : previous;
		List<TrackVisual> neu = next == null ? List.of() : next;
		int n = Math.min(old.size(), neu.size());
		for (int i = 0; i < n; i++) {
			if (!same(old.get(i), neu.get(i))) {
				return i;
			}
		}
		return n;
	}

	/**
	 * Keys of the chunks whose pieces differ between two bakes of one track.
	 * Displays in the other chunks can stay as they are.
	 */
	public static Set<Long> changedChunks(List<TrackVisual> previous, List<TrackVisual> next) {
		Map<Long, List<TrackVisual>> old = byChunk(previous);
		Map<Long, List<TrackVisual>> neu = byChunk(next);
		Set<Long> changed = new HashSet<>();
		for (Map.Entry<Long, List<TrackVisual>> entry : old.entrySet()) {
			if (!identical(entry.getValue(), neu.get(entry.getKey()))) {
				changed.add(entry.getKey());
			}
		}
		for (Long key : neu.keySet()) {
			if (!old.containsKey(key)) {
				changed.add(key);
			}
		}
		return changed;
	}

	private static Map<Long, List<TrackVisual>> byChunk(List<TrackVisual> visuals) {
		Map<Long, List<TrackVisual>> out = new HashMap<>();
		if (visuals == null) {
			return out;
		}
		for (TrackVisual visual : visuals) {
			out.computeIfAbsent(TrackChunks.keyAt(visual.x, visual.z), k -> new ArrayList<>()).add(visual);
		}
		return out;
	}

	private static boolean identical(List<TrackVisual> a, List<TrackVisual> b) {
		if (a == null || b == null || a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			TrackVisual x = a.get(i);
			TrackVisual y = b.get(i);
			if (!same(x, y) || Math.abs(x.yaw - y.yaw) >= 0.01f || Math.abs(x.pitch - y.pitch) >= 0.01f) {
				return false;
			}
		}
		return true;
	}

	public static boolean same(TrackVisual a, TrackVisual b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		return a.type == b.type
				&& a.startIndex == b.startIndex
				&& a.length == b.length
				&& a.fromEdge == b.fromEdge
				&& a.span == b.span
				&& Math.abs(a.x - b.x) < 0.01
				&& Math.abs(a.y - b.y) < 0.01
				&& Math.abs(a.z - b.z) < 0.01;
	}
}
