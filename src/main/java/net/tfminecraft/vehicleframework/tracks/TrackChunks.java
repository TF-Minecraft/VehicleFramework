package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TrackChunks {
	private TrackChunks() {
	}

	/** Groups pieces by the {@link #key} of their chunk, keeping their order within each chunk. */
	public static Map<Long, List<TrackVisual>> byChunk(List<TrackVisual> visuals) {
		Map<Long, List<TrackVisual>> out = new HashMap<>();
		for (TrackVisual visual : visuals) {
			out.computeIfAbsent(keyAt(visual.x, visual.z), k -> new ArrayList<>()).add(visual);
		}
		return out;
	}

	public static int chunkCoord(double block) {
		return (int) Math.floor(block / 16.0);
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}

	public static long keyAt(double x, double z) {
		return key(chunkCoord(x), chunkCoord(z));
	}

	public static boolean inChunk(double x, double z, int chunkX, int chunkZ) {
		return chunkCoord(x) == chunkX && chunkCoord(z) == chunkZ;
	}

	public static int edgeIndexForSample(int sampleIndex, int sampleCount, boolean loop) {
		int edges = TrackSpline.edgeCount(sampleCount, loop);
		if (edges <= 0) {
			return 0;
		}
		if (sampleIndex < 0) {
			return 0;
		}
		return Math.min(sampleIndex, edges - 1);
	}
}
