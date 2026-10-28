package com.ebike.bmsreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 电池 Tab 的视图:构建与刷新,业务动作全部由 Actions 注入,本类不碰连接逻辑。 */
final class BatteryPage {

    interface Actions {
        void onScanClicked();

        void onDisconnect();

        void onToggleAuto();

        void onKeepAlive();

        void onConnectDevice(String mac);
    }

    private final Activity act;
    private final Actions actions;
    private final ScrollView scroll;

    private TextView statusView;
    private Button autoBtn;
    private TextView lastDeviceView;
    private LinearLayout deviceBox;
    private LinearLayout dataBox;
    private LinearLayout cellSection;
    private TextView diagView;
    private TextView logView;
    private final StringBuilder logLines = new StringBuilder();
    private final List<BmsClient.Found> devices = new ArrayList<>();

    BatteryPage(Activity act, Actions actions) {
        this.act = act;
        this.actions = actions;
        this.scroll = build();
    }

    View pageView() {
        return scroll;
    }

    private ScrollView build() {
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 12));

        statusView = Ui.text(act, "正在初始化…", 14, 0xFFB0BEC5);
        statusView.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 8));
        content.addView(statusView);

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scanBtn = new Button(act);
        scanBtn.setText("扫描电池");
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onScanClicked();
            }
        });
        btnRow.addView(scanBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button disconnectBtn = new Button(act);
        disconnectBtn.setText("断开");
        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onDisconnect();
            }
        });
        btnRow.addView(disconnectBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(btnRow);

        LinearLayout autoRow = new LinearLayout(act);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoBtn = new Button(act);
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onToggleAuto();
            }
        });
        autoRow.addView(autoBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button keepAliveBtn = new Button(act);
        keepAliveBtn.setText("后台保活");
        keepAliveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onKeepAlive();
            }
        });
        autoRow.addView(keepAliveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(autoRow);

        lastDeviceView = Ui.text(act, "上次设备: 无", 13, 0xFF90A4AE);
        lastDeviceView.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 0));
        content.addView(lastDeviceView);

        Button widgetBtn = new Button(act);
        widgetBtn.setText("添加桌面小部件");
        widgetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(act)
                        .setTitle("添加桌面小部件")
                        .setMessage("长按桌面空白处 → 选择「小组件」→ 找到「电池蓝牙」→ 拖到桌面上。\n\n"
                                + "连接电池后,小部件会实时显示电量、当前速度、充放电状态和电压电流。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(act, 6);
        content.addView(widgetBtn, wlp);

        deviceBox = new LinearLayout(act);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(deviceBox);

        TextView dataTitle = Ui.text(act, "实时数据", 16, 0xFF80CBC4);
        dataTitle.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 4));
        content.addView(dataTitle);

        dataBox = new LinearLayout(act);
        dataBox.setOrientation(LinearLayout.VERTICAL);
        dataBox.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        dataBox.setBackgroundColor(0xFF1B2229);
        content.addView(dataBox);
        renderEmpty();

        diagView = Ui.text(act, "诊断: 尚未开始", 13, 0xFFFFB74D);
        diagView.setPadding(0, Ui.dp(act, 10), 0, 0);
        content.addView(diagView);

        TextView logTitle = Ui.text(act, "通信日志", 16, 0xFF80CBC4);
        logTitle.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 4));
        content.addView(logTitle);

        logView = Ui.text(act, "", 11, 0xFF78909C);
        logView.setBackgroundColor(0xFF161B20);
        logView.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10));
        content.addView(logView);

        ScrollView sc = new ScrollView(act);
        sc.addView(content);
        return sc;
    }

    // --------------- 状态/控件文案 ---------------

    void setStatus(String s) {
        if (statusView != null) {
            statusView.setText(s);
        }
    }

    void setAutoState(String autoText, String lastDeviceText) {
        if (autoBtn != null) {
            autoBtn.setText(autoText);
        }
        if (lastDeviceView != null) {
            lastDeviceView.setText(lastDeviceText);
        }
    }

    void setDiag(String diag) {
        if (diag != null && !diag.isEmpty() && diagView != null) {
            diagView.setText("诊断: " + diag);
        }
    }

    void appendLog(String line) {
        if (line == null || logView == null) {
            return;
        }
        logLines.append(line).append('\n');
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

    // --------------- 扫描设备列表 ---------------

    void appendCells(List<Float> volts) {
        updateCellSection(volts);
    }

    void clearDevices() {
        devices.clear();
        deviceBox.removeAllViews();
    }

    void addDevice(BmsClient.Found found) {
        for (BmsClient.Found f : devices) {
            if (f.mac.equals(found.mac)) {
                return;
            }
        }
        devices.add(found);
        deviceBox.addView(makeDeviceRow(found));
    }

    private View makeDeviceRow(final BmsClient.Found found) {
        TextView row = Ui.text(act, found.name + "\n" + found.mac + "   " + found.rssi + " dBm", 15, 0xFFE0E0E0);
        row.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        row.setBackgroundColor(0xFF1B2229);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 6);
        row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onConnectDevice(found.mac);
            }
        });
        return row;
    }

    // --------------- 实时数据渲染 ---------------

    void renderHubSnapshot(BmsHub hub) {
        BmsProtocol.BaseInfo info = hub.latest();
        if (info == null) {
            renderEmpty();
            return;
        }
        renderInfo(info);
        List<Float> cells = hub.latestCells();
        if (cells != null && !cells.isEmpty()) {
            updateCellSection(cells);
        }
    }

    void renderInfo(BmsProtocol.BaseInfo info) {
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
        }
    }

    private void renderEmpty() {
        dataBox.removeAllViews();
        dataBox.addView(Ui.text(act, "尚未连接电池", 15, 0xFF78909C));
    }

    private void addRow(String label, String value) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
        TextView k = Ui.text(act, label, 14, 0xFF90A4AE);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        TextView v = Ui.text(act, value, 15, 0xFFECEFF1);
        v.setGravity(Gravity.RIGHT);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        row.addView(k);
        row.addView(v);
        dataBox.addView(row);
    }

    private void addBig(String label, String value, int color) {
        TextView v = Ui.text(act, value, 34, color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, Ui.dp(act, 6), 0, 0);
        dataBox.addView(Ui.text(act, label, 13, 0xFF90A4AE), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dataBox.addView(v);
    }

    /** 单体电压单独一块,避免整页重绘。 */
    private void updateCellSection(List<Float> volts) {
        if (cellSection == null) {
            cellSection = new LinearLayout(act);
            cellSection.setOrientation(LinearLayout.VERTICAL);
            cellSection.setPadding(0, Ui.dp(act, 10), 0, 0);
            dataBox.addView(cellSection);
        }
        cellSection.removeAllViews();
        TextView head = Ui.text(act, "单体电压 (" + volts.size() + " 串)", 14, 0xFF90A4AE);
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
                cellSection.addView(Ui.text(act, sb.toString(), 13, 0xFFB0BEC5));
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            cellSection.addView(Ui.text(act, sb.toString(), 13, 0xFFB0BEC5));
        }
        cellSection.addView(Ui.text(act, String.format(Locale.US, "最高 %.3fV   最低 %.3fV   压差 %.3fV",
                max, min, max - min), 13, max - min > 0.05f ? 0xFFFFB74D : 0xFF9CCC65));
    }

    void showToast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
