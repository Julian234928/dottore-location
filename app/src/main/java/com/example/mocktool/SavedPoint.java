package com.example.mocktool;

import java.util.Locale;

/**
 * 时间轴上的一条记录。
 *
 * 同时保留"用户输入的原始坐标"与"实际注入系统的 WGS84 坐标"，
 * 这样点击记录重新应用时直接使用已转换好的值，不会发生二次转换。
 */
public final class SavedPoint {

    /** 用户输入的纬度（原始，未转换）。 */
    public final double originalLatitude;
    /** 用户输入的经度（原始，未转换）。 */
    public final double originalLongitude;
    /** 用户输入所用的坐标系。 */
    public final int sourceSystem;
    /** 实际注入系统的纬度（WGS84）。 */
    public final double injectedLatitude;
    /** 实际注入系统的经度（WGS84）。 */
    public final double injectedLongitude;
    public final long savedAtMillis;

    public SavedPoint(double originalLatitude,
                      double originalLongitude,
                      int sourceSystem,
                      double injectedLatitude,
                      double injectedLongitude,
                      long savedAtMillis) {
        this.originalLatitude = originalLatitude;
        this.originalLongitude = originalLongitude;
        this.sourceSystem = sourceSystem;
        this.injectedLatitude = injectedLatitude;
        this.injectedLongitude = injectedLongitude;
        this.savedAtMillis = savedAtMillis;
    }

    /** 是否发生过坐标系转换。 */
    public boolean wasConverted() {
        return sourceSystem == CoordinateConverter.SYS_GCJ02;
    }

    public String originalText() {
        return String.format(Locale.US, "%.6f, %.6f", originalLatitude, originalLongitude);
    }

    public String injectedText() {
        return String.format(Locale.US, "%.6f, %.6f", injectedLatitude, injectedLongitude);
    }

    /** 列表主标题：原始坐标 + 坐标系。 */
    public String titleText() {
        return originalText() + "  (" + CoordinateConverter.systemLabel(sourceSystem) + ")";
    }

    /** 列表副标题：时间，必要时附带转换后的注入值。 */
    public String subtitleText() {
        String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date(savedAtMillis));
        if (!wasConverted()) {
            return time;
        }
        return time + "  → 注入 " + injectedText();
    }
}
