package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

class TrackDisplayIndexTest {
	private static final TrackDisplayIndex.ChunkAt A = TrackDisplayIndex.chunk("world", 0, 0);
	private static final TrackDisplayIndex.ChunkAt B = TrackDisplayIndex.chunk("world", 1, -1);

	private static List<String> list(TrackDisplayIndex<String> index) {
		List<String> out = new ArrayList<>();
		index.forEach(out::add);
		return out;
	}

	@Test
	void chunkOfAPositionMatchesTheChunkCoordinatesInTheSameWorldOnly() {
		assertEquals(B, TrackDisplayIndex.at("world", 17.5, -0.5));
		assertEquals(A, TrackDisplayIndex.at("world", 15.9, 0));
		assertNotEquals(A, TrackDisplayIndex.chunk("nether", 0, 0));
	}

	@Test
	void keepsSpawnOrderAndIgnoresNullsAndRepeats() {
		TrackDisplayIndex<String> index = new TrackDisplayIndex<>();
		index.add("a", A);
		index.add("b", B);
		index.add("c", A);
		index.add("a", B);
		index.add(null, A);
		assertEquals(List.of("a", "b", "c"), list(index));
		assertEquals(2, index.sizeInChunk(A));
		assertEquals(1, index.sizeInChunk(B));
		assertEquals(3, index.size());
	}

	@Test
	void pruningAChunkDropsOnlyItsOwnMatchingEntries() {
		TrackDisplayIndex<String> index = new TrackDisplayIndex<>();
		index.add("dead-a", A);
		index.add("live-a", A);
		index.add("dead-b", B);
		assertEquals(List.of("dead-a"), index.pruneChunk(A, s -> s.startsWith("dead")));
		assertEquals(List.of("live-a", "dead-b"), list(index));
		assertEquals(List.of(), index.pruneChunk(TrackDisplayIndex.chunk("world", 5, 5), s -> true));
		assertEquals(List.of("dead-b"), index.pruneChunk(B, s -> s.startsWith("dead")));
		assertEquals(0, index.sizeInChunk(B));
		index.add("again", B);
		assertEquals(1, index.sizeInChunk(B));
	}

	@Test
	void removingThroughTheIteratorOrDirectlyUnlinksTheChunkEntry() {
		TrackDisplayIndex<String> index = new TrackDisplayIndex<>();
		index.add("a", A);
		index.add("b", A);
		index.add("c", B);
		Iterator<String> it = index.iterator();
		while (it.hasNext()) {
			if (it.next().equals("a")) {
				it.remove();
			}
		}
		assertEquals(1, index.sizeInChunk(A));
		assertTrue(index.remove("c"));
		assertFalse(index.remove("c"));
		assertEquals(0, index.sizeInChunk(B));
		assertEquals(List.of("b"), index.pruneChunk(A, s -> true));
		assertEquals(0, index.size());
		index.add("d", A);
		index.clear();
		assertEquals(0, index.size());
		assertEquals(0, index.sizeInChunk(A));
	}
}
