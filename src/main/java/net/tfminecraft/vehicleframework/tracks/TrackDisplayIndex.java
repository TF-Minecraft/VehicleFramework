package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The live track displays in spawn order, also grouped by the chunk they stand in.
 * Displays never change chunk (a switch only turns in place), so a chunk event can
 * prune its own displays without visiting every display on the server.
 */
final class TrackDisplayIndex<T> implements Iterable<T> {
	record ChunkAt(String world, long chunk) {
	}

	private final Map<T, ChunkAt> all = new LinkedHashMap<>();
	private final Map<ChunkAt, List<T>> byChunk = new HashMap<>();

	static ChunkAt at(String world, double x, double z) {
		return new ChunkAt(world, TrackChunks.keyAt(x, z));
	}

	static ChunkAt chunk(String world, int chunkX, int chunkZ) {
		return new ChunkAt(world, TrackChunks.key(chunkX, chunkZ));
	}

	void add(T display, ChunkAt at) {
		if (display == null || all.containsKey(display)) {
			return;
		}
		all.put(display, at);
		byChunk.computeIfAbsent(at, k -> new ArrayList<>()).add(display);
	}

	boolean remove(T display) {
		ChunkAt at = all.remove(display);
		if (at == null) {
			return false;
		}
		unlinkFromChunk(display, at);
		return true;
	}

	/** Drops the displays of one chunk that match {@code dead}, and returns them. */
	List<T> pruneChunk(ChunkAt at, Predicate<T> dead) {
		List<T> bucket = byChunk.get(at);
		if (bucket == null) {
			return List.of();
		}
		List<T> removed = new ArrayList<>();
		Iterator<T> it = bucket.iterator();
		while (it.hasNext()) {
			T display = it.next();
			if (dead.test(display)) {
				it.remove();
				all.remove(display);
				removed.add(display);
			}
		}
		if (bucket.isEmpty()) {
			byChunk.remove(at);
		}
		return removed;
	}

	int size() {
		return all.size();
	}

	int sizeInChunk(ChunkAt at) {
		List<T> bucket = byChunk.get(at);
		return bucket == null ? 0 : bucket.size();
	}

	void clear() {
		all.clear();
		byChunk.clear();
	}

	/** Spawn order. Removing through the iterator also drops the chunk entry. */
	@Override
	public Iterator<T> iterator() {
		Iterator<Map.Entry<T, ChunkAt>> it = all.entrySet().iterator();
		return new Iterator<>() {
			private Map.Entry<T, ChunkAt> current;

			@Override
			public boolean hasNext() {
				return it.hasNext();
			}

			@Override
			public T next() {
				current = it.next();
				return current.getKey();
			}

			@Override
			public void remove() {
				it.remove();
				unlinkFromChunk(current.getKey(), current.getValue());
			}
		};
	}

	private void unlinkFromChunk(T display, ChunkAt at) {
		// Every entry of all has its chunk list.
		List<T> bucket = byChunk.get(at);
		bucket.remove(display);
		if (bucket.isEmpty()) {
			byChunk.remove(at);
		}
	}
}
