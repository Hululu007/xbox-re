package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Pair;

import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地脚本目录当点播订阅源。
 *
 * 目录根生成「脚本扫描.json」：sites（生效）、sites_bac（回收站）、lives（占位）。
 * 递归扫描 .py / .js / .html；路径或文件名含「代理」→ "proxy":"proxy"。
 * 配置地址就是该 json 文件（file:/...），VodConfig.load 前会重扫并回写，
 * 因此手动更新、重启、切换接口都会按目录内容刷新。
 *
 * 本轮只生成 proxy 字段，Site 解析与单站代理生效留到以后（按要求）。
 */
public final class LocalScriptSource {

    public static final String STORE_FILE = "脚本扫描.json";
    public static final String PROXY_KEY = "代理";
    public static final String PROXY_FLAG = "proxy";
    public static final String HTML_API = "Csp_Nostr";

    private static final String KEY_DIRS = "local_script_dirs";
    private static final String[] EXTS = {".py", ".js", ".html"};
    private static final int MAX_FILES = 2000;

    private LocalScriptSource() {
    }

    /* ---------------- 目录登记（支持多个文件夹） ---------------- */

    public static List<String> dirs() {
        List<String> out = new ArrayList<>();
        String raw = Prefers.getString(KEY_DIRS, "");
        if (TextUtils.isEmpty(raw)) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String dir = array.optString(i, "");
                if (!TextUtils.isEmpty(dir) && new File(dir).isDirectory()) out.add(dir);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveDirs(List<String> list) {
        JSONArray array = new JSONArray();
        for (String dir : list) array.put(dir);
        Prefers.put(KEY_DIRS, array.toString());
    }

    public static boolean addDir(String dir) {
        if (TextUtils.isEmpty(dir) || !new File(dir).isDirectory()) return false;
        List<String> list = dirs();
        if (list.contains(dir)) return false;
        list.add(dir);
        saveDirs(list);
        load(dir);
        return true;
    }

    public static void removeDir(String dir) {
        List<String> list = dirs();
        if (list.remove(dir)) saveDirs(list);
    }

    public static File storeFile(String dir) {
        return new File(dir, STORE_FILE);
    }

    /** 与 ConfigDialog 选本地文件时的格式保持一致：file:/ + 相对 Path.rootPath() 的路径 */
    public static String configUrl(String dir) {
        String path = storeFile(dir).getAbsolutePath();
        String root = Path.rootPath();
        String rel = path.startsWith(root) ? path.substring(root.length()) : path;
        return "file:/" + rel;
    }

    public static boolean isStoreUrl(String url) {
        return !TextUtils.isEmpty(dirOfUrl(url));
    }

    public static String dirOfUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        for (String dir : dirs()) if (configUrl(dir).equals(url)) return dir;
        return "";
    }

    /* ---------------- 扫描 ---------------- */

    public static List<File> scripts(String dir) {
        List<File> out = new ArrayList<>();
        File root = new File(dir);
        if (!root.isDirectory()) return out;
        collect(root, out);
        out.sort((a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        return out;
    }

    private static void collect(File dir, List<File> out) {
        if (out.size() >= MAX_FILES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (out.size() >= MAX_FILES) return;
            if (file.isDirectory()) collect(file, out);
            else if (isScript(file)) out.add(file);
        }
    }

    private static boolean isScript(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : EXTS) if (name.endsWith(ext)) return true;
        return false;
    }

    private static String extOf(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : EXTS) if (name.endsWith(ext)) return ext;
        return "";
    }

    private static String labelOf(String ext) {
        switch (ext) {
            case ".py": return "[py]";
            case ".js": return "[js]";
            case ".html": return "[html]";
            default: return "[url]";
        }
    }

