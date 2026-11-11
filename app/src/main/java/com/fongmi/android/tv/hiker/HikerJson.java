package com.fongmi.android.tv.hiker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化 —— 阶段八「补桥方法」的基础件。
 *
 * 为什么自己写：Java 桥 HikerBridge 只依赖 android.webkit.JavascriptInterface，
 * 业务实现层 HikerApi 刻意零 Android 依赖（可用 javac 单独编译与单测）。
 * 若用 org.json 则依赖 Android 运行时，纯 JDK 验证链路就断了。
 *
 * 同时解决一个真实的兼容问题：addJavascriptInterface 把 JS 数组/对象隐式转成
 * String 时结果不可靠（"[object Object]"）。因此 JS 侧 bridgeCall 统一用
 * JSON.stringify(args) 传参，Java 侧用本类显式还原。
 *
 * 语法：Java 8（与 build_verify.sh 的 ecj -8 一致），不依赖任何第三方库。
 */
public final class HikerJson {

    private HikerJson() {
    }

    /** 解析失败返回 null；支持 object / array / string / number / true / false / null。 */
    public static Object parse(String s) {
        if (s == null) return null;
        try {
            P p = new P(s);
            Object v = p.value();
            p.ws();
            return p.end() ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把任意参数数组解析成 List；非数组时包成单元素 List，失败返回空 List。 */
    @SuppressWarnings("unchecked")
    public static List<Object> args(String s) {
        Object o = parse(s);
        List<Object> out = new ArrayList<>();
        if (o instanceof List) return (List<Object>) o;
        if (o != null) out.add(o);
        return out;
    }

    /** 序列化：Map/List/String/Number/Boolean/null；其它按字符串处理。 */
    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    /** 任意值转字符串：字符串原样，容器转 JSON，数字去掉多余 .0，null 转空串。 */
    public static String str(Object o) {
        if (o == null) return "";
        if (o instanceof String) return (String) o;
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        if (o instanceof Boolean) return String.valueOf(o);
        if (o instanceof Map || o instanceof List) return stringify(o);
        return String.valueOf(o);
    }

    /** 取参数列表第 i 项的字符串，越界/缺失返回默认值。 */
    public static String s(List<Object> a, int i, String def) {
        if (a == null || i < 0 || i >= a.size()) return def;
        Object v = a.get(i);
        if (v == null) return def;
        return str(v);
    }

    /** 取第 i 项的 Map（对象），否则返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(List<Object> a, int i) {
        if (a == null || i < 0 || i >= a.size()) return null;
        Object v = a.get(i);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    /** 取第 i 项的 List；若该项是字符串且像 JSON 数组，也尝试解析。 */
    @SuppressWarnings("unchecked")
    public static List<Object> list(List<Object> a, int i) {
        if (a == null || i < 0 || i >= a.size()) return new ArrayList<>();
        Object v = a.get(i);
        if (v instanceof List) return (List<Object>) v;
        if (v instanceof String) {
            Object p = parse((String) v);
            if (p instanceof List) return (List<Object>) p;
            List<Object> one = new ArrayList<>();
            if (p != null) one.add(p);
            return one;
        }
        List<Object> one = new ArrayList<>();
        if (v != null) one.add(v);
        return one;
    }

    /* ---------------- Base64（自建，兼容 Android API 24；不用 java.util.Base64） ---------------- */

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String b64Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < data.length) ? (data[i + 2] & 0xFF) : 0;
            sb.append(B64[b0 >>> 2]);
            sb.append(B64[((b0 & 3) << 4) | (b1 >>> 4)]);
            sb.append(i + 1 < data.length ? B64[((b1 & 15) << 2) | (b2 >>> 6)] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }

    public static byte[] b64Decode(String s) {
        String clean = s == null ? "" : s.replaceAll("\\s", "");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int[] acc = new int[4];
        int idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '=') break;
            int v = c >= 'A' && c <= 'Z' ? c - 'A'
                  : c >= 'a' && c <= 'z' ? c - 'a' + 26
                  : c >= '0' && c <= '9' ? c - '0' + 52
                  : c == '+' ? 62 : c == '/' ? 63 : -1;
            if (v < 0) continue;
            acc[idx++] = v;
            if (idx == 4) {
                bos.write((acc[0] << 2) | (acc[1] >>> 4));
                bos.write((acc[1] << 4) | (acc[2] >>> 2));
                bos.write((acc[2] << 6) | acc[3]);
                idx = 0;
            }
        }
        if (idx >= 2) {
            bos.write((acc[0] << 2) | (acc[1] >>> 4));
            if (idx >= 3) bos.write((acc[1] << 4) | (acc[2] >>> 2));
        }
        return bos.toByteArray();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quote(sb, (String) o); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(String.valueOf(o)); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
            return;
        }
        quote(sb, String.valueOf(o));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** 递归下降解析器。 */
    private static final class P {
        private final String s;
        private int i;

        P(String s) { this.s = s; this.i = 0; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        boolean end() { return i >= s.length(); }

        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') break;
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        private List<Object> array() {
            ArrayList<Object> a = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (i < s.length()) {
                a.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return a;
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // 开引号
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                            catch (Throwable t) { b.append('?'); }
                            i += 4;
                        }
                        break;
                    default: b.append(e);
                }
            }
            return b.toString();
        }

        private Object number() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            if (i == st) { i++; return null; }
            String n = s.substring(st, i);
            try {
                if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.parseLong(n);
                return Double.parseDouble(n);
            } catch (Throwable t) {
                return n;
            }
        }
    }
}
