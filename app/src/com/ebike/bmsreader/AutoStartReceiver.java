package com.ebike.bmsreader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 自启入口:手机开机、App 被系统杀掉、服务被划掉之后,
 * 由系统广播/闹钟把 BmsService 拉起来;服务起来后会自动连上次的设备。
 */
public class AutoStartReceiver extends BroadcastReceiver {

    private static final String TAG = "BmsReader";

    /** 服务自己排的"马上重启"(onTaskRemoved / onDestroy)。 */
    public static final String ACTION_RESTART = "com.ebike.bmsreader.AUTO_RESTART";
    /** 15 分钟一次的兜底巡检,防止服务被 ROM 静默杀掉后没人拉它。 */
    public static final String ACTION_KEEPALIVE = "com.ebike.bmsreader.AUTO_KEEPALIVE";

    private static final long KEEPALIVE_MS = 15 * 60 * 1000L;
    private static final int REQ_KEEPALIVE = 8801;
    private static final int REQ_RESTART = 8802;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        try {
            if (!AutoLink.shouldRun(app)) {
                // 开关关掉或还没记住设备:不留闹钟,只把小部件刷成待连接
                cancelKeepAlive(app);
                BmsWidgetProvider.refreshAll(app);
                return;
            }
            BmsService.start(app);
            scheduleKeepAlive(app);
            BmsWidgetProvider.refreshAll(app);
            String action = intent == null ? null : intent.getAction();
            Log.i(TAG, "自启入口 " + (action == null ? "null" : action) + " -> 服务已拉起");
        } catch (Throwable t) {
            Log.w(TAG, "自启失败: " + t);
        }
    }

    /** 服务被划掉/销毁后马上重启。 */
    public static void scheduleRestart(Context ctx, long delayMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            PendingIntent pi = pending(ctx, ACTION_RESTART, REQ_RESTART);
            long at = SystemClock.elapsedRealtime() + Math.max(delayMs, 1_000L);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM 权限,doze 里也能被叫醒
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "安排重启失败: " + e.getMessage());
        }
    }

    public static void scheduleKeepAlive(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            PendingIntent pi = pending(ctx, ACTION_KEEPALIVE, REQ_KEEPALIVE);
            am.setInexactRepeating(AlarmManager.RTC,
                    System.currentTimeMillis() + KEEPALIVE_MS, KEEPALIVE_MS, pi);
        } catch (Exception e) {
            Log.w(TAG, "安排保活闹钟失败: " + e.getMessage());
        }
    }

    public static void cancelKeepAlive(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.cancel(pending(ctx, ACTION_KEEPALIVE, REQ_KEEPALIVE));
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static PendingIntent pending(Context ctx, String action, int request) {
        Intent i = new Intent(ctx.getApplicationContext(), AutoStartReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, request, i, flags);
    }
}
