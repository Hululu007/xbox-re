package com.fongmi.android.tv.api.hk.state;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A（一级 json × N，用户改、参与备份）/ B（hk.state.json 一份，自动写、不备份）的唯一读写口。 */
public final class HkStateStore {

    private static final String PREF = "hk_state";
    private static final String KEY_ROOT = "root_dir";
    private static final String KEY_DOC = "current_doc";
    public static final String STATE_FILE = "hk.state.json";

    private HkStateStore() {}

    private static SharedPreferences sp() {
        return App.get().getSharedPreferences(PREF, 0);
    }

    public static String rootDir() {
        String d = sp().getString(KEY_ROOT, "");
        // 必须可读可写：Android 10+ 分区存储下 isDirectory() 可能为 true 但 canWrite() 为 false，
        // 否则 A 写外部、B 回落私有，两份 json 分裂 → 列表永远空。
        if (d != null && !d.isEmpty()) {
            File dir = new File(d);
            if (dir.isDirectory() && dir.canRead() && dir.canWrite()) return d;
        }
        File f = new File(App.get().getFilesDir(), "hk");
        if (!f.exists()) f.mkdirs();
        return f.getAbsolutePath();
    }

    /** 返回是否真正可用；不可用时调用方要明确提示，不能静默回落。 */
    public static boolean setRootDir(String dir) {
        if (dir == null || dir.trim().isEmpty()) return false;
        File f = new File(dir.trim());
        if (!f.isDirectory() && !f.mkdirs()) return false;
        if (!f.canWrite()) return false;
        sp().edit().putString(KEY_ROOT, f.getAbsolutePath()).apply();
        return true;
    }

    public static String currentDoc() { return sp().getString(KEY_DOC, ""); }
    public static void setCurrentDoc(String doc) { sp().edit().putString(KEY_DOC, doc == null ? "" : doc).apply(); }

    public static File docFile(String doc) { return new File(rootDir(), safe(doc) + ".json"); }
    public static File stateFile() { return new File(rootDir(), STATE_FILE); }

    public static String safe(String name) {
        if (name == null) return "";
        return name.replace("/", "_").replace("\\", "_").replace("..", "_").trim();
    }

