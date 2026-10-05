package com.pacebeat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class BeatService extends Service {
    public static final String ACTION_START = "com.pacebeat.app.START";
    public static final String ACTION_UPDATE = "com.pacebeat.app.UPDATE";
    public static final String ACTION_STOP = "com.pacebeat.app.STOP";
    public static final String EXTRA_SPM = "spm";
    public static final String EXTRA_SOUND = "sound";
    public static final String EXTRA_VIBRATE = "vibrate";

    private static final int RATE = 22050;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "running_beat";

    public static volatile boolean isRunning;
    public static volatile long startedAt;
    public static volatile long lastBeatAt;
    public static volatile long beatCount;
    public static volatile long lastSessionMs;

    private volatile int spm = Cadence.DEFAULT;
    private volatile boolean sound = true;
    private volatile boolean vibrate;
    private volatile boolean active;
    private volatile long sessionId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread audioThread;
    private volatile AudioTrack audioTrack;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (active) lastSessionMs = SystemClock.elapsedRealtime() - startedAt;
            active = false;
            isRunning = false;
            sessionId++;
            if (audioThread != null) audioThread.interrupt();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null) {
            spm = Cadence.clamp(intent.getIntExtra(EXTRA_SPM, spm));
            sound = intent.getBooleanExtra(EXTRA_SOUND, sound);
            vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, vibrate);
        }
        if (ACTION_START.equals(action) && !active) {
            createChannel();
            startForeground(NOTIFICATION_ID, notification());
            active = true;
            long session = ++sessionId;
            isRunning = true;
            startedAt = SystemClock.elapsedRealtime();
            lastSessionMs = 0;
            lastBeatAt = startedAt;
            beatCount = 0;
            audioThread = new Thread(() -> play(session), "PaceBeat-Audio");
            audioThread.start();
        } else if (active) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID, notification());
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "跑步节拍",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示正在运行的节拍与停止操作");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BeatService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(spm + " SPM · 跑步节拍")
                .setContentText("节拍进行中")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(android.R.drawable.ic_media_pause, "停止", stop)
                .build();
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
                    lastSessionMs = SystemClock.elapsedRealtime() - startedAt;
                    active = false;
                    isRunning = false;
                    stopSelf();
                }
            });
        }
    }

    @Override public void onDestroy() {
        if (active) lastSessionMs = SystemClock.elapsedRealtime() - startedAt;
        active = false;
        isRunning = false;
        sessionId++;
        if (audioThread != null) audioThread.interrupt();
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            } catch (IllegalStateException ignored) {
                // The audio thread owns release and final cleanup.
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
