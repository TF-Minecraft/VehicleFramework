package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The sorted playback index must answer exactly as the scans it replaced did. */
class ThrottleTapeIndexTest {
	private static final String ORIGIN = "11111111-1111-1111-1111-111111111111";
	private static final String OTHER = "22222222-2222-2222-2222-222222222222";
	private static final double[] ODD = {
			Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0, Double.MAX_VALUE,
			-Double.MAX_VALUE, Double.MIN_VALUE, 1e300, -1e300 };

	@Test
	void randomTapesAnswerAsTheOldScansDid() {
		Random random = new Random(20261105L);
		for (int round = 0; round < 3000; round++) {
			List<ThrottleTape.Sample> samples = new ArrayList<>();
			int count = random.nextInt(round % 7 == 0 ? 3 : 40);
			for (int i = 0; i < count; i++) {
				samples.add(new ThrottleTape.Sample(position(random), random.nextBoolean() ? 1 : -1,
						random.nextInt(201) - 100, 1 + random.nextInt(60), spline(random), null,
						random.nextBoolean() ? 1 : -1));
			}
			ThrottleTape tape = new ThrottleTape(ORIGIN, samples);
			for (int ask = 0; ask < 40; ask++) {
				double s = ask % 3 == 0 && !samples.isEmpty()
						? samples.get(random.nextInt(samples.size())).s + (random.nextInt(3) - 1) * random.nextDouble()
						: position(random);
				int sign = random.nextInt(3) - 1;
				for (String want : new String[] { null, "", ORIGIN, ORIGIN.toUpperCase(), OTHER, "missing" }) {
					List<ThrottleTape.Sample> filtered = oldSamplesFor(samples, want);
					String where = "round " + round + " s=" + s + " sign=" + sign + " spline=" + want;
					assertEquals(ThrottleTape.lookup(filtered, s, sign), tape.lookup(s, sign, want), where);
					assertEquals(oldHoldAt(filtered, s, sign), tape.holdAt(s, sign, want), where);
				}
			}
		}
	}

	@Test
	void addingASampleRebuildsTheIndex() {
		ThrottleTape tape = new ThrottleTape(ORIGIN);
		assertEquals(0, tape.lookup(5, 1));
		assertEquals(1, tape.holdAt(5, 1));
		tape.tryAppend(5, 1, 40);
		assertEquals(40, tape.lookup(5, 1));
		assertEquals(ThrottleTape.AppendResult.HELD, tape.tryAppend(5.5, 1, 40));
		// A held sample's hold grows in place and is read live.
		assertEquals(2, tape.holdAt(5, 1));
		tape.tryAppend(10, 1, 80);
		assertEquals(60, tape.lookup(7.5, 1));
		assertEquals(80, tape.lookup(36, 1, (UUID) null));
	}

	private static double position(Random random) {
		int pick = random.nextInt(20);
		if (pick == 0) {
			return ODD[random.nextInt(ODD.length)];
		}
		if (pick < 5) {
			// Repeated positions make ties.
			return random.nextInt(6);
		}
		return random.nextDouble() * 50 - 10;
	}

	private static String spline(Random random) {
		return switch (random.nextInt(5)) {
			case 0 -> null;
			case 1 -> OTHER;
			case 2 -> ORIGIN.toUpperCase();
			default -> ORIGIN;
		};
	}

	// ThrottleTape's filtering before the index: the samples on the asked spline, in recording order.
	private static List<ThrottleTape.Sample> oldSamplesFor(List<ThrottleTape.Sample> samples, String currentSpline) {
		String want = currentSpline == null || currentSpline.isBlank() || "null".equalsIgnoreCase(currentSpline)
				? ORIGIN : currentSpline;
		List<ThrottleTape.Sample> out = new ArrayList<>();
		for (ThrottleTape.Sample sample : samples) {
			if (sample.resolvedSpline(ORIGIN).equalsIgnoreCase(want)) {
				out.add(sample);
			}
		}
		return out;
	}

	// ThrottleTape.holdAt before the index.
	private static int oldHoldAt(List<ThrottleTape.Sample> samples, double s, int sign) {
		int want = sign < 0 ? -1 : 1;
		ThrottleTape.Sample best = null;
		double bestD = ThrottleTape.HOLD_S;
		for (ThrottleTape.Sample sample : samples) {
			if (sample.sign != want) {
				continue;
			}
			double d = Math.abs(sample.s - s);
			if (d < bestD) {
				bestD = d;
				best = sample;
			}
		}
		return best == null ? 1 : best.holdTicks;
	}
}
