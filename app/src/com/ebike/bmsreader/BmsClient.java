package com.ebike.bmsreader;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 电池 BMS 的 BLE 连接与轮询。 */
public class BmsClient {

    public static final UUID SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb");
    public static final UUID NOTIFY_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    public static final UUID WRITE_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    private static final long POLL_INTERVAL_MS = 2000L;
    private static final long WRITE_GAP_MS = 120L;
    private static final int MTU = 247;
    private static final int MAX_STREAM = 2048;
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onDeviceFound(String name, String mac, int rssi);

        void onLog(String line);

        void onState(String state);

        void onBaseInfo(BmsProtocol.BaseInfo info);

        void onCellVoltages(List<Float> volts);

        void onError(String message);

        /** 诊断信息:发送/接收计数与最近一帧,方便现场判断卡在哪一步。 */
        void onDiag(String diag);
    }

    /** 已扫描到的设备(去重)。 */
    public static final class Found {
        public final String name;
        public final String mac;
        public int rssi;

        Found(String name, String mac, int rssi) {
            this.name = name;
            this.mac = mac;
            this.rssi = rssi;
        }
    }

    private final Context context;
    private final Listener listener;
    private static final String TAG = "BmsReader";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Map<String, Found> foundMap = new LinkedHashMap<>();
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notifyChar;
    private BluetoothGattCharacteristic writeChar;
    private String connectedMac;
    private String connectedName;
    private boolean scanning;
    private boolean connected;
    /** 用户主动点的断开:看门狗不该把它连回来。 */
    private boolean userDisconnect;
    private boolean polling;
    private boolean writing;

    /** 等待响应的命令队列(BMS 按顺序应答)。 */
    private final Deque<Byte> pending = new ArrayDeque<>();
    /** 待发送队列:GATT 一次只允许一个写操作在途,必须排队串行发。 */
    private final Deque<Byte> outQueue = new ArrayDeque<>();
    private boolean writeInFlight;
    private final byte[] stream = new byte[MAX_STREAM];
    private int streamLen;
    private int framesReceived;
    private int txCount;
    private String lastRxHex = "-";

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!connected || !polling) {
                return;
            }
            enqueue(BmsProtocol.CMD_BASE_INFO);
            enqueue(BmsProtocol.CMD_CELL_VOLTAGE);
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public BmsClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothAvailable() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null || manager.getAdapter() == null) {
            return false;
        }
        adapter = manager.getAdapter();
        return adapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothOn() {
        return isBluetoothAvailable() && adapter.isEnabled();
    }

    public List<Found> getFoundDevices() {
        return new ArrayList<>(foundMap.values());
    }

    public boolean isConnected() {
        return connected;
    }

    public String getConnectedName() {
        return connectedName;
    }

    public String getConnectedMac() {
        return connectedMac;
    }

    public boolean isUserDisconnect() {
        return userDisconnect;
    }

    /** 重新打开自动连接时,清掉"用户主动断开"的标记。 */
    public void clearUserDisconnect() {
        userDisconnect = false;
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (!isBluetoothAvailable() || scanning) {
            return;
        }
        foundMap.clear();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        listener.onState("扫描中…");
        try {
            adapter.getBluetoothLeScanner().startScan(filters, settings, scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            listener.onError("缺少蓝牙权限: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!scanning || adapter == null) {
            return;
        }
        scanning = false;
        try {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        } catch (SecurityException ignored) {
            // 权限被回收时忽略
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onDevice(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                onDevice(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            listener.onError("扫描失败,错误码 " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void onDevice(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        if (device == null) {
            return;
        }
        String mac = device.getAddress();
        String name = null;
        try {
            name = device.getName();
        } catch (SecurityException ignored) {
            // Android 12+ 需要 BLUETOOTH_CONNECT
        }
        if (name == null || name.isEmpty()) {
            name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
        }
        if (name == null || name.isEmpty()) {
            name = "未知设备";
        }
        Found item = foundMap.get(mac);
        if (item == null) {
            foundMap.put(mac, new Found(name, mac, result.getRssi()));
            listener.onDeviceFound(name, mac, result.getRssi());
        } else {
            item.rssi = result.getRssi();
        }
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    public void connect(String mac) {
        if (!isBluetoothAvailable()) {
            listener.onError("蓝牙未开启");
            return;
        }
        stopScan();
        disconnect();
        userDisconnect = false;
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            listener.onError("MAC 地址无效: " + mac);
            return;
        }
        connectedMac = mac;
        connectedName = foundMap.containsKey(mac) ? foundMap.get(mac).name : mac;
        listener.onState("正在连接 " + connectedName + "…");
        gatt = device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        userDisconnect = true;
        handler.removeCallbacks(pollTask);
        handler.removeCallbacks(writeGuard);
        polling = false;
        writeInFlight = false;
        outQueue.clear();
        pending.clear();
        streamLen = 0;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
                // ignore
            }
            gatt = null;
        }
        notifyChar = null;
        writeChar = null;
        if (connected) {
            connected = false;
            listener.onState("已断开");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            android.util.Log.d(TAG, "gattStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onState("已连接,正在发现服务…");
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    listener.onError("发现服务失败: " + e.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = connected;
                connected = false;
                polling = false;
                writeInFlight = false;
                outQueue.clear();
                handler.removeCallbacks(pollTask);
                pending.clear();
                streamLen = 0;
                if (was) {
                    listener.onState("连接已断开");
                }
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("服务发现失败,状态 " + status);
                return;
            }
            android.bluetooth.BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                listener.onError("未找到 BMS 服务 " + SERVICE_UUID);
                return;
            }
            android.util.Log.d(TAG, "service found, chars: " + service.getCharacteristics());
            notifyChar = service.getCharacteristic(NOTIFY_UUID);
            writeChar = service.getCharacteristic(WRITE_UUID);
            if (notifyChar == null || writeChar == null) {
                listener.onError("BMS 服务缺少必要特征值");
                return;
            }
            connected = true;
            framesReceived = 0;
            // 连通了才算数:把这台设备记下来,下次开 App / 自启直接连它
            AutoLink.saveDevice(context, connectedMac, connectedName);
        android.util.Log.d(TAG, "connected mac=" + connectedMac + " name=" + connectedName);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 8000L);
            listener.onState("已连接 " + (connectedName == null ? "" : connectedName));

            enableNotify(g, notifyChar);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            android.util.Log.d(TAG, "descriptorWrite status=" + status);
            reportDiag();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startPolling();
            } else {
                listener.onError("开启通知失败,状态 " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            onData(value);
        }

        @Override
        @SuppressLint("Deprecated")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            onData(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                IdsLogCompat("写回调异常 status=" + status);
            }
            writing = false;
            writeInFlight = false;
            handler.removeCallbacks(writeGuard);
            handler.postDelayed(pumpTask, WRITE_GAP_MS);
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        boolean subscribed;
        try {
            subscribed = g.setCharacteristicNotification(ch, true);
        } catch (SecurityException e) {
            listener.onError("订阅通知失败: " + e.getMessage());
            return;
        }
        if (!subscribed) {
            listener.onError("无法订阅通知");
            return;
        }
        BluetoothGattDescriptor descriptor = ch.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            android.util.Log.w(TAG, "CCCD not found on notify char, poll anyway");
            listener.onLog("未找到 CCCD,直接开始轮询");
            startPolling();
            return;
        }
        android.util.Log.d(TAG, "CCCD found, enabling notification");
        listener.onDiag("");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS) {
                    startPolling();
                }
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(descriptor);
            }
        } catch (SecurityException e) {
            listener.onError("开启通知失败: " + e.getMessage());
        }
    }

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        listener.onState("已连接,读取数据中");
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, 300L);
    }

    // ------------------------------------------------------------------
    // 收发
    // ------------------------------------------------------------------

    /** 把命令放进发送队列,并立刻尝试发一帧。 */
    private void enqueue(byte cmd) {
        if (!connected || writeChar == null) {
            return;
        }
        if (outQueue.size() > 8) {
            outQueue.clear();
        }
        outQueue.add(cmd);
        pump();
    }

    private void reportDiag() {
        listener.onDiag("发送 " + txCount + " 次 / 收到 " + framesReceived + " 帧\n最近应答: " + lastRxHex);
    }

    /** GATT 写操作必须一条一条来,否则会返回"通道忙"。 */
    @SuppressLint("MissingPermission")
    private void pump() {
        if (writeInFlight || gatt == null || !connected || writeChar == null) {
            return;
        }
        Byte cmdBox = outQueue.poll();
        if (cmdBox == null) {
            return;
        }
        byte cmd = cmdBox;
        byte[] frame = BmsProtocol.buildRequest(cmd, null);
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = gatt.writeCharacteristic(writeChar, frame, writeChar.getWriteType())
                        == BluetoothGatt.GATT_SUCCESS;
            } else {
                ok = writeChar.setValue(frame) && gatt.writeCharacteristic(writeChar);
            }
        } catch (SecurityException e) {
            listener.onError("写入异常: " + e.getMessage());
            return;
        }
        if (!ok) {
            listener.onLog("写入未被接受 cmd=" + cmd + ",稍后重试");
            handler.postDelayed(pumpTask, 300L);
            return;
        }
        writeInFlight = true;
        pending.add(cmd);
        txCount++;
        android.util.Log.d(TAG, "TX " + BmsProtocol.toHex(frame));
        reportDiag();
        listener.onLog("发送 " + BmsProtocol.toHex(frame));
        handler.removeCallbacks(writeGuard);
        handler.postDelayed(writeGuard, 1500L);
    }

    /** 兜底:个别模块不回写回调,超时后放开通道,避免轮询卡死。 */
    private final Runnable writeGuard = new Runnable() {
        @Override
        public void run() {
            writing = false;
            writeInFlight = false;
            pump();
        }
    };

    /** 看门狗:连上后迟迟没有回包,多半是通知没真正打开,重新订阅一次。 */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!connected || framesReceived > 0) {
                return;
            }
            android.util.Log.w(TAG, "watchdog: no frame yet, re-enable notify");
            listener.onLog("未收到数据,重新开启通知…");
            BluetoothGatt g = gatt;
            if (g != null && notifyChar != null) {
                try {
                    enableNotify(g, notifyChar);
                } catch (SecurityException ignored) {
                    // ignore
                }
            }
            handler.postDelayed(this, 6000L);
        }
    };

    private final Runnable pumpTask = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };

    private void IdsLogCompat(String line) {
        android.util.Log.d(TAG, line);
        listener.onLog(line);
    }

    private void onData(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        lastRxHex = BmsProtocol.toHex(data);
        android.util.Log.d(TAG, "RX " + BmsProtocol.toHex(data) + " (streamLen=" + streamLen + ")");
        reportDiag();
        listener.onLog("接收 " + BmsProtocol.toHex(data));
        if (streamLen + data.length > MAX_STREAM) {
            streamLen = 0;
        }
        System.arraycopy(data, 0, stream, streamLen, data.length);
        streamLen += data.length;

        BmsProtocol.ExtractResult result = BmsProtocol.extractFrames(stream, streamLen);
        if (result.consumed > 0) {
            System.arraycopy(stream, result.consumed, stream, 0, streamLen - result.consumed);
            streamLen -= result.consumed;
        }
        for (BmsProtocol.Frame f : result.frames) {
            handleFrame(f);
        }
    }

    private void handleFrame(BmsProtocol.Frame frame) {
        framesReceived++;
        android.util.Log.d(TAG, "FRAME ok status=" + frame.status
                + " echo=" + (frame.raw[1] & 0xFF)
                + " contentLen=" + frame.content.length
                + " raw=" + BmsProtocol.toHex(frame.raw));
        if (!frame.isOk()) {
            listener.onLog("设备返回错误状态 " + frame.status);
            if (!pending.isEmpty()) {
                pending.poll();
            }
            return;
        }
        // 原 App 用 readContent[1] == cmd 判断是 03 还是 04 的应答,
        // 优先采信回显,回显不认识时退回按发送顺序配对。
        int echoed = frame.raw[1] & 0xFF;
        Byte head = pending.peek();
        byte cmd;
        if (head != null && (head & 0xFF) == echoed) {
            cmd = head;
        } else if (echoed == BmsProtocol.CMD_BASE_INFO || echoed == BmsProtocol.CMD_CELL_VOLTAGE) {
            cmd = (byte) echoed;
        } else {
            listener.onLog("忽略未知响应,回显=" + echoed);
            return;
        }
        if (head != null) {
            pending.poll();
        }
        try {
            if (cmd == BmsProtocol.CMD_BASE_INFO) {
                android.util.Log.d(TAG, "base info len=" + frame.content.length);
                listener.onBaseInfo(BmsProtocol.parseBaseInfo(frame.content));
            } else {
                android.util.Log.d(TAG, "cell volts len=" + frame.content.length);
                listener.onCellVoltages(BmsProtocol.parseCellVoltages(frame.content));
            }
        } catch (RuntimeException e) {
            listener.onLog("解析失败: " + e.getMessage());
            android.util.Log.e(TAG, "parse fail", e);
        }
    }
}
