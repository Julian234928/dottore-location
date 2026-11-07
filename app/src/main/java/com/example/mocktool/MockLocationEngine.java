package com.example.mocktool;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 定位注入引擎。
 *
 * 原理：向系统注册与真实提供者同名的"测试提供者"（gps / network / fused），
 * 把设定坐标写入系统定位管线。此后系统内任何应用通过
 * LocationManager / FusedLocationProvider 取到的都是这个坐标。
 *
 * 前提：本应用必须被选为「开发者选项 → 选择模拟位置信息应用」，
 * 否则 addTestProvider 会抛 SecurityException。
 */
public final class MockLocationEngine {

    public static final String PROVIDER_GPS = LocationManager.GPS_PROVIDER;
    public static final String PROVIDER_NETWORK = LocationManager.NETWORK_PROVIDER;
    public static final String PROVIDER_FUSED = LocationManager.FUSED_PROVIDER;

    /*
     * 分源设置精度，让每个来源都符合自己的物理特性。
     *
     * 之前三个源统一报 5 米，这是个明显的破绽：
     * WiFi / 基站定位的真实精度是 20~100 米，物理上不可能做到 5 米。
     * 定位 SDK 做合理性校验时，很可能直接丢弃这种 "network fix"。
     */
    private static final float ACCURACY_GPS_METERS = 8.0f;      // 开阔地 GPS 典型值
    private static final float ACCURACY_NETWORK_METERS = 65.0f; // WiFi 定位典型值
    private static final float ACCURACY_FUSED_METERS = 12.0f;   // 融合定位略差于纯 GPS

    /*
     * 以下为模拟真实 GNSS 定位的固定字段。
     * 真实 GPS 定位一定带海拔、速度、方向、卫星数、信噪比，
     * 缺少这些字段的 Location 会被定位 SDK 判为合成数据。
     * 数值取城市开阔地带的典型值，全部固定，不做随机变化。
     */
    /*
     * 海拔固定为一个内陆城市的典型值。
     * 这是一个已知限制：若目标地点是沿海或高原，海拔会与地形不符，
     * 对海拔敏感的校验可能察觉。当前版本不做动态推算，保持固定值。
     */
    private static final double ALTITUDE_METERS = 780.0;
    private static final float VERTICAL_ACCURACY_METERS = 8.0f;
    private static final float VERTICAL_ACCURACY_FUSED_METERS = 15.0f;
    private static final float SPEED_ACCURACY_MPS = 0.5f;
    private static final float BEARING_ACCURACY_DEGREES = 10.0f;
    private static final int SATELLITE_COUNT = 12;
    private static final float MEAN_CN0 = 32.0f;
    private static final float MAX_CN0 = 41.0f;

    private final LocationManager locationManager;
    private final List<String> activeProviders = new ArrayList<>();

