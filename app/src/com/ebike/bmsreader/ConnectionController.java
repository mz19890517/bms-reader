package com.ebike.bmsreader;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import Toast;

/**
 * 连接编排:扫描流程(10 秒停扫 / 6 秒自动连首台)、断开流程、自动连开关、蓝牙检查。
 * 定时器挂在自身(非 Activity 视图)上,Activity 销毁前后必须调用 cancel() 让其失效。
 */
final class ConnectionController {

    interface Callbacks {
        /** 扫描结束但没扫到设备之类的状态提示,交还给界面显示。 */
        void onScanStatus(String s);

        /** 自动连开关/上次设备文案变化,交还给界面显示。 */
        void onAutoState(String autoText, String lastDeviceText);
    }

    private final Context ctx;
    private final BmsHub hub;
    private final Callbacks cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable scanStopTask = new Runnable() {
        @Override
        public void run() {
            hub.client().stopScan();
            if (hub.client().getFoundDevices().isEmpty()) {
                cb.onScanStatus("未扫描到电池,请靠近电池后重试");
            }
        }
    };
    private final Runnable autoConnectTask = new Runnable() {
        @Override
        public void run() {
            if (!hub.client().isConnected() && !hub.client().getFoundDevices().isEmpty()) {
                connect(hub.client().getFoundDevices().get(0).mac);
            }
        }
    };

    ConnectionController(Activity ctx, BmsHub hub, Callbacks cb) {
        this.ctx = ctx;
        this.hub = hub;
        this.cb = cb;
    }

    boolean ensureBluetooth() {
        if (!hub.client().isBluetoothOn()) {
            try {
                ctx.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
                // 没有系统设置页时忽略
            }
            return false;
        }
        return true;
    }

    /** 开始扫描:10 秒后自动停;期间若没手动选择,6 秒时自动连扫到的第一台。 */
    void startScan() {
        hub.client().startScan();
        main.removeCallbacks(scanStopTask);
        main.removeCallbacks(autoConnectTask);
        main.postDelayed(scanStopTask, 10000L);
        main.postDelayed(autoConnectTask, 6000L);
    }

    void connect(String mac) {
        hub.client().connect(mac);
    }

    /** 用户点的"断开":关自动连、断 GATT、停服务、清小部件快照。 */
    void disconnect() {
        AutoLink.setEnabled(ctx, false);
        hub.client().disconnect();
        BmsService.stop(ctx);
        WidgetState.clear(ctx);
        BmsWidgetProvider.refreshAll(ctx);
        refreshAutoUi();
    }

    /** 自动连开关切换。 */
    void toggleAuto() {
        boolean on = !AutoLink.isEnabled(ctx);
        AutoLink.setEnabled(ctx, on);
        if (on) {
            hub.client().clearUserDisconnect();
            if (AutoLink.hasDevice(ctx)) {
                BmsService.start(ctx);
                Toast.makeText(ctx,
                        "会自动连接 " + AutoLink.describe(ctx), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx,
                        "还没有连过设备,先扫描连接一次吧", Toast.LENGTH_LONG).show();
            }
        } else {
            BmsService.stop(ctx);
            Toast.makeText(ctx,
                    "自动连接已关闭", Toast.LENGTH_SHORT).show();
        }
        refreshAutoUi();
    }

    /** 刷新"自动连接/上次设备"两行文案。 */
    void refreshAutoUi() {
        String when = AutoLink.savedAt(ctx) > 0
                ? "  ·  " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(AutoLink.savedAt(ctx)))
                : "";
        cb.onAutoState(
                "自动连接: " + (AutoLink.isEnabled(ctx) ? "开" : "关"),
                "上次连接: " + AutoLink.describe(ctx) + when);
    }

    /** 国产 ROM 常把后台杀掉,让用户给本 App 关闭电池优化,自启才靠得住。 */
    void openKeepAliveSettings() {
        try {
            ctx.startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(ctx,
                        "本机没有电池优化设置页", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Activity 销毁/离开时调用:定时器失效,并兜底停掉 BLE 扫描,
     * 避免低功耗扫描没有任何东西去停,一直挂到进程被杀。 */
    void cancel() {
        hub.client().stopScan();
        main.removeCallbacks(scanStopTask);
        main.removeCallbacks(autoConnectTask);
    }
}