    public static List<String> docNames() {
        List<String> out = new ArrayList<>();
        File[] files = new File(rootDir()).listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;
            if (f.getName().equals(STATE_FILE)) continue;
            out.add(f.getName().substring(0, f.getName().length() - 5));
        }
        return out;
    }

    public static HkDoc readDoc(String doc) {
        try {
            String text = migrateDocJson(read(docFile(doc)));
            if (text == null || text.isEmpty()) return new HkDoc(doc);
            HkDoc d = new Gson().fromJson(text, HkDoc.class);
            if (d == null) return new HkDoc(doc);
            if (d.name == null || d.name.isEmpty()) d.name = doc;
            if (d.categories == null) d.categories = new ArrayList<>();
            // 出口净化：Gson 若把元素读成 LinkedTreeMap（混淆/坏数据），直接丢掉，
            // 保证后面所有 for (HkFeed f : d.feeds) 都不会 ClassCastException。
            List<HkFeed> clean = new ArrayList<>();
            if (d.feeds != null) for (Object o : d.feeds) if (o instanceof HkFeed) clean.add((HkFeed) o);
            d.feeds = clean;
            return d;
        } catch (Throwable e) {
            return new HkDoc(doc);
        }
    }

    /** 兼容「修复前」由 R8 混淆字段名写出的旧 json：
     *  HkDoc a/b/c → name/categories/feeds；HkFeed a/b/c/d → feedId/raw/category/mark。
     *  用户已生成的 123.json 不用删，读一次即自动转正（下次 writeDoc 会写成标准字段名）。 */
    private static String migrateDocJson(String text) {
        if (text == null || text.isEmpty() || !text.trim().startsWith("{")) return text;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return text;
            JsonObject o = el.getAsJsonObject();
            boolean legacy = o.has("a") || o.has("b") || o.has("c");
            if (!legacy) return text;
            rename(o, new String[]{"a", "b", "c"},
                       new String[]{"name", "categories", "feeds"});
            if (o.has("feeds") && o.get("feeds").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("feeds")) {
                    if (!e.isJsonObject()) continue;
                    rename(e.getAsJsonObject(),
                        new String[]{"a", "b", "c", "d"},
                        new String[]{"feedId", "raw", "category", "mark"});
                }
            }
            return o.toString();
        } catch (Throwable e) {
            return text;
        }
    }

    private static void rename(JsonObject o, String[] from, String[] to) {
        for (int i = 0; i < from.length; i++) {
            if (o.has(from[i]) && !o.has(to[i])) o.add(to[i], o.remove(from[i]));
        }
    }

    public static void writeDoc(HkDoc doc) {
        write(docFile(doc.name), new Gson().toJson(doc));
    }

    public static void deleteDoc(String doc) {
        File f = docFile(doc);
        if (f.exists()) f.delete();
    }

    public static HkState readState() {
        try {
            String text = migrateStateJson(read(stateFile()));
            if (text == null || text.isEmpty()) return new HkState();
            HkState s = new Gson().fromJson(text, HkState.class);
            if (s == null) return new HkState();
            List<HkSiteEntry> clean = new ArrayList<>();
            if (s.sites != null) for (Object o : s.sites) if (o instanceof HkSiteEntry) clean.add((HkSiteEntry) o);
            s.sites = clean;
            return s;
        } catch (Throwable e) {
            return new HkState();
        }
    }

    /** B 的旧混淆字段：HkState a/b → version/sites；
     *  HkSiteEntry a..h → doc/feedId/id/title/displayName/mark/origin/firstSeen。 */
    private static String migrateStateJson(String text) {
        if (text == null || text.isEmpty() || !text.trim().startsWith("{")) return text;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return text;
            JsonObject o = el.getAsJsonObject();
            if (!(o.has("a") || o.has("b"))) return text;
            rename(o, new String[]{"a", "b"}, new String[]{"version", "sites"});
            if (o.has("sites") && o.get("sites").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("sites")) {
                    if (!e.isJsonObject()) continue;
                    rename(e.getAsJsonObject(),
                        new String[]{"a", "b", "c", "d", "e", "f", "g", "h"},
                        new String[]{"doc", "feedId", "id", "title", "displayName", "mark", "origin", "firstSeen"});
                }
            }
            return o.toString();
        } catch (Throwable e) {
            return text;
        }
    }

    public static void writeState(HkState state) {
        state.version = System.currentTimeMillis();
        write(stateFile(), new Gson().toJson(state));
    }

    public static List<HkSiteEntry> sitesOf(HkState state, String doc) {
        List<HkSiteEntry> out = new ArrayList<>();
        for (HkSiteEntry e : state.sites) if (doc.equals(e.doc)) out.add(e);
        return out;
    }

    /** 只替换某个 doc 下某个 feedId 的站点段，其余分段原样不动（按 feed 增量刷新）。 */
    public static void replaceFeed(HkState state, String doc, String feedId, List<HkSiteEntry> fresh) {
        List<HkSiteEntry> keep = new ArrayList<>();
        for (HkSiteEntry e : state.sites) {
            if (doc.equals(e.doc) && feedId.equals(e.feedId)) continue;
            keep.add(e);
        }
        keep.addAll(fresh);
        state.sites = keep;
    }

    public static void removeFeed(HkState state, String doc, String feedId) {
        List<HkSiteEntry> keep = new ArrayList<>();
        for (HkSiteEntry e : state.sites)
            if (!(doc.equals(e.doc) && feedId.equals(e.feedId))) keep.add(e);
        state.sites = keep;
    }

    /** 不用 java.nio.file.Files：它 API 26+ 才提供，minSdk 24 的老车机/TV
     *  （甲壳虫助手常见环境）会抛 NoClassDefFoundError / NoSuchMethodError，
     *  且 Error 不会被 catch(Exception) 捕获，直接把 APP 带崩。 */
    private static String read(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void write(File f, String text) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (f.exists() && !f.delete()) return;
            tmp.renameTo(f);
        } catch (Throwable ignored) {}
    }
}
