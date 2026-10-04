package net.tfminecraft.vehicleframework.tracks;

import java.util.List;

/** A shortest along-track route with immutable points ordered from A to B. */
public record TrackRoute(double length, List<TrackSamplePoint> points) {
	public TrackRoute {
		points = List.copyOf(points);
	}
}
