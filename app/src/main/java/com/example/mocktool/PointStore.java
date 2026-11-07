package com.example.mocktool;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间轴记录的本地存储（SharedPreferences + JSON）。
 * 最多保留 {@link #MAX_POINTS} 条，超出时丢弃最旧的。
 */
public final class PointStore {

    private static final String PREF_NAME = "mock_timeline";
    private static final String KEY_POINTS = "points";
    public static final int MAX_POINTS = 20;

    private final SharedPreferences preferences;

    public PointStore(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 按时间倒序返回全部记录（最新在前）。 */
    public List<SavedPoint> load() {
        List<SavedPoint> result = new ArrayList<>();
        String raw = preferences.getString(KEY_POINTS, null);
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                long ts = item.optLong("ts", 0L);
                if (item.has("olat")) {
                    // 新格式：原始坐标 + 坐标系 + 注入坐标
                    result.add(new SavedPoint(
                            item.optDouble("olat", Double.NaN),
                            item.optDouble("olon", Double.NaN),
                            item.optInt("sys", CoordinateConverter.SYS_WGS84),
                            item.optDouble("ilat", Double.NaN),
                            item.optDouble("ilon", Double.NaN),
                            ts));
                } else {
                    // 旧格式兼容：只有 lat/lon，视为 WGS84 且未转换
                    double lat = item.optDouble("lat", Double.NaN);
                    double lon = item.optDouble("lon", Double.NaN);
                    result.add(new SavedPoint(
                            lat, lon, CoordinateConverter.SYS_WGS84, lat, lon, ts));
                }
            }
        } catch (Exception ignored) {
            // 数据损坏时按空列表处理，不崩溃
        }
        return result;
    }

    /** 追加一条记录并持久化。 */
    public void append(SavedPoint point) {
        List<SavedPoint> points = load();
        points.add(0, point);
        while (points.size() > MAX_POINTS) {
            points.remove(points.size() - 1);
        }
        persist(points);
    }

    /** 清空全部记录。 */
    public void clear() {
        preferences.edit().remove(KEY_POINTS).apply();
    }

    private void persist(List<SavedPoint> points) {
        JSONArray array = new JSONArray();
        for (SavedPoint point : points) {
            JSONObject item = new JSONObject();
            try {
                item.put("olat", point.originalLatitude);
                item.put("olon", point.originalLongitude);
                item.put("sys", point.sourceSystem);
                item.put("ilat", point.injectedLatitude);
                item.put("ilon", point.injectedLongitude);
                item.put("ts", point.savedAtMillis);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(KEY_POINTS, array.toString()).apply();
    }
}
