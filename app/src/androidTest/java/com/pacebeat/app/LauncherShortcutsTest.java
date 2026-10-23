package com.pacebeat.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.util.List;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class LauncherShortcutsTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final ShortcutManager shortcuts = context.getSystemService(ShortcutManager.class);
    private ActivityScenario<MainActivity> screen;

    @Before public void setUp() throws Exception {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION);
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) grant(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.POST_NOTIFICATIONS);
        context.stopService(new Intent(context, BeatService.class));
        await(() -> !BeatService.isActive);
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putInt("spm", 180).putBoolean("sound", false).putBoolean("vibrate", false)
                .putInt("rhythm_mode", Cadence.MODE_ALTERNATING).commit();
        screen = ActivityScenario.launch(new Intent(context, MainActivity.class));
        assertShortcuts(BeatService.ACTION_START);
    }

    @After public void tearDown() {
        context.stopService(new Intent(context, BeatService.class));
        await(() -> !BeatService.isActive);
        assertShortcuts(BeatService.ACTION_START);
        if (screen != null) screen.close();
    }

    @Test public void shortcutsFollowRunAndNotificationActions() {
        openShortcut(BeatService.ACTION_START);
        await(() -> BeatService.isRunning);
        assertShortcuts(BeatService.ACTION_PAUSE, BeatService.ACTION_STOP);
        Notification notification = context.getSystemService(NotificationManager.class)
                .getActiveNotifications()[0].getNotification();
        assertEquals("PaceBeat · 180 SPM", notification.extras.getString(Notification.EXTRA_TITLE));
        assertTrue(BeatService.beatCount > 0 || BeatService.lastBeatAt > 0);

        // Use the actual notification action so publication is verified outside the activity.
        try {
            notification.actions[0].actionIntent.send();
        } catch (android.app.PendingIntent.CanceledException e) {
            throw new AssertionError(e);
        }
        await(() -> BeatService.isActive && !BeatService.isRunning);
        assertShortcuts(BeatService.ACTION_RESUME, BeatService.ACTION_STOP);
        long elapsed = BeatService.elapsedMs();
        openShortcut(BeatService.ACTION_RESUME);
        await(() -> BeatService.isRunning);
        assertTrue(BeatService.elapsedMs() >= elapsed);
        assertShortcuts(BeatService.ACTION_PAUSE, BeatService.ACTION_STOP);
        openShortcut(BeatService.ACTION_STOP);
        await(() -> !BeatService.isActive);
        assertShortcuts(BeatService.ACTION_START);
    }

    @Test public void staleAndRepeatedIntentsNeverToggleOrRestartSession() {
        openShortcut(BeatService.ACTION_PAUSE);
        openShortcut(BeatService.ACTION_RESUME);
        openShortcut(BeatService.ACTION_STOP);
        assertFalse(BeatService.isActive);

        openShortcut(BeatService.ACTION_START);
        await(() -> BeatService.isRunning);
        long started = BeatService.startedAt;
        openShortcut(BeatService.ACTION_START);
        assertEquals(started, BeatService.startedAt);
        assertTrue(BeatService.isRunning);
        openShortcut(BeatService.ACTION_PAUSE);
        await(() -> !BeatService.isRunning);
        openShortcut(BeatService.ACTION_PAUSE);
        assertTrue(BeatService.isActive);
        assertFalse(BeatService.isRunning);
        openShortcut(BeatService.ACTION_STOP);
        await(() -> !BeatService.isActive);

        // Recreation of an activity opened by a start shortcut must not replay that start.
        openShortcut(BeatService.ACTION_START);
        await(() -> BeatService.isRunning);
        screen.onActivity(activity -> activity.startService(
                new Intent(activity, BeatService.class).setAction(BeatService.ACTION_STOP)));
        await(() -> !BeatService.isActive);
        screen.recreate();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(BeatService.isActive);
        assertShortcuts(BeatService.ACTION_START);
    }

    @Test public void coldShortcutLaunchAndServiceDestructionRestoreStart() {
        screen.close();
        screen = ActivityScenario.launch(new Intent(context, MainActivity.class)
                .setAction(BeatService.ACTION_START));
        await(() -> BeatService.isRunning);
        assertShortcuts(BeatService.ACTION_PAUSE, BeatService.ACTION_STOP);
        context.stopService(new Intent(context, BeatService.class));
        await(() -> !BeatService.isActive);
        assertShortcuts(BeatService.ACTION_START);
    }

    private void openShortcut(String action) {
        context.startActivity(new Intent(context, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void assertShortcuts(String... actions) {
        await(() -> {
            List<ShortcutInfo> published = shortcuts.getDynamicShortcuts();
            if (published.size() != actions.length) return false;
            for (int i = 0; i < actions.length; i++) {
                ShortcutInfo match = null;
                for (ShortcutInfo item : published) {
                    if (actions[i].equals(item.getId())) match = item;
                }
                if (match == null || !match.isEnabled() || match.getRank() != i
                        || !actions[i].equals(match.getIntent().getAction())
                        || !MainActivity.class.getName().equals(match.getIntent().getComponent().getClassName())) {
                    return false;
                }
            }
            return true;
        });
    }

    private void grant(String permission) throws Exception {
        try (ParcelFileDescriptor command = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm grant " + context.getPackageName() + " " + permission);
             FileInputStream output = new FileInputStream(command.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (output.read(buffer) != -1) { /* Wait for the shell command to finish. */ }
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50);
        }
        assertTrue("Expected service/shortcut state within 10 seconds", condition.getAsBoolean());
    }
}
