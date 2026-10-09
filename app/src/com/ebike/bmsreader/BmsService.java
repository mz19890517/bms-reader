package com.ebike.bmsreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * 常驻前台服务,负责"开机/被杀后自启 + 自动连上次的电池"。
 *
 * 三层自愈:
 *  1. START_STICKY —— 系统回收服务后自己拉起来;
 *  2. onTaskRemoved / onDestroy —— 用户划掉任务或服务被杀,立刻排一个 1.5s 闹钟重启;
 *  3. 15 分钟一次的兜底闹钟(见 AutoStartReceiver)—— 被 ROM 静默杀掉也能回来。
 * 另外看门狗每 5 秒检查一次,蓝牙断了就按退避间隔自动重连。
 */
public class BmsService extends Service {

    private static final String TAG = "BmsReader";
    private static final String CHANNEL_ID = "bms_conn";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_STOP = "com.ebike.bmsreader.STOP";
    public static final String ACTION_RETRY = "com.ebike.bmsreader.RETRY";

    /** 看门狗节奏与重连退避。 */
    private static final long TICK_MS = 5_000L;
    private static final long FIRST_BACKOFF_MS = 10_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long NOTIFY_THROTTLE_MS = 10_000L;

    /** 服务是否正在前台运行,给外部决定要不要更新通知。 */
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long backoffMs = FIRST_BACKOFF_MS;
    private long nextAttemptAt;
    private long lastNotifyAt;
    private boolean stopping;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "看门狗异常: " + t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        stopping = false;
        createChannel();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 3_000L);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        BmsHub hub = BmsHub.get(this);

        // 通知里点"断开":关掉自动连接,这样才不会被看门狗立刻连回来
        if (ACTION_STOP.equals(action)) {
            AutoLink.setEnabled(this, false);
            hub.client().disconnect();
            stopEverything();
            return START_NOT_STICKY;
        }

        // 无论如何先把前台通知顶上去,否则 Android 会判定"启动了却没通知"而报错
        startForegroundCompat(buildNotification(hub));

        if (!AutoLink.shouldRun(this) && !hub.client().isConnected()) {
            // 没开自动连接、也没连着,没必要常驻
            stopEverything();
            return START_NOT_STICKY;
        }

        AutoStartReceiver.scheduleKeepAlive(this);
        if (ACTION_RETRY.equals(action)) {
            tryReconnect(hub.client(), true);
        }
        // intent 为 null 是系统按 START_STICKY 重启的,这里什么都不用做,看门狗会接手
        return START_STICKY;
    }

    /** 看门狗:没连上就按退避重连,同时刷新通知。 */
    private void tick() {
        BmsHub hub = BmsHub.get(this);
        BmsClient client = hub.client();
        if (client.isConnected()) {
            backoffMs = FIRST_BACKOFF_MS;
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotifyAt > NOTIFY_THROTTLE_MS) {
                refreshNotification();
            }
            return;
        }
        if (!AutoLink.shouldRun(this)) {
            return;
        }
        if (!client.isBluetoothOn()) {
            // 等用户打开蓝牙,不要反复刷错误提示
            refreshNotification();
            return;
        }
        tryReconnect(client, false);
    }

    private void tryReconnect(BmsClient client, boolean force) {
        if (client.isConnected() || client.isUserDisconnect()) {
            return;
        }
        String mac = AutoLink.mac(this);
        if (mac.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now < nextAttemptAt) {
            return;
        }
        nextAttemptAt = now + backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        Log.i(TAG, "自动连接上次设备 " + AutoLink.describe(this));
        client.connect(mac);
    }

    private void stopEverything() {
        stopping = true;
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
            // ignore
        }
        AutoStartReceiver.cancelKeepAlive(this);
        stopSelf();
    }

    private void refreshNotification() {
        if (!running) {
            return;
        }
        lastNotifyAt = SystemClock.elapsedRealtime();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(this, BmsHub.get(this)));
            }
        } catch (Exception e) {
            Log.w(TAG, "刷新通知失败: " + e.getMessage());
        }
    }

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Android 14 对前台服务类型校验较严,退回普通类型,别把 App 搞崩
            Log.w(TAG, "startForeground(connectedDevice) 失败: " + e.getMessage());
            try {
                startForeground(NOTIF_ID, n);
            } catch (Exception ignored) {
                Log.w(TAG, "startForeground 也失败,放弃常驻");
            }
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 用户把 App 从最近任务划掉了:自动连接开着就把自己拉回来
        if (AutoLink.shouldRun(this)) {
            Log.i(TAG, "任务被划掉,安排重启");
            AutoStartReceiver.scheduleRestart(this, 1_500L);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        running = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (!stopping && AutoLink.shouldRun(this)) {
            // 被系统杀掉:排个闹钟,20 秒后自启并连上次的设备
            Log.i(TAG, "服务被销毁,安排自启");
            AutoStartReceiver.scheduleRestart(this, 20_000L);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "电池连接",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("保持电池蓝牙连接,被杀后自动重连");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(BmsHub hub) {
        return buildNotification(this, hub);
    }

    /** 通知内容:已连接显示实时数据,没连上显示"正在等哪台设备"。 */
    static Notification buildNotification(Context ctx, BmsHub hub) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "电池连接",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                channel.setDescription("保持电池蓝牙连接,被杀后自动重连");
                nm.createNotificationChannel(channel);
            }
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, 0, open, flags);
        Intent stop = new Intent(ctx, BmsService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(ctx, 1, stop, flags);
        Intent retry = new Intent(ctx, BmsService.class).setAction(ACTION_RETRY);
        PendingIntent retryPi = PendingIntent.getService(ctx, 2, retry, flags);
        Intent btOn = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        PendingIntent btPi = PendingIntent.getActivity(ctx, 3, btOn, flags);

        BmsClient client = hub.client();
        BmsProtocol.BaseInfo info = hub.latest();
        String title;
        String text;
        if (info != null && WidgetState.isFresh(ctx)) {
            title = "电量 " + info.soc + "%  " + WidgetState.state(ctx);
            text = String.format(Locale.US, "%.2fV  %.2fA", info.totalVoltage, info.current);
        } else if (client.isConnected()) {
            title = "已连接 " + client.getConnectedName();
            text = "正在读取数据…";
        } else if (!client.isBluetoothOn()) {
            title = "蓝牙未开启";
            text = AutoLink.hasDevice(ctx)
                    ? "打开蓝牙后自动连接 " + AutoLink.name(ctx) : "打开蓝牙后可连接电池";
        } else {
            title = "等待连接";
            text = AutoLink.hasDevice(ctx)
                    ? "自动连接 " + AutoLink.describe(ctx) : "打开 App 选择电池";
        }

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "断开", stopPi).build());
        if (client.isConnected()) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_popup_sync, "重连", retryPi).build());
        } else if (!client.isBluetoothOn()) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_lock_lock, "开蓝牙", btPi).build());
        } else {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_popup_sync, "连接", retryPi).build());
        }
        return b.build();
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 数据来一次就更新一下通知内容。 */
    static void notifyState(Context ctx) {
        if (ctx == null || !running) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(ctx, BmsHub.get(ctx)));
            }
        } catch (Exception e) {
            Log.w(TAG, "更新通知失败: " + e.getMessage());
        }
    }

    /** 拉起服务(自启的公共入口)。 */
    static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(ctx.getApplicationContext(), BmsService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getApplicationContext().startForegroundService(intent);
            } else {
                ctx.getApplicationContext().startService(intent);
            }
        } catch (Exception e) {
            // Android 12+ 后台启动前台服务会被拒,靠闹钟下轮再来
            Log.w(TAG, "启动前台服务失败: " + e.getMessage());
        }
    }

    static void stop(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getApplicationContext().stopService(new Intent(ctx.getApplicationContext(), BmsService.class));
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 连接状态变化时由 BmsHub 调用:该常驻就常驻,该停就停。 */
    static void sync(Context ctx, boolean connected) {
        if (ctx == null) {
            return;
        }
        if (AutoLink.shouldRun(ctx) || connected) {
            start(ctx);
        } else {
            stop(ctx);
        }
    }
}
