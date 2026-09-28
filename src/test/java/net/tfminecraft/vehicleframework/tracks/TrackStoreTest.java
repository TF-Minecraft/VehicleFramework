package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackStoreTest {

	private static TrackSpline track(UUID id, int length) {
		List<double[]> points = new java.util.ArrayList<>();
		for (int z = 0; z <= length; z++) {
			points.add(new double[] {0, 64, z});
		}
		return TrackSpline.fromPoints(id, "world", false, points);
	}

	@Test
	void backgroundSaves_leaveLatestTrackOnDiskOnceClosed(@TempDir Path dir) {
		TrackStore store = new TrackStore(dir.toFile());
		store.writeInBackground();
		UUID id = UUID.randomUUID();
		for (int length = 1; length <= 20; length++) {
			store.save(track(id, length));
		}
		store.close();
		List<TrackSpline> loaded = new TrackStore(dir.toFile()).loadAll();
		assertEquals(1, loaded.size());
		assertEquals(21, loaded.get(0).getSamples().size());
		File[] files = store.fileFor("world", id).getParentFile().listFiles();
		assertEquals(1, files.length);
	}

	@Test
	void backgroundDelete_afterSave_removesTheFile(@TempDir Path dir) {
		TrackStore store = new TrackStore(dir.toFile());
		store.writeInBackground();
		TrackSpline spline = track(UUID.randomUUID(), 5);
		store.save(spline);
		store.delete(spline);
		store.close();
		assertFalse(store.fileFor(spline).exists());
	}

	@Test
	void saveAfterClose_writesStraightAway(@TempDir Path dir) {
		TrackStore store = new TrackStore(dir.toFile());
		store.writeInBackground();
		store.close();
		TrackSpline spline = track(UUID.randomUUID(), 3);
		store.save(spline);
		assertTrue(store.fileFor(spline).exists());
	}
}
