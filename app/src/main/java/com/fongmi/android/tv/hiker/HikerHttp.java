package com.fongmi.android.tv.hiker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 同步 HTTP —— 在 JavaBridge 线程上执行，返回「字符串」，不是 Promise。
 *
 * 为什么不用宿主 HomeWebBridge / fm.req：
 *   oktv-cat 的 invoke() 走 Task.execute 线程池 + fongmiNative.resolve 回调，是异步；
 *   海阔规则形如 eval.call(null, fetch(url)) / pdfh(fetch(url), ...)，要求同步拿到正文。
 *   阶段二（hiker_54.db / home_js）与阶段四（v390 JsReqBridge）都证实必须同步。
 *
 * 本类刻意只依赖 JDK（HttpURLConnection），不 import 任何 com.fongmi.android.tv.* 业务类，
 * 因此可以用 ecj + android-all.jar 单独真实编译，作为「构建前置」的编译验证对象。
 */
public final class HikerHttp {

    private static final int TIMEOUT = 15000;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private HikerHttp() {
    }

    /** 同步 GET，返回响应正文字符串（失败返回空串，绝不返回 Promise / 对象）。 */
    public static String get(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", HikerContract.UA);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null)
                        conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            return read(conn);
        } catch (Throwable t) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 同步 POST（表单或 JSON 字符串 body）。 */
    public static String post(String url, String body, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", HikerContract.UA);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null)
                        conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            OutputStream os = conn.getOutputStream();
            if (body != null) os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            return read(conn);
        } catch (Throwable t) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BODY) break;
        }
        in.close();
        byte[] bytes = bos.toByteArray();
        Charset cs = detect(conn.getContentType(), bytes);
        return new String(bytes, cs);
    }

    /** 编码探测：海阔站源大量 GBK，Content-Type 未声明时按 UTF-8 合法性回退 GBK。 */
    private static Charset detect(String contentType, byte[] bytes) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("gbk") || ct.contains("gb2312") || ct.contains("gb18030")) {
            return forName("GBK");
        }
        if (ct.contains("utf-8") || ct.contains("utf8")) return forName("UTF-8");
        if (isUtf8(bytes)) return forName("UTF-8");
        return forName("GBK");
    }

    private static Charset forName(String name) {
        try {
            return Charset.forName(name);
        } catch (Throwable t) {
            return Charset.defaultCharset();
        }
    }

    private static boolean isUtf8(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (c < 0x80) { i++; continue; }
            int need = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC0 ? 1 : -1;
            if (need < 0 || i + need >= b.length) return false;
            for (int k = 1; k <= need; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
            i += need + 1;
        }
        return true;
    }
}
