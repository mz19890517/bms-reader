package com.ebike.bmsreader;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 电动车电池蓝牙信息查看器。 */
public class MainActivity extends android.app.Activity implements BmsClient.Listener {

    private static final int REQ_PERMS = 1001;

    private BmsHub hub;
    private EcoTracker ecoTracker;
    private LinearLayout deviceBox;
    private LinearLayout dataBox;
    private TextView statusView;
    private TextView logView;
    private TextView diagView;
    private Button scanBtn;
    private Button autoBtn;
    private TextView lastDeviceView;
    private final StringBuilder logLines = new StringBuilder();
    private LinearLayout ecoBox;
    private TextView ecoStateView;

    private final List<BmsClient.Found> devices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        BmsWidgetProvider.selfCheck(this);
        hub = BmsHub.get(this);
        hub.addListener(this);
        // 省电分析器:常驻挂在 Hub 监听链上,不随界面销毁,后台也继续统计
        EcoTracker.get(this)
                .ensureListening(hub);
        ecoTracker = EcoTracker.get(this);
        ecoTracker.setUiListener(new EcoTracker.UiListener() {
            @Override
            public void onEcoUpdated(EcoTracker.Stats stats) {
                updateEcoUi(stats);
            }
        });
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAutoUi();
        if (AutoLink.shouldRun(this)) {
            // 服务可能已经被系统杀掉了,这里再拉一次,连上次成功的设备
            BmsService.start(this);
        }
        if (!ensureBluetooth()) {
            statusView.setText("蓝牙未开启,请打开后重试");
        }
        updateEcoUi(EcoTracker.get(this).snapshot());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ecoTracker != null) {
            ecoTracker.setUiListener(null);
            ecoTracker.save();
        }
        if (hub != null) {
            hub.removeListener(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台时不要断开,小部件还要靠这条连接刷新
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // 省电分析需要 GPS 速度,任何版本都要精确位置权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
        if (permGranted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            EcoTracker.get(this).ensureGps();
        }
    }

    private static boolean permGranted(Context c, String p) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // 老版本安装时静态授权
            return true;
        }
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureBluetooth() {
        if (!hub.client().isBluetoothOn()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
                // 没有系统设置页时忽略
            }
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                // 位置权限到手:立刻启动 GPS,开始在骑行中采样
                EcoTracker.get(this).ensureGps();
            }
        }
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTextColor(color);
        return tv;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = text("电池蓝牙信息查看器", 20, 0xFF4FC3F7);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = text("正在初始化…", 14, 0xFFB0BEC5);
        statusView.setPadding(0, dp(6), 0, dp(8));
        root.addView(statusView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = new Button(this);
        scanBtn.setText("扫描电池");
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onScanClicked();
            }
        });
        btnRow.addView(scanBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button disconnectBtn = new Button(this);
        disconnectBtn.setText("断开");
        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoLink.setEnabled(MainActivity.this, false);
                hub.client().disconnect();
                BmsService.stop(MainActivity.this);
                WidgetState.clear(MainActivity.this);
                BmsWidgetProvider.refreshAll(MainActivity.this);
                updateAutoUi();
                Toast.makeText(MainActivity.this, "已断开,自动连接已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(disconnectBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btnRow);

        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoBtn = new Button(this);
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAuto();
            }
        });
        autoRow.addView(autoBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button keepAliveBtn = new Button(this);
        keepAliveBtn.setText("后台保活");
        keepAliveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        autoRow.addView(keepAliveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(autoRow);

        lastDeviceView = text("上次设备: 无", 13, 0xFF90A4AE);
        lastDeviceView.setPadding(0, dp(6), 0, dp(0));
        root.addView(lastDeviceView);

        Button widgetBtn = new Button(this);
        widgetBtn.setText("添加桌面小部件");
        widgetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("添加桌面小部件")
                        .setMessage("长按桌面空白处 → 选择「小组件」→ 找到「电池蓝牙」→ 拖到桌面上。\n\n"
                                + "连接电池后,小部件会实时显示电量、充放电状态和电压电流。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        root.addView(widgetBtn, wlp);

        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceBox);

        TextView dataTitle = text("实时数据", 16, 0xFF80CBC4);
        dataTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(dataTitle);

        dataBox = new LinearLayout(this);
        dataBox.setOrientation(LinearLayout.VERTICAL);
        dataBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        dataBox.setBackgroundColor(0xFF1B2229);
        root.addView(dataBox);
        renderEmpty();

        // -------- 速度省电分析区块 --------
        TextView ecoTitle = text("速度省电分析", 16, 0xFF80CBC4);
        ecoTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(ecoTitle);

        ecoBox = new LinearLayout(this);
        ecoBox.setOrientation(LinearLayout.VERTICAL);
        ecoBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        ecoBox.setBackgroundColor(0xFF1B2229);
        root.addView(ecoBox);

        ecoStateView = text("", 12, 0xFF78909C);
        ecoStateView.setPadding(0, dp(6), 0, 0);
        root.addView(ecoStateView);

        Button ecoClearBtn = new Button(this);
        ecoClearBtn.setText("清零统计数据");
        ecoClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("清零")
                        .setMessage("要清空省电分析的累计数据吗?")
                        .setPositiveButton("清零", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                EcoTracker.get(MainActivity.this).reset();
                                EcoTracker.get(MainActivity.this).save();
                                Toast.makeText(MainActivity.this, "已清零", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        root.addView(ecoClearBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        diagView = text("诊断: 尚未开始", 13, 0xFFFFB74D);
        diagView.setPadding(0, dp(10), 0, 0);
        root.addView(diagView);

        TextView logTitle = text("通信日志", 16, 0xFF80CBC4);
        logTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(logTitle);

        logView = text("", 11, 0xFF78909C);
        logView.setBackgroundColor(0xFF161B20);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void onScanClicked() {
        if (!ensureBluetooth()) {
            return;
        }
        devices.clear();
        deviceBox.removeAllViews();
        hub.client().startScan();
        appendLog("开始扫描…");
        scanBtn.postDelayed(new Runnable() {
            @Override
            public void run() {
                hub.client().stopScan();
                if (devices.isEmpty()) {
                    statusView.setText("未扫描到电池,请靠近电池后重试");
                }
            }
        }, 10000L);
        // 6 秒内没手动选,就自动连上扫到的第一台,省得反复点
        scanBtn.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!hub.client().isConnected() && !devices.isEmpty()) {
                    hub.client().connect(devices.get(0).mac);
                }
            }
        }, 6000L);
    }

    private void toggleAuto() {
        boolean on = !AutoLink.isEnabled(this);
        AutoLink.setEnabled(this, on);
        if (on) {
            hub.client().clearUserDisconnect();
            if (AutoLink.hasDevice(this)) {
                BmsService.start(this);
                Toast.makeText(this, "会自动连接 " + AutoLink.describe(this), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "还没有连过设备,先扫描连接一次吧", Toast.LENGTH_LONG).show();
            }
        } else {
            BmsService.stop(this);
            Toast.makeText(this, "自动连接已关闭", Toast.LENGTH_SHORT).show();
        }
        updateAutoUi();
        BmsWidgetProvider.refreshAll(this);
    }

    private void updateAutoUi() {
        if (autoBtn == null) {
            return;
        }
        autoBtn.setText("自动连接: " + (AutoLink.isEnabled(this) ? "开" : "关"));
        if (lastDeviceView != null) {
            String when = AutoLink.savedAt(this) > 0
                    ? "  ·  " + new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    .format(new Date(AutoLink.savedAt(this)))
                    : "";
            lastDeviceView.setText("上次连接: " + AutoLink.describe(this) + when);
        }
    }

    /** 国产 ROM 常把后台杀掉,让用户给本 App 关闭电池优化,自启才靠得住。 */
    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "本机没有电池优化设置页", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void renderEmpty() {
        dataBox.removeAllViews();
        dataBox.addView(text("尚未连接电池", 15, 0xFF78909C));
    }

    private void addRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView k = text(label, 14, 0xFF90A4AE);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        TextView v = text(value, 15, 0xFFECEFF1);
        v.setGravity(Gravity.RIGHT);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        row.addView(k);
        row.addView(v);
        dataBox.addView(row);
    }

    private void addBig(String label, String value, int color) {
        TextView v = text(value, 34, color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(6), 0, 0);
        dataBox.addView(text(label, 13, 0xFF90A4AE), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dataBox.addView(v);
    }

    // ------------------------------------------------------------------
    // BmsClient.Listener
    // ------------------------------------------------------------------

    @Override
    public void onDeviceFound(final String name, final String mac, final int rssi) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (BmsClient.Found f : devices) {
                    if (f.mac.equals(mac)) {
                        return;
                    }
                }
                BmsClient.Found found = new BmsClient.Found(name, mac, rssi);
                devices.add(found);
                deviceBox.addView(makeDeviceRow(found));
            }
        });
    }

    private View makeDeviceRow(final BmsClient.Found found) {
        TextView row = text(found.name + "\n" + found.mac + "   " + found.rssi + " dBm", 15, 0xFFE0E0E0);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(0xFF1B2229);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.client().connect(found.mac);
            }
        });
        return row;
    }

    @Override
    public void onLog(final String line) {
        appendLog(line);
    }

    @Override
    public void onState(final String state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText(state);
            }
        });
    }

    @Override
    public void onDiag(final String diag) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (diag != null && !diag.isEmpty() && diagView != null) {
                    diagView.setText("诊断: " + diag);
                }
            }
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                appendLog("错误: " + message);
            }
        });
    }

    @Override
    public void onCellVoltages(final List<Float> volts) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (volts.isEmpty()) {
                    return;
                }
                dataBox.post(new Runnable() {
                    @Override
                    public void run() {
                        // 单体电压单独一块,避免整页重绘
                    }
                });
                lastCells = volts;
                updateCellSection(volts);
            }
        });
    }

    private List<Float> lastCells;
    private LinearLayout cellSection;

    private void updateCellSection(List<Float> volts) {
        if (cellSection == null) {
            cellSection = new LinearLayout(this);
            cellSection.setOrientation(LinearLayout.VERTICAL);
            cellSection.setPadding(0, dp(10), 0, 0);
            dataBox.addView(cellSection);
        }
        cellSection.removeAllViews();
        TextView head = text("单体电压 (" + volts.size() + " 串)", 14, 0xFF90A4AE);
        cellSection.addView(head);
        StringBuilder sb = new StringBuilder();
        float max = 0f, min = Float.MAX_VALUE;
        for (int i = 0; i < volts.size(); i++) {
            float v = volts.get(i);
            max = Math.max(max, v);
            min = Math.min(min, v);
            if (i > 0) {
                sb.append("   ");
            }
            sb.append(String.format(Locale.US, "#%d %.3fV", i + 1, v));
            if (i % 3 == 2) {
                cellSection.addView(text(sb.toString(), 13, 0xFFB0BEC5));
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            cellSection.addView(text(sb.toString(), 13, 0xFFB0BEC5));
        }
        cellSection.addView(text(String.format(Locale.US, "最高 %.3fV   最低 %.3fV   压差 %.3fV",
                max, min, max - min), 13, max - min > 0.05f ? 0xFFFFB74D : 0xFF9CCC65));
    }

    @Override
    public void onBaseInfo(final BmsProtocol.BaseInfo info) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                renderInfo(info);
            }
        });
    }

    private void renderInfo(BmsProtocol.BaseInfo info) {
        dataBox.removeAllViews();
        cellSection = null;

        int socColor = info.soc >= 50 ? 0xFF66BB6A : (info.soc >= 20 ? 0xFFFFB74D : 0xFFEF5350);
        addBig("剩余电量 SOC", info.soc + " %", socColor);

        addRow("总电压", String.format(Locale.US, "%.2f V", info.totalVoltage));
        boolean out = info.isFlowingOut();
        String state = Math.abs(info.current) <= 0.05f ? "静置" : (out ? "放电中" : "充电中");
        addRow("电流", String.format(Locale.US, "%.2f A   (%s)", info.current, state));
        addRow("功率", String.format(Locale.US, "%.1f W", info.totalVoltage * info.current));
        addRow("剩余能量", String.format(Locale.US, "%.2f", info.remainPower));
        addRow("额定能量", String.format(Locale.US, "%.2f", info.nominalPower));
        addRow("电芯串数", String.valueOf(info.cellCount));
        addRow("循环次数", String.valueOf(info.cycles));
        addRow("生产日期", info.productYear + "-" + info.productMonth + "-" + info.productDay);
        addRow("固件版本", String.format(Locale.US, "0x%02X", info.firmwareVersion));

        String mos = (info.isCharging() ? "充电开" : "充电关") + " / "
                + (info.isDischarging() ? "放电开" : "放电关")
                + String.format(Locale.US, "   (0x%02X)", info.fetState);
        addRow("MOS 状态", mos);

        if (!info.temperatures.isEmpty()) {
            StringBuilder ts = new StringBuilder();
            for (int i = 0; i < info.temperatures.size(); i++) {
                if (i > 0) {
                    ts.append("  ");
                }
                ts.append(String.format(Locale.US, "%.1f℃", info.temperatures.get(i)));
            }
            addRow("温度 (" + info.temperatures.size() + ")", ts.toString());
        }

        List<String> prot = BmsProtocol.parseProtection(info.protectionBits);
        addRow("保护状态", prot.isEmpty() ? "正常" : TextUtils.join(" / ", prot));

        if (info.extended) {
            addRow("平衡电流", String.format(Locale.US, "%.2f A", info.balanceCurrent));
            if (info.humidity > 0 && info.humidity < 101) {
                addRow("湿度", info.humidity + " %");
            }
            addRow("海拔", info.altitude + " m");
            addRow("学习容量", String.format(Locale.US, "%.2f Ah", info.learnedCapacity));
        }

        if (!info.cellVoltages.isEmpty()) {
            updateCellSection(info.cellVoltages);
            lastCells = info.cellVoltages;
        }
    }

    // ------------------------------------------------------------------
    // 速度省电分析
    // ------------------------------------------------------------------

    private void updateEcoUi(EcoTracker.Stats s) {
        if (ecoBox == null || ecoStateView == null) {
            return;
        }
        ecoBox.removeAllViews();

        ecoBox.addView(text("说明:骑行时自动记录各速度档的实际能耗,累计越多越准。(速度来自手机GPS,需允许定位)", 12, 0xFF78909C));

        // 表头
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        int[] weight = {3, 3, 3, 2};
        String[] cols = {"速度区间", "平均功率", "能耗Wh/km", "时长"};
        for (int i = 0; i < cols.length; i++) {
            TextView t = text(cols[i], 12, 0xFF90A4AE);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight[i]));
            head.addView(t);
        }
        ecoBox.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean anyData = false;
        for (int i = 0; i < EcoTracker.BIN_COUNT; i++) {
            EcoTracker.Bucket b = s.bins[i];
            if (b.secs <= 0) {
                continue;
            }
            anyData = true;
            String bestTag = (i == s.bestBin) ? " ◀最省电" : "";
            String kmh = EcoTracker.binLabel(i);
            float avgW = b.secs > 0 ? b.wh * 3600f / b.secs : 0;
            float whkm = b.km > 0.001f ? b.wh / b.km : 0;
            String secs = b.secs >= 3600
                    ? String.format(Locale.US, "%.1fh", b.secs / 3600f)
                    : (b.secs >= 60 ? String.format(Locale.US, "%.1fmin", b.secs / 60f)
                    : String.format(Locale.US, "%ds", b.secs));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int bg = (i == s.bestBin) ? 0xFF23331F : 0xFF222B33;
            String[] strs = {
                    kmh + bestTag,
                    String.format(Locale.US, "%.0f W", avgW),
                    String.format(Locale.US, "%.1f", whkm),
                    secs
            };
            int[] colors = {
                    i == s.bestBin ? 0xFF9CCC65 : 0xFFECEFF1,
                    0xFFECEFF1,
                    i == s.bestBin ? 0xFF9CCC65 : 0xFF4FC3F7,
                    0xFF90A4AE
            };
            for (int c = 0; c < strs.length; c++) {
                TextView t = text(strs[c], 13, colors[c]);
                t.setGravity(Gravity.CENTER);
                t.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, new float[]{3f, 3f, 3f, 2f}[c]));
                row.addView(t);
            }
            row.setPadding(0, dp(3), 0, dp(3));
            row.setBackgroundColor(bg);
            ecoBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (!anyData) {
            ecoBox.addView(text("还没有数据。连接电池后开始骑行,会自动统计各档能耗。",
                    13, 0xFF78909C));
        }

        String stateLine;
        if (s.curKmh > 0) {
            stateLine = String.format(Locale.US, "统计中:当前 %.0f km/h · 累计 %.2f km / %s",
                    s.curKmh, s.totalKm, fmtSecs(s.totalSecs));
        } else {
            stateLine = String.format(Locale.US, "统计已累计 %.2f km / %s %s",
                    s.totalKm, fmtSecs(s.totalSecs),
                    EcoTracker.get(this).isGpsRunning() ? "(GPS已就绪)" : "(GPS未就绪:请给本App位置权限)");
        }
        ecoStateView.setText(stateLine);
    }

    private static String fmtSecs(long s) {
        if (s >= 3600) {
            return String.format(Locale.US, "%.1fh", s / 3600f);
        }
        return String.format(Locale.US, "%.0fmin", s / 60f);
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        final String text = line;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logLines.append(text).append('\n');
                String[] all = logLines.toString().split("\n");
                int from = Math.max(0, all.length - 40);
                StringBuilder keep = new StringBuilder();
                for (int i = from; i < all.length; i++) {
                    keep.append(all[i]).append('\n');
                }
                logLines.setLength(0);
                logLines.append(keep);
                logView.setText(logLines.toString());
            }
        });
    }
}
