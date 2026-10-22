package com.ebike.bmsreader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 省电区间分析器:把"BMS 实时功率"和"GPS 车速"配对,按速度档累计能量与里程,
 * 算出各档 Wh/km,自动找出最省电的速度区间。
 *
 * 单例常驻(挂在 BmsHub 监听链上),退到后台/锁屏也继续统计;
 * 结果存 eco_stats.json,跨次骑行累计,可在界面上清零。
 */
public class EcoTracker implements BmsClient.Listener {

    /** 速度档宽 5km/h:0-5,5-10,...,95-100,100+ 共 21 档(覆盖高速电摩,空档不显示)。 */
    static final int BIN_KMH = 5;
    static final int BIN_COUNT = 21;

    /** 节流:至少间隔 1 秒记一个样本;两个 BMS 包间隔超过 5 秒当新一段。 */
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static final long GAP_MS = 5000L;
    /** 距上次 GPS 定位超过这个时间就认为速度失效。 */
    private static final long GPS_STALE_MS = 6000L;
    /** 低于该功率视为静止/滑行,不计档(避免滑行把低档刷成"最省电")。 */
    private static final float MIN_DISCHARGE_W = 2f;
    /** 低于该速度不算任何档(原地/推行)。 */
    private static final float MIN_KMH = 1.5f;
    private static final long SAVE_INTERVAL_MS = 30_000L;

    /** 单个速度档的累计量。 */
    public static final class Bucket {
        public float wh;
        public float km;
        public long secs;
    }

    public static final class Stats {
        public final Bucket[] bins = new Bucket[BIN_COUNT];
        /** 符合可信条件的最省电档下标,-1 表示还看不出。 */
        public int bestBin = -1;
        public long totalSecs;
        public float totalKm;
        /** 最近一次采样时的速度,用于界面提示。 */
        public float curKmh = -1;

        Stats() {
            for (int i = 0; i < BIN_COUNT; i++) {
                bins[i] = new Bucket();
            }
        }
    }

    public interface UiListener {
        void onEcoUpdated(Stats stats);
    }

    private static EcoTracker instance;

