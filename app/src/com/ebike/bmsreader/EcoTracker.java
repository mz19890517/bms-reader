package com.ebike.bmsreader;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 省电区间分析器。
 *
 * 采样口径:
 *  - 能量:按 BMS 报文积分 功率(W)×时间,先暂存 pendingWh;
 *  - 里程:用相邻两个 GPS 定位点的真实位移(distanceTo),不用"速度×时间"猜;
 *  - 归档:一个 GPS 路段(位移+时长)算平均速度,把该段内的 pendingWh 记到对应速度档;
 *          静止/推行的路段直接丢弃,避免原地耗电污染低速度档。
 *
 * 结果存 eco_stats.json(ver=2),跨次骑行累计,可在界面清零;档位升级会自动弃用旧格式。
 */
public class EcoTracker implements BmsClient.Listener {

    /** 速度档宽 5km/h:0-5,5-10,...,95-100,100+ 共 21 档(覆盖高速电摩,空档不显示)。 */
    static final int BIN_KMH = 5;
    static final int BIN_COUNT = 21;
    private static final int FILE_VER = 2;

    /** 能量积分:两个 BMS 包间隔太短不计,超过 5 秒视为断流丢弃。 */
    private static final long MIN_POWER_DT_MS = 100L;
    private static final long MAX_POWER_DT_MS = 5000L;
    /** 低于该功率视为静止/滑行,不计能量。 */
    private static final float MIN_DISCHARGE_W = 2f;
    /** GPS 段:时长/精度/位移的可用范围。 */
    private static final long MAX_FIX_GAP_MS = 5000L;
    private static final long MIN_FIX_GAP_MS = 200L;
    private static final float MAX_ACCURACY_M = 50f;
    private static final float MIN_SEGMENT_M = 0.5f;
    /** 路段平均速度低于该值视为没在骑(停车/推行),丢弃该段能量。无多普勒时兜底用。 */
    private static final float MIN_KMH = 1.5f;
    /** 路段平均速度超过该值明显异常(GPS 跳点),丢弃。 */
    private static final float MAX_KMH = 160f;
    /** 无功率且速度低于该值推断为步行;高于该值推断为滑行(下坡等),两者都不记录。 */
    private static final float WALK_MAX_KMH = 8f;
    /** 分类窗口:累计这么久才判一次状态,避免单段噪声把骑行误判成步行/滑行。 */
    private static final long WINDOW_MS = 4000L;
    /** 窗口平均功率达到该值才认定骑行(仪表/灯光待机电流远低于此,拧电门远高于此)。 */
    private static final float RIDE_MIN_W = 15f;
    /** 已处于骑行态时,窗口平均功率仍大于该值就继续骑行(迟滞,防抖)。 */
    private static final float RIDE_HOLD_W = 3f;
    /** 多普勒速度低于该值一律视为静止:滤掉位置漂移造成的假速度。 */
    private static final float STATIC_DOPPLER_KMH = 2.0f;
    /** 提前判静止所需的最短窗口。 */
    private static final long STATIC_WIN_MS = 2000L;
    /** 除窗口能量外,再参照最近这么长时间的放电功率,防 BMS 偶发断流误判。 */
    private static final long POWER_RECENT_MS = 6000L;

    /** 运动状态。 */
    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_IDLE = 1;
    public static final int MODE_RIDING = 2;
    public static final int MODE_WALKING = 3;
    public static final int MODE_COASTING = 4;

    /** 当前速度显示:最近一次定位超过这个时间就认为失效。 */
    private static final long CUR_SPEED_FRESH_MS = 3000L;
    /** 无 GPS 定位超过这个时间,暂存能量作废。 */
    private static final long PENDING_EXPIRE_MS = 6000L;

    private static final long UI_THROTTLE_MS = 800L;
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
        /** 当前 GPS 速度 km/h;<=0 表示无有效速度。 */
        public float curKmh = -1;
        public boolean gpsRunning;
        public boolean gpsFresh;
        /** 当前运动状态:MODE_*。 */
        public int mode = MODE_UNKNOWN;

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
    private final Handler main = new Handler(Looper.getMainLooper());
    private UiListener uiListener;

