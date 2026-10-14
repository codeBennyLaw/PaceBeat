package com.pacebeat.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 16, 31);
    private static final int SURFACE = Color.rgb(19, 31, 53);
    private static final int CARD = Color.rgb(23, 38, 66);
    private static final int BORDER = Color.rgb(43, 61, 91);
    private static final int TEXT = Color.rgb(244, 247, 255);
    private static final int MUTED = Color.rgb(145, 164, 194);
    private static final int ORANGE = Color.rgb(255, 107, 74);
    private static final int CYAN = Color.rgb(83, 224, 192);
    private static final int BLUE = Color.rgb(91, 140, 255);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int spm;
    private boolean sound;
    private boolean vibrate;
    private int rhythmMode;
    private TextView spmText;
    private TextView statusText;
    private TextView timerText;
    private TextView actionText;
    private TextView finishText;
    private TextView stepsText;
    private TextView distanceText;
    private TextView paceText;
    private TextView gpsHint;
    private TextView[] presets;
    private TextView[] modeButtons;
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
                statusText.setText(state == 0 ? "READY" : state == 1 ? "LIVE" : "PAUSED");
                statusText.setTextColor(state == 1 ? CYAN : MUTED);
                actionText.setText(state == 0 ? "开始跑" : state == 1 ? "暂停" : "继续");
                actionText.setContentDescription(actionText.getText());
                finishText.setVisibility(active ? View.VISIBLE : View.GONE);
            }

            long elapsedMs = active ? BeatService.elapsedMs() : BeatService.lastSessionMs;
            long seconds = elapsedMs / 1000;
            timerText.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d",
                    seconds / 3600, seconds / 60 % 60, seconds % 60));
            stepsText.setText(BeatService.stepsAvailable
                    ? String.format(Locale.getDefault(), "%,d", BeatService.steps) : "—");
            distanceText.setText(BeatService.gpsReady
                    ? String.format(Locale.getDefault(), "%.2f", BeatService.distanceMeters / 1000) : "—");
            long pace = RunMetrics.paceSecondsPerKm(BeatService.distanceMeters, BeatService.gpsTrackedMs);
            paceText.setText(BeatService.gpsReady && pace > 0
                    ? String.format(Locale.getDefault(), "%d:%02d", pace / 60, pace % 60) : "—");

            StringBuilder available = new StringBuilder();
            if (BeatService.stepsAvailable) available.append("计步");
            if (BeatService.gpsReady) {
                if (available.length() > 0) available.append(" · ");
                available.append("GPS");
            }
            gpsHint.setText(available.length() == 0 ? "手机数据 · —" : "手机数据 · " + available);
            gpsHint.setTextColor(available.length() == 0 ? MUTED : CYAN);
            rhythmView.invalidate();
            handler.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        spm = getPreferences(MODE_PRIVATE).getInt("spm", Cadence.DEFAULT);
        sound = getPreferences(MODE_PRIVATE).getBoolean("sound", true);
        vibrate = getPreferences(MODE_PRIVATE).getBoolean("vibrate", false);
        rhythmMode = Cadence.normalizeRhythm(getPreferences(MODE_PRIVATE).getInt("rhythm_mode", Cadence.MODE_STEADY));
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
        scroll.setPadding(dp(20), dp(landscape ? 8 : 18), dp(20), dp(landscape ? 104 : 128));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = column();
        scroll.addView(content);

        LinearLayout header = row(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(54));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout brand = column();
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text("PACEBEAT", landscape ? 21 : 27, TEXT, true);
        name.setLetterSpacing(.08f);
        brand.addView(name);
        TextView tagline = text("RUN YOUR RHYTHM", 11, MUTED, true);
        tagline.setLetterSpacing(.12f);
        brand.addView(tagline);
        statusText = text("READY", 12, MUTED, true);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackground(shape(SURFACE, 20, BORDER));
        header.addView(statusText, new LinearLayout.LayoutParams(dp(82), dp(38)));

        TextView overline = text("LIVE CADENCE", 12, ORANGE, true);
        overline.setLetterSpacing(.14f);
        LinearLayout.LayoutParams overlineLp = new LinearLayout.LayoutParams(-1, -2);
        overlineLp.topMargin = dp(landscape ? 12 : 28);
        content.addView(overline, overlineLp);

        FrameLayout dial = new FrameLayout(this);
        int dialHeight = landscape ? 126 : 296;
        LinearLayout.LayoutParams dialLp = new LinearLayout.LayoutParams(-1, dp(dialHeight));
        dialLp.topMargin = dp(8);
        content.addView(dial, dialLp);
        rhythmView = new RhythmView();
        dial.addView(rhythmView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout dialContent = column();
        dialContent.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dialContentLp = new FrameLayout.LayoutParams(-1, dp(landscape ? 112 : 168), Gravity.CENTER);
        dialContentLp.bottomMargin = dp(landscape ? 0 : 12);
        dial.addView(dialContent, dialContentLp);
        TextView dialLabel = text("目标步频", 14, MUTED, false);
        if (landscape) dialLabel.setVisibility(View.GONE);
        dialLabel.setGravity(Gravity.CENTER);
        dialContent.addView(dialLabel);
        spmText = text(String.valueOf(spm), landscape ? 64 : 96, TEXT, true);
        spmText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        spmText.setIncludeFontPadding(false);
        spmText.setGravity(Gravity.CENTER);
        spmText.setAutoSizeTextTypeUniformWithConfiguration(32, landscape ? 64 : 96, 2, 2);
        dialContent.addView(spmText, new LinearLayout.LayoutParams(-1, dp(landscape ? 66 : 108)));
        TextView unit = text("SPM  /  步每分钟", 12, CYAN, true);
        unit.setGravity(Gravity.CENTER);
        dialContent.addView(unit);

        LinearLayout fineTune = row(Gravity.CENTER_VERTICAL);
        content.addView(fineTune, new LinearLayout.LayoutParams(-1, dp(64)));
        TextView minus = stepper("−", "减少 1 步每分钟", -1);
        fineTune.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(56)));
        seekBar = new SeekBar(this);
        seekBar.setMax(Cadence.MAX - Cadence.MIN);
        seekBar.setProgress(spm - Cadence.MIN);
        seekBar.setProgressTintList(ColorStateList.valueOf(ORANGE));
        seekBar.setThumbTintList(ColorStateList.valueOf(ORANGE));
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
        fineTune.addView(stepper("+", "增加 1 步每分钟", 1), new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView presetTitle = text("常用步频", 13, MUTED, true);
        content.addView(presetTitle, withTop(18));
        LinearLayout presetRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams presetLp = new LinearLayout.LayoutParams(-1, dp(52));
        presetLp.topMargin = dp(8);
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

        TextView modeTitle = text("节拍模式", 13, MUTED, true);
        content.addView(modeTitle, withTop(26));
        LinearLayout modeRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-1, dp(54));
        modeLp.topMargin = dp(8);
        content.addView(modeRow, modeLp);
        String[] labels = {"均匀", "四拍重音", "轻重交替"};
        modeButtons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            TextView option = button(labels[i], 14, TEXT, true);
            option.setGravity(Gravity.CENTER);
            option.setContentDescription(labels[i] + "节拍模式");
            option.setOnClickListener(v -> setRhythmMode(mode));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            if (i > 0) lp.leftMargin = dp(8);
            modeRow.addView(option, lp);
            modeButtons[i] = option;
        }
        refreshModes();

        TextView dataTitle = text("本次数据", 13, MUTED, true);
        content.addView(dataTitle, withTop(28));
        LinearLayout metrics = row(Gravity.TOP);
        content.addView(metrics, withTop(8));
        stepsText = addMetric(metrics, "步数", CYAN);
        distanceText = addMetric(metrics, "距离 / km", ORANGE);
        paceText = addMetric(metrics, "配速 / km", BLUE);
        gpsHint = text("手机数据 · —", 12, MUTED, true);
        content.addView(gpsHint, withTop(10));

        LinearLayout permissionRow = row(Gravity.CENTER_VERTICAL);
        content.addView(permissionRow, withTop(8));
        TextView permissionLabel = text("权限", 14, TEXT, true);
        permissionRow.addView(permissionLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView permissions = button("管理", 14, CYAN, true);
        permissions.setGravity(Gravity.CENTER);
        permissions.setBackground(ripple(SURFACE, 16, BORDER));
        permissions.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        permissionRow.addView(permissions, new LinearLayout.LayoutParams(dp(82), dp(48)));

        TextView feedbackTitle = text("反馈", 13, MUTED, true);
        content.addView(feedbackTitle, withTop(26));
        content.addView(settingRow("声音", "每一步提示音", sound, checked -> {
            sound = checked;
            saveAndUpdate();
        }), withTop(8));
        content.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        content.addView(settingRow("震动", "跑步时轻触提醒", vibrate, checked -> {
            vibrate = checked;
            saveAndUpdate();
        }), new LinearLayout.LayoutParams(-1, dp(74)));

        LinearLayout session = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sessionLp = new LinearLayout.LayoutParams(-1, -2);
        sessionLp.topMargin = dp(12);
        content.addView(session, sessionLp);
        session.setMinimumHeight(dp(62));
        session.addView(text("本次用时", 15, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        timerText = text("00:00:00", 22, TEXT, true);
        timerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        session.addView(timerText);

        FrameLayout dock = new FrameLayout(this);
        dock.setBackground(shape(SURFACE, 22, BORDER));
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(-1, dp(landscape ? 72 : 96), Gravity.BOTTOM);
        dockLp.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(dock, dockLp);
        LinearLayout dockContent = column();
        dockContent.setPadding(dp(10), dp(10), dp(10), dp(12));
        dock.addView(dockContent, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout actions = row(Gravity.CENTER_VERTICAL);
        dockContent.addView(actions, new LinearLayout.LayoutParams(-1, dp(56)));
        actionText = button("开始跑", 18, Color.WHITE, true);
        actionText.setGravity(Gravity.CENTER);
        actionText.setBackground(ripple(ORANGE, 18, ORANGE));
        actionText.setContentDescription("开始跑步节拍");
        actionText.setOnClickListener(v -> toggleRunning());
        actions.addView(actionText, new LinearLayout.LayoutParams(0, -1, 1));
        finishText = button("结束", 15, TEXT, true);
        finishText.setGravity(Gravity.CENTER);
        finishText.setContentDescription("结束本次跑步");
        finishText.setBackground(ripple(CARD, 18, BORDER));
        finishText.setOnClickListener(v -> {
            startService(new Intent(this, BeatService.class).setAction(BeatService.ACTION_STOP));
            handler.postDelayed(updateClock, 120);
        });
        LinearLayout.LayoutParams finishLp = new LinearLayout.LayoutParams(dp(76), -1);
        finishLp.leftMargin = dp(8);
        actions.addView(finishText, finishLp);
        finishText.setVisibility(View.GONE);
    }

    private TextView addMetric(LinearLayout parent, String label, int color) {
        LinearLayout cell = column();
        cell.setPadding(dp(12), dp(12), dp(8), dp(10));
        cell.setBackground(shape(CARD, 16, BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(78), 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(8);
        parent.addView(cell, lp);
        TextView value = text("—", 19, color, true);
        value.setAutoSizeTextTypeUniformWithConfiguration(13, 19, 1, 2);
        cell.addView(value, new LinearLayout.LayoutParams(-1, dp(32)));
        cell.addView(text(label, 11, MUTED, false));
        return value;
    }

    private void toggleRunning() {
        if (BeatService.isActive) {
            startService(new Intent(this, BeatService.class).setAction(
                    BeatService.isRunning ? BeatService.ACTION_PAUSE : BeatService.ACTION_RESUME));
            handler.postDelayed(updateClock, 120);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        requestActivityPermission();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            requestActivityPermission();
        } else if (requestCode == 2) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_activity", true).apply();
            requestLocationPermission();
        } else if (requestCode == 3) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_location", true).apply();
            startSession();
        }
    }

    private void requestActivityPermission() {
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("asked_activity", false)) {
            requestPermissions(new String[] {Manifest.permission.ACTIVITY_RECOGNITION}, 2);
        } else {
            requestLocationPermission();
        }
    }

    private void requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("asked_location", false)) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 3);
        } else {
            startSession();
        }
    }

    private void startSession() {
        Intent intent = settingsIntent(BeatService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        handler.postDelayed(updateClock, 120);
    }

    private void setSpm(int value) {
        spm = Cadence.clamp(value);
        spmText.setText(String.valueOf(spm));
        seekBar.setProgress(spm - Cadence.MIN);
        seekBar.setContentDescription("目标步频，" + spm + " 步每分钟");
        refreshPresets();
        saveAndUpdate();
    }

    private void setRhythmMode(int mode) {
        rhythmMode = Cadence.normalizeRhythm(mode);
        refreshModes();
        saveAndUpdate();
    }

    private void refreshPresets() {
        if (presets == null) return;
        int[] values = {160, 170, 180, 190};
        for (int i = 0; i < presets.length; i++) {
            boolean selected = values[i] == spm;
            presets[i].setTextColor(selected ? BG : TEXT);
            presets[i].setBackground(ripple(selected ? ORANGE : SURFACE, 16,
                    selected ? ORANGE : BORDER));
            presets[i].setSelected(selected);
        }
    }

    private void refreshModes() {
        if (modeButtons == null) return;
        for (int i = 0; i < modeButtons.length; i++) {
            boolean selected = i == rhythmMode;
            modeButtons[i].setTextColor(selected ? BG : TEXT);
            modeButtons[i].setBackground(ripple(selected ? CYAN : SURFACE, 16,
                    selected ? CYAN : BORDER));
            modeButtons[i].setSelected(selected);
        }
    }

    private Intent settingsIntent(String action) {
        return new Intent(this, BeatService.class).setAction(action)
                .putExtra(BeatService.EXTRA_SPM, spm)
                .putExtra(BeatService.EXTRA_SOUND, sound)
                .putExtra(BeatService.EXTRA_VIBRATE, vibrate)
                .putExtra(BeatService.EXTRA_RHYTHM_MODE, rhythmMode);
    }

    private void saveAndUpdate() {
        getPreferences(MODE_PRIVATE).edit()
                .putInt("spm", spm)
                .putBoolean("sound", sound)
                .putBoolean("vibrate", vibrate)
                .putInt("rhythm_mode", rhythmMode)
                .apply();
        if (BeatService.isActive) startService(settingsIntent(BeatService.ACTION_UPDATE));
    }

    private TextView stepper(String symbol, String description, int delta) {
        TextView button = button(symbol, 28, TEXT, false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(SURFACE, 16, BORDER));
        button.setContentDescription(description);
        button.setOnClickListener(v -> setSpm(spm + delta));
        return button;
    }

    private interface CheckedCallback { void onChecked(boolean checked); }

    private LinearLayout settingRow(String title, String hint, boolean checked, CheckedCallback callback) {
        LinearLayout row = row(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        LinearLayout labels = column();
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(text(title, 16, TEXT, true));
        labels.addView(text(hint, 12, MUTED, false));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setThumbTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {ORANGE, MUTED}));
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
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
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
            float cy = getHeight() / 2f - dp(1);
            float radius = Math.min(Math.min(getWidth() * .39f, getHeight() * .38f), dp(136));
            boolean running = BeatService.isRunning;
            boolean motion = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
            float age = running ? SystemClock.elapsedRealtime() - BeatService.lastBeatAt : 999;
            float pulse = motion && running ? Math.max(0, 1 - age / 260f) : 0;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(10));
            paint.setColor(CARD);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStrokeWidth(dp(7));
            paint.setColor(running ? ORANGE : BLUE);
            RectF bounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(bounds, -90, running && motion ? 280 + 25 * pulse : 250, false, paint);

            paint.setStrokeWidth(dp(1.5f));
            for (int i = 0; i < 40; i++) {
                double angle = Math.PI * 2 * i / 40 - Math.PI / 2;
                float start = radius + dp(15);
                float end = start + dp(i % 5 == 0 ? 10 : 5);
                paint.setColor(i % 5 == 0 ? CYAN : BORDER);
                canvas.drawLine(cx + (float) Math.cos(angle) * start,
                        cy + (float) Math.sin(angle) * start,
                        cx + (float) Math.cos(angle) * end,
                        cy + (float) Math.sin(angle) * end, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(running ? ORANGE : BLUE);
            canvas.drawCircle(cx, cy - radius, dp(5 + pulse * 3), paint);
            if (pulse > 0) {
                paint.setColor(Color.argb((int) (48 * pulse), 255, 107, 74));
                canvas.drawCircle(cx, cy, radius * (.68f + .08f * (1 - pulse)), paint);
            }
            for (int i = 0; i < 9; i++) {
                float height = dp((i % 2 == 0 ? 5 : 9) + (running && motion ? pulse * (i % 3 + 2) * 5 : 0));
                paint.setColor(i == 4 ? ORANGE : CYAN);
                canvas.drawRoundRect(cx + dp((i - 4) * 10 - 2), cy + radius * .9f - height / 2,
                        cx + dp((i - 4) * 10 + 2), cy + radius * .9f + height / 2,
                        dp(2), dp(2), paint);
            }
        }
    }
}
