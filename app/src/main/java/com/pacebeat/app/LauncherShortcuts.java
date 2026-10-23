package com.pacebeat.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LauncherShortcuts {
    private static final String TAG = "LauncherShortcuts";
    private static final List<String> IDS = Arrays.asList(
            BeatService.ACTION_START, BeatService.ACTION_PAUSE,
            BeatService.ACTION_RESUME, BeatService.ACTION_STOP);
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
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        if (state == 0) {
            shortcuts.add(shortcut(context, BeatService.ACTION_START,
                    R.string.shortcut_start, R.drawable.ic_shortcut_play, 0));
        } else {
            shortcuts.add(shortcut(context,
                    state == 1 ? BeatService.ACTION_PAUSE : BeatService.ACTION_RESUME,
                    state == 1 ? R.string.shortcut_pause : R.string.shortcut_resume,
                    state == 1 ? R.drawable.ic_shortcut_pause : R.drawable.ic_shortcut_play, 0));
            shortcuts.add(shortcut(context, BeatService.ACTION_STOP,
                    R.string.shortcut_stop, R.drawable.ic_shortcut_stop, 1));
        }
        List<String> enabled = new ArrayList<>();
        for (ShortcutInfo shortcut : shortcuts) enabled.add(shortcut.getId());
        List<String> disabled = new ArrayList<>(IDS);
        disabled.removeAll(enabled);
        try {
            manager.enableShortcuts(enabled);
            if (manager.setDynamicShortcuts(shortcuts)) {
                // Also disable pinned copies of actions that no longer apply.
                manager.disableShortcuts(disabled, context.getString(R.string.shortcut_unavailable));
                publishedState = state;
            } else {
                Log.w(TAG, "Shortcut publication was rate limited");
            }
        } catch (IllegalStateException e) {
            // Shortcuts are unavailable while credential-encrypted storage is locked.
            Log.w(TAG, "Shortcuts are temporarily unavailable", e);
        }
    }

    private static ShortcutInfo shortcut(Context context, String action, int label, int icon, int rank) {
        Intent intent = new Intent(context, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new ShortcutInfo.Builder(context, action)
                .setShortLabel(context.getString(label))
                .setLongLabel(context.getString(label))
                .setIcon(Icon.createWithResource(context, icon))
                .setActivity(new ComponentName(context, MainActivity.class))
                .setIntent(intent)
                .setRank(rank)
                .build();
    }
}
