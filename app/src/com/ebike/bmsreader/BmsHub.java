package com.ebike.bmsreader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BLE 客户端的单例持有者:管连接、缓存最新数据、把事件多播给所有监听者。
 * 所有 BmsClient 回调统一先转主线程再分发,下游监听者不用再各自切线程;
 * "数据落地"这类副作用(小部件快照/自启记录/通知/前台服务)由注册在
 * 最前面的 DataPump 负责,本类不掺和任何业务落地。
 */
public class BmsHub {

    private static BmsHub instance;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<BmsClient.Listener> listeners = new CopyOnWriteArrayList<>();

    private BmsClient client;
    private BmsProtocol.BaseInfo latest;
    private List<Float> latestCells;
    private String deviceName = "";

    public static synchronized BmsHub get(Context ctx) {
        if (instance == null) {
            instance = new BmsHub(ctx.getApplicationContext());
        }
        return instance;
    }

    private BmsHub(Context appContext) {
        this.appContext = appContext;
        // DataPump 固定第一个:先落快照/自启记录,再轮到界面等下游刷新
        listeners.add(new DataPump(appContext, this));
        this.client = new BmsClient(appContext, bridge);
    }

    /** BmsClient 回调发生在蓝牙线程,这里统一转主线程后多播。 */
    private final BmsClient.Listener bridge = new BmsClient.Listener() {
        @Override
        public void onDeviceFound(final String name, final String mac, final int rssi) {
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onDeviceFound(name, mac, rssi);
                    }
                }
            });
        }

        @Override
        public void onLog(final String line) {
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onLog(line);
                    }
                }
            });
        }

        @Override
        public void onState(final String state) {
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onState(state);
                    }
                }
            });
        }

        @Override
        public void onBaseInfo(final BmsProtocol.BaseInfo info) {
            if (info == null) {
                return;
            }
            // 缓存更新放在 Bluetooth 线程同步做,保证主线程多播时已就位
            synchronized (lock) {
                if (latestCells != null && !latestCells.isEmpty()) {
                    info.cellVoltages.addAll(latestCells);
                }
                latest = info;
                String name = client.getConnectedName();
                if (name != null) {
                    deviceName = name;
                }
            }
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onBaseInfo(info);
                    }
                }
            });
        }

        @Override
        public void onCellVoltages(final List<Float> volts) {
            if (volts == null || volts.isEmpty()) {
                return;
            }
            synchronized (lock) {
                latestCells = volts;
            }
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onCellVoltages(volts);
                    }
                }
            });
        }

        @Override
        public void onError(final String message) {
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onError(message);
                    }
                }
            });
        }

        @Override
        public void onDiag(final String diag) {
            post(new Runnable() {
                @Override
                public void run() {
                    for (BmsClient.Listener l : listeners) {
                        l.onDiag(diag);
                    }
                }
            });
        }
    };

    private void post(Runnable r) {
        main.post(r);
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
        synchronized (lock) {
            return latest;
        }
    }

    public List<Float> latestCells() {
        synchronized (lock) {
            return latestCells;
        }
    }

    public String deviceName() {
        synchronized (lock) {
            return deviceName;
        }
    }
}
