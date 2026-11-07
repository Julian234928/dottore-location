package com.example.mocktool;

import java.util.regex.Pattern;

/**
 * 经纬度文本解析与范围校验。
 *
 * 刻意不依赖任何 Android API，以便在 JVM 上直接跑单元测试。
 */
public final class CoordinateValidator {

    public static final double MIN_LATITUDE = -90.0;
    public static final double MAX_LATITUDE = 90.0;
    public static final double MIN_LONGITUDE = -180.0;
    public static final double MAX_LONGITUDE = 180.0;

    /** 只接受普通十进制与科学计数法，拒绝 Double.parseDouble 的宽松语法（如 "1d"、"0x1p3"）。 */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private CoordinateValidator() {
    }

    /** 校验结果。valid 为 false 时 error 非空。 */
    public static final class Result {
        public final boolean valid;
        public final double latitude;
        public final double longitude;
        public final String error;

        private Result(boolean valid, double latitude, double longitude, String error) {
            this.valid = valid;
            this.latitude = latitude;
            this.longitude = longitude;
            this.error = error;
        }

        public static Result ok(double latitude, double longitude) {
            return new Result(true, latitude, longitude, null);
        }

        public static Result fail(String error) {
            return new Result(false, Double.NaN, Double.NaN, error);
        }
    }

    /**
     * 解析并校验用户输入。
     *
     * @param latitudeText  纬度文本
     * @param longitudeText 经度文本
     * @return 校验结果，永不返回 null
     */
    public static Result parse(String latitudeText, String longitudeText) {
        Result lat = parseSingle(latitudeText, true);
        if (!lat.valid) {
            return lat;
        }
        Result lon = parseSingle(longitudeText, false);
        if (!lon.valid) {
            return lon;
        }
        return Result.ok(lat.latitude, lon.longitude);
    }

    private static Result parseSingle(String text, boolean isLatitude) {
        String label = isLatitude ? "纬度" : "经度";
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Result.fail(label + "不能为空");
        }
        if (!NUMBER.matcher(trimmed).matches()) {
            return Result.fail(label + "格式不正确，请输入十进制数字");
        }
        double value;
        try {
            value = Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Result.fail(label + "无法解析");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Result.fail(label + "不是有效数字");
        }
        double min = isLatitude ? MIN_LATITUDE : MIN_LONGITUDE;
        double max = isLatitude ? MAX_LATITUDE : MAX_LONGITUDE;
        if (value < min || value > max) {
            return Result.fail(label + "超出范围，应在 " + (int) min + " 到 " + (int) max + " 之间");
        }
        return Result.ok(isLatitude ? value : 0d, isLatitude ? 0d : value);
    }
}
