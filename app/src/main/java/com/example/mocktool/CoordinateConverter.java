package com.example.mocktool;

/**
 * WGS84 与 GCJ-02 互转。
 *
 * 背景：Android 系统定位（LocationManager / GPS）返回的是 WGS84 原始坐标，
 * 而国内地图（高德、腾讯、百度等）底图使用 GCJ-02（俗称"火星坐标"）。
 * 两者在国内偏差可达 300~800 米。
 *
 * 本工具的策略：
 *  - 系统注入**一律使用 WGS84**，因为那是 Android 定位管线的原生基准；
 *  - 用户若按 GCJ-02 输入（从国内地图上抄下来的坐标），
 *    在注入前做一次 GCJ-02 → WGS84 的转换；
 *  - 转换只在"应用位置"时发生一次，转换结果被保存下来复用，
 *    读回、时间轴、界面显示都不再二次转换。
 *
 * 纯 Java 实现，不依赖 Android，可直接单元测试。
 */
public final class CoordinateConverter {

    /** 输入坐标系：WGS84（GPS 原始）。 */
    public static final int SYS_WGS84 = 0;
    /** 输入坐标系：GCJ-02（国内地图）。 */
    public static final int SYS_GCJ02 = 1;

    /** 克拉索夫斯基椭球长半轴。 */
    private static final double A = 6378245.0;
    /** 椭球偏心率平方。 */
    private static final double EE = 0.00669342162296594323;

    private CoordinateConverter() {
    }

    public static String systemLabel(int system) {
        return system == SYS_GCJ02 ? "GCJ-02" : "WGS84";
    }

    /** 粗略判断是否在中国境外。境外不做偏移。 */
    public static boolean outOfChina(double latitude, double longitude) {
        return longitude < 72.004 || longitude > 137.8347
                || latitude < 0.8293 || latitude > 55.8271;
    }

    /**
     * 把用户输入的坐标统一换算成用于注入的 WGS84。
     *
     * @param latitude  用户输入的纬度
     * @param longitude 用户输入的经度
     * @param system    用户输入所用的坐标系
     * @return 长度为 2 的数组 {纬度, 经度}，已是 WGS84
     */
    public static double[] toWgs84(double latitude, double longitude, int system) {
        if (system != SYS_GCJ02) {
            // 本来就是 WGS84，直接返回，不做任何转换（避免重复转换）
            return new double[]{latitude, longitude};
        }
        return gcj02ToWgs84(latitude, longitude);
    }

    /**
     * WGS84 → GCJ-02。
     *
     * @return {纬度, 经度}
     */
    public static double[] wgs84ToGcj02(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) {
            return new double[]{latitude, longitude};
        }
        double deltaLat = transformLatitude(longitude - 105.0, latitude - 35.0);
        double deltaLon = transformLongitude(longitude - 105.0, latitude - 35.0);

        double radLat = latitude / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);

        deltaLat = (deltaLat * 180.0)
                / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        deltaLon = (deltaLon * 180.0)
                / (A / sqrtMagic * Math.cos(radLat) * Math.PI);

        return new double[]{latitude + deltaLat, longitude + deltaLon};
    }

    /**
     * GCJ-02 → WGS84。
     *
     * 该变换没有解析反函数，这里用迭代逼近：
     * 反复用正向变换校正，直到误差小于 1e-9 度（约 0.1 毫米）。
     *
     * @return {纬度, 经度}
     */
    public static double[] gcj02ToWgs84(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) {
            return new double[]{latitude, longitude};
        }
        double wgsLat = latitude;
        double wgsLon = longitude;
        for (int i = 0; i < 12; i++) {
            double[] guess = wgs84ToGcj02(wgsLat, wgsLon);
            double errorLat = guess[0] - latitude;
            double errorLon = guess[1] - longitude;
            if (Math.abs(errorLat) < 1e-9 && Math.abs(errorLon) < 1e-9) {
                break;
            }
            wgsLat -= errorLat;
            wgsLon -= errorLon;
        }
        return new double[]{wgsLat, wgsLon};
    }

    /** 两点间近似距离（米），用于界面提示偏移量。 */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000.0;
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double deltaLat = radLat2 - radLat1;
        double deltaLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * earthRadius * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static double transformLatitude(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLongitude(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
