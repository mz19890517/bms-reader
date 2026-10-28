package com.ebike.bmsreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** 省电分析 Tab 的视图:速度卡片与能耗表格,清零等业务动作由 Actions 注入。 */
final class EcoPage {

    interface Actions {
        void onClearStats();
    }

    private final Activity act;
    private final Actions actions;
    private final ScrollView scroll;

    private TextView speedView;
    private TextView speedModeView;
    private LinearLayout ecoBox;
    private TextView ecoStateView;
    /** 页面不可见时跳过表格重建,切回来时由调用方立即刷新一次。 */
    private boolean pageVisible;

    EcoPage(Activity act, Actions actions) {
        this.act = act;
        this.actions = actions;
        this.scroll = build();
    }

    View pageView() {
        return scroll;
    }

    void setPageVisible(boolean visible) {
        pageVisible = visible;
    }

    private ScrollView build() {
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 12));

        LinearLayout speedCard = new LinearLayout(act);
        speedCard.setOrientation(LinearLayout.VERTICAL);
        speedCard.setGravity(Gravity.CENTER_HORIZONTAL);
        speedCard.setPadding(Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 12));
        speedCard.setBackgroundColor(0xFF1B2229);

        LinearLayout speedRow = new LinearLayout(act);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        speedView = Ui.text(act, "--", 56, 0xFF4FC3F7);
        speedView.setTypeface(Typeface.DEFAULT_BOLD);
        speedRow.addView(speedView);
        TextView speedUnit = Ui.text(act, "km/h", 18, 0xFF80CBC4);
        speedUnit.setPadding(Ui.dp(act, 6), 0, 0, Ui.dp(act, 8));
        speedUnit.setGravity(Gravity.BOTTOM);
        speedRow.addView(speedUnit);
        speedCard.addView(speedRow);

        speedModeView = Ui.text(act, "等待GPS定位", 14, 0xFF90A4AE);
        speedModeView.setGravity(Gravity.CENTER);
        speedCard.addView(speedModeView);
        content.addView(speedCard);

        TextView ecoPad = Ui.text(act, "说明:只有「有放电功率」的骑行才会统计;步行/滑行/静止不记录。速度来自手机GPS。",
                12, 0xFF78909C);
        ecoPad.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 6));
        content.addView(ecoPad);

        TextView ecoTitle = Ui.text(act, "各速度区间能耗", 16, 0xFF80CBC4);
        ecoTitle.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 4));
        content.addView(ecoTitle);

        ecoBox = new LinearLayout(act);
        ecoBox.setOrientation(LinearLayout.VERTICAL);
        ecoBox.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        ecoBox.setBackgroundColor(0xFF1B2229);
        content.addView(ecoBox);

        ecoStateView = Ui.text(act, "", 12, 0xFF78909C);
        ecoStateView.setPadding(0, Ui.dp(act, 6), 0, 0);
        content.addView(ecoStateView);

        Button ecoClearBtn = new Button(act);
        ecoClearBtn.setText("清零统计数据");
        ecoClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(act)
                        .setTitle("清零")
                        .setMessage("要清空省电分析的累计数据吗?")
                        .setPositiveButton("清零", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                actions.onClearStats();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(act, 8);
        content.addView(ecoClearBtn, clp);

        ScrollView sc = new ScrollView(act);
        sc.addView(content);
        return sc;
    }

    void update(EcoTracker.Stats s) {
        // 大号当前速度
        if (speedView != null) {
            if (s.gpsFresh && s.curKmh > 0) {
                speedView.setText(String.format(Locale.US, "%.0f", s.curKmh));
                speedView.setTextColor(modeColor(s.mode));
            } else {
                speedView.setText(s.gpsRunning ? "0" : "--");
                speedView.setTextColor(0xFF78909C);
            }
        }
        if (speedModeView != null) {
            speedModeView.setText(EcoTracker.modeLabel(s.mode));
            speedModeView.setTextColor(modeColor(s.mode));
        }

        // 页面没显示时跳过表格重建,切回来时调用方会立即刷新一次
        if (!pageVisible || ecoBox == null || ecoStateView == null) {
            return;
        }

        ecoBox.removeAllViews();

        // 表头
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        int[] weight = {3, 3, 3, 2};
        String[] cols = {"速度区间", "平均功率", "能耗Wh/km", "时长"};
        for (int i = 0; i < cols.length; i++) {
            TextView t = Ui.text(act, cols[i], 12, 0xFF90A4AE);
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
            float avgW = b.secs > 0 ? b.wh * 3_600_000f / b.secs : 0;
            float whkm = b.km > 0.001f ? b.wh / b.km : 0;
            String secs = b.secs >= 3_600_000L
                    ? String.format(Locale.US, "%.1fh", b.secs / 3_600_000f)
                    : (b.secs >= 60_000L ? String.format(Locale.US, "%.1fmin", b.secs / 60_000f)
                    : String.format(Locale.US, "%ds", b.secs / 1000L));

            LinearLayout row = new LinearLayout(act);
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
                TextView t = Ui.text(act, strs[c], 13, colors[c]);
                t.setGravity(Gravity.CENTER);
                t.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, new float[]{3f, 3f, 3f, 2f}[c]));
                row.addView(t);
            }
            row.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
            row.setBackgroundColor(bg);
            ecoBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (!anyData) {
            ecoBox.addView(Ui.text(act, "还没有数据。连接电池后开始骑行,会自动统计各档能耗。",
                    13, 0xFF78909C));
        }

        String stateLine;
        if (!s.gpsRunning) {
            stateLine = String.format(Locale.US, "GPS未启动(请给本App定位权限) · 累计 %.2f km / %s",
                    s.totalKm, fmtSecs(s.totalSecs));
        } else if (!s.gpsFresh) {
            stateLine = String.format(Locale.US, "等待GPS定位… · 累计 %.2f km / %s",
                    s.totalKm, fmtSecs(s.totalSecs));
        } else if (s.mode == EcoTracker.MODE_RIDING) {
            stateLine = String.format(Locale.US, "骑行统计中 · 累计 %.2f km / %s",
                    s.totalKm, fmtSecs(s.totalSecs));
        } else if (s.mode == EcoTracker.MODE_WALKING) {
            stateLine = String.format(Locale.US, "步行/推车中,不记录 · 累计 %.2f km / %s",
                    s.totalKm, fmtSecs(s.totalSecs));
        } else if (s.mode == EcoTracker.MODE_COASTING) {
            stateLine = String.format(Locale.US, "滑行中(无电门),不记录 · 累计 %.2f km / %s",
                    s.totalKm, fmtSecs(s.totalSecs));
        } else {
            stateLine = String.format(Locale.US, "静止 · 累计 %.2f km / %s",
                    s.totalKm, fmtSecs(s.totalSecs));
        }
        ecoStateView.setText(stateLine);
    }

    private static int modeColor(int mode) {
        switch (mode) {
            case EcoTracker.MODE_RIDING:
                return 0xFF9CCC65;
            case EcoTracker.MODE_WALKING:
                return 0xFFFFB74D;
            case EcoTracker.MODE_COASTING:
                return 0xFF4FC3F7;
            case EcoTracker.MODE_IDLE:
                return 0xFF90A4AE;
            default:
                return 0xFF78909C;
        }
    }

    private static String fmtSecs(long ms) {
        if (ms >= 3_600_000L) {
            return String.format(Locale.US, "%.1fh", ms / 3_600_000f);
        }
        if (ms >= 60_000L) {
            return String.format(Locale.US, "%.0fmin", ms / 60_000f);
        }
        return String.format(Locale.US, "%ds", ms / 1000L);
    }
}
