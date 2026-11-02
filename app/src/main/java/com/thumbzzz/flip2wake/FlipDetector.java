package com.thumbzzz.flip2wake;

/**
 * Detects an "over and back" turn gesture from raw accelerometer samples.
 *
 * Everything is measured relative to the device's orientation at the start of the
 * movement, using the direction of gravity in device coordinates. That makes it
 * independent of the starting position and of the turning direction: turning left
 * or right, over the long or short edge, coming back the opposite way, or carrying
 * on round in the same direction until it is back where it started all count.
 *
 *   1. At some moment S the device is roughly still, with gravity direction g(S).
 *   2. It then turns at least (angle - tolerance) degrees away from g(S).
 *   3. It then comes back to within (tolerance) degrees of g(S).
 *   4. Steps 1-3 all happen inside maxTime.
 *
 * Note: spinning the device flat on a table (rotation around the vertical axis) does
 * not change gravity's direction, so it can't be detected, and doesn't count as a turn.
 *
 * Pure Java (no Android imports) so it can be unit-tested on a desktop JVM.
 */
public class FlipDetector {

    /** Low-pass filter time constant for isolating gravity from raw acceleration. */
    private static final double GRAVITY_TAU_S = 0.03;
    /** True when fed by Android's fused TYPE_GRAVITY sensor, which needs no extra filtering. */
    private volatile boolean inputIsGravity;
    /** Minimum spacing between stored history samples. */
    private static final long SAMPLE_SPACING_NS = 30_000_000L; // 30 ms
    /** After a detection, ignore further gestures for this long. */
    private static final long COOLDOWN_NS = 1_500_000_000L; // 1.5 s
    /** Max rotation speed (deg/s) for a sample to count as a resting "start" position. */
    private static final double STILL_DEG_PER_S = 90.0;
    /** Ignore samples where the measured acceleration is far from 1 g (free fall / big jolts). */
    private static final double MIN_G = 3.0, MAX_G = 25.0;

    private volatile double angleDeg;
    private volatile double toleranceDeg;
    private volatile long maxTimeNs;
    /** Biggest turn (deg) seen inside the current time window, for the live tuning display. */
    private double recentMaxTurnDeg;

    // Ring buffer of unit gravity vectors and their timestamps.
    private static final int CAP = 512;
    private final double[] hx = new double[CAP], hy = new double[CAP], hz = new double[CAP];
    private final long[] ht = new long[CAP];
    /** Whether the device was roughly still at each stored sample (valid gesture start). */
    private final boolean[] hs = new boolean[CAP];
    private int head = 0, size = 0;

    private double gx, gy, gz;
    private long lastNs = -1;
    private long lastStoredNs = Long.MIN_VALUE / 2;
    private long cooldownUntilNs = Long.MIN_VALUE;

    public FlipDetector(int angleDeg, int toleranceDeg, int maxTimeMs) {
        setConfig(angleDeg, toleranceDeg, maxTimeMs);
    }

    public synchronized void setConfig(int angleDeg, int toleranceDeg, int maxTimeMs) {
        this.angleDeg = Math.max(30, Math.min(180, angleDeg));
        this.toleranceDeg = Math.max(0, Math.min(60, toleranceDeg));
        this.maxTimeNs = Math.max(100, maxTimeMs) * 1_000_000L;
    }

    /**
     * Set to true when samples come from Sensor.TYPE_GRAVITY (gyro-fused, accurate during
     * fast motion) rather than the raw accelerometer.
     */
    public void setInputIsGravity(boolean v) {
        inputIsGravity = v;
    }

    /** Minimum turn (deg) away from the start that counts as "turned far enough". */
    public double minTurnDeg() {
        // Keep a clear gap between "away" and "back" so small wobbles can never trigger.
        return Math.max(angleDeg - toleranceDeg, 2 * toleranceDeg);
    }

    /** How close (deg) the device must come back to its starting orientation. */
    public double returnToleranceDeg() {
        return toleranceDeg;
    }

    /** Biggest turn away from the oldest position in the time window (for tuning). */
    public synchronized double recentMaxTurnDeg() {
        return recentMaxTurnDeg;
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
        if (lastNs < 0 || inputIsGravity) {
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
        updateRecentMaxTurn();

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
        double cosFar = Math.cos(Math.toRadians(minTurnDeg())) + 1e-9; // must be <= this
        double cosBack = Math.cos(Math.toRadians(returnToleranceDeg())); // must be >= this
        int last = size - 1;
        double cx = hx[index(last)], cy = hy[index(last)], cz = hz[index(last)];

        // Oldest-first: find a start S that the current orientation has returned to,
        // with some sample in between that rotated far enough away from S.
        for (int i = 0; i < last - 1; i++) {
            int si = index(i);
            if (!hs[si]) continue; // only count from a moment the device was resting
            double sx = hx[si], sy = hy[si], sz = hz[si];
            if (sx * cx + sy * cy + sz * cz < cosBack) continue;
            for (int j = i + 1; j < last; j++) {
                int mj = index(j);
                if (sx * hx[mj] + sy * hy[mj] + sz * hz[mj] <= cosFar) return true;
            }
        }
        return false;
    }

    private void updateRecentMaxTurn() {
        if (size < 2) { recentMaxTurnDeg = 0; return; }
        int s0 = index(0);
        double minCos = 1;
        for (int j = 1; j < size; j++) {
            int k = index(j);
            double c = hx[s0] * hx[k] + hy[s0] * hy[k] + hz[s0] * hz[k];
            if (c < minCos) minCos = c;
        }
        recentMaxTurnDeg = Math.toDegrees(Math.acos(clamp(minCos)));
    }

    // History is stored oldest at logical index 0.
    private int index(int logical) {
        return (head - size + logical + CAP * 2) % CAP;
    }

    private void push(long t, double x, double y, double z) {
        boolean still = true;
        if (size > 0) {
            int p = index(size - 1);
            double dt = (t - ht[p]) / 1e9;
            double c = clamp(hx[p] * x + hy[p] * y + hz[p] * z);
            double speed = dt > 0 ? Math.toDegrees(Math.acos(c)) / dt : 0;
            still = speed <= STILL_DEG_PER_S;
        }
        hs[head] = still;
        hx[head] = x; hy[head] = y; hz[head] = z; ht[head] = t;
        head = (head + 1) % CAP;
        if (size < CAP) size++;
    }

    private static double clamp(double v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }
}
