package net.tfminecraft.vehicleframework.vehicles;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How much health one decay step takes from a vehicle and where it stops.
 *
 * @param fractionOfMax          share of each part's max health removed per step
 * @param minHealthFraction      remaining-health floor for every part
 * @param partMinHealthFractions floors for single parts, keyed by component type
 *                               ({@code hull}, {@code balloon}) or weapon id
 * @param keepAfloat             never take a floating sinkable hull below the
 *                               health where it starts to sink
 * @param keepAloft              never take a balloon below the health where its
 *                               lift turns negative
 */
public record HealthDecayRule(
		double fractionOfMax,
		double minHealthFraction,
		Map<String, Double> partMinHealthFractions,
		boolean keepAfloat,
		boolean keepAloft) {

	public HealthDecayRule {
		Map<String, Double> parts = new HashMap<>();
		if (partMinHealthFractions != null) {
			partMinHealthFractions.forEach((part, floor) -> {
				if (part != null && floor != null) {
					parts.put(part.toLowerCase(Locale.ROOT), floor);
				}
			});
		}
		partMinHealthFractions = Map.copyOf(parts);
	}

	/** A flat rule: the same floor for every part and no safe floors. */
	public static HealthDecayRule of(double fractionOfMax, double minHealthFraction) {
		return new HealthDecayRule(fractionOfMax, minHealthFraction, Map.of(), false, false);
	}

	/** The floor for one component type or weapon id, falling back to {@link #minHealthFraction()}. */
	public double minHealthFor(String part) {
		if (part == null) {
			return minHealthFraction;
		}
		return partMinHealthFractions.getOrDefault(part.toLowerCase(Locale.ROOT), minHealthFraction);
	}
}