    public static synchronized EcoTracker get(Context ctx) {
        if (instance == null) {
            instance = new EcoTracker(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context ctx;
    private final Object lock = new Object();
    private final Stats stats = new Stats();
    private final RideGps gps;
    private UiListener uiListener;

    private float lastKmh = -1;
    private long lastGpsAt;
    private long lastSampleAt;
    private boolean sampleArmed;
    private boolean dirty;
    private long lastSaveAt;

    private EcoTracker(Context appContext) {
        this.ctx = appContext;
        this.gps = new RideGps(appContext, new RideGps.Callback() {
            @Override
            public void onSpeed(float kmh) {
                lastKmh = kmh;
                lastGpsAt = android.os.SystemClock.elapsedRealtime();
            }
        });
        load();
    }

    /** 启动 GPS(需要位置权限),应由拿到权限的时刻调用。 */
    public void ensureGps() {
        gps.start();
    }

    /** 注册到 Hub 监听链(幂等,Hub 里有 contains 检查)。 */
    public void ensureListening(BmsHub hub) {
        hub.addListener(this);
    }

    public boolean isGpsRunning() {
        return gps.isRunning();
    }

    public void setUiListener(UiListener l) {
        uiListener = l;
    }

    public Stats snapshot() {
        return snapshotLocked();
    }

    /** 挂到 BmsHub 监听链:每次实时数据到达都试采样。 */
    @Override
    public void onBaseInfo(BmsProtocol.BaseInfo info) {
        if (info == null) {
            return;
        }
        // 电流方向:负=放电。功率取放电绝对值。
        float watts = -info.current * info.totalVoltage;
        if (watts <= 0) {
            // 充电或静置:本轮不采样,下一包重新对齐 dt
            synchronized (lock) {
                sampleArmed = false;
            }
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (lock) {
            long sinceGps = now - lastGpsAt;
            if (lastGpsAt == 0 || sinceGps > GPS_STALE_MS || lastKmh < MIN_KMH || watts < MIN_DISCHARGE_W) {
                // 速度失效或功率太小:不累计,只重置计时起点
                sampleArmed = false;
                lastSampleAt = now;
                stats.curKmh = lastGpsAt == 0 ? -1 : (sinceGps > GPS_STALE_MS ? -1 : lastKmh);
                return;
            }
            if (!sampleArmed) {
                sampleArmed = true;
                lastSampleAt = now;
                stats.curKmh = lastKmh;
                return;
            }
            long dt = now - lastSampleAt;
            if (dt < SAMPLE_INTERVAL_MS) {
                return;
            }
            if (dt > GAP_MS) {
                // 断流太久,这一段丢掉不积分
                lastSampleAt = now;
                stats.curKmh = lastKmh;
                return;
            }
            lastSampleAt = now;
            float ds = dt / 1000f;
            int idx = binIndex(lastKmh);
            Bucket b = stats.bins[idx];
            b.wh += watts * ds / 3600f;
            b.km += lastKmh * ds / 3600f;   // km thanks to kmh * s
            b.secs += (long) ds;
            stats.totalSecs += (long) ds;
            stats.totalKm += lastKmh * ds / 3600f;
            stats.curKmh = lastKmh;
            recomputeBestLocked();
            dirty = true;
        }
        fireUi();
        maybeSave();
    }

    private void fireUi() {
        final Stats s = snapshotLocked();
        final UiListener l = uiListener;
        if (l != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (uiListener == l) {
                        l.onEcoUpdated(s);
                    }
                }
            });
        }
    }

    private void recomputeBestLocked() {
        int best = -1;
        float bestWhPerKm = Float.MAX_VALUE;
        boolean anyCandidate = false;
        for (int i = 0; i < BIN_COUNT; i++) {
            Bucket b = stats.bins[i];
            // 每档要跑过一定距离/时长才可信
            if (b.km >= 0.15f && b.secs >= 20) {
                anyCandidate = true;
                float v = b.wh / b.km;
                if (v < bestWhPerKm) {
                    bestWhPerKm = v;
                    best = i;
                }
            }
        }
        if (bestWhPerKm > 65535f) {
            best = -1;
        }
        // 至少两档有可信数据才有对比意义,只有一个档谈不上"哪个更省电"
        int trusted = 0;
        if (anyCandidate) {
            trusted = 0;
            for (int i = 0; i < BIN_COUNT; i++) {
                Bucket b = stats.bins[i];
                if (b.km >= 0.15f && b.secs >= 20) {
                    trusted++;
                }
            }
        }
        stats.bestBin = trusted >= 2 ? best : -1;
    }

    /** 0-5,5-10...35+。外部 UI 也用它算档名。 */
    private static int binIndex(float kmh) {
        int idx = (int) (kmh / BIN_KMH);
        return idx < 0 ? 0 : Math.min(idx, BIN_COUNT - 1);
    }

    static String binLabel(int i) {
        int lo = i * BIN_KMH;
        return i == BIN_COUNT - 1 ? lo + "+km/h" : lo + "-" + (lo + BIN_KMH) + "km/h";
    }

    public void reset() {
        synchronized (lock) {
            for (Bucket b : stats.bins) {
                b.wh = 0;
                b.km = 0;
                b.secs = 0;
            }
            stats.bestBin = -1;
            stats.totalSecs = 0;
            stats.totalKm = 0;
            sampleArmed = false;
        }
        fireUi();
        save();
    }

    // ------------------------------------------------------------------
    // 持久化: eco_stats.json
    // ------------------------------------------------------------------

    private File file() {
        return new File(ctx.getFilesDir(), "eco_stats.json");
    }

    private void maybeSave() {
        boolean need;
        synchronized (lock) {
            need = dirty && (android.os.SystemClock.elapsedRealtime() - lastSaveAt > SAVE_INTERVAL_MS);
            if (need) {
                dirty = false;
                lastSaveAt = android.os.SystemClock.elapsedRealtime();
            }
        }
        if (need) {
            save();
        }
    }

    void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            synchronized (lock) {
                for (int i = 0; i < BIN_COUNT; i++) {
                    Bucket b = stats.bins[i];
                    JSONObject o = new JSONObject();
                    o.put("wh", b.wh);
                    o.put("km", b.km);
                    o.put("s", b.secs);
                    arr.put(o);
                }
            }
            root.put("bins", arr);
            root.put("ver", 1);
            File f = file();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
            // 存不上就算了,内存里的统计还在
        }
    }

    private void load() {
        try {
            File f = file();
            if (!f.exists()) {
                return;
            }
            FileInputStream in = new FileInputStream(f);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            in.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("bins");
            if (arr != null) {
                synchronized (lock) {
                    int len = Math.min(arr.length(), BIN_COUNT);
                    for (int i = 0; i < len; i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) {
                            continue;
                        }
                        Bucket b = stats.bins[i];
                        b.wh = (float) o.optDouble("wh", 0);
                        b.km = (float) o.optDouble("km", 0);
                        b.secs = o.optLong("s", 0);
                        stats.totalSecs += b.secs;
                        stats.totalKm += b.km;
                    }
                    recomputeBestLocked();
                }
            }
        } catch (Exception ignored) {
            // 损坏文件当没存过
        }
    }

    private Stats snapshotLocked() {
        Stats s = new Stats();
        synchronized (lock) {
            for (int i = 0; i < BIN_COUNT; i++) {
                s.bins[i].wh = stats.bins[i].wh;
                s.bins[i].km = stats.bins[i].km;
                s.bins[i].secs = stats.bins[i].secs;
            }
            s.bestBin = stats.bestBin;
            s.totalSecs = stats.totalSecs;
            s.totalKm = stats.totalKm;
            s.curKmh = stats.curKmh;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 其余监听回调(与统计无关,空实现)
    // ------------------------------------------------------------------

    @Override
    public void onDeviceFound(String name, String mac, int rssi) {
    }

    @Override
    public void onLog(String line) {
    }

    @Override
    public void onState(String state) {
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
