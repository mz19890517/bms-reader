package com.ebike.bmsreader;

import java.util.ArrayList;
import java.util.List;

/**
 * 小象电动 BMS 蓝牙私有协议。
 *
 * 帧格式(与 com.jiabaida.little_elephant.entity.BMSCommandEntity 一致):
 *   DD | mode | cmd | len | content[len] | chkHi | chkLo | 77
 *   0    1     2     3    4..            +4+len  +5+len  +6+len
 *
 * 校验和 = (~(sum(content) + len + cmd)) & 0xFFFF ,高字节在前。
 * 请求 mode: 0xA5 读 / 0x5A 写 ;响应 [1] 未知,[2] 为状态码(0 = 成功)。
 */
public final class BmsProtocol {

    public static final byte FRAME_START = (byte) 0xDD;
    public static final byte FRAME_END = (byte) 0x77;
    public static final byte MODE_READ = (byte) 0xA5;
    public static final byte MODE_WRITE = (byte) 0x5A;

    /** 实时总信息(总压/电流/SOC/温度...) */
    public static final byte CMD_BASE_INFO = 0x03;
    /** 各电芯单体电压 */
    public static final byte CMD_CELL_VOLTAGE = 0x04;

    private BmsProtocol() {
    }

    /** 组装一帧请求。 */
    public static byte[] buildRequest(byte cmd, byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        byte[] frame = new byte[body.length + 7];
        frame[0] = FRAME_START;
        frame[1] = MODE_READ;
        frame[2] = cmd;
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        int sum = body.length + (cmd & 0xFF);
        for (byte b : body) {
            sum += b & 0xFF;
        }
        int checksum = (~sum + 1) & 0xFFFF;
        frame[4 + body.length] = (byte) (checksum >>> 8);
        frame[5 + body.length] = (byte) checksum;
        frame[6 + body.length] = FRAME_END;
        return frame;
    }

    public static int checksum(byte cmdOrStatus, int length, byte[] content) {
        int sum = length + (cmdOrStatus & 0xFF);
        for (byte b : content) {
            sum += b & 0xFF;
        }
        return (~sum + 1) & 0xFFFF;
    }

    public static boolean verifyChecksum(byte[] frame) {
        int len = frame[3] & 0xFF;
        if (frame.length < len + 7) {
            return false;
        }
        int sum = len + (frame[2] & 0xFF);
        for (int i = 0; i < len; i++) {
            sum += frame[4 + i] & 0xFF;
        }
        int expected = (~sum + 1) & 0xFFFF;
        return frame[frame.length - 3] == (byte) (expected >>> 8)
                && frame[frame.length - 2] == (byte) expected;
    }

    /** 解析出的完整响应帧。 */
    public static final class Frame {
        public final int status;
        public final byte[] content;
        public final byte[] raw;

        Frame(int status, byte[] content, byte[] raw) {
            this.status = status;
            this.content = content;
            this.raw = raw;
        }

        public boolean isOk() {
            return status == 0;
        }
    }

    /** 分帧结果:完整帧列表 + 已消费的字节数。 */
    public static final class ExtractResult {
        public final List<Frame> frames;
        public final int consumed;

        ExtractResult(List<Frame> frames, int consumed) {
            this.frames = frames;
            this.consumed = consumed;
        }
    }