    private static String keyOf(File root, File file) {
        String rel = file.getAbsolutePath();
        String base = root.getAbsolutePath();
        if (rel.startsWith(base)) rel = rel.substring(base.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        String ext = extOf(file);
        int len = Math.max(0, rel.length() - ext.length());
        return rel.substring(0, len) + labelOf(ext);
    }

    private static String proxyOf(File root, File file) {
        String rel = file.getAbsolutePath();
        String base = root.getAbsolutePath();
        if (rel.startsWith(base)) rel = rel.substring(base.length());
        String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
        return rel.contains(PROXY_KEY) || file.getName().contains(PROXY_KEY) || parent.contains(PROXY_KEY)
                ? PROXY_FLAG : "";
    }

    private static JSONObject newItem(File root, File file) {
        JSONObject item = new JSONObject();
        String ext = extOf(file);
        String label = labelOf(ext);
        try {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            item.put("key", keyOf(root, file));
            item.put("name", (dot > 0 ? name.substring(0, dot) : name) + label);
            item.put("type", 3);
            if (".html".equals(ext)) {
                item.put("api", HTML_API);
                item.put("homePage", fileHomePage(file));
            } else {
                item.put("api", "file://" + file.getAbsolutePath());
            }
            item.put("ext", "");
            item.put("proxy", proxyOf(root, file));
            item.put("searchable", 1);
            item.put("changeable", 1);
            item.put("quickSearch", 1);
        } catch (Exception ignored) {
        }
        return item;
    }

    /** 本地 html 页面地址：端口取运行时实际值，不写死 9978/9979 */
    private static String fileHomePage(File file) {
        String abs = file.getAbsolutePath();
        String root = Path.rootPath();
        String rel = abs.startsWith(root) ? abs.substring(root.length()) : abs;
        while (rel.startsWith("/")) rel = rel.substring(1);
        return Server.get().getAddress("/file/") + rel;
    }

    private static boolean isRemoteItem(JSONObject item) {
        String home = item.optString("homePage");
        return (home.startsWith("http://") || home.startsWith("https://")) && !home.contains("127.0.0.1");
    }

    /* ---------------- store 读写 ---------------- */

    private static String readText(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static void writeText(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(text.getBytes("UTF-8"));
        out.flush();
        out.close();
    }

    private static JSONObject readStore(String dir) {
        File file = storeFile(dir);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(readText(file));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static List<JSONObject> toList(JSONArray array) {
        List<JSONObject> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) out.add(item);
        }
        return out;
    }

    private static JSONArray toArray(List<JSONObject> list) {
        JSONArray array = new JSONArray();
        for (JSONObject item : list) array.put(item);
        return array;
    }

    public static void save(String dir, List<JSONObject> sites, List<JSONObject> bac) {
        JSONObject store = readStore(dir);
        try {
            store.put("sites", toArray(sites));
            store.put("sites_bac", toArray(bac));
            if (!store.has("lives")) {
                JSONArray lives = new JSONArray();
                lives.put(new JSONObject());
                store.put("lives", lives);
            }
            writeText(storeFile(dir), store.toString(2));
        } catch (Exception ignored) {
        }
    }

    /** 扫描目录 + 读 json，按 5 条规则合并；有变更时回写。 */
    public static Pair<List<JSONObject>, List<JSONObject>> load(String dir) {
        File root = new File(dir);
        List<JSONObject> sites = new ArrayList<>();
        List<JSONObject> bac = new ArrayList<>();
        if (!root.isDirectory()) return new Pair<>(sites, bac);

        JSONObject store = readStore(dir);
        sites = toList(store.optJSONArray("sites"));
        bac = toList(store.optJSONArray("sites_bac"));

        List<File> found = scripts(dir);
        Set<String> foundPaths = new HashSet<>();
        for (File file : found) foundPaths.add(file.getAbsolutePath());

        // key 集合必须在清除之前算，否则刚被清掉的条目会被当成新文件加回来
        Set<String> liveKeys = new HashSet<>();
        for (JSONObject item : sites) liveKeys.add(item.optString("key"));
        for (JSONObject item : bac) liveKeys.add(item.optString("key"));

        // 规则 4：两边都清掉已不存在的本地文件条目（远程条目永不自动删除）
        int before = sites.size() + bac.size();
        purgeGone(sites, foundPaths);
        purgeGone(bac, foundPaths);
        boolean purged = before != sites.size() + bac.size();

        // 规则 3：新文件追加进 sites（规则 1、2 自然满足——已在里面就不动，保留手改的 name/ext/proxy）
        boolean changed = false;
        for (File file : found) {
            String key = keyOf(root, file);
            if (liveKeys.contains(key)) continue;
            sites.add(newItem(root, file));
            liveKeys.add(key);
            changed = true;
        }
        boolean first = !storeFile(dir).isFile();
        if (changed || purged || first) save(dir, sites, bac);
        return new Pair<>(sites, bac);
    }

    private static void purgeGone(List<JSONObject> list, Set<String> foundPaths) {
        list.removeIf(item -> {
            if (isRemoteItem(item)) return false;
            String api = item.optString("api");
            if (api.startsWith("file://")) {
                return !foundPaths.contains(api.substring("file://".length()));
            }
            String home = item.optString("homePage");
            int idx = home.indexOf("/file/");
            if (idx < 0) return false;
            String rel = home.substring(idx + 6);
            return !foundPaths.contains(Path.rootPath() + "/" + rel);
        });
    }

    /** 交给解析器用的 json：只输出 sites，sites_bac 是管理用的，app 不识别 */
    public static String buildJson(String dir) {
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        JSONObject root = new JSONObject();
        try {
            root.put("warningText", "本地脚本目录：" + dir);
            root.put("sites", toArray(pair.first));
            JSONArray lives = new JSONArray();
            lives.put(new JSONObject());
            root.put("lives", lives);
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    public static void refresh(String dir) {
        load(dir);
    }

    /* ---------------- 分类 ---------------- */

    public static String kindOf(JSONObject item) {
        String key = item.optString("key");
        if (key.endsWith("[url]")) return "url";
        if (key.endsWith("[py]")) return "py";
        if (key.endsWith("[js]")) return "js";
        if (key.endsWith("[html]")) return "html";
        String api = item.optString("api");
        if (api.endsWith(".py")) return "py";
        if (api.endsWith(".js")) return "js";
        if (!item.optString("homePage").isEmpty()) return "html";
        return "url";
    }

    public static boolean isEditable(JSONObject item) {
        return "url".equals(kindOf(item));
    }

    /* ---------------- 两栏交换 / 排序 ---------------- */

    /** 两侧被选中的条目同时移到对面尾部，保持各自相对顺序；脚本文件本身不动 */
    public static boolean swap(String dir, Set<String> fromSites, Set<String> fromBac) {
        if (fromSites.isEmpty() && fromBac.isEmpty()) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        List<JSONObject> sites = pair.first;
        List<JSONObject> bac = pair.second;
        List<JSONObject> a = new ArrayList<>();
        List<JSONObject> b = new ArrayList<>();
        for (JSONObject item : sites) if (fromSites.contains(item.optString("key"))) a.add(item);
        for (JSONObject item : bac) if (fromBac.contains(item.optString("key"))) b.add(item);
        if (a.isEmpty() && b.isEmpty()) return false;
        sites.removeAll(a);
        bac.removeAll(b);
        for (JSONObject item : a) bac.add(item);
        for (JSONObject item : b) sites.add(item);
        save(dir, sites, bac);
        return true;
    }

    /** 分类筛选下只在可见序列内与相邻可见项交换，避免「点了没反应」 */
    public static boolean reorder(String dir, Set<String> keys, boolean up, boolean inBac, Set<String> visibleKeys) {
        if (keys.isEmpty()) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        List<JSONObject> list = inBac ? pair.second : pair.first;
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) if (visibleKeys.contains(list.get(i).optString("key"))) pos.add(i);
        if (pos.isEmpty()) return false;
        List<JSONObject> vis = new ArrayList<>();
        for (int i : pos) vis.add(list.get(i));
        boolean changed = false;
        if (up) {
            for (int i = 1; i < vis.size(); i++) {
                if (keys.contains(vis.get(i).optString("key")) && !keys.contains(vis.get(i - 1).optString("key"))) {
                    JSONObject t = vis.get(i);
                    vis.set(i, vis.get(i - 1));
                    vis.set(i - 1, t);
                    changed = true;
                }
            }
        } else {
            for (int i = vis.size() - 2; i >= 0; i--) {
                if (keys.contains(vis.get(i).optString("key")) && !keys.contains(vis.get(i + 1).optString("key"))) {
                    JSONObject t = vis.get(i);
                    vis.set(i, vis.get(i + 1));
                    vis.set(i + 1, t);
                    changed = true;
                }
            }
        }
        if (!changed) return false;
        for (int i = 0; i < pos.size(); i++) list.set(pos.get(i), vis.get(i));
        save(dir, pair.first, pair.second);
        return true;
    }

    /* ---------------- 远程 html URL ---------------- */

    private static String stripProxy(String url) {
        String out = url == null ? "" : url.trim();
        String suffix = "#" + PROXY_KEY;
        while (out.endsWith(suffix)) out = out.substring(0, out.length() - suffix.length());
        return out;
    }

    private static String withLabel(String name) {
        String out = name == null ? "" : name.trim();
        return out.endsWith("[url]") ? out : out + "[url]";
    }

    /** 新增：勾选代理 → url 尾部加 #代理 + "proxy":"proxy" */
    public static boolean addUrl(String dir, String name, String url, boolean proxy) {
        if (TextUtils.isEmpty(url)) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        String clean = stripProxy(url);
        if (clean.isEmpty()) return false;
        String full = proxy ? clean + "#" + PROXY_KEY : clean;
        JSONObject item = new JSONObject();
        try {
            item.put("key", "url_" + Integer.toHexString(full.hashCode()) + "[url]");
            item.put("name", withLabel(TextUtils.isEmpty(name) ? clean : name));
            item.put("type", 3);
            item.put("api", HTML_API);
            item.put("homePage", full);
            item.put("ext", "");
            item.put("proxy", proxy ? PROXY_FLAG : "");
            item.put("searchable", 1);
            item.put("changeable", 1);
            item.put("quickSearch", 1);
        } catch (Exception ignored) {
        }
        pair.first.add(item);
        save(dir, pair.first, pair.second);
        return true;
    }

    /** 改远程 html URL：名称、url、代理 */
    public static boolean updateUrl(String dir, String key, String name, String url, boolean proxy) {
        if (TextUtils.isEmpty(url)) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        JSONObject target = null;
        for (JSONObject item : pair.first) if (key.equals(item.optString("key"))) target = item;
        if (target == null) for (JSONObject item : pair.second) if (key.equals(item.optString("key"))) target = item;
        if (target == null || !isEditable(target)) return false;
        String clean = stripProxy(url);
        if (clean.isEmpty()) return false;
        try {
            target.put("name", withLabel(TextUtils.isEmpty(name) ? clean : name));
            target.put("homePage", proxy ? clean + "#" + PROXY_KEY : clean);
            target.put("proxy", proxy ? PROXY_FLAG : "");
            target.put("api", HTML_API);
        } catch (Exception ignored) {
        }
        save(dir, pair.first, pair.second);
        return true;
    }

    public static JSONObject find(List<JSONObject> list, String key) {
        for (JSONObject item : list) if (key.equals(item.optString("key"))) return item;
        return null;
    }
}
