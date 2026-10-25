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
 * GPS 速度源:只订阅 GPS_PROVIDER(网络定位没有可靠速度),500ms 出点。
 * 回调统一抛原始 Location,由使用方决定如何用(速度显示 / 位移计算)。
 */
final class RideGps {

    interface Callback {
        void onFix(Location location);
    }

    private static final long MIN_INTERVAL_MS = 500L;

    private final Context ctx;
    private final Handler main;
    private final Callback callback;
    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(final Location location) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (location != null) {
                        callback.onFix(location);
                    }
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
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            return false;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return false;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_INTERVAL_MS, 0f, listener, Looper.getMainLooper());
            running = true;
        } catch (Exception ignored) {
            return false;
        }
        // 先用最近一次定位点垫上,避免界面等首点
        try {
            final Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            callback.onFix(last);
                        }
                    }
                });
            }
        } catch (Exception ignored) {
            // 权限/无历史点,等新点位
        }
        return true;
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
