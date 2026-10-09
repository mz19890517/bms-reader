package com.ebike.bmsreader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BLE 客户端的单例持有者。
 * 界面和前台服务都从这里拿连接,保证退到后台后连接不断、小部件能继续刷新。
 */
public class BmsHub implements BmsClient.Listener {

    private static BmsHub instance;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<BmsClient.Listener> listeners = new CopyOnWriteArrayList<>();

    private BmsClient client;
    private BmsProtocol.BaseInfo latest;
    private List<Float> latestCells;
    private String deviceName = "";
    private int txCount;
    private String lastRx = "-";

    public static synchronized BmsHub get(Context ctx) {
        if (instance == null) {
            instance = new BmsHub(ctx.getApplicationContext());
        }
        return instance;
    }

    private BmsHub(Context appContext) {
        this.appContext = appContext;
        this.client = new BmsClient(appContext, this);
    }

    public BmsClient client() {
        return client;
    }

    public void addListener(BmsClient.Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(BmsClient.Listener l) {
        listeners.remove(l);
    }

    public BmsProtocol.BaseInfo latest() {
        return latest;
    }

    public List<Float> latestCells() {
        return latestCells;
    }

    public String deviceName() {
        return deviceName;
    }

    public int txCount() {
        return txCount;
    }

    public String lastRx() {
        return lastRx;
    }

    // ---------------- BmsClient.Listener 转发 ----------------

    @Override
    public void onDeviceFound(String name, String mac, int rssi) {
        for (BmsClient.Listener l : listeners) {
            l.onDeviceFound(name, mac, rssi);
        }
    }

    @Override
    public void onLog(String line) {
        if (line.startsWith("发送")) {
            txCount++;
        } else if (line.startsWith("接收")) {
            lastRx = line.length() > 3 ? line.substring(3) : "-";
        }
        for (BmsClient.Listener l : listeners) {
            l.onLog(line);
        }
    }

    @Override
    public void onState(String state) {
        for (BmsClient.Listener l : listeners) {
            l.onState(state);
        }
        BmsService.sync(appContext, client.isConnected());
    }

    @Override
    public void onError(String message) {
        for (BmsClient.Listener l : listeners) {
            l.onError(message);
        }
    }

    @Override
    public void onDiag(String diag) {
        for (BmsClient.Listener l : listeners) {
            l.onDiag(diag);
        }
    }

    @Override
    public void onBaseInfo(BmsProtocol.BaseInfo info) {
        if (latestCells != null && !latestCells.isEmpty()) {
            info.cellVoltages.addAll(latestCells);
        }
        latest = info;
        if (client.getConnectedName() != null) {
            deviceName = client.getConnectedName();
        }
        final BmsProtocol.BaseInfo snapshot = info;
        main.post(new Runnable() {
            @Override
            public void run() {
                WidgetState.save(appContext, snapshot, deviceName);
                // 连接成功过一次就把设备名/地址刷新到"上次设备"里
                if (client.getConnectedMac() != null) {
                    AutoLink.saveDevice(appContext, client.getConnectedMac(), deviceName);
                }
                BmsWidgetProvider.refreshAll(appContext);
                BmsService.sync(appContext, client.isConnected());
                BmsService.notifyState(appContext);
            }
        });
        for (BmsClient.Listener l : listeners) {
            l.onBaseInfo(info);
        }
    }

    @Override
    public void onCellVoltages(List<Float> volts) {
        latestCells = volts;
        for (BmsClient.Listener l : listeners) {
            l.onCellVoltages(volts);
        }
    }
}
