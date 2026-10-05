package com.pacebeat.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.qmdeve.liquidglass.widget.LiquidGlassView;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 20, 18);
    private static final int SURFACE = Color.rgb(26, 32, 28);
    private static final int BORDER = Color.rgb(48, 59, 51);
    private static final int TEXT = Color.rgb(243, 246, 241);
    private static final int MUTED = Color.rgb(166, 180, 169);
    private static final int LIME = Color.rgb(217, 244, 91);
    private static final int TEAL = Color.rgb(115, 190, 169);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int spm;
    private boolean sound;
    private boolean vibrate;
    private int goalMinutes;
    private TextView spmText;
    private TextView statusText;
    private TextView timerText;
    private TextView actionText;
    private TextView goalText;
    private TextView stepsText;
    private TextView distanceText;
    private TextView paceText;
    private TextView gpsHint;
    private TextView finishText;
    private TextView[] durationOptions;
    private TextView[] presets;
    private SeekBar seekBar;
    private RhythmView rhythmView;
    private int displayedState = -1;

    private final Runnable updateClock = new Runnable() {
        @Override public void run() {
            boolean active = BeatService.isActive;
            boolean running = BeatService.isRunning;
            int state = !active ? 0 : running ? 1 : 2;
            if (state != displayedState) {
                displayedState = state;
                statusText.setText(state == 0 ? "准备开始" : state == 1 ? "节拍进行中" : "训练已暂停");
                statusText.setTextColor(state == 1 ? LIME : MUTED);
                actionText.setText(state == 0 ? "开始跑步" : state == 1 ? "暂停训练" : "继续训练");
                actionText.setContentDescription(actionText.getText());
                finishText.setVisibility(active ? View.VISIBLE : View.GONE);
                refreshDurations();
            }
            long elapsedMs = active ? BeatService.elapsedMs()
                    : BeatService.lastSessionMs;
            long seconds = elapsedMs / 1000;
            timerText.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d",
                    seconds / 3600, seconds / 60 % 60, seconds % 60));
            int target = active ? BeatService.goalMinutes : goalMinutes;
            if (target == 0) {
                goalText.setText("自由跑 · 不限时");
            } else if (active) {
                long remaining = Math.max(0, target * 60L - seconds);
                goalText.setText(String.format(Locale.getDefault(), "目标 %d 分钟 · %s %02d:%02d",
                        target, running ? "剩余" : "暂停于", remaining / 60, remaining % 60));
            } else {
                goalText.setText("目标 " + target + " 分钟 · 到时自动停止");
            }
            stepsText.setText(BeatService.stepsAvailable ? String.format(Locale.getDefault(), "%d", BeatService.steps) : "—");
            distanceText.setText(BeatService.gpsReady
                    ? String.format(Locale.getDefault(), "%.2f km", BeatService.distanceMeters / 1000) : "—");
            long pace = RunMetrics.paceSecondsPerKm(BeatService.distanceMeters, BeatService.gpsTrackedMs);
            paceText.setText(BeatService.gpsReady && pace > 0
                    ? String.format(Locale.getDefault(), "%d:%02d /km", pace / 60, pace % 60) : "—");
            boolean fineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            String hint;
            if (!fineLocation) hint = "距离需精确定位权限；拒绝后节拍器仍可使用";
            else if (active && !BeatService.locationTrackingEnabled) hint = "本次未启用 GPS，请开启系统定位后重新开始训练";
            else if (active && !running) hint = "已暂停定位和计步；继续时重新建立 GPS 基点";
            else if (running && (BeatService.lastGpsFixMs == 0 || SystemClock.elapsedRealtime() - BeatService.lastGpsFixMs > 15000))
                hint = "等待可靠 GPS 信号；已累计距离保留，不补算失联路段";
            else if (BeatService.gpsReady) hint = "仅统计有效 GPS 路段；100 米后显示该路段平均配速";
            else hint = "开始后在室外等待 GPS 定位；距离不会按步长估算";
            gpsHint.setText(hint);
            rhythmView.invalidate();
            handler.postDelayed(this, running ? 100 : 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        spm = Cadence.clamp(getPreferences(MODE_PRIVATE).getInt("spm", Cadence.DEFAULT));
        sound = getPreferences(MODE_PRIVATE).getBoolean("sound", true);
        vibrate = getPreferences(MODE_PRIVATE).getBoolean("vibrate", false);
        goalMinutes = getPreferences(MODE_PRIVATE).getInt("goal_minutes", 0);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updateClock);
        handler.post(updateClock);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(updateClock);
        super.onPause();
    }

    private void buildScreen() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(24), dp(landscape ? 8 : 16), dp(24), dp(landscape ? 112 : 140));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = column();
        scroll.addView(content);

        LinearLayout header = row(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(landscape ? 48 : 58));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout brand = column();
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text("PACEBEAT", landscape ? 20 : 27, TEXT, true);
        name.setLetterSpacing(0);
        name.setAutoSizeTextTypeUniformWithConfiguration(14, 27, 1, 2);
        brand.addView(name);
        TextView tagline = text("为每一步定下节奏", 12, MUTED, false);
        brand.addView(tagline);
        statusText = text("准备开始", 12, MUTED, true);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackground(shape(SURFACE, 8, BORDER));
        header.addView(statusText, new LinearLayout.LayoutParams(dp(100), dp(38)));

        TextView overline = text("01   当前步频  /  CADENCE", 12, TEAL, true);
        if (landscape) overline.setVisibility(View.GONE);
        LinearLayout.LayoutParams overlineLp = new LinearLayout.LayoutParams(-1, -2);
        overlineLp.topMargin = dp(landscape ? 12 : 30);
        content.addView(overline, overlineLp);

        FrameLayout dial = new FrameLayout(this);
        int dialHeight = landscape ? 126 : 320;
        LinearLayout.LayoutParams dialLp = new LinearLayout.LayoutParams(-1, dp(dialHeight));
        dialLp.topMargin = dp(landscape ? 0 : 8);
        content.addView(dial, dialLp);
        rhythmView = new RhythmView();
        dial.addView(rhythmView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout dialContent = column();
        dialContent.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dialContentLp = new FrameLayout.LayoutParams(-1, dp(landscape ? 112 : 180), Gravity.CENTER);
        dialContentLp.bottomMargin = dp(landscape ? 0 : 14);
        dial.addView(dialContent, dialContentLp);
        TextView dialLabel = text("目标步频", 14, MUTED, false);
        if (landscape) dialLabel.setVisibility(View.GONE);
        dialLabel.setGravity(Gravity.CENTER);
        dialContent.addView(dialLabel);
        spmText = text(String.valueOf(spm), landscape ? 64 : 106, TEXT, true);
        spmText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        spmText.setIncludeFontPadding(false);
        spmText.setGravity(Gravity.CENTER);
        spmText.setAutoSizeTextTypeUniformWithConfiguration(32, landscape ? 64 : 106, 2, 2);
        dialContent.addView(spmText, new LinearLayout.LayoutParams(-1, dp(landscape ? 66 : 118)));
        TextView unit = text("SPM  /  步每分钟", 12, LIME, true);
        if (landscape) unit.setText("SPM");
        unit.setGravity(Gravity.CENTER);
        dialContent.addView(unit);

        LinearLayout fineTune = row(Gravity.CENTER_VERTICAL);
        content.addView(fineTune, new LinearLayout.LayoutParams(-1, dp(64)));
        TextView minus = stepper("−", "减少 1 步每分钟", -1);
        fineTune.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(56)));
        seekBar = new SeekBar(this);
        seekBar.setMax(Cadence.MAX - Cadence.MIN);
        seekBar.setProgress(spm - Cadence.MIN);
        seekBar.setProgressTintList(ColorStateList.valueOf(LIME));
        seekBar.setThumbTintList(ColorStateList.valueOf(LIME));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        seekBar.setContentDescription("目标步频，" + spm + " 步每分钟");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) setSpm(Cadence.MIN + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(56), 1);
        seekLp.setMargins(dp(10), 0, dp(10), 0);
        fineTune.addView(seekBar, seekLp);
        TextView plus = stepper("+", "增加 1 步每分钟", 1);
        fineTune.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView presetTitle = text("快速设定", 14, MUTED, true);
        LinearLayout.LayoutParams presetTitleLp = new LinearLayout.LayoutParams(-1, -2);
        presetTitleLp.topMargin = dp(24);
        content.addView(presetTitle, presetTitleLp);
        LinearLayout presetRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams presetLp = new LinearLayout.LayoutParams(-1, dp(52));
        presetLp.topMargin = dp(12);
        content.addView(presetRow, presetLp);
        int[] values = {160, 170, 180, 190};
        presets = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            TextView preset = button(String.valueOf(value), 17, TEXT, true);
            preset.setGravity(Gravity.CENTER);
            preset.setContentDescription(value + " 步每分钟");
            preset.setOnClickListener(v -> setSpm(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
            if (i > 0) lp.leftMargin = dp(8);
            presetRow.addView(preset, lp);
            presets[i] = preset;
        }
        refreshPresets();

        TextView trainingTitle = text("02   训练计划  /  TRAINING", 12, TEAL, true);
        content.addView(trainingTitle, withTop(30));
        TextView durationTitle = text("训练时长", 15, TEXT, true);
        content.addView(durationTitle, withTop(16));
        LinearLayout durationRow = row(Gravity.CENTER_VERTICAL);
        content.addView(durationRow, withTop(10));
        int[] durations = {0, 10, 20, 30, 45, 60};
        durationOptions = new TextView[durations.length];
        for (int i = 0; i < durations.length; i++) {
            int minutes = durations[i];
            TextView option = button(minutes == 0 ? "不限" : String.valueOf(minutes), 14, TEXT, true);
            option.setGravity(Gravity.CENTER);
            option.setContentDescription(minutes == 0 ? "不限时" : minutes + " 分钟");
            option.setOnClickListener(v -> {
                goalMinutes = minutes;
                getPreferences(MODE_PRIVATE).edit().putInt("goal_minutes", minutes).apply();
                refreshDurations();
            });
            LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) optionLp.leftMargin = dp(6);
            durationRow.addView(option, optionLp);
            durationOptions[i] = option;
        }
        refreshDurations();
        goalText = text("", 13, MUTED, false);
        content.addView(goalText, withTop(10));

        TextView gpsTitle = text("手机实测  /  GPS + 计步", 15, TEXT, true);
        content.addView(gpsTitle, withTop(24));
        LinearLayout metrics = row(Gravity.TOP);
        content.addView(metrics, withTop(12));
        stepsText = addMetric(metrics, "实际步数");
        distanceText = addMetric(metrics, "GPS 距离");
        paceText = addMetric(metrics, "GPS 段均配速");
        gpsHint = text("暂无可靠 GPS 数据", 12, MUTED, false);
        content.addView(gpsHint, withTop(10));
        TextView permissionsHint = text("活动识别仅用于本次计步；位置仅用于本次测距，不读取健康历史，也不上传轨迹。计步无权限或无传感器时显示 —。", 12, MUTED, false);
        content.addView(permissionsHint, withTop(8));
        LinearLayout permissionActions = row(Gravity.CENTER_VERTICAL);
        content.addView(permissionActions, withTop(8));
        TextView permissions = button("应用权限", 14, TEAL, true);
        permissions.setGravity(Gravity.CENTER);
        permissions.setBackground(ripple(SURFACE, 6, BORDER));
        permissions.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        permissionActions.addView(permissions, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView locationSettings = button("系统定位", 14, TEAL, true);
        locationSettings.setGravity(Gravity.CENTER);
        locationSettings.setBackground(ripple(SURFACE, 6, BORDER));
        locationSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        LinearLayout.LayoutParams locationLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        locationLp.leftMargin = dp(8);
        permissionActions.addView(locationSettings, locationLp);

        TextView settingsTitle = text("03   反馈方式  /  FEEDBACK", 12, TEAL, true);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-1, -2);
        settingsLp.topMargin = dp(34);
        content.addView(settingsTitle, settingsLp);
        content.addView(settingRow("节拍声音", "每一步的短促提示音", sound, checked -> {
            sound = checked;
            saveAndUpdate();
        }), withTop(18));
        content.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        content.addView(settingRow("震动反馈", "适合不方便听声音时", vibrate, checked -> {
            vibrate = checked;
            saveAndUpdate();
        }), new LinearLayout.LayoutParams(-1, dp(74)));

        LinearLayout session = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sessionLp = new LinearLayout.LayoutParams(-1, -2);
        sessionLp.topMargin = dp(16);
        content.addView(session, sessionLp);
        session.setMinimumHeight(dp(78));
        LinearLayout sessionLabels = column();
        session.addView(sessionLabels, new LinearLayout.LayoutParams(0, -2, 1));
        sessionLabels.addView(text("本次训练", 15, TEXT, true));
        timerText = text("00:00:00", 22, TEXT, true);
        timerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        session.addView(timerText);

        FrameLayout dock = new FrameLayout(this);
        if (Build.VERSION.SDK_INT >= 33) {
            LiquidGlassView glass = new LiquidGlassView(this);
            glass.setCornerRadius(dp(8));
            glass.setRefractionHeight(dp(16));
            glass.setBlurRadius(12f);
            glass.setTintColorRed(.08f);
            glass.setTintColorGreen(.12f);
            glass.setTintColorBlue(.1f);
            glass.setTintAlpha(.55f);
            dock.addView(glass, new FrameLayout.LayoutParams(-1, -1));
            glass.bind(scroll);
            View scrim = new View(this);
            scrim.setBackground(shape(Color.argb(242, 26, 32, 28), 7,
                    Color.argb(242, 26, 32, 28)));
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(-1, -1);
            scrimLp.setMargins(dp(3), dp(3), dp(3), dp(3));
            dock.addView(scrim, scrimLp);
        } else {
            dock.setBackgroundColor(BG);
        }
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(-1, dp(landscape ? 72 : 100), Gravity.BOTTOM);
        root.addView(dock, dockLp);
        LinearLayout dockContent = column();
        dockContent.setPadding(dp(24), dp(landscape ? 8 : 12), dp(24), dp(landscape ? 8 : 20));
        dock.addView(dockContent, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout actions = row(Gravity.CENTER_VERTICAL);
        dockContent.addView(actions, new LinearLayout.LayoutParams(-1, dp(landscape ? 56 : 64)));
        actionText = button("开始跑步", 19, BG, true);
        actionText.setGravity(Gravity.CENTER);
        actionText.setBackground(ripple(LIME, 8, LIME));
        actionText.setContentDescription("开始跑步节拍");
        actionText.setOnClickListener(v -> toggleRunning());
        actions.addView(actionText, new LinearLayout.LayoutParams(0, -1, 1));
        finishText = button("结束", 16, TEXT, true);
        finishText.setGravity(Gravity.CENTER);
        finishText.setContentDescription("结束本次跑步训练");
        finishText.setBackground(ripple(SURFACE, 8, BORDER));
        finishText.setOnClickListener(v -> {
            startService(new Intent(this, BeatService.class).setAction(BeatService.ACTION_STOP));
            handler.removeCallbacks(updateClock);
            handler.postDelayed(updateClock, 120);
        });
        LinearLayout.LayoutParams finishLp = new LinearLayout.LayoutParams(dp(82), -1);
        finishLp.leftMargin = dp(8);
        actions.addView(finishText, finishLp);
        finishText.setVisibility(View.GONE);
    }

    private TextView addMetric(LinearLayout parent, String label) {
        LinearLayout cell = column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        parent.addView(cell, lp);
        TextView value = text("—", 18, LIME, true);
        value.setAutoSizeTextTypeUniformWithConfiguration(13, 18, 1, 2);
        cell.addView(value, new LinearLayout.LayoutParams(-1, dp(32)));
        cell.addView(text(label, 12, MUTED, false));
        return value;
    }

    private void toggleRunning() {
        if (BeatService.isActive) {
            startService(new Intent(this, BeatService.class).setAction(
                    BeatService.isRunning ? BeatService.ACTION_PAUSE : BeatService.ACTION_RESUME));
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            requestActivityPermission();
        }
        handler.removeCallbacks(updateClock);
        handler.postDelayed(updateClock, 120);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            requestActivityPermission();
        } else if (requestCode == 2) {
            requestLocationPermission();
        } else if (requestCode == 3 && !BeatService.isActive) {
            startForegroundService(settingsIntent(BeatService.ACTION_START));
            handler.removeCallbacks(updateClock);
            handler.postDelayed(updateClock, 120);
        }
    }

    private void requestActivityPermission() {
        if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACTIVITY_RECOGNITION}, 2);
        } else {
            requestLocationPermission();
        }
    }

    private void requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 3);
        } else if (!BeatService.isActive) {
            startForegroundService(settingsIntent(BeatService.ACTION_START));
            handler.removeCallbacks(updateClock);
            handler.postDelayed(updateClock, 120);
        }
    }

    private void setSpm(int value) {
        spm = Cadence.clamp(value);
        spmText.setText(String.valueOf(spm));
        seekBar.setProgress(spm - Cadence.MIN);
        seekBar.setContentDescription("目标步频，" + spm + " 步每分钟");
        refreshPresets();
        saveAndUpdate();
    }

    private void refreshDurations() {
        if (durationOptions == null) return;
        int[] durations = {0, 10, 20, 30, 45, 60};
        for (int i = 0; i < durationOptions.length; i++) {
            boolean selected = durations[i] == goalMinutes;
            durationOptions[i].setTextColor(selected ? BG : TEXT);
            durationOptions[i].setBackground(ripple(selected ? LIME : SURFACE, 6,
                    selected ? LIME : BORDER));
            durationOptions[i].setEnabled(!BeatService.isActive);
            durationOptions[i].setAlpha(BeatService.isActive ? .6f : 1f);
        }
    }

    private void refreshPresets() {
        int[] values = {160, 170, 180, 190};
        for (int i = 0; i < presets.length; i++) {
            boolean selected = values[i] == spm;
            presets[i].setTextColor(selected ? BG : TEXT);
            presets[i].setBackground(ripple(selected ? LIME : SURFACE, 6,
                    selected ? LIME : BORDER));
            presets[i].setSelected(selected);
        }
    }

    private Intent settingsIntent(String action) {
        return new Intent(this, BeatService.class).setAction(action)
                .putExtra(BeatService.EXTRA_SPM, spm)
                .putExtra(BeatService.EXTRA_SOUND, sound)
                .putExtra(BeatService.EXTRA_VIBRATE, vibrate)
                .putExtra(BeatService.EXTRA_DURATION, goalMinutes);
    }

    private void saveAndUpdate() {
        getPreferences(MODE_PRIVATE).edit()
                .putInt("spm", spm).putBoolean("sound", sound)
                .putBoolean("vibrate", vibrate).apply();
        if (BeatService.isActive) startService(settingsIntent(BeatService.ACTION_UPDATE));
    }

    private TextView stepper(String symbol, String description, int delta) {
        TextView button = button(symbol, 28, TEXT, false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(SURFACE, 6, BORDER));
        button.setContentDescription(description);
        button.setOnClickListener(v -> setSpm(spm + delta));
        return button;
    }

    private interface CheckedCallback { void onChecked(boolean checked); }

    private LinearLayout settingRow(String title, String hint, boolean checked, CheckedCallback callback) {
        LinearLayout row = row(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column();
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(text(title, 16, TEXT, true));
        labels.addView(text(hint, 12, MUTED, false));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setThumbTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {LIME, MUTED}));
        toggle.setTrackTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {BORDER, SURFACE}));
        toggle.setOnCheckedChangeListener((button, value) -> callback.onChecked(value));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(56)));
        return row;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(BORDER);
        return view;
    }

    private LinearLayout.LayoutParams withTop(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(margin);
        return lp;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row(int gravity) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(gravity);
        return layout;
    }

    private TextView text(String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button button(String content, int size, int color, boolean bold) {
        Button view = new Button(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setAllCaps(false);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setMinimumWidth(0);
        view.setMinimumHeight(0);
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    private RippleDrawable ripple(int color, int radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(65, 255, 255, 255)),
                shape(color, radius, border), null);
    }

    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != color) drawable.setStroke(dp(1), border);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class RhythmView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RhythmView() { super(MainActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f - dp(3);
            float radius = Math.min(Math.min(getWidth() * .39f, getHeight() * .38f), dp(144));
            boolean running = BeatService.isRunning;
            boolean motion = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
            float age = running ? SystemClock.elapsedRealtime() - BeatService.lastBeatAt : 999;
            float pulse = motion && running ? Math.max(0, 1 - age / 300f) : 0;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(BORDER);
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(LIME);
            RectF bounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(bounds, -90, running && motion ? 260 + 35 * pulse : 300, false, paint);

            paint.setStrokeWidth(dp(1.5f));
            for (int i = 0; i < 48; i++) {
                double angle = Math.PI * 2 * i / 48 - Math.PI / 2;
                float start = radius + dp(13);
                float end = start + dp(i % 4 == 0 ? 9 : 5);
                paint.setColor(i % 4 == 0 ? TEAL : BORDER);
                canvas.drawLine(cx + (float) Math.cos(angle) * start,
                        cy + (float) Math.sin(angle) * start,
                        cx + (float) Math.cos(angle) * end,
                        cy + (float) Math.sin(angle) * end, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(running ? LIME : TEAL);
            canvas.drawCircle(cx, cy - radius, dp(4 + pulse * 3), paint);
            if (pulse > 0) {
                paint.setColor(Color.argb((int) (50 * pulse), 217, 244, 91));
                canvas.drawCircle(cx, cy, radius * (.68f + .08f * (1 - pulse)), paint);
            }
            for (int i = 0; i < 9; i++) {
                float height = dp((i % 2 == 0 ? 5 : 9) + (running && motion ? pulse * (i % 3 + 2) * 5 : 0));
                paint.setColor(i == 4 ? LIME : TEAL);
                canvas.drawRoundRect(cx + dp((i - 4) * 10 - 2), cy + radius * .9f - height / 2,
                        cx + dp((i - 4) * 10 + 2), cy + radius * .9f + height / 2,
                        dp(2), dp(2), paint);
            }
        }
    }
}
