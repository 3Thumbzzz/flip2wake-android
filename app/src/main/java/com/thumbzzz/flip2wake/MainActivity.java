package com.thumbzzz.flip2wake;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_NOTIF = 10;

    private Switch serviceSwitch;
    private TextView statusText, angleValue, tolValue, ruleText, timeValue, testText, batteryHelp;
    private SeekBar angleSeek, tolSeek, timeSeek;
    private Button batteryButton, fullScreenButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private FlipDetector testDetector;
    private long lastDetectedAt = 0;
    private int detections = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serviceSwitch = findViewById(R.id.serviceSwitch);
        statusText = findViewById(R.id.statusText);
        angleValue = findViewById(R.id.angleValue);
        tolValue = findViewById(R.id.tolValue);
        ruleText = findViewById(R.id.ruleText);
        tolSeek = findViewById(R.id.tolSeek);
        timeValue = findViewById(R.id.timeValue);
        testText = findViewById(R.id.testText);
        batteryHelp = findViewById(R.id.batteryHelp);
        angleSeek = findViewById(R.id.angleSeek);
        timeSeek = findViewById(R.id.timeSeek);
        batteryButton = findViewById(R.id.batteryButton);
        fullScreenButton = findViewById(R.id.fullScreenButton);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        // Prefer the gyro-fused gravity sensor (accurate during fast turns); fall back to the
        // raw accelerometer on devices without a gyroscope.
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        boolean usingGravity = accelerometer != null;
        if (!usingGravity) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        testDetector = new FlipDetector(Prefs.angle(this), Prefs.tolerance(this), Prefs.maxTimeMs(this));
        testDetector.setInputIsGravity(usingGravity);

        // ---- angle
        angleSeek.setMax((Prefs.ANGLE_MAX - Prefs.ANGLE_MIN) / Prefs.ANGLE_STEP);
        angleSeek.setProgress((Prefs.angle(this) - Prefs.ANGLE_MIN) / Prefs.ANGLE_STEP);
        showAngle(Prefs.angle(this));
        angleSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = Prefs.ANGLE_MIN + p * Prefs.ANGLE_STEP;
                showAngle(v);
                if (fromUser) { Prefs.setAngle(MainActivity.this, v); applyTestConfig(); }
            }
        });

        // ---- tolerance
        tolSeek.setMax((Prefs.TOL_MAX - Prefs.TOL_MIN) / Prefs.TOL_STEP);
        tolSeek.setProgress((Prefs.tolerance(this) - Prefs.TOL_MIN) / Prefs.TOL_STEP);
        showTol(Prefs.tolerance(this));
        tolSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = Prefs.TOL_MIN + p * Prefs.TOL_STEP;
                showTol(v);
                if (fromUser) { Prefs.setTolerance(MainActivity.this, v); applyTestConfig(); }
            }
        });
        showRule();

        // ---- time
        timeSeek.setMax((Prefs.TIME_MAX - Prefs.TIME_MIN) / Prefs.TIME_STEP);
        timeSeek.setProgress((Prefs.maxTimeMs(this) - Prefs.TIME_MIN) / Prefs.TIME_STEP);
        showTime(Prefs.maxTimeMs(this));
        timeSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = Prefs.TIME_MIN + p * Prefs.TIME_STEP;
                showTime(v);
                if (fromUser) { Prefs.setMaxTimeMs(MainActivity.this, v); applyTestConfig(); }
            }
        });

        // ---- service switch
        serviceSwitch.setChecked(Prefs.enabled(this));
        serviceSwitch.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setEnabled(this, checked);
            if (checked) {
                requestNotificationPermissionIfNeeded();
                FlipWakeService.start(this);
            } else {
                FlipWakeService.stop(this);
            }
            updateStatus();
        });
        if (Prefs.enabled(this)) FlipWakeService.start(this); // make sure it's running

        batteryButton.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        fullScreenButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updatePermissionButtons();
        if (accelerometer != null) {
            testDetector.reset();
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        } else {
            testText.setText("No accelerometer found on this device.");
        }
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        super.onPause();
    }

    // ---- live test

    @Override
    public void onSensorChanged(SensorEvent e) {
        boolean hit = testDetector.onSample(e.timestamp, e.values[0], e.values[1], e.values[2]);
        if (hit) {
            detections++;
            lastDetectedAt = System.currentTimeMillis();
        }
        boolean recent = System.currentTimeMillis() - lastDetectedAt < 1500;
        testText.setText(String.format(Locale.US,
                "Sensor: %s\nTilt from face-up: %3.0f°\nBiggest turn in last %.1f s: %3.0f° (need %.0f°)\nGestures detected here: %d%s",
                e.sensor.getType() == Sensor.TYPE_GRAVITY ? "gravity (gyro-fused)" : "accelerometer",
                testDetector.currentTiltDeg(), Prefs.maxTimeMs(this) / 1000f,
                testDetector.recentMaxTurnDeg(), testDetector.minTurnDeg(), detections,
                recent ? "\n✔ FLIP DETECTED — this would wake the screen" : "\nTry it: flip the device over and back"));
        testText.setBackgroundColor(recent ? 0x5534C759 : 0x00000000);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ---- helpers

    private void applyTestConfig() {
        testDetector.setConfig(Prefs.angle(this), Prefs.tolerance(this), Prefs.maxTimeMs(this));
        showRule();
    }

    private void showAngle(int v) {
        angleValue.setText(String.format(Locale.US, "%d°", v));
    }

    private void showTol(int v) {
        tolValue.setText(String.format(Locale.US, "± %d°", v));
    }

    private void showRule() {
        ruleText.setText(String.format(Locale.US,
                "Counts when turned at least %.0f° away from the starting position, then back to within %.0f° of it, all within %.1f s.",
                testDetector.minTurnDeg(), testDetector.returnToleranceDeg(), Prefs.maxTimeMs(this) / 1000f));
    }

    private void showTime(int ms) {
        timeValue.setText(String.format(Locale.US, "%.1f s", ms / 1000f));
    }

    private void updateStatus() {
        if (accelerometer == null) {
            statusText.setText("This device has no accelerometer, so flip to wake can't work.");
            serviceSwitch.setEnabled(false);
            return;
        }
        statusText.setText(Prefs.enabled(this)
                ? "Running. With the screen off, turn the device over and back to wake it."
                : "Off. The power button works as normal.");
    }

    private void updatePermissionButtons() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryButton.setVisibility(ignoring ? View.GONE : View.VISIBLE);
        batteryHelp.setVisibility(ignoring ? View.GONE : View.VISIBLE);

        boolean needFsi = false;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            needFsi = !nm.canUseFullScreenIntent();
        }
        fullScreenButton.setVisibility(needFsi ? View.VISIBLE : View.GONE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
