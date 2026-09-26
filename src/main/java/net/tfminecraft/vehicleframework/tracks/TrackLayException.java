package net.tfminecraft.vehicleframework.tracks;

import java.util.List;

public final class TrackLayException extends Exception {
	public final Integer blockX;
	public final Integer blockY;
	public final Integer blockZ;
	/** Every block in the way of trains, when that is why the track was refused. */
	public final List<TrainBlockCollision.Obstruction> inTrainSpace;

	public TrackLayException(String message) {
		this(message, null, null, null);
	}

	public TrackLayException(String message, int x, int y, int z) {
		this(message, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
	}

	public TrackLayException(String message, List<TrainBlockCollision.Obstruction> inTrainSpace) {
		this(message, inTrainSpace.get(0).x(), inTrainSpace.get(0).y(), inTrainSpace.get(0).z(),
				List.copyOf(inTrainSpace));
	}

	private TrackLayException(String message, Integer x, Integer y, Integer z) {
		this(message, x, y, z, List.of());
	}

	private TrackLayException(String message, Integer x, Integer y, Integer z,
			List<TrainBlockCollision.Obstruction> inTrainSpace) {
		super(message);
		this.blockX = x;
		this.blockY = y;
		this.blockZ = z;
		this.inTrainSpace = inTrainSpace;
	}

	public boolean hasBlock() {
		return blockX != null && blockY != null && blockZ != null;
	}
}
