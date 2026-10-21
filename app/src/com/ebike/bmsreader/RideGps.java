package com.ebike.bmsreader;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * GPS 速度源:用系统 LocationManager 取速度,不依赖 Google 服务。
 * 每次定位回调把 speed(m/s) 换算成 km/h 抛给回调,回调统一在主线程。
 */
final class RideGps {

    interface Callback {
        /** kmh:当前 GPS 速度;valid:false 表示本次回调不可当速度用(如 GpsFix 刚拿到)。 */
        void onSpeed(float kmh);
    }

    private final Context ctx;
    private final Handler main;
    private final Callback callback;
    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(final Location location) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (location == null) {
                        return;
                    }
                    callback.onSpeed(location.getSpeed() * 3.6f);
                }
            });
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private boolean running;

    RideGps(Context ctx, Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.callback = callback;
        this.main = new Handler(Looper.getMainLooper());
    }

    /** 有定位权限且开始监听才返回 true。 */
    boolean start() {
        if (running) {
            return true;
        }
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || ctx.checkSelfPermission(Manifest.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            return false;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return false;
        }
        boolean ok = false;
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.5f, listener, Looper.getMainLooper());
                ok = true;
            } catch (Exception ignored) {
                // 权限/provider 异常,退回下面的通用 provider
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0.5f, listener, Looper.getMainLooper());
            ok = true;
        } catch (Exception ignored) {
            // 没有 network provider 也无所谓,gps 成了就行
        }
        running = ok;
        return ok;
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                lm.removeUpdates(listener);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    boolean isRunning() {
        return running;
    }
}
