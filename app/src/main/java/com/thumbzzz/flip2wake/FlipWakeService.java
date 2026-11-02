package com.thumbzzz.flip2wake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Foreground service. While the screen is off it keeps the CPU awake just enough to read
 * the accelerometer, and turns the screen on when the flip gesture is detected.
 * While the screen is on, the sensor is not used at all.
 */
public class FlipWakeService extends Service implements SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "Flip2Wake";
    private static final String CHANNEL_SERVICE = "service";
    private static final String CHANNEL_WAKE = "wake";
    private static final int NOTIF_SERVICE = 1;
    private static final int NOTIF_WAKE = 2;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private PowerManager powerManager;
    private PowerManager.WakeLock cpuLock;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FlipDetector detector;
    private boolean listening;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                startListening();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                stopListening();
            }
        }
    };

    public static void start(Context c) {
        Intent i = new Intent(c, FlipWakeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, FlipWakeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        goForeground();

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        detector = new FlipDetector(Prefs.angle(this), Prefs.maxTimeMs(this));
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this);

        sensorThread = new HandlerThread("flip2wake-sensor");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }

        if (!powerManager.isInteractive()) startListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopListening();
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this);
        if (sensorThread != null) sensorThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- sensor

    private synchronized void startListening() {
        if (listening || accelerometer == null) return;
        if (cpuLock == null) {
            cpuLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flip2wake:sensor");
            cpuLock.setReferenceCounted(false);
        }
        cpuLock.acquire();
        detector.reset();
        // ~50 Hz is plenty for a hand gesture and keeps power use low.
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, sensorHandler);
        listening = true;
        Log.d(TAG, "Screen off: listening for flip");
    }

    private synchronized void stopListening() {
        if (listening) {
            sensorManager.unregisterListener(this);
            listening = false;
            Log.d(TAG, "Screen on: stopped listening");
        }
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (detector.onSample(e.timestamp, e.values[0], e.values[1], e.values[2])) {
            main.post(this::wakeScreen);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        detector.setConfig(Prefs.angle(this), Prefs.maxTimeMs(this));
    }

    // ---------------------------------------------------------------- waking

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        if (powerManager.isInteractive()) return;
        Log.d(TAG, "Flip detected: waking screen");

        // Primary method: a screen wake lock with ACQUIRE_CAUSES_WAKEUP, the same thing
        // alarm/notification apps use. Works on most devices.
        try {
            PowerManager.WakeLock screen = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "flip2wake:screen");
            screen.acquire(2000);
        } catch (Exception ex) {
            Log.w(TAG, "Screen wake lock failed", ex);
        }

        // Fallback: if the screen is still off shortly after, launch an invisible
        // activity flagged turnScreenOn via a full-screen-intent notification.
        main.postDelayed(() -> {
            if (!powerManager.isInteractive()) fallbackWake();
        }, 350);
    }

    private void fallbackWake() {
        Log.d(TAG, "Using full-screen intent fallback");
        Intent i = new Intent(this, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_WAKE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(3000)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_WAKE, n);
        main.postDelayed(() -> nm.cancel(NOTIF_WAKE), 3000);
    }

    // ---------------------------------------------------------------- notification

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel svc = new NotificationChannel(CHANNEL_SERVICE,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel wake = new NotificationChannel(CHANNEL_WAKE,
                getString(R.string.wake_channel), NotificationManager.IMPORTANCE_HIGH);
        wake.setSound(null, null);
        wake.enableVibration(false);
        wake.setShowBadge(false);
        nm.createNotificationChannel(wake);
    }

    private void goForeground() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_SERVICE, n);
        }
    }
}
