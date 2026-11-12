package com.fongmi.android.tv.hiker;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

/**
 * 海阔官方 bridge 档 API 的真语义实现（阶段八 · 补桥方法）。
 *
 * 背景：阶段七让 134 个官方 API「符号可见」，但其中 25 个 bridge 档只做 JS 转发，
 * Java 侧 HikerBridge 当时只有 13 个方法，这 25 个调用全部落到空串并留痕。
 * 本类把它们逐项实现，且刻意零 Android 依赖（只用 JDK + 同包 HikerHttp/HikerJson/HikerQR），
 * 因此可以用 javac 单独编译、用 Java 驱动逐项断言，不必等真机。
 *
 * 调用契约：HikerBridge.call(name, argsJson) -> String
 *   - argsJson 由 JS 侧 bridgeCall 用 JSON.stringify(args) 生成，显式还原参数，
 *     绕开 addJavascriptInterface 对 JS 数组/对象隐式转 String 的不可靠行为。
 *   - 返回值统一 String；结构化结果用 JSON 字符串（JS 侧按需解析）。
 *   - 不静默假成功：内核依赖 / 超容量 / 异常都会记入 report()，返回空串或明确标记。
 *
 * 语法：Java 8（与 build_verify.sh 的 ecj -8 一致）。
 */
public final class HikerApi {

