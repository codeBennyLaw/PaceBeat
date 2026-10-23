package com.pacebeat.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LauncherShortcuts {
    static final String ACTION_TOGGLE = "com.pacebeat.app.TOGGLE_RUN";
    // Reuse the 1.1.0 start ID so cached and pinned launchers keep the same entry.
    static final String SHORTCUT_ID = BeatService.ACTION_START;
    private static final String TAG = "LauncherShortcuts";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    // Accessed only on WORKER. Failed publications are retried on the next sync.
    private static int publishedState = -1;

    private LauncherShortcuts() {}

    static void sync(Context context) {
        Context app = context.getApplicationContext();
        int state = !BeatService.isActive ? 0 : BeatService.isRunning ? 1 : 2;
        WORKER.execute(() -> publish(app, state));
    }

    private static void publish(Context context, int state) {
        if (state == publishedState) return;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        int label = state == 0 ? R.string.shortcut_start
                : state == 1 ? R.string.shortcut_pause : R.string.shortcut_resume;
        int icon = state == 1 ? R.drawable.ic_shortcut_pause : R.drawable.ic_shortcut_play;
        List<ShortcutInfo> shortcuts = Collections.singletonList(shortcut(context, label, icon));
        try {
            // Recover a start entry disabled by 1.1.0, without ever disabling it again.
            manager.enableShortcuts(Collections.singletonList(SHORTCUT_ID));
            // Replace the old multi-entry menu once, then update the same entry in place.
            boolean published = publishedState < 0 ? manager.setDynamicShortcuts(shortcuts)
                    : manager.updateShortcuts(shortcuts);
            if (published) {
                publishedState = state;
            } else {
                Log.w(TAG, "Shortcut publication was rate limited");
            }
        } catch (IllegalStateException e) {
            // Shortcuts are unavailable while credential-encrypted storage is locked.
            Log.w(TAG, "Shortcuts are temporarily unavailable", e);
        }
    }

    private static ShortcutInfo shortcut(Context context, int label, int icon) {
        Intent intent = new Intent(context, MainActivity.class).setAction(ACTION_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new ShortcutInfo.Builder(context, SHORTCUT_ID)
                .setShortLabel(context.getString(label))
                .setLongLabel(context.getString(label))
                .setIcon(Icon.createWithResource(context, icon))
                .setActivity(new ComponentName(context, MainActivity.class))
                .setIntent(intent)
                .setRank(0)
                .build();
    }
}
