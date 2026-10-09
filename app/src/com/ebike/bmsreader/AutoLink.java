package com.ebike.bmsreader;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * "上次连接成功"的设备 + 自动连接开关。
 * 落 SharedPreferences,进程被杀、重启手机后依然在,
 * 供 BmsService 自启 / AutoStartReceiver 开机拉起时直接用。
 */
public final class AutoLink {

    private static final String SP = "auto_link";
    private static final String K_MAC = "mac";
    private static final String K_NAME = "name";
    private static final String K_TIME = "time";
    private static final String K_ENABLED = "enabled";

    private AutoLink() {
    }

    /** 连接成功(服务发现完成)时调用,记下这台设备。 */
    public static void saveDevice(Context ctx, String mac, String name) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        SharedPreferences sp = sp(ctx);
        String oldName = sp.getString(K_NAME, "");
        // 扫描没拿到名字时会退化成 MAC,这种情况别把原来的名字冲掉
        String label = (name == null || name.isEmpty() || name.equals(mac)) ? oldName : name;
        sp.edit()
                .putString(K_MAC, mac)
                .putString(K_NAME, label == null ? "" : label)
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    public static String mac(Context ctx) {
        return sp(ctx).getString(K_MAC, "");
    }

    public static String name(Context ctx) {
        return sp(ctx).getString(K_NAME, "");
    }

    public static long savedAt(Context ctx) {
        return sp(ctx).getLong(K_TIME, 0L);
    }

    public static boolean hasDevice(Context ctx) {
        return !mac(ctx).isEmpty();
    }

    public static void clearDevice(Context ctx) {
        sp(ctx).edit().remove(K_MAC).remove(K_NAME).remove(K_TIME).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return sp(ctx).getBoolean(K_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(K_ENABLED, on).apply();
    }

    /** 该不该常驻:总开关打开,并且确实记住了一台设备。 */
    public static boolean shouldRun(Context ctx) {
        return isEnabled(ctx) && hasDevice(ctx);
    }

    /** 给界面显示用:"名字 (MAC)"。 */
    public static String describe(Context ctx) {
        String mac = mac(ctx);
        if (mac.isEmpty()) {
            return "无";
        }
        String name = name(ctx);
        return name.isEmpty() || name.equals(mac) ? mac : name + " (" + mac + ")";
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
