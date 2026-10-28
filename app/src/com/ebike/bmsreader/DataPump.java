package com.ebike.bmsreader;

import android.content.Context;

import java.util.List;

/**
 * Hub 的第 0 号监听者:负责"数据落地"副作用——
 * 小部件快照(WdigketState)、自动连设备记录(AutoLink)、
 * 通知栏与前台服务保活(BmsService)。Hub 保证回调都在主线程。
 */
final class DataPump implements BmsClient.Listener {

    private final Context ctx;
    private final BmsHub hub;

    DataPump(Context ctx, BmsHub hub) {
        this.ctx = ctx;
        this.hub = hub;
    }

    @Override
    public void onDeviceFound(String name, String mac, int rssi) {
    }

    @Override
    public void onLog(String line) {
    }

    @Override
    public void onState(String state) {
        // 连接状态变化:该常驻的就拉起,不该的就停
        BmsService.sync(ctx, hub.client().isConnected());
    }

    @Override
    public void onBaseInfo(BmsProtocol.BaseInfo info) {
        if (info == null) {
            return;
        }
        String name = hub.deviceName();
        WidgetState.save(ctx, info, name);
        // 连接成功过一次就把设备名/地址刷新到"上次设备"里
        String mac = hub.client().getConnectedMac();
        if (mac != null) {
            AutoLink.saveDevice(ctx, mac, name);
        }
        BmsWidgetProvider.refreshAll(ctx);
        BmsService.sync(ctx, hub.client().isConnected());
        BmsService.notifyState(ctx);
    }

    @Override
    public void onCellVoltages(List<Float> volts) {
    }

    @Override
    public void onError(String message) {
    }

    @Override
    public void onDiag(String diag) {
    }
}
