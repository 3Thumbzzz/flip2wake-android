package com.thumbzzz.flip2wake;

/**
 * Detects an "over and back" flip gesture from raw accelerometer samples.
 *
 * The gesture is relative to wherever the device starts, so it works whether the
 * device is lying face-up on a table, face-down, or held at an angle:
 *
 *   1. At some moment S the device has gravity direction g(S).
 *   2. Afterwards it rotates at least {@code angleDeg} away from g(S)
 *      (e.g. 150 deg = nearly upside down).
 *   3. It then comes back to within {@code returnToleranceDeg} of g(S).
 *   4. Steps 1-3 all happen inside {@code maxTimeMs}.
 *
 * Pure Java (no Android imports) so it can be unit-tested on a desktop JVM.
 */
public class FlipDetector {

    /** Low-pass filter time constant for isolating gravity from raw acceleration. */
    private static final double GRAVITY_TAU_S = 0.06;
    /** Minimum spacing between stored history samples. */
    private static final long SAMPLE_SPACING_NS = 30_000_000L; // 30 ms
    /** After a detection, ignore further gestures for this long. */
    private static final long COOLDOWN_NS = 1_500_000_000L; // 1.5 s
    /** Ignore samples where the measured acceleration is far from 1 g (free fall / big jolts). */
    private static final double MIN_G = 3.0, MAX_G = 25.0;

    private volatile double angleDeg;
    private volatile long maxTimeNs;

    // Ring buffer of unit gravity vectors and their timestamps.
    private static final int CAP = 512;
    private final double[] hx = new double[CAP], hy = new double[CAP], hz = new double[CAP];
    private final long[] ht = new long[CAP];
    private int head = 0, size = 0;

    private double gx, gy, gz;
    private long lastNs = -1;
    private long lastStoredNs = Long.MIN_VALUE / 2;
    private long cooldownUntilNs = Long.MIN_VALUE;

    public FlipDetector(int angleDeg, int maxTimeMs) {
        setConfig(angleDeg, maxTimeMs);
    }

    public synchronized void setConfig(int angleDeg, int maxTimeMs) {
        this.angleDeg = Math.max(30, Math.min(180, angleDeg));
        this.maxTimeNs = Math.max(100, maxTimeMs) * 1_000_000L;
    }

    /** How close (degrees) the device must come back to its starting orientation. */
    public double returnToleranceDeg() {
        // Scales with the requested angle, capped so small angles still need a clear return.
        return Math.min(35.0, angleDeg / 3.0);
    }

    public synchronized void reset() {
        size = 0;
        head = 0;
        lastNs = -1;
        lastStoredNs = Long.MIN_VALUE / 2;
    }

    /** Current tilt of the screen away from facing straight up, in degrees (0 = face up, 180 = face down). */
    public synchronized double currentTiltDeg() {
        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (n < 1e-6) return 0;
        return Math.toDegrees(Math.acos(clamp(gz / n)));
    }

    /**
     * Feed one accelerometer sample (m/s^2, device axes).
     *
     * @return true if this sample completed a valid flip gesture.
     */
    public synchronized boolean onSample(long timestampNs, float ax, float ay, float az) {
        if (lastNs < 0) {
            gx = ax; gy = ay; gz = az;
        } else {
            double dt = (timestampNs - lastNs) / 1e9;
            if (dt <= 0) return false;
            if (dt > 0.5) { // big gap (e.g. sensor paused): restart the filter and history
                gx = ax; gy = ay; gz = az;
                size = 0;
            } else {
                double a = dt / (GRAVITY_TAU_S + dt);
                gx += a * (ax - gx);
                gy += a * (ay - gy);
                gz += a * (az - gz);
            }
        }
        lastNs = timestampNs;

        double raw = Math.sqrt((double) ax * ax + (double) ay * ay + (double) az * az);
        if (raw < MIN_G || raw > MAX_G) return false;

        if (timestampNs - lastStoredNs < SAMPLE_SPACING_NS) return false;
        lastStoredNs = timestampNs;

        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (n < 1e-6) return false;
        push(timestampNs, gx / n, gy / n, gz / n);

        // Drop history older than the allowed gesture duration.
        long oldest = timestampNs - maxTimeNs;
        while (size > 0 && ht[index(0)] < oldest) size--;

        if (timestampNs < cooldownUntilNs) return false;
        if (detect()) {
            cooldownUntilNs = timestampNs + COOLDOWN_NS;
            size = 0; // start fresh so the same motion can't trigger twice
            return true;
        }
        return false;
    }

    private boolean detect() {
        if (size < 3) return false;
        double cosFar = Math.cos(Math.toRadians(angleDeg)) + 1e-9; // must be <= this
        double cosBack = Math.cos(Math.toRadians(returnToleranceDeg())); // must be >= this
        int last = size - 1;
        double cx = hx[index(last)], cy = hy[index(last)], cz = hz[index(last)];

        // Oldest-first: find a start S that the current orientation has returned to,
        // with some sample in between that rotated far enough away from S.
        for (int i = 0; i < last - 1; i++) {
            int si = index(i);
            double sx = hx[si], sy = hy[si], sz = hz[si];
            if (sx * cx + sy * cy + sz * cz < cosBack) continue;
            for (int j = i + 1; j < last; j++) {
                int mj = index(j);
                if (sx * hx[mj] + sy * hy[mj] + sz * hz[mj] <= cosFar) return true;
            }
        }
        return false;
    }

    // History is stored oldest at logical index 0.
    private int index(int logical) {
        return (head - size + logical + CAP * 2) % CAP;
    }

    private void push(long t, double x, double y, double z) {
        hx[head] = x; hy[head] = y; hz[head] = z; ht[head] = t;
        head = (head + 1) % CAP;
        if (size < CAP) size++;
    }

    private static double clamp(double v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }
}
