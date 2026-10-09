package com.ebike.bmsreader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Locale;

/** 桌面小部件:电量 / 充放电状态 / 电压电流。 */
public class BmsWidgetProvider extends AppWidgetProvider {

    /** 系统对 updatePeriodMillis 的下限是 30 分钟,这里靠自定闹钟保活得更勤。 */
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    private static final int REQ = 7717;

    @Override
    public void onEnabled(Context ctx) {
        super.onEnabled(ctx);
        selfCheck(ctx);
    }

    /**
     * 本地膨胀一次小部件布局:能在系统真正渲染之前就发现布局写错,
     * 不然只能等用户把小组件拖到桌面后才知道是空的。
     */
    static void selfCheck(Context ctx) {
        try {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_bms);
            v.setTextViewText(R.id.w_soc, "100%");
            v.setTextViewText(R.id.w_state, "自检");
            v.setTextViewText(R.id.w_detail, "0.0V");
            v.setTextViewText(R.id.w_name, "x");
            v.setTextColor(R.id.w_soc, 0xFFFFFFFF);
            v.apply(ctx, (android.view.ViewGroup) null);
            android.util.Log.i("BmsReader", "widget self-check OK");
        } catch (Throwable t) {
            android.util.Log.e("BmsReader", "widget self-check FAILED", t);
        }
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            render(ctx, mgr, id);
        }
        scheduleRefresh(ctx);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle opts) {
        render(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String action = intent.getAction();
        if (BmsWidget.ACTION_REFRESH.equals(action)) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BmsWidgetProvider.class));
            for (int id : ids) {
                render(ctx, mgr, id);
            }
            scheduleRefresh(ctx);
        }
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        super.onDeleted(ctx, ids);
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr.getAppWidgetIds(new ComponentName(ctx, BmsWidgetProvider.class)).length == 0) {
            cancelRefresh(ctx);
        }
    }

    /** 把快照刷到所有小部件上。数据来一次就调一次。 */
    public static void refreshAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BmsWidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        for (int id : ids) {
            render(ctx, mgr, id);
        }
    }

    static void render(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_bms);
        boolean fresh = WidgetState.isFresh(ctx);
        int soc = WidgetState.soc(ctx);

        if (fresh && soc >= 0) {
            v.setTextViewText(R.id.w_soc, soc + "%");
            v.setTextColor(R.id.w_soc, socColor(soc));
            // 有保护时用状态行提示,比挤在电压电流后面更容易看见
            String alarm = WidgetState.alarm(ctx);
            if (alarm.isEmpty()) {
                v.setTextViewText(R.id.w_state, WidgetState.state(ctx));
            } else {
                v.setTextViewText(R.id.w_state, alarm);
                v.setTextColor(R.id.w_state, 0xFFFF8A65);
            }
            v.setTextViewText(R.id.w_detail, WidgetState.summary(ctx));

            String name = WidgetState.deviceName(ctx);
            v.setTextViewText(R.id.w_name, name.isEmpty() ? "电池" : name);
            v.setViewVisibility(R.id.w_name, View.VISIBLE);
        } else {
            boolean auto = AutoLink.shouldRun(ctx);
            String lastName = AutoLink.name(ctx);
            v.setTextViewText(R.id.w_soc, "--");
            v.setTextColor(R.id.w_soc, 0xFF78909C);
            if (auto) {
                // 服务被杀/蓝牙断开的这段时间,告诉用户它在等着连回来
                v.setTextViewText(R.id.w_state, "自动连接中…");
                v.setTextColor(R.id.w_state, 0xFFFFB74D);
                v.setTextViewText(R.id.w_detail, AutoLink.describe(ctx));
                v.setTextViewText(R.id.w_name, lastName.isEmpty() ? "电池" : lastName);
                v.setViewVisibility(R.id.w_name, View.VISIBLE);
            } else {
                v.setTextViewText(R.id.w_state, soc < 0 ? "打开 App 连接电池" : "等待连接");
                v.setTextColor(R.id.w_state, 0xFFECEFF1);
                v.setTextViewText(R.id.w_detail, "");
                v.setViewVisibility(R.id.w_name, View.GONE);
            }
        }

        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx));
        mgr.updateAppWidget(id, v);
    }

    private static int socColor(int soc) {
        if (soc >= 50) {
            return 0xFF66BB6A;
        }
        if (soc >= 20) {
            return 0xFFFFB74D;
        }
        return 0xFFEF5350;
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, 0, i, flags);
    }

    private static void scheduleRefresh(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(ctx, BmsWidgetProvider.class).setAction(BmsWidget.ACTION_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, i, flags);
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + REFRESH_MS, REFRESH_MS, pi);
    }

    private static void cancelRefresh(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(ctx, BmsWidgetProvider.class).setAction(BmsWidget.ACTION_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        am.cancel(PendingIntent.getBroadcast(ctx, REQ, i, flags));
    }
}