    public MockLocationEngine(Context context) {
        this.locationManager =
                (LocationManager) context.getApplicationContext()
                        .getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * 检查本应用是否已被授予模拟位置权限。
     * 对应「开发者选项 → 选择模拟位置信息应用」的设置项。
     */
    public static boolean isMockLocationAllowed(Context context) {
        AppOpsManager ops =
                (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) {
            return false;
        }
        int mode;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(), context.getPackageName());
            }
        } catch (Exception e) {
            return false;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 注册测试提供者并注入坐标。
     *
     * @throws SecurityException 未获得模拟位置权限时抛出
     */
    public void start(double latitude, double longitude) {
        stop();
        register(PROVIDER_GPS, true);
        register(PROVIDER_NETWORK, false);
        // fused 是系统"融合定位"源，负责把 gps / network 合成一个最可信的坐标。
        // 不接管它的话，走 FusedLocationProvider 的应用拿到的永远是 null，
        // 只能退回自己缓存的位置。
        register(PROVIDER_FUSED, true);
        push(latitude, longitude);
    }

    /** 每个来源报不同的精度，避免"network 也有 5 米精度"这种不合物理规律的数据。 */
    private static float accuracyFor(String provider) {
        if (PROVIDER_GPS.equals(provider)) {
            return ACCURACY_GPS_METERS;
        }
        if (PROVIDER_FUSED.equals(provider)) {
            return ACCURACY_FUSED_METERS;
        }
        return ACCURACY_NETWORK_METERS;
    }

    private void register(String provider, boolean needsSatellite) {
        // 先清掉可能残留的同名测试提供者，避免 IllegalArgumentException
        try {
            locationManager.removeTestProvider(provider);
        } catch (Exception ignored) {
            // 不存在时忽略
        }

        // gps 提供者声明支持海拔/速度/方向，与真实 GNSS 一致；
        // network 提供者不支持这些字段，保持 false。
        boolean supportsExtra = needsSatellite;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProviderProperties properties = new ProviderProperties.Builder()
                        .setHasNetworkRequirement(false)
                        .setHasSatelliteRequirement(needsSatellite)
                        .setHasCellRequirement(false)
                        .setHasMonetaryCost(false)
                        .setHasAltitudeSupport(supportsExtra)
                        .setHasSpeedSupport(supportsExtra)
                        .setHasBearingSupport(supportsExtra)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build();
                locationManager.addTestProvider(provider, properties);
            } else {
                locationManager.addTestProvider(
                        provider,
                        false,              // requiresNetwork
                        needsSatellite,     // requiresSatellite
                        false,              // requiresCell
                        false,              // hasMonetaryCost
                        supportsExtra,      // supportsAltitude
                        supportsExtra,      // supportsSpeed
                        supportsExtra,      // supportsBearing
                        Criteria.POWER_LOW,
                        Criteria.ACCURACY_FINE);
            }
            locationManager.setTestProviderEnabled(provider, true);
            activeProviders.add(provider);
        } catch (Exception e) {
            // 个别 ROM 会保护 fused 等系统级提供者，接管失败不影响其余来源
        }
    }

    /** 向已注册的提供者写入当前坐标。服务每秒调用一次，防止系统回退到真实定位。 */
    public void push(double latitude, double longitude) {
        if (activeProviders.isEmpty()) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        for (String provider : activeProviders) {
            Location location = new Location(provider);
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setAccuracy(accuracyFor(provider));
            location.setTime(nowMillis);
            location.setElapsedRealtimeNanos(nowNanos);
            location.setMock(true);

            if (PROVIDER_GPS.equals(provider)) {
                // 补齐真实 GPS 定位一定携带的字段。
                // 只给经纬度的 Location 一眼就是合成的，容易被定位 SDK 判为无效。
                location.setAltitude(ALTITUDE_METERS);
                location.setVerticalAccuracyMeters(VERTICAL_ACCURACY_METERS);
                location.setSpeed(0.0f);
                location.setSpeedAccuracyMetersPerSecond(SPEED_ACCURACY_MPS);
                location.setBearing(0.0f);
                location.setBearingAccuracyDegrees(BEARING_ACCURACY_DEGREES);

                Bundle extras = new Bundle();
                extras.putInt("satellites", SATELLITE_COUNT);
                extras.putFloat("meanCn0", MEAN_CN0);
                extras.putFloat("maxCn0", MAX_CN0);
                extras.putBoolean("hasAlmanac", true);
                extras.putBoolean("hasEphemeris", true);
                extras.putBoolean("usedInFix", true);
                location.setExtras(extras);
            } else if (PROVIDER_FUSED.equals(provider)) {
                // 融合定位通常带海拔和方向（来自 GPS 分量），但精度略差
                location.setAltitude(ALTITUDE_METERS);
                location.setVerticalAccuracyMeters(VERTICAL_ACCURACY_FUSED_METERS);
                location.setSpeed(0.0f);
                location.setSpeedAccuracyMetersPerSecond(SPEED_ACCURACY_MPS);
                location.setBearing(0.0f);
                location.setBearingAccuracyDegrees(BEARING_ACCURACY_DEGREES);
            }
            // network 不补海拔/速度/方向：真实的 WiFi 定位本来就没有这些字段，
            // 硬塞反而更可疑。

            try {
                locationManager.setTestProviderLocation(provider, location);
            } catch (Exception ignored) {
                // 单个提供者失败不影响另一个
            }
        }
    }

    /** 注销测试提供者，系统随即回落到真实定位。 */
    public void stop() {
        for (String provider : new ArrayList<>(activeProviders)) {
            try {
                locationManager.setTestProviderEnabled(provider, false);
            } catch (Exception ignored) {
            }
            try {
                locationManager.removeTestProvider(provider);
            } catch (Exception ignored) {
            }
        }
        activeProviders.clear();
    }

    public boolean isActive() {
        return !activeProviders.isEmpty();
    }

    /**
     * 读回系统当前实际返回的位置，用于自证注入是否生效。
     * 需要 ACCESS_FINE_LOCATION 权限，未授权时返回 null。
     */
    public Location readBackFromSystem() {
        Location gps = safeLastKnown(PROVIDER_GPS);
        Location network = safeLastKnown(PROVIDER_NETWORK);
        Location fused = safeLastKnown(PROVIDER_FUSED);
        Location best = null;
        for (Location candidate : new Location[]{gps, fused, network}) {
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.getTime() >= best.getTime()) {
                best = candidate;
            }
        }
        return best;
    }

    private Location safeLastKnown(String provider) {
        try {
            return locationManager.getLastKnownLocation(provider);
        } catch (SecurityException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
