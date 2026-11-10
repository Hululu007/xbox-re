package com.fongmi.android.tv.api.hk.state;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;
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
        if (d != null && !d.isEmpty() && new File(d).isDirectory()) return d;
        File f = new File(App.get().getFilesDir(), "hk");
        if (!f.exists()) f.mkdirs();
        return f.getAbsolutePath();
    }

    public static void setRootDir(String dir) {
        sp().edit().putString(KEY_ROOT, dir == null ? "" : dir).apply();
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
            String text = read(docFile(doc));
            if (text == null || text.isEmpty()) return new HkDoc(doc);
            HkDoc d = new Gson().fromJson(text, HkDoc.class);
            if (d == null) return new HkDoc(doc);
            if (d.name == null || d.name.isEmpty()) d.name = doc;
            if (d.categories == null) d.categories = new ArrayList<>();
            if (d.feeds == null) d.feeds = new ArrayList<>();
            return d;
        } catch (Throwable e) {
            return new HkDoc(doc);
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
            String text = read(stateFile());
            if (text == null || text.isEmpty()) return new HkState();
            HkState s = new Gson().fromJson(text, HkState.class);
            if (s == null) return new HkState();
            if (s.sites == null) s.sites = new ArrayList<>();
            return s;
        } catch (Throwable e) {
            return new HkState();
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
