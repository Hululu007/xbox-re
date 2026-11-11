package com.fongmi.android.tv.hiker;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 二维码编码（ISO/IEC 18004）—— createQRCode() 的真实现，零第三方依赖。
 *
 * 为什么不用 ZXing：本模块坚持「零耦合 + 可用 ecj/android-all 单独编译」，
 * 引第三方 jar 会破坏该属性，也会让宿主 App 多一份依赖冲突风险。
 * 这里实现 Byte 模式 / ECC-M / version 1~10 的完整编码链：
 *   位流 → RS 纠错 → 分块交织 → 功能图形 → 格式/版本信息 → 数据掩码 → SVG。
 *
 * 正确性保证：RS 块表、对齐图案位置、BCH 常量与 qrcode(python) 官方表逐项对齐，
 * verify_bridge_api.py 会把 Java 矩阵与 Python qrcode 同 version/同 mask 的矩阵
 * 逐格对比（见阶段八验证报告）。超过 version 10 容量时返回空串并留痕，不伪造图片。
 *
 * 语法：Java 8，纯 JDK。
 */
public final class HikerQR {

    private static final int G15 = 0x537;
    private static final int G18 = 0x1F25;
    private static final int G15_MASK = 0x5412;
    private static final int PAD0 = 0xEC;
    private static final int PAD1 = 0x11;
    private static final int MODE_BYTE = 4;

    /** ECC-M 各版本的 RS 块：{块数, 每块总码字, 每块数据码字}（与 qrcode.base.rs_blocks 一致）。 */
    private static final int[][][] RS = {
            {{1, 26, 16}},                                  // v1
            {{1, 44, 28}},                                  // v2
            {{1, 70, 44}},                                  // v3
            {{2, 50, 32}},                                  // v4
            {{2, 67, 43}},                                  // v5
            {{4, 43, 27}},                                  // v6
            {{4, 49, 31}},                                  // v7
            {{2, 60, 38}, {2, 61, 39}},                    // v8
            {{3, 58, 36}, {2, 59, 37}},                    // v9
            {{4, 69, 43}, {1, 70, 44}}                     // v10
    };

    /** 对齐图案中心坐标（PATTERN_POSITION_TABLE v1~10）。 */
    private static final int[][] ALIGN = {
            {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}
    };

    private HikerQR() {
    }

    /** 生成 data URI（SVG 二维码），失败返回空串。scale 为每模块像素，默认 4。 */
    public static String dataUri(String text, int scale, int mask) {
        boolean[][] m = matrix(text, mask);
        if (m == null) return "";
        if (scale < 1) scale = 4;
        int border = 4;
        int n = m.length + border * 2;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(n * scale)
          .append("\" height=\"").append(n * scale).append("\" viewBox=\"0 0 ").append(n)
          .append(' ').append(n).append("\" shape-rendering=\"crispEdges\">")
          .append("<rect width=\"").append(n).append("\" height=\"").append(n)
          .append("\" fill=\"#fff\"/>");
        for (int r = 0; r < m.length; r++) {
            for (int c = 0; c < m.length; c++) {
                if (m[r][c]) sb.append("<rect x=\"").append(c + border)
                  .append("\" y=\"").append(r + border)
                  .append("\" width=\"1\" height=\"1\" fill=\"#000\"/>");
            }
        }
        sb.append("</svg>");
        String b64;
        try {
            b64 = HikerJson.b64Encode(sb.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            return "";
        }
        /* data URI 里的 + / = 显式百分号转义，避免 WebView / URL 解析歧义 */
        b64 = b64.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D");
        return "data:image/svg+xml;base64," + b64;
    }

    /** 生成核心模块矩阵（不含 quiet zone），null 表示超出容量。供验证脚本逐格比对。 */
    public static boolean[][] matrix(String text, int mask) {
        if (text == null) text = "";
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Throwable t) { data = text.getBytes(); }
        int version = chooseVersion(data.length);
        if (version < 0) return null;
        int[] cw = createData(version, data);
        return makeMatrix(version, mask, cw);
    }

