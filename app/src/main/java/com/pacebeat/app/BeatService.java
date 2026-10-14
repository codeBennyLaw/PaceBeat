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
    public static final String EXTRA_RHYTHM_MODE = "rhythm_mode";

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

    private volatile int spm = Cadence.DEFAULT;
    private volatile int rhythmMode = Cadence.MODE_STEADY;
    private volatile boolean sound = true;
    private volatile boolean vibrate;
    private volatile boolean active;
    private boolean notificationDismissed;
    private volatile long sessionId;
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
                if (!Float.isNaN(counterBaseline)) steps += Math.max(0, Math.round(event.values[0] - counterBaseline));
                counterBaseline = event.values[0];
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
    private final LocationListener locationListener = location -> {
        if (!isRunning || location.getElapsedRealtimeNanos() < startedAt * 1000000L) return;
        long age = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000;
        if (!location.hasAccuracy() || !RunMetrics.reliableFix(location.getAccuracy(), age, location.isFromMockProvider())) return;
        lastGpsFixMs = location.getElapsedRealtimeNanos() / 1000000;
        if (previousFix != null) {
            long delta = (location.getElapsedRealtimeNanos() - previousFix.getElapsedRealtimeNanos()) / 1000000;
            if (delta <= 0) return;
            float meters = previousFix.distanceTo(location);
            if (RunMetrics.reliableSegment(meters, previousFix.getAccuracy(), location.getAccuracy(), delta)) {
                distanceMeters += meters;
                gpsTrackedMs += delta;
                gpsReady = true;
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
            if (active && !notificationDismissed) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
                mainHandler.postDelayed(this, 5000);
            }
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
            rhythmMode = Cadence.normalizeRhythm(intent.getIntExtra(EXTRA_RHYTHM_MODE, rhythmMode));
            sound = intent.getBooleanExtra(EXTRA_SOUND, sound);
            vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, vibrate);
        }
        if (ACTION_START.equals(action) && !active) {
            notificationDismissed = false;
            startedAt = SystemClock.elapsedRealtime();
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
                    == PackageManager.PERMISSION_GRANTED && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (locationTrackingEnabled && Build.VERSION.SDK_INT >= 29) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                    == PackageManager.PERMISSION_GRANTED) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
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
        } else if (ACTION_UPDATE.equals(action) && active) {
            if (!notificationDismissed) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
        } else if (active) {
            if (!notificationDismissed) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "跑步节拍", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("节拍与本次跑步数据");
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
        ++sessionId;
        Thread thread = audioThread;
        if (thread != null) thread.interrupt();
        AudioTrack track = audioTrack;
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            audioTrack = null;
        }
        audioThread = null;
    }

    private void beginTracking() {
        counterBaseline = Float.NaN;
        previousFix = null;
        lastGpsFixMs = 0;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            if (sensor == null) sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (sensor != null) stepsAvailable = sensorManager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (locationTrackingEnabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 0, locationListener, Looper.getMainLooper());
            } catch (SecurityException ignored) {
                locationTrackingEnabled = false;
            }
        }
    }

    private void endTracking() {
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) {}
        }
        if (sensorManager != null) sensorManager.unregisterListener(stepListener);
        previousFix = null;
        counterBaseline = Float.NaN;
    }

    private void pauseSession() {
        activeMs += Math.max(0, SystemClock.elapsedRealtime() - startedAt);
        isRunning = false;
        endTracking();
        stopAudio();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }

    private void resumeSession() {
        startedAt = SystemClock.elapsedRealtime();
        isRunning = true;
        beginTracking();
        startAudio();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }

    private void stopSession() {
        if (!active) {
            stopSelf();
            return;
        }
        if (isRunning) activeMs += Math.max(0, SystemClock.elapsedRealtime() - startedAt);
        lastSessionMs = activeMs;
        active = false;
        isActive = false;
        isRunning = false;
        mainHandler.removeCallbacks(refreshNotification);
        endTracking();
        stopAudio();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 11,
                new Intent(this, BeatService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 12,
                new Intent(this, BeatService.class).setAction(isRunning ? ACTION_PAUSE : ACTION_RESUME), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent dismiss = PendingIntent.getService(this, 13,
                new Intent(this, BeatService.class).setAction(ACTION_DISMISS), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String data = stepsAvailable ? steps + " 步" : "步数 —";
        if (gpsReady) data += String.format(java.util.Locale.getDefault(), " · %.2f km", distanceMeters / 1000);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.pacebeat.app.R.drawable.ic_notification)
                .setContentTitle("PaceBeat · " + spm + " SPM")
                .setContentText(data)
                .setContentIntent(open)
                .setOngoing(isActive)
                .setOnlyAlertOnce(true)
                .setCategory(Build.VERSION.SDK_INT >= 31
                        ? Notification.CATEGORY_WORKOUT : Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, isRunning ? "暂停" : "继续", pause).build())
                .addAction(new Notification.Action.Builder(null, "结束", stop).build())
                .setDeleteIntent(dismiss);
        if (isRunning) {
            builder.setWhen(System.currentTimeMillis() - elapsedMs()).setUsesChronometer(true);
        }
        if (supportsLiveUpdates() && !notificationDismissed) {
            builder.setRequestPromotedOngoing(true)
                    .setShortCriticalText(isRunning ? spm + " SPM" : "已暂停")
                    .setWhen(System.currentTimeMillis() - elapsedMs())
                    .setUsesChronometer(isRunning);
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
        double frequency = 980;
        try {
            int minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) return;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBuffer, 2048)).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) return;
            audioTrack = track;
            if (!active || session != sessionId) return;
            track.play();
            while (active && session == sessionId && !Thread.currentThread().isInterrupted()) {
                for (int i = 0; i < buffer.length; i++) {
                    long interval = Cadence.samplesPerBeat(RATE, spm);
                    if (phase == 0) {
                        frequency = Cadence.toneFrequency(rhythmMode, beatCount);
                        lastBeatAt = SystemClock.elapsedRealtime();
                        beatCount++;
                        clickSample = 0;
                        if (vibrate && canVibrate) motor.vibrate(VibrationEffect.createOneShot(16, 75));
                    }
                    if (sound && clickSample < RATE / 20) {
                        double envelope = Math.exp(-clickSample / 160.0);
                        buffer[i] = (short) (Math.sin(2 * Math.PI * frequency * clickSample / RATE) * envelope * 12000);
                    } else {
                        buffer[i] = 0;
                    }
                    clickSample++;
                    phase++;
                    if (phase >= interval) phase = 0;
                }
                if (track.write(buffer, 0, buffer.length, AudioTrack.WRITE_BLOCKING) < 0) break;
            }
        } catch (RuntimeException ignored) {
            // The audio device can disappear while the service is running.
        } finally {
            if (audioTrack == track) audioTrack = null;
            if (track != null) {
                try { if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            mainHandler.post(() -> {
                if (active && session == sessionId) stopSession();
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
