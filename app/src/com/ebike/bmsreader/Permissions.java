package com.ebike.bmsreader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** 集中处理蓝牙/定位/通知权限的申请与检查。 */
final class Permissions {

    private Permissions() {
    }

    static void requestIfNeeded(Activity act, int requestCode) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (act.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // 省电分析需要 GPS 速度,任何版本都要精确位置权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            act.requestPermissions(missing.toArray(new String[0]), requestCode);
        }
    }

    static boolean granted(Context c, String p) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // 老版本安装时静态授权
            return true;
        }
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }
}
