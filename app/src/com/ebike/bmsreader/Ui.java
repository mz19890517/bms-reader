package com.ebike.bmsreader;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

/** 界面小工具:dp/sp 换算与统一样式的文本构造。 */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, String s, float size, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTextColor(color);
        return tv;
    }
}
