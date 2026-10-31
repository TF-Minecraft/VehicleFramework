package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TrackChunksTest {

	private static TrackVisual piece(int edge, double x, double z) {
		return new TrackVisual(TrackVisual.Type.SMALL, edge, 1, edge, 1, x, 64, z, 0, 0);
	}

	@Test
	void byChunk_holdsTheSamePiecesInChunkAsTheInChunkFilterInOrder() {
		List<TrackVisual> visuals = List.of(
				piece(0, 1, 1), piece(1, 17, 1), piece(2, 15.9, -0.1), piece(3, 2, 3), piece(4, -0.1, -16.1));
		Map<Long, List<TrackVisual>> byChunk = TrackChunks.byChunk(visuals);
		int total = 0;
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -2; cz <= 1; cz++) {
				List<TrackVisual> filtered = new ArrayList<>();
				for (TrackVisual visual : visuals) {
					if (TrackChunks.inChunk(visual.x, visual.z, cx, cz)) {
						filtered.add(visual);
					}
				}
				List<TrackVisual> grouped = byChunk.getOrDefault(TrackChunks.key(cx, cz), List.of());
				assertEquals(filtered, grouped);
				total += grouped.size();
			}
		}
		assertEquals(visuals.size(), total);
		assertEquals(List.of(visuals.get(0), visuals.get(3)), byChunk.get(TrackChunks.key(0, 0)));
	}

	@Test
	void chunkCoord_floorsNegatives() {
		assertEquals(0, TrackChunks.chunkCoord(0));
		assertEquals(0, TrackChunks.chunkCoord(15.9));
		assertEquals(1, TrackChunks.chunkCoord(16));
		assertEquals(-1, TrackChunks.chunkCoord(-0.1));
		assertEquals(-1, TrackChunks.chunkCoord(-16));
		assertEquals(-2, TrackChunks.chunkCoord(-16.1));
	}

	@Test
	void inChunk_usesBlockXZ() {
		assertEquals(true, TrackChunks.inChunk(16.2, 32.9, 1, 2));
		assertEquals(false, TrackChunks.inChunk(15.9, 32.9, 1, 2));
	}

	@Test
	void edgeIndexForSample_openSplineLastMapsToLastEdge() {
		assertEquals(0, TrackChunks.edgeIndexForSample(0, 3, false));
		assertEquals(1, TrackChunks.edgeIndexForSample(1, 3, false));
		assertEquals(1, TrackChunks.edgeIndexForSample(2, 3, false));
	}

	@Test
	void edgeIndexForSample_loopUsesAllSamples() {
		assertEquals(0, TrackChunks.edgeIndexForSample(0, 3, true));
		assertEquals(1, TrackChunks.edgeIndexForSample(1, 3, true));
		assertEquals(2, TrackChunks.edgeIndexForSample(2, 3, true));
	}
}
