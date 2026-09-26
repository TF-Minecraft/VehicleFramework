package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import org.json.simple.JSONObject;

/** A shared 200 percentage-second boost budget, followed by five minutes of cooldown. */
public final class LocomotiveOverdrive {
    private static final double BUDGET = 200_000; // percentage points * milliseconds
    private static final long COOLDOWN = 300_000;
    private double remaining = BUDGET;
    private long lastUpdate;
    private int previousBoost;
    private long cooldownUntil;

    public int update(int requestedThrottle, long now) {
        int throttle = Math.min(requestedThrottle, 120);
        if (previousBoost > 0) {
            long elapsed = Math.max(0, now - lastUpdate);
            if (elapsed * (double) previousBoost >= remaining) {
                cooldownUntil = lastUpdate + (long) Math.ceil(remaining / previousBoost) + COOLDOWN;
                previousBoost = 0;
                remaining = BUDGET;
            } else {
                remaining -= elapsed * previousBoost;
                if (throttle <= 100) {
                    cooldownUntil = now + COOLDOWN;
                    previousBoost = 0;
                    remaining = BUDGET;
                }
            }
        }
        if (now < cooldownUntil) throttle = Math.min(throttle, 100);
        previousBoost = Math.max(0, throttle - 100);
        lastUpdate = now;
        return throttle;
    }

    public static double fuelMultiplier(int throttle) {
        int boost = Math.max(0, Math.min(throttle, 120) - 100);
        return 1.0 + boost * boost / 400.0;
    }

    public long cooldownSeconds(long now) {
        return Math.max(0, (cooldownUntil - now + 999) / 1000);
    }

    public long remainingSeconds() {
        return previousBoost == 0 ? 0 : (long) Math.ceil(remaining / previousBoost / 1000);
    }

    @SuppressWarnings("unchecked")
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("remaining", remaining);
        json.put("lastUpdate", lastUpdate);
        json.put("previousBoost", previousBoost);
        json.put("cooldownUntil", cooldownUntil);
        return json;
    }

    public void restore(JSONObject json) {
        if (json == null) return;
        if (json.get("remaining") instanceof Number value && Double.isFinite(value.doubleValue())) {
            remaining = Math.clamp(value.doubleValue(), 0, BUDGET);
        }
        if (json.get("lastUpdate") instanceof Number value) lastUpdate = value.longValue();
        if (json.get("previousBoost") instanceof Number value) previousBoost = Math.clamp(value.intValue(), 0, 20);
        if (json.get("cooldownUntil") instanceof Number value) cooldownUntil = value.longValue();
    }
}
