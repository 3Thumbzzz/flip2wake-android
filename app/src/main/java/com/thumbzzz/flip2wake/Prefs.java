package com.thumbzzz.flip2wake;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared between the UI and the background service. */
public final class Prefs {
    public static final String FILE = "flip2wake";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ANGLE = "angle_deg";
    public static final String KEY_MAX_TIME = "max_time_ms";
    public static final String KEY_TOLERANCE = "tolerance_deg";

    public static final int ANGLE_MIN = 60, ANGLE_MAX = 180, ANGLE_STEP = 5, ANGLE_DEFAULT = 180;
    public static final int TOL_MIN = 5, TOL_MAX = 45, TOL_STEP = 5, TOL_DEFAULT = 20;
    public static final int TIME_MIN = 300, TIME_MAX = 5000, TIME_STEP = 100, TIME_DEFAULT = 1500;

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return get(c).getBoolean(KEY_ENABLED, false); }
    public static int angle(Context c) { return get(c).getInt(KEY_ANGLE, ANGLE_DEFAULT); }
    public static int tolerance(Context c) { return get(c).getInt(KEY_TOLERANCE, TOL_DEFAULT); }
    public static int maxTimeMs(Context c) { return get(c).getInt(KEY_MAX_TIME, TIME_DEFAULT); }

    public static void setEnabled(Context c, boolean v) { get(c).edit().putBoolean(KEY_ENABLED, v).apply(); }
    public static void setAngle(Context c, int v) { get(c).edit().putInt(KEY_ANGLE, v).apply(); }
    public static void setTolerance(Context c, int v) { get(c).edit().putInt(KEY_TOLERANCE, v).apply(); }
    public static void setMaxTimeMs(Context c, int v) { get(c).edit().putInt(KEY_MAX_TIME, v).apply(); }
}