    /** 矩阵转 0/1 行串（无 quiet zone），便于 Python 侧断言。 */
    public static String matrixString(boolean[][] m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < m.length; r++) {
            for (int c = 0; c < m.length; c++) sb.append(m[r][c] ? '1' : '0');
            if (r + 1 < m.length) sb.append('\n');
        }
        return sb.toString();
    }

    /** 选择能容纳数据的最小 version（1~10），-1 表示超容量。 */
    private static int chooseVersion(int byteLen) {
        for (int v = 1; v <= 10; v++) {
            int cc = (v < 10) ? 8 : 16;
            long need = 4L + cc + 8L * byteLen;
            long limit = 0;
            for (int[] b : RS[v - 1]) limit += 8L * b[0] * b[2];
            if (need <= limit) return v;
        }
        return -1;
    }

    /* ------------------------------------------------------------ 位流 + RS 纠错 */

    private static final class Bits {
        private int[] buf = new int[64];
        private int len;   // 位数

        void put(int num, int length) {
            for (int i = 0; i < length; i++) putBit(((num >>> (length - 1 - i)) & 1) == 1);
        }

        void putBit(boolean bit) {
            int idx = len / 8;
            if (idx >= buf.length) buf = Arrays.copyOf(buf, idx * 2 + 16);
            if (bit) buf[idx] |= 0x80 >>> (len % 8);
            len++;
        }
    }

    private static int[] createData(int version, byte[] data) {
        Bits b = new Bits();
        b.put(MODE_BYTE, 4);
        b.put(data.length, (version < 10) ? 8 : 16);
        for (byte x : data) b.put(x & 0xFF, 8);

        int bitLimit = 0;
        List<int[]> blocks = expandBlocks(version);
        for (int[] blk : blocks) bitLimit += 8 * blk[2];
        for (int i = 0; i < Math.min(bitLimit - b.len, 4); i++) b.putBit(false);
        int delimit = b.len % 8;
        if (delimit != 0) for (int i = 0; i < 8 - delimit; i++) b.putBit(false);
        int fill = (bitLimit - b.len) / 8;
        for (int i = 0; i < fill; i++) b.put((i % 2 == 0) ? PAD0 : PAD1, 8);

        /* 按块切分 → 每块算 EC → 交织 */
        List<int[]> dc = new ArrayList<>();
        List<int[]> ec = new ArrayList<>();
        int off = 0;
        for (int[] blk : blocks) {
            int dcCount = blk[2], ecCount = blk[1] - blk[2];
            int[] d = new int[dcCount];
            for (int i = 0; i < dcCount; i++) d[i] = b.buf[off + i] & 0xFF;
            off += dcCount;
            dc.add(d);
            ec.add(remainder(d, divisor(ecCount)));
        }
        int maxDc = 0, maxEc = 0;
        for (int[] blk : blocks) { maxDc = Math.max(maxDc, blk[2]); maxEc = Math.max(maxEc, blk[1] - blk[2]); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < maxDc; i++) for (int[] d : dc) if (i < d.length) out.write(d[i]);
        for (int i = 0; i < maxEc; i++) for (int[] e : ec) if (i < e.length) out.write(e[i]);
        byte[] raw = out.toByteArray();
        int[] cw = new int[raw.length];
        for (int i = 0; i < raw.length; i++) cw[i] = raw[i] & 0xFF;
        return cw;
    }

    /** 把 {块数,total,data} 展开成逐块数组。 */
    private static List<int[]> expandBlocks(int version) {
        List<int[]> out = new ArrayList<>();
        for (int[] g : RS[version - 1]) {
            for (int i = 0; i < g[0]; i++) out.add(new int[]{g[1], g[1], g[2]});
        }
        return out;
    }

    /* GF(256)，本原多项式 0x11D，生成元 2 —— 标准 RS，与 qrcode.js / ZXing 一致 */
    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static int[] divisor(int degree) {
        int[] r = new int[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = gfMul(r[j], root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static int[] remainder(int[] data, int[] div) {
        int[] res = new int[div.length];
        for (int d : data) {
            int factor = d ^ res[0];
            System.arraycopy(res, 1, res, 0, res.length - 1);
            res[res.length - 1] = 0;
            for (int i = 0; i < res.length; i++) res[i] ^= gfMul(div[i], factor);
        }
        return res;
    }

    /* ------------------------------------------------------------ 矩阵构造 */

    private static boolean[][] makeMatrix(int version, int mask, int[] data) {
        int size = version * 4 + 17;
        Boolean[][] m = new Boolean[size][size];

        probe(m, 0, 0);
        probe(m, size - 7, 0);
        probe(m, 0, size - 7);
        adjust(m, version);
        timing(m);
        typeInfo(m, mask);
        if (version >= 7) typeNumber(m, version);
        mapData(m, data, mask);

        boolean[][] out = new boolean[size][size];
        for (int r = 0; r < size; r++) for (int c = 0; c < size; c++) out[r][c] = Boolean.TRUE.equals(m[r][c]);
        return out;
    }

    private static void probe(Boolean[][] m, int row, int col) {
        int n = m.length;
        for (int r = -1; r <= 7; r++) {
            if (row + r < 0 || row + r >= n) continue;
            for (int c = -1; c <= 7; c++) {
                if (col + c < 0 || col + c >= n) continue;
                boolean dark = (r >= 0 && r <= 6 && (c == 0 || c == 6))
                        || (c >= 0 && c <= 6 && (r == 0 || r == 6))
                        || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
                m[row + r][col + c] = dark;
            }
        }
    }

    private static void adjust(Boolean[][] m, int version) {
        int[] pos = ALIGN[version - 1];
        for (int i = 0; i < pos.length; i++) {
            int row = pos[i];
            for (int j = 0; j < pos.length; j++) {
                int col = pos[j];
                if (m[row][col] != null) continue;
                for (int r = -2; r <= 2; r++) for (int c = -2; c <= 2; c++) {
                    boolean dark = (r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0));
                    m[row + r][col + c] = dark;
                }
            }
        }
    }

    private static void timing(Boolean[][] m) {
        int n = m.length;
        for (int r = 8; r < n - 8; r++) if (m[r][6] == null) m[r][6] = (r % 2 == 0);
        for (int c = 8; c < n - 8; c++) if (m[6][c] == null) m[6][c] = (c % 2 == 0);
    }

    private static void typeInfo(Boolean[][] m, int mask) {
        int n = m.length;
        int data = (0 << 3) | mask;   // ECC-M 的格式信息等级位为 00
        int bits = bchTypeInfo(data);
        for (int i = 0; i < 15; i++) {
            boolean mod = ((bits >>> i) & 1) == 1;
            if (i < 6) m[i][8] = mod;
            else if (i < 8) m[i + 1][8] = mod;
            else m[n - 15 + i][8] = mod;
        }
        for (int i = 0; i < 15; i++) {
            boolean mod = ((bits >>> i) & 1) == 1;
            if (i < 8) m[8][n - i - 1] = mod;
            else if (i < 9) m[8][15 - i] = mod;
            else m[8][15 - i - 1] = mod;
        }
        m[n - 8][8] = true;   // dark module
    }

    private static void typeNumber(Boolean[][] m, int version) {
        int n = m.length;
        int bits = bchTypeNumber(version);
        for (int i = 0; i < 18; i++) {
            boolean mod = ((bits >>> i) & 1) == 1;
            m[i / 3][i % 3 + n - 8 - 3] = mod;
        }
        for (int i = 0; i < 18; i++) {
            boolean mod = ((bits >>> i) & 1) == 1;
            m[i % 3 + n - 8 - 3][i / 3] = mod;
        }
    }

    private static void mapData(Boolean[][] m, int[] data, int mask) {
        int n = m.length, inc = -1, row = n - 1, bitIndex = 7, byteIndex = 0;
        for (int col = n - 1; col > 0; col -= 2) {
            /* 注意：Python 参考实现里 `for col in range(...)` 的变量被内部赋值不影响迭代序列；
             * Java 若直接改 col 会让 col -= 2 基于已改的值，从而漏掉一列（曾导致数据区错位）。
             * 因此用局部 c0 承载「遇到 timing 列左移一列」的效果，循环变量 col 保持不变。 */
            int c0 = (col <= 6) ? col - 1 : col;
            while (true) {
                for (int c = c0; c >= c0 - 1; c--) {
                    if (m[row][c] != null) continue;
                    boolean dark = false;
                    if (byteIndex < data.length) dark = ((data[byteIndex] >>> bitIndex) & 1) == 1;
                    if (maskBit(mask, row, c)) dark = !dark;
                    m[row][c] = dark;
                    bitIndex--;
                    if (bitIndex == -1) { byteIndex++; bitIndex = 7; }
                }
                row += inc;
                if (row < 0 || row >= n) { row -= inc; inc = -inc; break; }
            }
        }
    }

    private static boolean maskBit(int p, int i, int j) {
        switch (p) {
            case 0: return (i + j) % 2 == 0;
            case 1: return i % 2 == 0;
            case 2: return j % 3 == 0;
            case 3: return (i + j) % 3 == 0;
            case 4: return (i / 2 + j / 3) % 2 == 0;
            case 5: return (i * j) % 2 + (i * j) % 3 == 0;
            case 6: return ((i * j) % 2 + (i * j) % 3) % 2 == 0;
            case 7: return ((i * j) % 3 + (i + j) % 2) % 2 == 0;
            default: return false;
        }
    }

    private static int bchDigit(int d) {
        int n = 0;
        while (d != 0) { n++; d >>>= 1; }
        return n;
    }

    private static int bchTypeInfo(int data) {
        int d = data << 10;
        while (bchDigit(d) - bchDigit(G15) >= 0) d ^= G15 << (bchDigit(d) - bchDigit(G15));
        return ((data << 10) | d) ^ G15_MASK;
    }

    private static int bchTypeNumber(int data) {
        int d = data << 12;
        while (bchDigit(d) - bchDigit(G18) >= 0) d ^= G18 << (bchDigit(d) - bchDigit(G18));
        return (data << 12) | d;
    }
}
