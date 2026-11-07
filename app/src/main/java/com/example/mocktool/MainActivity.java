package com.example.mocktool;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M1 主界面：设定坐标 → 换算为 WGS84 → 注入系统定位管线 → 读回验证。
 *
 * 坐标转换策略：只在"应用位置"这一步转换一次，转换结果被记录下来，
 * 时间轴回放时直接使用已转换的注入值，不会二次转换。
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1001;
    private static final int REQ_NOTIFICATION = 1002;
    private static final String PREF_UI = "mock_ui";
    private static final String KEY_LAST_LAT = "last_olat";
    private static final String KEY_LAST_LON = "last_olon";
    private static final String KEY_LAST_SYS = "last_sys";

    private EditText etLatitude;
    private EditText etLongitude;
    private TextView tvStatus;
    private TextView tvOriginal;
    private TextView tvPermission;
    private TextView tvHint;
    private TextView tvTimelineEmpty;
    private ListView lvTimeline;
    private RadioGroup rgSystem;
    private RadioButton rbWgs84;
    private RadioButton rbGcj02;

    private PointStore pointStore;
    private MockLocationEngine engine;
    private final List<SavedPoint> timeline = new ArrayList<>();
    private TimelineAdapter adapter;

    /** 最近一次应用时用户输入的原始坐标（未转换）。 */
    private double lastOriginalLatitude = Double.NaN;
    private double lastOriginalLongitude = Double.NaN;
    private int lastSourceSystem = CoordinateConverter.SYS_WGS84;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /**
     * 状态轮询器。
     * startForegroundService 是异步的，服务真正起来之前 isRunning() 仍为 false，
     * 所以不能只在点击后刷新一次，需要持续跟随服务状态。
     */
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            uiHandler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etLatitude = findViewById(R.id.etLatitude);
        etLongitude = findViewById(R.id.etLongitude);
        tvStatus = findViewById(R.id.tvStatus);
        tvOriginal = findViewById(R.id.tvOriginal);
        tvPermission = findViewById(R.id.tvPermission);
        tvHint = findViewById(R.id.tvHint);
        tvTimelineEmpty = findViewById(R.id.tvTimelineEmpty);
        lvTimeline = findViewById(R.id.lvTimeline);
        rgSystem = findViewById(R.id.rgSystem);
        rbWgs84 = findViewById(R.id.rbWgs84);
        rbGcj02 = findViewById(R.id.rbGcj02);

        pointStore = new PointStore(this);
        engine = new MockLocationEngine(this);

        adapter = new TimelineAdapter();
        lvTimeline.setAdapter(adapter);
        lvTimeline.setOnItemClickListener((parent, view, position, id) ->
                applySavedPoint(timeline.get(position)));

        findViewById(R.id.btnApply).setOnClickListener(v -> applyCurrentInput());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopMocking());
        findViewById(R.id.btnReadBack).setOnClickListener(v -> readBackFromSystem());
        findViewById(R.id.btnSavePoint).setOnClickListener(v -> saveCurrentPoint());
        findViewById(R.id.btnClearPoints).setOnClickListener(v -> clearTimeline());

        restoreLastInput();
        reloadTimeline();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
        refreshStatus();
        uiHandler.removeCallbacks(statusTicker);
        uiHandler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statusTicker);
    }

    // ---------------------------------------------------------------- 核心操作

    private int currentSourceSystem() {
        return rbGcj02.isChecked()
                ? CoordinateConverter.SYS_GCJ02
                : CoordinateConverter.SYS_WGS84;
    }

    private void applyCurrentInput() {
        CoordinateValidator.Result result =
                CoordinateValidator.parse(etLatitude.getText().toString(),
                        etLongitude.getText().toString());
        if (!result.valid) {
            toast(result.error);
            return;
        }
        if (!MockLocationEngine.isMockLocationAllowed(this)) {
            showPermissionGuide();
            return;
        }

        int system = currentSourceSystem();
        // 唯一一次坐标转换：输入坐标系 → WGS84
        double[] wgs = CoordinateConverter.toWgs84(result.latitude, result.longitude, system);
        startMocking(result.latitude, result.longitude, system, wgs[0], wgs[1]);
    }

    /** 时间轴回放：直接使用记录里已转换好的注入值，不再转换。 */
    private void applySavedPoint(SavedPoint point) {
        etLatitude.setText(String.format(Locale.US, "%.6f", point.originalLatitude));
        etLongitude.setText(String.format(Locale.US, "%.6f", point.originalLongitude));
        if (point.sourceSystem == CoordinateConverter.SYS_GCJ02) {
            rbGcj02.setChecked(true);
        } else {
            rbWgs84.setChecked(true);
        }

        if (!MockLocationEngine.isMockLocationAllowed(this)) {
            showPermissionGuide();
            return;
        }
        startMocking(point.originalLatitude, point.originalLongitude, point.sourceSystem,
                point.injectedLatitude, point.injectedLongitude);
    }

    private void startMocking(double originalLat, double originalLon, int system,
                              double injectedLat, double injectedLon) {
        Intent intent = new Intent(this, MockLocationService.class);
        intent.setAction(MockLocationService.ACTION_START);
        intent.putExtra(MockLocationService.EXTRA_LATITUDE, injectedLat);
        intent.putExtra(MockLocationService.EXTRA_LONGITUDE, injectedLon);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        lastOriginalLatitude = originalLat;
        lastOriginalLongitude = originalLon;
        lastSourceSystem = system;
        saveLastInput(originalLat, originalLon, system);

        tvHint.setText(system == CoordinateConverter.SYS_GCJ02
                ? R.string.hint_applied_converted
                : R.string.hint_applied);
        refreshStatus();
    }

    private void stopMocking() {
        Intent intent = new Intent(this, MockLocationService.class);
        intent.setAction(MockLocationService.ACTION_STOP);
        startService(intent);
        tvHint.setText(R.string.hint_stopped);
        refreshStatus();
    }

    private void readBackFromSystem() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        Location location = engine.readBackFromSystem();
        if (location == null) {
            toast(getString(R.string.hint_readback_empty));
            return;
        }
        String message = String.format(Locale.US,
                "系统返回的定位数据（WGS84）：\n\n纬度：%.6f\n经度：%.6f\n来源：%s\nisMock：%s\n时间：%s",
                location.getLatitude(),
                location.getLongitude(),
                location.getProvider(),
                location.isMock() ? "true（模拟）" : "false（真实）",
                new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new java.util.Date(location.getTime())));
        new AlertDialog.Builder(this)
                .setTitle("读取系统定位")
                .setMessage(message)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private void saveCurrentPoint() {
        if (!MockLocationService.isRunning()) {
            toast(getString(R.string.hint_no_active_point));
            return;
        }
        // 保存时同时记录原始坐标与已转换的注入坐标
        pointStore.append(new SavedPoint(
                lastOriginalLatitude,
                lastOriginalLongitude,
                lastSourceSystem,
                MockLocationService.currentLatitude(),
                MockLocationService.currentLongitude(),
                System.currentTimeMillis()));
        reloadTimeline();
        toast(getString(R.string.hint_saved));
    }

    private void clearTimeline() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_clear_points)
                .setMessage("确定清空全部时间轴记录？")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (d, w) -> {
                    pointStore.clear();
                    reloadTimeline();
                    toast(getString(R.string.hint_cleared));
                })
                .show();
    }

    // ---------------------------------------------------------------- 界面刷新

    private void refreshStatus() {
        if (!MockLocationService.isRunning()) {
            tvStatus.setText(R.string.status_idle);
            tvOriginal.setVisibility(View.GONE);
            return;
        }
        double injectedLat = MockLocationService.currentLatitude();
        double injectedLon = MockLocationService.currentLongitude();
        tvStatus.setText(getString(R.string.status_active,
                String.format(Locale.US, "%.6f, %.6f", injectedLat, injectedLon)));

        if (lastSourceSystem == CoordinateConverter.SYS_GCJ02
                && !Double.isNaN(lastOriginalLatitude)) {
            double offset = CoordinateConverter.distanceMeters(
                    lastOriginalLatitude, lastOriginalLongitude, injectedLat, injectedLon);
            tvOriginal.setText(getString(R.string.status_original,
                    CoordinateConverter.systemLabel(lastSourceSystem),
                    String.format(Locale.US, "%.6f, %.6f",
                            lastOriginalLatitude, lastOriginalLongitude),
                    String.format(Locale.US, "%.0f", offset)));
            tvOriginal.setVisibility(View.VISIBLE);
        } else {
            tvOriginal.setVisibility(View.GONE);
        }
    }

    private void refreshPermissionState() {
        boolean allowed = MockLocationEngine.isMockLocationAllowed(this);
        if (allowed) {
            tvPermission.setText(R.string.permission_granted);
            tvPermission.setTextColor(0xFF1B7F3B);
        } else {
            tvPermission.setText(R.string.permission_denied);
            tvPermission.setTextColor(0xFFB00020);
        }
    }

    private void reloadTimeline() {
        timeline.clear();
        timeline.addAll(pointStore.load());
        adapter.notifyDataSetChanged();
        tvTimelineEmpty.setVisibility(timeline.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showPermissionGuide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_denied_dialog_title)
                .setMessage(R.string.permission_denied_dialog_msg)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    // ---------------------------------------------------------------- 辅助

    private void restoreLastInput() {
        SharedPreferences prefs = getSharedPreferences(PREF_UI, MODE_PRIVATE);
        String latText = prefs.getString(KEY_LAST_LAT, null);
        String lonText = prefs.getString(KEY_LAST_LON, null);
        int system = prefs.getInt(KEY_LAST_SYS, CoordinateConverter.SYS_WGS84);
        if (system == CoordinateConverter.SYS_GCJ02) {
            rbGcj02.setChecked(true);
        } else {
            rbWgs84.setChecked(true);
        }
        if (latText != null && lonText != null) {
            try {
                lastOriginalLatitude = Double.parseDouble(latText);
                lastOriginalLongitude = Double.parseDouble(lonText);
                lastSourceSystem = system;
                etLatitude.setText(String.format(Locale.US, "%.6f", lastOriginalLatitude));
                etLongitude.setText(String.format(Locale.US, "%.6f", lastOriginalLongitude));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveLastInput(double latitude, double longitude, int system) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_LAT, Double.toString(latitude))
                .putString(KEY_LAST_LON, Double.toString(longitude))
                .putInt(KEY_LAST_SYS, system)
                .apply();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                readBackFromSystem();
            } else {
                toast(getString(R.string.hint_readback_need_permission));
            }
        }
    }

    private void toast(String message) {
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    /** 时间轴列表适配器。 */
    private final class TimelineAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return timeline.size();
        }

        @Override
        public Object getItem(int position) {
            return timeline.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_point, parent, false);
            }
            SavedPoint point = timeline.get(position);
            TextView coords = view.findViewById(R.id.itemCoords);
            TextView time = view.findViewById(R.id.itemTime);
            coords.setText(point.titleText());
            time.setText(point.subtitleText());
            return view;
        }
    }
}
