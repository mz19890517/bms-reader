package com.ebike.bmsreader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 壳:权限、生命周期、Tab 切换与事件转发。
 * 电池页/省电页视图在 BatteryPage/EcoPage,连接编排在 ConnectionController。
 */
public class MainActivity extends Activity implements BmsClient.Listener {

    private static final int REQ_PERMS = 1001;

    private BmsHub hub;
    private EcoTracker ecoTracker;
    private BatteryPage batteryPage;
    private EcoPage ecoPage;
    private ConnectionController controller;
    private Button batteryTab;
    private Button ecoTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildPages();
        setContentView(buildUi());
        BmsWidgetProvider.selfCheck(this);
        hub = BmsHub.get(this);
        controller = new ConnectionController(this, hub, new ConnectionController.Callbacks() {
            @Override
            public void onScanStatus(String s) {
                batteryPage.setStatus(s);
            }

            @Override
            public void onAutoState(String autoText, String lastDeviceText) {
                batteryPage.setAutoState(autoText, lastDeviceText);
            }
        });
        hub.addListener(this);
        // 省电分析器:常驻挂在 Hub 监听链上,不随界面销毁,后台也继续统计
        EcoTracker.get(this).ensureListening(hub);
        ecoTracker = EcoTracker.get(this);
        ecoTracker.setUiListener(new EcoTracker.UiListener() {
            @Override
            public void onEcoUpdated(EcoTracker.Stats stats) {
                ecoPage.update(stats);
            }
        });
        Permissions.requestIfNeeded(this, REQ_PERMS);
        if (Permissions.granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            EcoTracker.get(this).ensureGps();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.refreshAutoUi();
        if (AutoLink.shouldRun(this)) {
            // 服务可能已经被系统杀掉了,这里再拉一次,连上次成功的设备
            BmsService.start(this);
        }
        if (!controller.ensureBluetooth()) {
            batteryPage.setStatus("蓝牙未开启,请打开后重试");
        }
        // 定位可能上次没开/刚授权,每次回到前台再试一次(内部幂等)
        EcoTracker.get(this).ensureGps();
        ecoPage.update(EcoTracker.get(this).snapshot());
        // 旋转重建后用 Hub 缓存的最新数据立即铺一遍,不等下个 BLE 包
        batteryPage.renderHubSnapshot(hub);
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
        controller.cancel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台时不要断开,小部件还要靠这条连接刷新
    }

    // ------------------------------------------------------------------
    // 界面组装
    // ------------------------------------------------------------------

    private void buildPages() {
        batteryPage = new BatteryPage(this, new BatteryPage.Actions() {
            @Override
            public void onScanClicked() {
                doScan();
            }

            @Override
            public void onDisconnect() {
                controller.disconnect();
                Toast.makeText(MainActivity.this, "已断开,自动连接已关闭", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onToggleAuto() {
                controller.toggleAuto();
            }

            @Override
            public void onKeepAlive() {
                controller.openKeepAliveSettings();
            }

            @Override
            public void onConnectDevice(String mac) {
                controller.connect(mac);
            }
        });
        ecoPage = new EcoPage(this, new EcoPage.Actions() {
            @Override
            public void onClearStats() {
                EcoTracker.get(MainActivity.this).reset();
                EcoTracker.get(MainActivity.this).save();
                Toast.makeText(MainActivity.this, "已清零", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 0));

        TextView title = Ui.text(this, "电池蓝牙信息查看器", 20, 0xFF4FC3F7);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        batteryTab = new Button(this);
        batteryTab.setText("电池信息");
        batteryTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(0);
            }
        });
        tabRow.addView(batteryTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ecoTab = new Button(this);
        ecoTab.setText("省电分析");
        ecoTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(1);
            }
        });
        tabRow.addView(ecoTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabRow);

        // 页面容器:切 Tab 靠显隐,两个页等高占满
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.addView(batteryPage.pageView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pages.addView(ecoPage.pageView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pages);

        selectTab(0);
        return root;
    }

    /** 切换分页:0=电池信息 1=省电分析。 */
    private void selectTab(int tab) {
        batteryPage.pageView().setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        ecoPage.pageView().setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        ecoPage.setPageVisible(tab == 1);
        styleTab(batteryTab, tab == 0);
        styleTab(ecoTab, tab == 1);
        if (tab == 1) {
            ecoPage.update(EcoTracker.get(this).snapshot());
            ecoTracker.ensureGps();
        }
    }

    private void styleTab(Button b, boolean active) {
        b.setTextColor(active ? 0xFF4FC3F7 : 0xFF90A4AE);
        b.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setBackgroundColor(active ? 0xFF1B2229 : 0xFF101418);
    }

    // ------------------------------------------------------------------
    // 业务动作
    // ------------------------------------------------------------------

    private void doScan() {
        if (!controller.ensureBluetooth()) {
            return;
        }
        batteryPage.clearDevices();
        controller.startScan();
        batteryPage.appendLog("开始扫描…");
    }

    // ------------------------------------------------------------------
    // 权限结果
    // ------------------------------------------------------------------

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
    // BmsClient.Listener(Hub 保证已在主线程) → 转发给页面
    // ------------------------------------------------------------------

    @Override
    public void onDeviceFound(String name, String mac, int rssi) {
        batteryPage.addDevice(new BmsClient.Found(name, mac, rssi));
    }

    @Override
    public void onLog(String line) {
        batteryPage.appendLog(line);
    }

    @Override
    public void onState(String state) {
        batteryPage.setStatus(state);
    }

    @Override
    public void onDiag(String diag) {
        batteryPage.setDiag(diag);
    }

    @Override
    public void onError(String message) {
        batteryPage.showToast(message);
        batteryPage.appendLog("错误: " + message);
    }

    @Override
    public void onCellVoltages(List<Float> volts) {
        if (volts.isEmpty()) {
            return;
        }
        batteryPage.appendCells(volts);
    }

    @Override
    public void onBaseInfo(BmsProtocol.BaseInfo info) {
        batteryPage.renderInfo(info);
    }
}