    /**
     * 从字节流的 [0,length) 中切出所有完整帧,返回结果并报告消费位置。
     * BLE 分包会截断一帧,未成帧的尾巴不消费,留给下次数据拼接。
     */
    public static ExtractResult extractFrames(byte[] data, int length) {
        List<Frame> out = new ArrayList<>();
        int i = 0;
        while (i < length) {
            if (data[i] != FRAME_START) {
                i++;
                continue;
            }
            if (length - i < 4) {
                break;
            }
            int total = (data[i + 3] & 0xFF) + 7;
            if (length - i < total) {
                break;
            }
            byte[] frame = new byte[total];
            System.arraycopy(data, i, frame, 0, total);
            if (frame[total - 1] == FRAME_END && verifyChecksum(frame)) {
                int len = frame[3] & 0xFF;
                byte[] content = new byte[len];
                System.arraycopy(frame, 4, content, 0, len);
                out.add(new Frame(frame[2] & 0xFF, content, frame));
                i += total;
            } else {
                i++;
            }
        }
        return new ExtractResult(out, i);
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // cmd 0x03 实时信息
    // ------------------------------------------------------------------

    public static final class BaseInfo {
        public float totalVoltage;
        public float current;
        public float remainPower;
        public float nominalPower;
        public int cycles;
        public int productYear, productMonth, productDay;
        public int balanceBits;
        public int protectionBits;
        public int firmwareVersion;
        public int soc;
        public int fetState;
        public int cellCount;
        public int ntcCount;
        public final List<Float> temperatures = new ArrayList<>();
        public final List<Float> cellVoltages = new ArrayList<>();
        public int humidity = -1;
        public int altitude;
        public float learnedCapacity;
        public float balanceCurrent;
        public boolean extended;

        public boolean isCharging() {
            return (fetState & 0x01) != 0;
        }

        public boolean isDischarging() {
            return (fetState & 0x02) != 0;
        }

        /** 实际充放电方向以电流符号为准(负=放电),电流为 0 时退回 MOS 状态。 */
        public boolean isFlowingOut() {
            if (current < -0.05f) {
                return true;
            }
            if (current > 0.05f) {
                return false;
            }
            return isDischarging() && !isCharging();
        }

        public float maxCell() {
            float m = 0f;
            for (float v : cellVoltages) {
                m = Math.max(m, v);
            }
            return m;
        }

        public float minCell() {
            float m = Float.MAX_VALUE;
            for (float v : cellVoltages) {
                m = Math.min(m, v);
            }
            return cellVoltages.isEmpty() ? 0f : m;
        }
    }

    public static final String[] PROTECTION_NAMES = {
            "单体过压", "单体欠压", "总压过压", "总压欠压",
            "充电过温", "充电低温", "放电过温", "放电低温",
            "充电过流", "放电过流", "DSG保护", "IC故障", "软件锁MOS"
    };

    public static BaseInfo parseBaseInfo(byte[] c) {
        if (c.length < 23) {
            throw new IllegalArgumentException("基础信息过短: " + c.length);
        }
        BaseInfo info = new BaseInfo();
        info.totalVoltage = u16(c, 0) / 100f;
        // 电流是带符号的:放电时高位为 1,原 App 靠 (content[2] << 8) 的符号扩展拿到负数
        info.current = s16(c, 2) / 100f;
        info.remainPower = u16(c, 4) / 100f;
        info.nominalPower = u16(c, 6) / 100f;
        info.cycles = u16(c, 8);

        int date = u16(c, 10);
        info.productYear = (date >> 9) + 2000;
        info.productMonth = (date >> 5) & 0x0F;
        info.productDay = date & 0x1F;

        info.balanceBits = (c[12] & 0xFF) | ((c[13] & 0xFF) << 8)
                | ((c[14] & 0xFF) << 16) | ((c[15] & 0xFF) << 24);
        info.protectionBits = (c[16] & 0xFF) | ((c[17] & 0xFF) << 8);
        info.firmwareVersion = c[18] & 0xFF;
        info.soc = c[19] & 0xFF;
        info.fetState = c[20] & 0xFF;
        info.cellCount = c[21] & 0xFF;
        info.ntcCount = c[22] & 0xFF;

        for (int i = 0; i < info.ntcCount; i++) {
            int idx = 23 + i * 2;
            if (idx + 1 >= c.length) {
                break;
            }
            float t = (u16(c, idx) - 2731) / 10f;
            // 明显不合理的温度直接丢弃,避免显示乱码
            if (t > -60f && t < 200f) {
                info.temperatures.add(t);
            }
        }

        // 原 App 的扩展块基址是 ntcCount*2 + 22(不是 23),整体偏移少 1 字节,
        // 对齐它才能正确读出湿度/海拔/学习容量/平衡电流。
        int tail = info.ntcCount * 2 + 22;
        if (c.length > tail + 5) {
            info.extended = true;
            info.humidity = c[tail + 1] & 0xFF;
            info.altitude = u16(c, tail + 2);
            info.learnedCapacity = u16(c, tail + 4) / 100f;
            info.balanceCurrent = u16(c, tail + 6) / 100f;
            if (info.humidity == 136) {
                info.current = s16(c, 2) / 10f;
                info.remainPower = u16(c, 4) / 10f;
            }
        }
        return info;
    }

    /** 提取保护状态名称。 */
    public static List<String> parseProtection(int bits) {
        List<String> names = new ArrayList<>();
        // 低字节对应前 8 项,高字节对应后 5 项
        for (int i = 0; i < 8; i++) {
            if (((bits >>> i) & 1) != 0) {
                names.add(PROTECTION_NAMES[i]);
            }
        }
        for (int i = 8; i < PROTECTION_NAMES.length; i++) {
            if (((bits >>> (i - 8)) & 1) != 0) {
                names.add(PROTECTION_NAMES[i]);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // cmd 0x04 单体电压
    // ------------------------------------------------------------------

    public static List<Float> parseCellVoltages(byte[] c) {
        List<Float> out = new ArrayList<>();
        for (int i = 0; i + 1 < c.length; i += 2) {
            out.add(u16(c, i) / 1000f);
        }
        return out;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int s16(byte[] b, int i) {
        return (short) (((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF));
    }
}
