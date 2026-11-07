package com.example.mocktool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：持续向系统推送设定坐标。
 *
 * 为什么需要服务：测试提供者注册后，若长时间没有新位置写入，
 * 系统可能回退到真实定位。每秒重推一次可保持生效，同时避免
 * 界面切到后台后进程被回收。
 */
public class MockLocationService extends Service {

    public static final String ACTION_START = "com.example.mocktool.action.START";
    public static final String ACTION_STOP = "com.example.mocktool.action.STOP";
    public static final String EXTRA_LATITUDE = "extra_latitude";
    public static final String EXTRA_LONGITUDE = "extra_longitude";

    private static final long PUSH_INTERVAL_MS = 1000L;
    private static final String CHANNEL_ID = "mock_location_channel";
    private static final int NOTIFICATION_ID = 1001;

    /** 供界面读取的运行态（同进程内共享）。 */
    private static volatile boolean sRunning = false;
    private static volatile double sLatitude = Double.NaN;
    private static volatile double sLongitude = Double.NaN;

    private MockLocationEngine engine;
    private volatile double latitude;
    private volatile double longitude;
    private volatile boolean ticking;
    private Thread pushThread;

    public static boolean isRunning() {
        return sRunning;
    }

    public static double currentLatitude() {
        return sLatitude;
    }

    public static double currentLongitude() {
        return sLongitude;
    }

    /**
     * 用独立线程推送，而不是 Handler.postDelayed。
     *
     * 原因：实测在 ColorOS 16 上，应用退到后台后主线程的延时消息会被挂起，
     * 定时器在切走约 5 秒后就彻底停摆（进程 isFrozen=false、仍是前台服务，
     * 但不再有任何 setTestProviderLocation 调用）。改用独立线程 + sleep 循环，
     * 不受消息队列调度策略影响；并且循环体吞掉所有 Throwable，
     * 单次推送异常不会让整个推送停掉。
     */
    private void startPushThread() {
        if (ticking) {
            return;
        }
        ticking = true;
        pushThread = new Thread(() -> {
            while (ticking) {
                try {
                    engine.push(latitude, longitude);
                } catch (Throwable ignored) {
                    // 单次推送失败不能让循环退出
                }
                try {
                    Thread.sleep(PUSH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "mock-push");
        pushThread.setDaemon(true);
        pushThread.start();
    }

    private void stopPushThread() {
        ticking = false;
        Thread t = pushThread;
        pushThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new MockLocationEngine(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN);
            longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN);
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                shutdown();
                return START_NOT_STICKY;
            }

            startForeground(NOTIFICATION_ID, buildNotification(latitude, longitude));
            try {
                engine.start(latitude, longitude);
            } catch (SecurityException e) {
                // 未获得模拟位置权限：停止服务并告知界面
                sRunning = false;
                sLatitude = Double.NaN;
                sLongitude = Double.NaN;
                stopSelf();
                return START_NOT_STICKY;
            }

            sRunning = true;
            sLatitude = latitude;
            sLongitude = longitude;

            startPushThread();
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void shutdown() {
        stopPushThread();
        engine.stop();
        sRunning = false;
        sLatitude = Double.NaN;
        sLongitude = Double.NaN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopPushThread();
        if (engine != null) {
            engine.stop();
        }
        sRunning = false;
        sLatitude = Double.NaN;
        sLongitude = Double.NaN;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(double lat, double lon) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }
}
