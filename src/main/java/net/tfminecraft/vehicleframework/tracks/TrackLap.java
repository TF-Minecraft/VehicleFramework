package net.tfminecraft.vehicleframework.tracks;

public final class TrackLap {
	private TrackLap() {
	}

	public static double wrapDelta(double prevS, double s, double length) {
		if (length <= 1e-9) {
			return 0;
		}
		double ds = s - prevS;
		if (ds < -length / 2) {
			ds += length;
		}
		if (ds > length / 2) {
			ds -= length;
		}
		return Math.abs(ds);
	}

	public static boolean complete(double traveled, double length) {
		return length > 1.0 && traveled + 1e-6 >= length;
	}

	/**
	 * A recording on a line with ends is complete when the train comes back to where it
	 * started, heading the way it first left, after a real run: at least {@code minDistance}
	 * driven and within {@code finish} blocks of the start, under power.
	 */
	public static boolean backAtStart(double distance, double minDistance, double fromStart, double finish,
			int sign, int startSign, int throttle) {
		return startSign != 0 && throttle != 0 && sign == startSign && distance >= minDistance
				&& fromStart <= finish;
	}
}