    private Location lastFix;
    private long lastFixAt;
    private long lastPowerAt;
    private float pendingWh;
    /** 分类窗口内累计的能量/位移/时长,攒够 WINDOW_MS 才判定一次。 */
    private float winWh;
    private double winDist;
    private long winMs;
    /** 窗口内多普勒速度(芯片直接测速,静止≈0)累计,精度远高于位置差分。 */
    private float winDopSum;
    private int winDopN;
    /** 最近功率环形缓冲:只用于状态判定,不参与能量统计。 */
    private static final int POWER_BUF = 12;
    private final long[] pwrT = new long[POWER_BUF];
    private final float[] pwrW = new float[POWER_BUF];
    private int pwrIdx;
    private long lastUiPostAt;
    private boolean dirty;
    private long lastSaveAt;
    private long lastWidgetAt;

    private EcoTracker(Context appContext) {
        this.ctx = appContext;
        this.gps = new RideGps(appContext, new RideGps.Callback() {
            @Override
            public void onFix(Location location) {
                onGpsFix(location);
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
        fireUi(true);
    }

    public Stats snapshot() {
        return snapshotLocked();
    }

    // ------------------------------------------------------------------
    // 能量:按 BMS 报文积分,先攒着等 GPS 路段来认领
    // ------------------------------------------------------------------

    @Override
    public void onBaseInfo(BmsProtocol.BaseInfo info) {
        if (info == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // 电流方向:负=放电。功率取放电功率。
        float watts = -info.current * info.totalVoltage;
        synchronized (lock) {
            pushPowerLocked(now, watts);
            long dt = lastPowerAt == 0 ? 0 : now - lastPowerAt;
            lastPowerAt = now;
            if (dt > MAX_POWER_DT_MS) {
                // 断流太久,这段不积
                pendingWh = 0f;
                return;
            }
            if (watts >= MIN_DISCHARGE_W && dt >= MIN_POWER_DT_MS) {
                pendingWh += watts * (dt / 1000f) / 3600f;
            }
            // 长时间没有 GPS 点,停止累积(避免能量越攒越多)
            if (lastFixAt == 0 || now - lastFixAt > PENDING_EXPIRE_MS) {
                pendingWh = 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // 里程:相邻定位点真实位移,连同该段能量归档到平均速度档
    // ------------------------------------------------------------------

    private void onGpsFix(Location loc) {
        long now = SystemClock.elapsedRealtime();
        boolean attributed = false;
        synchronized (lock) {
            float dop = loc.hasSpeed() ? loc.getSpeed() * 3.6f : -1f;
            stats.curKmh = dop;
            int newMode = stats.mode;
            if (lastFix != null) {
                long gap = now - lastFixAt;
                double dist = lastFix.distanceTo(loc);
                boolean accuracyOk = !loc.hasAccuracy() || loc.getAccuracy() <= MAX_ACCURACY_M;
                boolean lastAccOk = !lastFix.hasAccuracy() || lastFix.getAccuracy() <= MAX_ACCURACY_M;
                if (gap > MAX_FIX_GAP_MS) {
                    // 定位断档,能量与窗口都不可信
                    newMode = MODE_UNKNOWN;
                    pendingWh = 0f;
                    resetWinLocked();
                } else if (gap >= MIN_FIX_GAP_MS && accuracyOk && lastAccOk) {
                    // 能量并入窗口(不再按单段清零,单段功率噪声大)
                    winWh += pendingWh;
                    pendingWh = 0f;
                    winDist += dist;
                    winMs += gap;
                    if (dop >= 0) {
                        winDopSum += dop;
                        winDopN++;
                    }
                    float winKmh = (float) (winDist / (winMs / 1000.0) * 3.6);
                    float avgDop = winDopN > 0 ? winDopSum / winDopN : -1f;
                    // 判"是否在动"优先信芯片测速:静止漂移不会变成假速度
                    boolean stationary = avgDop >= 0 ? avgDop < STATIC_DOPPLER_KMH : winKmh < MIN_KMH;
                    if (!stationary && winKmh > MAX_KMH) {
                        // GPS 跳点:窗口作废,能量留给下个窗口
                        pendingWh += winWh;
                        resetWinLocked();
                    } else if (stationary && winMs >= STATIC_WIN_MS) {
                        // 真静止:原地耗电不属于任何速度档
                        newMode = MODE_IDLE;
                        resetWinLocked();
                    } else if (winMs >= WINDOW_MS) {
                        // 功率判定:窗口积分能量 + 最近几秒实时功率,谁大信谁,
                        // 这样 BMS 偶发断流(积分断档)不会误判成滑行
                        float winPowerW = Math.max(winWh * 3_600_000f / winMs, recentPowerWLocked(now));
                        boolean wasRiding = stats.mode == MODE_RIDING;
                        if (winPowerW >= (wasRiding ? RIDE_HOLD_W : RIDE_MIN_W)) {
                            // 有速度且有放电功率 = 骑行,整个窗口一起归档
                            newMode = MODE_RIDING;
                            float binKmh;
                            double kmAdd;
                            if (winDopN >= 2) {
                                // 有芯片测速:速度和里程都按多普勒来,位置漂移会污染档位
                                binKmh = avgDop;
                                kmAdd = avgDop * (winMs / 3_600_000.0);
                            } else {
                                binKmh = winKmh;
                                kmAdd = winDist / 1000.0;
                            }
                            int idx = binIndex(binKmh);
                            Bucket b = stats.bins[idx];
                            b.wh += winWh;
                            b.km += (float) kmAdd;
                            b.secs += winMs;
                            attributed = true;
                            recomputeBestLocked();
                            dirty = true;
                        } else {
                            // 没有明显驱动功率:低速=步行/推车,高速=滑行,都不记录
                            float moveKmh = winDopN > 0 ? avgDop : winKmh;
                            newMode = moveKmh <= WALK_MAX_KMH ? MODE_WALKING : MODE_COASTING;
                        }
                        resetWinLocked();
                    }
                }
                // 要么窗口尚未判定,要么 gap 太小/精度差:能量留在窗口,等下一段
            }
            lastFix = loc;
            lastFixAt = now;
            stats.gpsRunning = gps.isRunning();
            stats.gpsFresh = true;
            stats.mode = newMode;
        }
        // 小部件速度串流:5 秒刷一次,避免过于频繁
        if (now - lastWidgetAt > 5000L) {
            lastWidgetAt = now;
            float kmh;
            int mode;
            synchronized (lock) {
                kmh = (stats.curKmh >= STATIC_DOPPLER_KMH && stats.curKmh <= MAX_KMH)
                        ? stats.curKmh : -1f;
                mode = stats.mode;
            }
            WidgetState.saveSpeed(ctx, kmh, mode);
            BmsWidgetProvider.refreshAll(ctx);
        }
        fireUi(false);
        if (attributed) {
            maybeSave();
        }
    }

    private void pushPowerLocked(long now, float watts) {
        pwrW[pwrIdx] = watts > 0 ? watts : 0f;
        pwrT[pwrIdx] = now;
        pwrIdx = (pwrIdx + 1) % POWER_BUF;
    }

    /** 最近 POWER_RECENT_MS 内的放电功率均值;缓冲里没有数据返回 0。 */
    private float recentPowerWLocked(long now) {
        long from = now - POWER_RECENT_MS;
        float sum = 0f;
        int n = 0;
        for (int i = 0; i < POWER_BUF; i++) {
            long t = pwrT[i];
            if (t >= from && t <= now) {
                sum += pwrW[i];
                n++;
            }
        }
        return n > 0 ? sum / n : 0f;
    }

    private void resetWinLocked() {
        winWh = 0f;
        winDist = 0;
        winMs = 0;
        winDopSum = 0f;
        winDopN = 0;
    }

    private void fireUi(boolean force) {
        final Stats s = snapshotLocked();
        final UiListener l = uiListener;
        if (l == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastUiPostAt < UI_THROTTLE_MS) {
            return;
        }
        lastUiPostAt = now;
        main.post(new Runnable() {
            @Override
            public void run() {
                UiListener cur = uiListener;
                if (cur == l) {
                    cur.onEcoUpdated(s);
                }
            }
        });
    }

    private void recomputeBestLocked() {
        int best = -1;
        float bestWhPerKm = Float.MAX_VALUE;
        int trusted = 0;
        for (int i = 0; i < BIN_COUNT; i++) {
            Bucket b = stats.bins[i];
            // 每档要跑过一定距离/时长才可信
            if (b.km >= 0.15f && b.secs >= 20_000L) {
                trusted++;
                float v = b.wh / b.km;
                if (v < bestWhPerKm) {
                    bestWhPerKm = v;
                    best = i;
                }
            }
        }
        // 至少两档有可信数据才有对比意义
        stats.bestBin = trusted >= 2 ? best : -1;
    }

    /** 0-5,5-10...100+。 */
    private static int binIndex(float kmh) {
        int idx = (int) (kmh / BIN_KMH);
        return idx < 0 ? 0 : Math.min(idx, BIN_COUNT - 1);
    }

    static String binLabel(int i) {
        int lo = i * BIN_KMH;
        return i == BIN_COUNT - 1 ? lo + "+km/h" : lo + "-" + (lo + BIN_KMH) + "km/h";
    }

    static String modeLabel(int mode) {
        switch (mode) {
            case MODE_RIDING:
                return "骑行中(统计中)";
            case MODE_WALKING:
                return "步行中(不记录)";
            case MODE_COASTING:
                return "滑行(不记录)";
            case MODE_IDLE:
                return "静止";
            default:
                return "等待GPS定位";
        }
    }

    /** 小部件用的短文案。 */
    static String modeLabelShort(int mode) {
        switch (mode) {
            case MODE_RIDING:
                return "骑行";
            case MODE_WALKING:
                return "步行";
            case MODE_COASTING:
                return "滑行";
            case MODE_IDLE:
                return "静止";
            default:
                return "--";
        }
    }

    public void reset() {
        synchronized (lock) {
            for (Bucket b : stats.bins) {
                b.wh = 0;
                b.km = 0;
                b.secs = 0;
            }
            stats.bestBin = -1;
            pendingWh = 0f;
            lastPowerAt = 0;
        }
        fireUi(true);
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
            need = dirty && (SystemClock.elapsedRealtime() - lastSaveAt > SAVE_INTERVAL_MS);
            if (need) {
                dirty = false;
                lastSaveAt = SystemClock.elapsedRealtime();
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
            root.put("ver", FILE_VER);
            File f = file();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            synchronized (lock) {
                dirty = false;
                lastSaveAt = SystemClock.elapsedRealtime();
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
            // 档位布局/口径变更后旧数据不可比,直接弃用
            if (root.optInt("ver", 0) != FILE_VER) {
                return;
            }
            JSONArray arr = root.optJSONArray("bins");
            if (arr == null || arr.length() != BIN_COUNT) {
                return;
            }
            synchronized (lock) {
                for (int i = 0; i < BIN_COUNT; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Bucket b = stats.bins[i];
                    b.wh = (float) o.optDouble("wh", 0);
                    b.km = (float) o.optDouble("km", 0);
                    b.secs = o.optLong("s", 0);
                }
                recomputeBestLocked();
            }
        } catch (Exception ignored) {
            // 损坏文件当没存过
        }
    }

    private Stats snapshotLocked() {
        Stats s = new Stats();
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            long totalSecs = 0;
            float totalKm = 0f;
            for (int i = 0; i < BIN_COUNT; i++) {
                s.bins[i].wh = stats.bins[i].wh;
                s.bins[i].km = stats.bins[i].km;
                s.bins[i].secs = stats.bins[i].secs;
                totalSecs += stats.bins[i].secs;
                totalKm += stats.bins[i].km;
            }
            s.bestBin = stats.bestBin;
            s.totalSecs = totalSecs;
            s.totalKm = totalKm;
            s.gpsRunning = gps.isRunning();
            s.gpsFresh = lastFixAt != 0 && now - lastFixAt <= CUR_SPEED_FRESH_MS;
            s.curKmh = (s.gpsFresh && stats.curKmh >= STATIC_DOPPLER_KMH && stats.curKmh <= MAX_KMH)
                    ? stats.curKmh : -1;
            s.mode = s.gpsFresh ? stats.mode : MODE_UNKNOWN;
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
