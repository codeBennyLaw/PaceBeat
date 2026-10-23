package com.pacebeat.app;

import android.app.Application;

public class PaceBeatApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        LauncherShortcuts.sync(this);
    }
}
