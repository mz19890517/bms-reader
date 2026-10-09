package com.ebike.bmsreader;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/**
 * 小部件用的最近一次数据快照。
 * 进程被杀后桌面还能显示,所以落 SharedPreferences。
 */
public final class WidgetState {

    private static final String SP = "widget_state";
    private static final String K_SOC = "soc";
    private static final String K_VOLT = "volt";
    private static final String K_CUR = "cur";
    private static final String K_STATE = "state";
    private static final String K_CELLS = "cells";
    private static final String K_SPREAD = "spread";
    private static final String K_TEMP = "temp";
    private static final String K_PROT = "prot";
    private static final String K_TIME = "time";
    private static final String K_NAME = "name";

    /** 超过这个时间就认为数据过期,小部件显示"等待连接"。 */
    public static final long STALE_MS = 90_000L;

    private WidgetState() {
    }

    public static void save(Context ctx, BmsProtocol.BaseInfo info, String deviceName) {
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        float max = 0f, min = Float.MAX_VALUE;
        for (float v : info.cellVoltages) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        float spread = info.cellVoltages.isEmpty() ? 0f : max - min;
        StringBuilder temps = new StringBuilder();
        for (int i = 0; i < info.temperatures.size() && i < 3; i++) {
            if (i > 0) {
                temps.append(' ');
            }
            temps.append(String.format(Locale.US, "%.0f℃", info.temperatures.get(i)));
        }
        String state = Math.abs(info.current) <= 0.05f
                ? "静置" : (info.isFlowingOut() ? "放电中" : "充电中");
        List<String> prot = BmsProtocol.parseProtection(info.protectionBits);
        String protText = prot.isEmpty() ? "正常" : prot.get(0);

        sp.edit()
                .putInt(K_SOC, info.soc)
                .putFloat(K_VOLT, info.totalVoltage)
                .putFloat(K_CUR, info.current)
                .putString(K_STATE, state)
                .putInt(K_CELLS, info.cellCount)
                .putFloat(K_SPREAD, spread)
                .putString(K_TEMP, temps.toString())
                .putString(K_PROT, protText)
                .putLong(K_TIME, System.currentTimeMillis())
                .putString(K_NAME, deviceName == null ? "" : deviceName)
                .apply();
    }

    public static void clear(Context ctx) {
        ctx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    public static boolean isFresh(Context ctx) {
        long t = ctx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE)
                .getLong(K_TIME, 0L);
        return t > 0 && System.currentTimeMillis() - t < STALE_MS;
    }

    public static int soc(Context ctx) {
        return sp(ctx).getInt(K_SOC, -1);
    }

    public static String summary(Context ctx) {
        SharedPreferences p = sp(ctx);
        if (p.getLong(K_TIME, 0L) == 0L) {
            return "尚未读取";
        }
        if (!isFresh(ctx)) {
            return "数据已过期";
        }
        return String.format(Locale.US, "%.1fV  %+.1fA", p.getFloat(K_VOLT, 0f), p.getFloat(K_CUR, 0f));
    }

    /** 保护状态异常时才提示,正常时返回空串。 */
    public static String alarm(Context ctx) {
        SharedPreferences p = sp(ctx);
        if (!isFresh(ctx)) {
            return "";
        }
        String prot = p.getString(K_PROT, "正常");
        return "正常".equals(prot) ? "" : "⚠ " + prot;
    }

    /** 小部件扩展行:串数 / 温度 / 压差 / 保护状态。 */
    public static String extra(Context ctx) {
        SharedPreferences p = sp(ctx);
        if (!isFresh(ctx)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.getInt(K_CELLS, 0)).append("串");
        String t = p.getString(K_TEMP, "");
        if (!t.isEmpty()) {
            sb.append("  ").append(t);
        }
        float spread = p.getFloat(K_SPREAD, 0f);
        if (p.getInt(K_CELLS, 0) > 0) {
            sb.append(String.format(Locale.US, "  压差%.0fmV", spread * 1000f));
        }
        String prot = p.getString(K_PROT, "正常");
        if (!"正常".equals(prot)) {
            sb.append("  ⚠").append(prot);
        }
        return sb.toString();
    }

    public static String state(Context ctx) {
        return sp(ctx).getString(K_STATE, "--");
    }

    public static String deviceName(Context ctx) {
        return sp(ctx).getString(K_NAME, "");
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