    /* ------------------------------------------------------------ 状态（进程级单例） */
    private static final Map<String, Map<String, String>> COOKIES = new HashMap<>();
    private static final Map<String, CacheEntry> CACHE = new HashMap<>();
    private static final Map<String, byte[]> FILES = new HashMap<>();
    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<String>());
    /* daemon 线程：App 常驻无影响，但纯 JDK 单测/探针跑完后 JVM 必须能正常退出。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4,
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "hiker-api-pool");
                    t.setDaemon(true);
                    return t;
                }
            });
    private static volatile String ROOT = null;
    private static volatile long DEFAULT_TTL = 10 * 60 * 1000L;

    private static final class CacheEntry {
        final String body;
        final long time;
        CacheEntry(String b, long t) { body = b; time = t; }
    }

    private HikerApi() {
    }

    /** 由 HikerBridge 在独立 Activity 创建时注入应用私有目录；纯 JDK 单测用临时目录。 */
    public static void setRoot(String dir) {
        ROOT = dir;
        if (dir != null) {
            try { new File(dir).mkdirs(); } catch (Throwable ignored) {}
        }
    }

    private static File root() {
        if (ROOT == null) {
            String tmp = System.getProperty("java.io.tmpdir", "/tmp");
            ROOT = new File(tmp, "hiker").getAbsolutePath();
        }
        File f = new File(ROOT);
        try { f.mkdirs(); } catch (Throwable ignored) {}
        return f;
    }

    private static void mark(String kind, String name) {
        EVENTS.add(kind + ":" + name);
        if (EVENTS.size() > 300) EVENTS.remove(0);
    }

    /** 运行期留痕（错误 / 降级 / 统计），供 capabilities() 与验证脚本读取。 */
    public static String report() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", new ArrayList<>(EVENTS));
        m.put("cookies", COOKIES.size());
        m.put("cache", CACHE.size());
        m.put("files", FILES.size());
        m.put("root", root().getAbsolutePath());
        return HikerJson.stringify(m);
    }

    /** 能力表：列出本类真正实现的 bridge 方法数，供页面二次确认。 */
    public static String capabilities() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", "HikerApi");
        m.put("implemented", METHODS.size());
        m.put("methods", new ArrayList<>(METHODS));
        return HikerJson.stringify(m);
    }

    private static final List<String> METHODS = new ArrayList<>();

    /* ------------------------------------------------------------ 统一分发 */

    public static String call(String name, String argsJson) {
        if (name == null) return "";
        List<Object> a = HikerJson.args(argsJson);
        try {
            String n = name.trim();
            if ("md5".equals(n)) return md5(HikerJson.s(a, 0, ""));
            if ("aesEncode".equals(n)) return aesEncode(a, true);
            if ("aesDecode".equals(n)) return aesEncode(a, false);
            if ("rsaEncrypt".equals(n)) return rsa(a, true);
            if ("rsaDecrypt".equals(n)) return rsa(a, false);
            if ("getCryptoJS".equals(n) || "getCryptoJS2".equals(n)) {
                mark("owner-js", n);
                return "";   /* 返回 JS 对象的 API 由 hiker_api_surface.js 的 CryptoJS 兼容对象承担 */
            }
            if ("getCookie".equals(n)) return getCookie(HikerJson.s(a, 0, ""));
            if ("fetchCookie".equals(n)) return fetchCookie(a);
            if ("fetchCache".equals(n) || "requireCache".equals(n)) return fetchCache(a);
            if ("fetchCodeByWebView".equals(n) || "x5Rule".equals(n)
                    || "parseLazyRuleAsync".equals(n) || "refreshX5WebView".equals(n)) {
                mark("degraded", n);   /* 依赖 X5/WebView 渲染或异步语义，独立同步桥不伪装成功 */
                return "";
            }
            if ("batchFetch".equals(n)) return batchFetch(a, false);
            if ("batchExecute".equals(n)) return batchFetch(a, true);
            if ("postRequest".equals(n)) return postRequest(a);
            if ("downloadFile".equals(n)) return downloadFile(a, false);
            if ("requireDownload".equals(n)) return downloadFile(a, true);
            if ("cacheM3u8".equals(n)) return cacheM3u8(a);
            if ("batchCacheM3u8".equals(n)) return batchCacheM3u8(a);
            if ("clearM3u8Ad".equals(n) || "clearM3u8AdLazy".equals(n)) return clearM3u8Ad(a);
            if ("fixM3u8".equals(n)) return fixM3u8(a);
            if ("parseLazyRule".equals(n)) return parseLazyRule(a);
            if ("getIP".equals(n)) return getIP();
            if ("getCpuAbi".equals(n)) return System.getProperty("os.arch", "");
            if ("findReachableIP".equals(n)) return findReachableIP(a);
            if ("ipping".equals(n)) return ipping(a);
            if ("refreshVideoUrl".equals(n)) return refreshVideoUrl(a);
            if ("convertBase64Image".equals(n)) return convertBase64Image(a);
            if ("writeHexFile".equals(n)) return writeHexFile(a);
            if ("createQRCode".equals(n)) return createQRCode(a);
            if ("readFile".equals(n)) return readFile(HikerJson.s(a, 0, ""));
            if ("writeFile".equals(n) || "saveFile".equals(n)) {
                return writeTextFile(HikerJson.s(a, 0, ""), HikerJson.s(a, 1, ""));
            }
            if ("fileExist".equals(n)) {
                try { return new File(root(), safeName(HikerJson.s(a, 0, "")
                        .replace("hiker://files/", ""))).exists() ? "1" : "0"; }
                catch (Throwable t) { return "0"; }
            }
            if ("report".equals(n)) return report();
            mark("unknown", n);
            return "";
        } catch (Throwable t) {
            mark("error", name + ":" + t.getClass().getSimpleName());
            return "";
        }
    }

    /** HikerBridge 生成具名方法时复用：把单个/多个参数拼成 argsJson。 */
    public static String callVarargs(String name, Object... args) {
        List<Object> a = new ArrayList<>();
        if (args != null) for (Object o : args) a.add(o);
        return call(name, HikerJson.stringify(a));
    }

    static {
        String[] names = {
                "md5", "aesEncode", "aesDecode", "rsaEncrypt", "rsaDecrypt", "getCryptoJS",
                "getCookie", "fetchCookie", "fetchCache", "fetchCodeByWebView", "batchFetch",
                "batchExecute", "postRequest", "downloadFile", "requireDownload", "cacheM3u8",
                "batchCacheM3u8", "clearM3u8Ad", "clearM3u8AdLazy", "x5Rule", "parseLazyRule",
                "parseLazyRuleAsync", "getIP", "convertBase64Image", "createQRCode",
                "requireCache", "fixM3u8", "refreshVideoUrl", "refreshX5WebView", "getCpuAbi",
                "findReachableIP", "ipping", "getCryptoJS2", "writeHexFile", "readFile",
                "writeFile", "saveFile", "fileExist"
        };
        for (String s : names) METHODS.add(s);
    }

    /* ------------------------------------------------------------ 加密 / 摘要 */

    public static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            return hex(d);
        } catch (Throwable t) {
            mark("error", "md5");
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            String h = Integer.toHexString(x & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    /**
     * AES：兼容「(明文, key) / (明文, key, iv) / (明文, key, iv, ECB)」三种常见规则写法。
     * key 非 16/24/32 字节时按 CryptoJS 惯例用 MD5(key) 的 hex 前 16 字节派生；
     * 输出 Base64。iv 缺省时由 key 确定性派生，保证 encode/decode 对称。
     */
    private static String aesEncode(List<Object> a, boolean enc) {
        String text = HikerJson.s(a, 0, "");
        String key = HikerJson.s(a, 1, "");
        String iv = HikerJson.s(a, 2, "");
        String mode = HikerJson.s(a, 3, "");
        try {
            byte[] kb = deriveKey(key);
            boolean ecb = "ECB".equalsIgnoreCase(mode);
            String transformation = ecb ? "AES/ECB/PKCS5Padding" : "AES/CBC/PKCS5Padding";
            Cipher c = Cipher.getInstance(transformation);
            if (ecb) {
                c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(kb, "AES"));
            } else {
                byte[] ivb = (iv.length() == 16) ? iv.getBytes("UTF-8") : deriveIv(key);
                c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                        new SecretKeySpec(kb, "AES"), new IvParameterSpec(ivb));
            }
            if (enc) return b64Encode(c.doFinal(text.getBytes("UTF-8")));
            byte[] raw = b64Decode(text);
            return new String(c.doFinal(raw), "UTF-8");
        } catch (Throwable t) {
            mark("error", (enc ? "aesEncode" : "aesDecode") + ":" + t.getClass().getSimpleName());
            return "";
        }
    }

    private static byte[] deriveKey(String key) throws Exception {
        if (key.length() == 16 || key.length() == 24 || key.length() == 32) return key.getBytes("UTF-8");
        String h = md5(key);
        return h.substring(0, 16).getBytes("UTF-8");
    }

    private static byte[] deriveIv(String key) throws Exception {
        String h = md5(key + "_hiker_iv");
        return h.substring(0, 16).getBytes("UTF-8");
    }

    /** RSA：公钥 X509 / 私钥 PKCS8，兼容 PEM 与纯 Base64，输出 Base64。 */
    private static String rsa(List<Object> a, boolean enc) {
        String text = HikerJson.s(a, 0, "");
        String key = HikerJson.s(a, 1, "");
        try {
            String clean = key.replaceAll("-----[^-]*-----", "").replaceAll("\\s", "");
            byte[] kb = b64Decode(clean);
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            if (enc) {
                c.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(kb)));
                return b64Encode(c.doFinal(text.getBytes("UTF-8")));
            }
            c.init(Cipher.DECRYPT_MODE, KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(kb)));
            return new String(c.doFinal(b64Decode(text)), "UTF-8");
        } catch (Throwable t) {
            mark("error", (enc ? "rsaEncrypt" : "rsaDecrypt"));
            return "";
        }
    }

    /* ------------------------------------------------------------ Cookie / 缓存 / 批量 */

    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Throwable t) { return url; }
    }

    private static String getCookie(String url) {
        Map<String, String> jar = COOKIES.get(hostOf(url));
        if (jar == null || jar.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static void putCookie(String url, List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return;
        String host = hostOf(url);
        Map<String, String> jar = COOKIES.get(host);
        if (jar == null) { jar = new LinkedHashMap<>(); COOKIES.put(host, jar); }
        for (String line : setCookies) {
            String first = line.split(";", 2)[0].trim();
            int p = first.indexOf('=');
            if (p > 0) jar.put(first.substring(0, p), first.substring(p + 1));
        }
    }

    /** fetchCookie：GET 一次，收集 Set-Cookie 入 jar，返回可直接放进 Cookie 头的字符串。 */
    private static String fetchCookie(List<Object> a) {
        String url = HikerJson.s(a, 0, "");
        if (url.length() == 0) return "";
        Map<String, String> headers = headersOf(a, 1);
        String existing = getCookie(url);
        if (existing.length() > 0) headers.put("Cookie", existing);
        Map<String, Object> res = exchange(url, "GET", null, headers);
        putCookie(url, asStringList(res.get("setCookie")));
        String body = HikerJson.str(res.get("body"));
        CACHE.put("body:" + url, new CacheEntry(body, System.currentTimeMillis()));
        return getCookie(url);
    }

    /** fetchCache：带 TTL 的内存缓存；命中直接返回，未命中走同步 GET。 */
    private static String fetchCache(List<Object> a) {
        String url = HikerJson.s(a, 0, "");
        if (url.length() == 0) return "";
        long ttl = DEFAULT_TTL;
        Object second = a.size() > 1 ? a.get(1) : null;
        if (second instanceof Number) ttl = ((Number) second).longValue();
        String ck = url + "|" + HikerJson.stringify(second == null ? "" : second);
        CacheEntry hit = CACHE.get(ck);
        if (hit != null && System.currentTimeMillis() - hit.time < ttl) return hit.body;
        Map<String, String> headers = headersOf(a, 1);
        String existing = getCookie(url);
        if (existing.length() > 0) headers.put("Cookie", existing);
        String body = HikerHttp.get(url, headers);
        CACHE.put(ck, new CacheEntry(body, System.currentTimeMillis()));
        return body;
    }

    /** batchFetch / batchExecute：并发生成结果数组（JSON 字符串），保持同步返回语义。 */
    private static String batchFetch(List<Object> a, boolean execute) {
        List<Object> urls = HikerJson.list(a, 0);
        final Map<String, String> headers = headersOf(a, 1);
        List<Callable<String>> jobs = new ArrayList<>();
        for (final Object u : urls) {
            jobs.add(new Callable<String>() {
                @Override
                public String call() {
                    String url = HikerJson.str(u);
                    if (url.length() == 0) return "";
                    Map<String, String> h = new HashMap<>(headers);
                    String ck = getCookie(url);
                    if (ck.length() > 0) h.put("Cookie", ck);
                    return HikerHttp.get(url, h);
                }
            });
        }
        List<Object> out = new ArrayList<>();
        try {
            List<Future<String>> fs = POOL.invokeAll(jobs);
            for (Future<String> f : fs) {
                try { out.add(f.get()); } catch (Throwable t) { out.add(""); }
            }
        } catch (Throwable t) {
            mark("error", execute ? "batchExecute" : "batchFetch");
            for (Object u : urls) out.add("");
        }
        return HikerJson.stringify(out);
    }

    private static String postRequest(List<Object> a) {
        String url = HikerJson.s(a, 0, "");
        String body = HikerJson.s(a, 1, "");
        Map<String, String> headers = headersOf(a, 2);
        String ck = getCookie(url);
        if (ck.length() > 0) headers.put("Cookie", ck);
        return HikerHttp.post(url, body, headers);
    }

    /** 把参数里的 {headers:{...}} / {"K":"V"} 统一成 Map<String,String>。 */
    private static Map<String, String> headersOf(List<Object> a, int idx) {
        Map<String, String> out = new HashMap<>();
        Map<String, Object> m = HikerJson.map(a, idx);
        if (m == null) {
            /* 也兼容 {"headers":{...}} 或 ["K@V", ...] 形态 */
            Map<String, Object> top = HikerJson.map(a, 1);
            if (top != null && top.get("headers") instanceof Map) {
                m = castMap(top.get("headers"));
            }
        }
        if (m == null && a.size() > idx && a.get(idx) instanceof List) {
            for (Object o : (List<?>) a.get(idx)) {
                String kv = HikerJson.str(o);
                int p = kv.indexOf('@');
                if (p > 0) out.put(kv.substring(0, p), kv.substring(p + 1));
            }
            return out;
        }
        if (m == null) return out;
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), HikerJson.str(e.getValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (!(o instanceof List)) return out;
        for (Object x : (List<?>) o) out.add(HikerJson.str(x));
        return out;
    }

    /* ------------------------------------------------------------ 文件 / 下载 */

    private static String safeName(String p) {
        String s = p == null ? "" : p;
        s = s.replace('\\', '/');
        int i = s.lastIndexOf('/');
        if (i >= 0) s = s.substring(i + 1);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() == 0 ? "file.bin" : s;
    }

    private static String writeBytes(String name, byte[] data) {
        String n = safeName(name);
        FILES.put(n, data);
        try {
            File f = new File(root(), n);
            FileOutputStream os = new FileOutputStream(f);
            os.write(data);
            os.close();
        } catch (Throwable t) {
            mark("error", "writeFile");
        }
        return "hiker://files/" + n;
    }

    private static byte[] fetchBytes(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", HikerContract.UA);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 16 * 1024 * 1024) break;
            }
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            mark("error", "fetchBytes");
            return new byte[0];
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String downloadFile(List<Object> a, boolean require) {
        String url = HikerJson.s(a, 0, "");
        String name = HikerJson.s(a, 1, "");
        if (url.length() == 0) return "";
        String n = name.length() > 0 ? safeName(name) : safeName(url);
        if (require && FILES.containsKey(n)) return "hiker://files/" + n;
        Map<String, String> headers = headersOf(a, 2);
        String ck = getCookie(url);
        if (ck.length() > 0) headers.put("Cookie", ck);
        byte[] data = fetchBytes(url, headers);
        if (data.length == 0) return "";
        return writeBytes(n, data);
    }

    /**
     * 规则自己 writeFile 出来的内容（缓存、配置、备份）。
     * 落到 filesDir/hiker/ 下，和 readFile 同一套命名，App 重启后还在。
     */
    private static String writeTextFile(String path, String content) {
        try {
            String n = safeName(String.valueOf(path).replace("hiker://files/", ""));
            File f = new File(root(), n);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(String.valueOf(content).getBytes(java.nio.charset.Charset.forName("UTF-8")));
            out.close();
            return "1";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readFile(String path) {
        String n = safeName(path.replace("hiker://files/", ""));
        byte[] b = FILES.get(n);
        if (b != null) return new String(b, java.nio.charset.Charset.forName("UTF-8"));
        try {
            File f = new File(root(), n);
            if (!f.exists()) return "";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) != -1) bos.write(buf, 0, k);
            in.close();
            return new String(bos.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String convertBase64Image(List<Object> a) {
        String b64 = HikerJson.s(a, 0, "");
        String name = HikerJson.s(a, 1, "");
        int p = b64.indexOf("base64,");
        if (p >= 0) b64 = b64.substring(p + 7);
        byte[] data = b64Decode(b64.trim());
        if (data.length == 0) return "";
        String n = name.length() > 0 ? name : ("img_" + md5(b64).substring(0, 8) + ".png");
        if (!n.contains(".")) n = n + ".png";
        return writeBytes(n, data);
    }

    private static String writeHexFile(List<Object> a) {
        String path = HikerJson.s(a, 0, "");
        String h = HikerJson.s(a, 1, "").replaceAll("\\s", "");
        byte[] out = new byte[h.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        } catch (Throwable t) {
            mark("error", "writeHexFile");
            return "";
        }
        return writeBytes(path, out);
    }

    /* ------------------------------------------------------------ M3U8 */

    /** 相对分片地址改写为绝对地址（本地缓存后播放器才能直连）。 */
    private static String rewriteM3u8(String body, String base) {
        StringBuilder sb = new StringBuilder();
        String[] lines = body.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0 && line.charAt(0) != '#'
                    && !line.startsWith("http") && !line.startsWith("data:")
                    && !line.startsWith("//")) {
                try { line = new URL(new URL(base), line).toString(); } catch (Throwable ignored) {}
            }
            sb.append(line);
            if (i + 1 < lines.length) sb.append('\n');
        }
        return sb.toString();
    }

    private static String fixM3u8(List<Object> a) {
        String body = HikerJson.s(a, 0, "");
        String base = HikerJson.s(a, 1, "");
        if (body.startsWith("http")) {
            String fetched = HikerHttp.get(body, null);
            return rewriteM3u8(fetched, body);
        }
        return rewriteM3u8(body, base);
    }

    private static String cacheM3u8(List<Object> a) {
        String url = HikerJson.s(a, 0, "");
        if (url.length() == 0) return "";
        Map<String, String> headers = headersOf(a, 1);
        String ck = getCookie(url);
        if (ck.length() > 0) headers.put("Cookie", ck);
        String body = HikerHttp.get(url, headers);
        if (body.length() == 0) return "";
        String rewritten = rewriteM3u8(body, url);
        return writeBytes("m3u8_" + md5(url).substring(0, 12) + ".m3u8",
                rewritten.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    private static String batchCacheM3u8(List<Object> a) {
        List<Object> urls = HikerJson.list(a, 0);
        List<Object> out = new ArrayList<>();
        for (Object u : urls) {
            List<Object> one = new ArrayList<>();
            one.add(u);
            if (a.size() > 1) one.add(a.get(1));
            out.add(cacheM3u8(one));
        }
        return HikerJson.stringify(out);
    }

    /**
     * 去广告：启发式移除匹配广告关键词的分片及其前置 #EXTINF，
     * 并丢弃 #EXT-X-DISCONTINUITY 标记行。输入可以是 m3u8 文本，也可以是 URL。
     * 不砍含 :// 的真实地址（xingchen 1.2.8 事故的反面）。
     */
    private static String clearM3u8Ad(List<Object> a) {
        String src = HikerJson.s(a, 0, "");
        String body = src.startsWith("http") ? HikerHttp.get(src, null) : src;
        if (body.length() == 0) return "";
        String[] lines = body.split("\\r?\\n", -1);
        List<String> out = new ArrayList<>();
        String pending = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) { pending = raw; continue; }
            if (line.startsWith("#EXT-X-DISCONTINUITY")) { pending = null; continue; }
            boolean ad = line.length() > 0 && line.charAt(0) != '#'
                    && (line.matches("(?i).*(/ad/|/ads/|advert|guanggao|_ad\\.|\\?ad=).*"));
            if (ad) { pending = null; continue; }
            if (pending != null) { out.add(pending); pending = null; }
            out.add(raw);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            sb.append(out.get(i));
            if (i + 1 < out.size()) sb.append('\n');
        }
        return sb.toString();
    }

    private static String parseLazyRule(List<Object> a) {
        String url = HikerJson.s(a, 0, "");
        if (url.length() == 0) return "";
        String body = fetchCache(HikerJson.args(HikerJson.stringify(toList(url))));
        String rule = HikerJson.s(a, 1, "");
        if (rule.length() == 0) return body;
        /* 简易选择器：取第一个 <a> 的文本，与 hiker_api_surface.domParse 同口径 */
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<a[^>]*>([\\s\\S]*?)</a>").matcher(body);
        return m.find() ? m.group(1).replaceAll("<[^>]*>", "") : "";
    }

    private static List<Object> toList(Object o) {
        List<Object> l = new ArrayList<>();
        l.add(o);
        return l;
    }

    /* ------------------------------------------------------------ 网络工具 */

    private static String getIP() {
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (n.isLoopback() || !n.isUp()) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable t) {
            mark("error", "getIP");
        }
        return "127.0.0.1";
    }

    private static String findReachableIP(List<Object> a) {
        List<Object> ips = HikerJson.list(a, 0);
        for (Object o : ips) {
            List<Object> one = toList(o);
            if (!"timeout".equals(ipping(one))) return HikerJson.str(o);
        }
        return "";
    }

    private static String ipping(List<Object> a) {
        String ip = HikerJson.s(a, 0, "");
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isReachable(500) ? "ok" : "timeout";
        } catch (Throwable t) {
            return "timeout";
        }
    }

    private static String refreshVideoUrl(List<Object> a) {
        String url = HikerJson.s(a, 0, "");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", HikerContract.UA);
            c.getResponseCode();
            return c.getURL().toString();
        } catch (Throwable t) {
            return url;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ------------------------------------------------------------ 二维码 */

    private static String createQRCode(List<Object> a) {
        String text = HikerJson.s(a, 0, "");
        if (text.length() == 0) return "";
        int scale = 4, mask = 0;
        if (a.size() > 1 && a.get(1) instanceof Number) scale = ((Number) a.get(1)).intValue();
        if (a.size() > 2 && a.get(2) instanceof Number) mask = ((Number) a.get(2)).intValue();
        String uri = HikerQR.dataUri(text, scale, mask);
        if (uri.length() == 0) mark("degraded", "createQRCode:overflow");
        return uri;
    }

    /* ------------------------------------------------------------ 原生 HTTP（带响应头） */

    private static Map<String, Object> exchange(String url, String method, String body,
                                                Map<String, String> headers) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("body", "");
        out.put("setCookie", new ArrayList<String>());
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", HikerContract.UA);
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            if ("POST".equalsIgnoreCase(method)) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                if (body != null) os.write(body.getBytes("UTF-8"));
                os.close();
            }
            List<String> set = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && (e.getKey().equalsIgnoreCase("Set-Cookie")
                        || e.getKey().equalsIgnoreCase("Set-Cookie2")) && e.getValue() != null) {
                    set.addAll(e.getValue());
                }
            }
            out.put("setCookie", set);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                byte[] raw = bos.toByteArray();
                String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
                String cs = ct.contains("gbk") || ct.contains("gb2312") ? "GBK" : "UTF-8";
                out.put("body", new String(raw, cs));
            }
            out.put("status", code);
        } catch (Throwable t) {
            mark("error", "exchange");
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    /* ------------------------------------------------------------ Base64（自建，兼容低版本 Android） */

    private static String b64Encode(byte[] data) {
        return HikerJson.b64Encode(data);
    }

    private static byte[] b64Decode(String s) {
        return HikerJson.b64Decode(s);
    }
}
