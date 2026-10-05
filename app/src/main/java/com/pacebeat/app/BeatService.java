package com.pacebeat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class BeatService extends Service {
    public static final String ACTION_START = "com.pacebeat.app.START";
    public static final String ACTION_UPDATE = "com.pacebeat.app.UPDATE";
    public static final String ACTION_STOP = "com.pacebeat.app.STOP";
    public static final String ACTION_PAUSE = "com.pacebeat.app.PAUSE";
    public static final String ACTION_RESUME = "com.pacebeat.app.RESUME";
    private static final String ACTION_DISMISS = "com.pacebeat.app.DISMISS_NOTIFICATION";
    public static final String EXTRA_SPM = "spm";
    public static final String EXTRA_SOUND = "sound";
    public static final String EXTRA_VIBRATE = "vibrate";
    public static final String EXTRA_DURATION = "duration_minutes";

    private static final int RATE = 22050;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "running_beat";

    public static volatile boolean isRunning;
    public static volatile boolean isActive;
    public static volatile long activeMs;
    public static volatile long steps;
    public static volatile boolean stepsAvailable;
    public static volatile double distanceMeters;
    public static volatile long gpsTrackedMs;
    public static volatile boolean gpsReady;
    public static volatile long lastGpsFixMs;
    public static volatile boolean locationTrackingEnabled;
    public static volatile long startedAt;
    public static volatile long lastBeatAt;
    public static volatile long beatCount;
    public static volatile long lastSessionMs;
    public static volatile int goalMinutes;

    private volatile int spm = Cadence.DEFAULT;
    private volatile boolean sound = true;
    private volatile boolean vibrate;
    private volatile boolean active;
    private boolean notificationDismissed;
    private volatile long sessionId;
    private long accumulatedMs;
    private float counterBaseline = Float.NaN;
    private Location previousFix;
    private SensorManager sensorManager;
    private LocationManager locationManager;
    private final SensorEventListener stepListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (!isRunning || event.timestamp < startedAt * 1000000L) return;
            if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
                steps++;
            } else if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                if (!Float.isNaN(counterBaseline)) {
                    steps += Math.max(0, Math.round(event.values[0] - counterBaseline));
                }
                counterBaseline = event.values[0];
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
    private final LocationListener locationListener = location -> {
        if (!isRunning || location.getElapsedRealtimeNanos() < startedAt * 1000000L) return;
        long age = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000;
        if (!location.hasAccuracy() || !RunMetrics.reliableFix(location.getAccuracy(), age,
                location.isFromMockProvider())) return;
        lastGpsFixMs = location.getElapsedRealtimeNanos() / 1000000;
        if (previousFix != null) {
            long delta = (location.getElapsedRealtimeNanos() - previousFix.getElapsedRealtimeNanos()) / 1000000;
            if (delta <= 0) return;
            float meters = previousFix.distanceTo(location);
            if (RunMetrics.reliableSegment(meters, previousFix.getAccuracy(), location.getAccuracy(), delta)) {
                distanceMeters += meters;
                gpsTrackedMs += delta;
                gpsReady = true; // One fix alone cannot establish a measured distance.
            } else if (delta > 30000) {
                previousFix = location;
                return;
            } else {
                return;
            }
        }
        previousFix = location;
    };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread audioThread;
    private volatile AudioTrack audioTrack;
    private final Runnable refreshNotification = new Runnable() {
        @Override public void run() {
            if (!active || notificationDismissed) return;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
            mainHandler.postDelayed(this, 5000);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (ACTION_DISMISS.equals(action)) {
            if (active) {
                notificationDismissed = true;
                mainHandler.removeCallbacks(refreshNotification);
            } else {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action) && active && isRunning) {
            pauseSession();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action) && active && !isRunning) {
            resumeSession();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            spm = Cadence.clamp(intent.getIntExtra(EXTRA_SPM, spm));
            sound = intent.getBooleanExtra(EXTRA_SOUND, sound);
            vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, vibrate);
        }
        if (ACTION_START.equals(action) && !active) {
            notificationDismissed = false;
            goalMinutes = Math.max(0, Math.min(120, intent.getIntExtra(EXTRA_DURATION, 0)));
            startedAt = SystemClock.elapsedRealtime();
            accumulatedMs = 0;
            activeMs = 0;
            steps = 0;
            stepsAvailable = false;
            distanceMeters = 0;
            gpsTrackedMs = 0;
            gpsReady = false;
            lastGpsFixMs = 0;
            previousFix = null;
            counterBaseline = Float.NaN;
            createChannel();
            int types = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationTrackingEnabled = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (locationTrackingEnabled && Build.VERSION.SDK_INT >= 29) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                    == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            }
            isRunning = true;
            isActive = true;
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), types);
            else startForeground(NOTIFICATION_ID, notification());
            active = true;
            lastSessionMs = 0;
            lastBeatAt = startedAt;
            beatCount = 0;
            beginTracking();
            startAudio();
            mainHandler.postDelayed(refreshNotification, 5000);
        } else if (active) {
            if (!notificationDismissed) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(NOTIFICATION_ID, notification());
            }
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "跑步节拍",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("跑步数据与暂停、继续、结束操作");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private static boolean supportsLiveUpdates() {
        return Build.VERSION.SDK_INT >= 36
                && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1;
    }

    public static long elapsedMs() {
        return activeMs + (isRunning ? Math.max(0, SystemClock.elapsedRealtime() - startedAt) : 0);
    }

    private void startAudio() {
        long session = ++sessionId;
        audioThread = new Thread(() -> play(session), "PaceBeat-Audio");
        audioThread.start();
    }

    private void stopAudio() {
        sessionId++;
        if (audioThread != null) audioThread.interrupt();
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            } catch (IllegalStateException ignored) {
                // The audio thread releases the track.
            }
        }
    }

    private void beginTracking() {
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED) {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            if (sensor == null) sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            counterBaseline = Float.NaN;
            if (sensor != null) stepsAvailable = sensorManager.registerListener(stepListener, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mainHandler);
        } else {
            stepsAvailable = false;
        }
        previousFix = null;
        lastGpsFixMs = 0;
        if (locationTrackingEnabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 0,
                        locationListener, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Location may be revoked or disabled during a session.
                locationTrackingEnabled = false;
            }
        }
    }

    private void endTracking() {
        if (sensorManager != null) sensorManager.unregisterListener(stepListener);
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (SecurityException ignored) { /* Permission may have been revoked. */ }
        }
        previousFix = null;
        counterBaseline = Float.NaN;
    }

    private void pauseSession() {
        accumulatedMs += SystemClock.elapsedRealtime() - startedAt;
        activeMs = accumulatedMs;
        isRunning = false;
        endTracking();
        stopAudio();
        mainHandler.removeCallbacks(refreshNotification);
        updateNotification();
    }

    private void resumeSession() {
        startedAt = SystemClock.elapsedRealtime();
        isRunning = true;
        beginTracking();
        startAudio();
        updateNotification();
        if (!notificationDismissed) mainHandler.postDelayed(refreshNotification, 5000);
    }

    private void updateNotification() {
        if (!notificationDismissed) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification());
    }

    private void stopSession() {
        mainHandler.removeCallbacks(refreshNotification);
        if (active) lastSessionMs = elapsedMs();
        active = false;
        isActive = false;
        isRunning = false;
        endTracking();
        stopAudio();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BeatService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getService(this, 3,
                new Intent(this, BeatService.class).setAction(isRunning ? ACTION_PAUSE : ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dismiss = PendingIntent.getService(this, 2,
                new Intent(this, BeatService.class).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(spm + " SPM · 跑步节拍")
                .setContentText((isRunning ? "跑步中" : "已暂停") + " · "
                        + (stepsAvailable ? steps + " 步" : "计步不可用") + " · "
                        + (gpsReady ? String.format(java.util.Locale.getDefault(), "%.2f km", distanceMeters / 1000) : "GPS 待定位"))
                .setContentIntent(open)
                .setDeleteIntent(dismiss)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Build.VERSION.SDK_INT >= 31
                        ? Notification.CATEGORY_WORKOUT : Notification.CATEGORY_SERVICE)
                .addAction(isRunning ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isRunning ? "暂停" : "继续", toggle)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束", stop);
        if (supportsLiveUpdates() && !notificationDismissed) {
            long elapsed = elapsedMs() / 1000;
            long remaining = Math.max(0, goalMinutes * 60L - elapsed);
            builder.setRequestPromotedOngoing(true);
            if (goalMinutes > 0) {
                String remainingText = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                        remaining / 60, remaining % 60);
                builder.setShortCriticalText(isRunning ? remainingText : "已暂停")
                        .setSubText("剩余 " + remainingText)
                        .setWhen(System.currentTimeMillis() + remaining * 1000)
                        .setUsesChronometer(isRunning)
                        .setChronometerCountDown(true);
                builder.setStyle(new Notification.ProgressStyle()
                        .setProgressSegments(java.util.Collections.singletonList(
                                new Notification.ProgressStyle.Segment(goalMinutes * 60)))
                        .setProgress((int) Math.min(goalMinutes * 60L, elapsed)));
            } else {
                builder.setShortCriticalText(isRunning ? spm + " SPM" : "已暂停")
                        .setWhen(System.currentTimeMillis() - elapsedMs())
                        .setUsesChronometer(isRunning);
            }
        }
        return builder.build();
    }

    private void play(long session) {
        AudioTrack track = null;
        Vibrator motor = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        boolean canVibrate = motor != null && motor.hasVibrator();
        short[] buffer = new short[256];
        long phase = 0;
        int clickSample = 0;
        double frequency = 1100;
        try {
            int minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) return;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBuffer, 2048))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) return;
            audioTrack = track;
            if (!active || session != sessionId) return;
            track.play();
            while (active && session == sessionId && !Thread.currentThread().isInterrupted()) {
                if (goalMinutes > 0 && elapsedMs() >= goalMinutes * 60000L) {
                    mainHandler.post(() -> {
                        if (active && session == sessionId) {
                            stopSession();
                        }
                    });
                    break;
                }
                for (int i = 0; i < buffer.length; i++) {
                    long interval = Cadence.samplesPerBeat(RATE, spm);
                    if (phase == 0) {
                        frequency = (beatCount % 2 == 0) ? 1150 : 820;
                        lastBeatAt = SystemClock.elapsedRealtime();
                        beatCount++;
                        clickSample = 0;
                        if (vibrate && canVibrate) {
                            motor.vibrate(VibrationEffect.createOneShot(16, 75));
                        }
                    }
                    if (sound && clickSample < RATE / 20) {
                        double envelope = Math.exp(-clickSample / 160.0);
                        buffer[i] = (short) (Math.sin(2 * Math.PI * frequency * clickSample / RATE)
                                * envelope * 12000);
                    } else {
                        buffer[i] = 0;
                    }
                    clickSample++;
                    phase++;
                    if (phase >= interval) phase = 0;
                }
                int written = track.write(buffer, 0, buffer.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) break;
            }
        } catch (RuntimeException ignored) {
            // The audio device can disappear while the service is running.
        } finally {
            if (audioTrack == track) audioTrack = null;
            if (track != null) {
                try {
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
                } catch (IllegalStateException ignored) {
                    // Playback may already have stopped when the output device disconnects.
                }
                track.release();
            }
            mainHandler.post(() -> {
                if (active && session == sessionId) {
                    stopSession();
                }
            });
        }
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(refreshNotification);
        if (active) lastSessionMs = elapsedMs();
        active = false;
        isActive = false;
        isRunning = false;
        endTracking();
        stopAudio();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
