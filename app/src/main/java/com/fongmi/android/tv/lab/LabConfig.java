package com.fongmi.android.tv.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LabConfig {

    public static final int SOURCE_LOCAL = 1;
    public static final int SOURCE_URL = 2;
    /** 内置模板的默认落地目录，与 lab_template.json 里声明的 root 保持一致。 */
    private static final String TEMPLATE_ROOT = "/storage/emulated/0/WebHTV";

    private static final String PREF = "lab";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_URL = "url";
    private static final String KEY_ROOT = "root";
    private static final String KEY_LOCAL_PATH = "local_path";
    private static final String KEY_FOREGROUND = "foreground";
    private static final String KEY_BATTERY = "battery";
    private static final String KEY_GLOBAL_PROXY = "global_proxy";
    private static final String KEY_GLOBAL_PROXY_PORT = "global_proxy_port";
    private static final String KEY_GLOBAL_PROXY_NO_PROXY = "global_proxy_no_proxy";
    private static final String KEY_NAV_ENTRY = "nav_entry";
    private static final String KEY_IMPORTED = "imported";
    /** 环境包源：php/python/nodejs 没有官方 Android 构建，用户可填一个可直连的目录基址。 */
    private static final String KEY_ENV_SOURCE = "env_source";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Gson gson = new Gson();
    private LabModels.LabRoot root;
    private volatile String configRoot;
    private String sourceName = "";
    private volatile String loadError = "";

    private static class Loader {
        static final LabConfig INSTANCE = new LabConfig();
    }

    public static LabConfig get() {
        return Loader.INSTANCE;
    }

    private SharedPreferences sp() {
        return App.get().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 只有本地配置和网络 URL 两种；历史上存过的"内置配置"(0) 一律按本地配置处理。 */
    public int getSource() {
        return sp().getInt(KEY_SOURCE, SOURCE_LOCAL) == SOURCE_URL ? SOURCE_URL : SOURCE_LOCAL;
    }

    public void setSource(int source) {
        sp().edit().putInt(KEY_SOURCE, source).apply();
    }

    public String getUrl() {
        return sp().getString(KEY_URL, "");
    }

    public void setUrl(String url) {
        sp().edit().putString(KEY_URL, url).apply();
    }

    /** 用户手填的根目录，未设置时为空串——设置弹窗直接展示这个值，不带兜底。 */
    public String getRootOverride() {
        return sp().getString(KEY_ROOT, "");
    }

    /**
     * 实际使用的根目录：用户手填 > 配置自带/推断 > 应用私有目录。
     * 最后一级只是保证路径合法（不再回落到其它应用的目录），此时通常还没有可用配置。
     */
    public String getRoot() {
        String override = getRootOverride();
        if (!TextUtils.isEmpty(override)) return override;
        if (configRoot != null && !configRoot.isEmpty()) return configRoot;
        return fallbackRoot();
    }

    private String fallbackRoot() {
        File external = App.get().getExternalFilesDir(null);
        return external == null ? App.get().getFilesDir().getAbsolutePath() : external.getAbsolutePath();
    }

    public void setRoot(String root) {
        sp().edit().putString(KEY_ROOT, root).apply();
    }

    public String getLocalPath() {
        return sp().getString(KEY_LOCAL_PATH, "");
    }

    public void setLocalPath(String path) {
        sp().edit().putString(KEY_LOCAL_PATH, path).apply();
    }

    /** 环境包源基址，空串表示只用本机 EnvFiles 目录。 */
    public String getEnvSource() {
        return sp().getString(KEY_ENV_SOURCE, "");
    }

    public void setEnvSource(String url) {
        sp().edit().putString(KEY_ENV_SOURCE, url).apply();
    }

    public boolean getForeground() {
        return sp().getBoolean(KEY_FOREGROUND, false);
    }

    public void setForeground(boolean value) {
        sp().edit().putBoolean(KEY_FOREGROUND, value).apply();
    }

    public boolean getBattery() {
        return sp().getBoolean(KEY_BATTERY, false);
    }

    public void setBattery(boolean value) {
        sp().edit().putBoolean(KEY_BATTERY, value).apply();
    }

    public boolean getNavEntry() {
        return sp().getBoolean(KEY_NAV_ENTRY, false);
    }

    public void setNavEntry(boolean value) {
        sp().edit().putBoolean(KEY_NAV_ENTRY, value).apply();
    }

    public boolean getGlobalProxy() {
        return sp().getBoolean(KEY_GLOBAL_PROXY, false);
    }

    public void setGlobalProxy(boolean value) {
        sp().edit().putBoolean(KEY_GLOBAL_PROXY, value).apply();
    }

    public int getGlobalProxyPort() {
        return sp().getInt(KEY_GLOBAL_PROXY_PORT, 7890);
    }

    public void setGlobalProxyPort(int value) {
        sp().edit().putInt(KEY_GLOBAL_PROXY_PORT, value).apply();
    }

    public String getGlobalProxyNoProxy() {
        return sp().getString(KEY_GLOBAL_PROXY_NO_PROXY, "localhost,127.0.0.1,::1");
    }

    public void setGlobalProxyNoProxy(String value) {
        sp().edit().putString(KEY_GLOBAL_PROXY_NO_PROXY, value).apply();
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getLoadError() {
        return loadError;
    }

    public LabModels.LabRoot getLabRoot() {
        return root;
    }

    public interface LoadCallback {
        void onLoaded(LabModels.LabRoot root);

        void onError(String message);
    }

    public void reload(LoadCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                root = loadSync();
                sourceName = describeSource();
                if (callback != null) App.post(() -> callback.onLoaded(root));
            } catch (Exception e) {
                loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (callback != null) App.post(() -> callback.onError(loadError));
            }
        });
    }

    public LabModels.LabRoot loadSync() throws IOException {
        try {
            return loadSyncInner();
        } catch (Exception e) {
            // 配置源返回 HTML / 404 / 非 JSON，或本地文件读不到时，绝不清空列表。
            // 依次回落：上次成功缓存 → APK 内置模板；原因留给 UI 提示，但数据仍在。
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            loadError = "配置源解析失败，已回落内置模板：" + reason
                    + "；来源=" + (getSource() == SOURCE_URL ? getUrl() : "本地");
            LabModels.LabRoot fallback = null;
            String fallbackError = "";
            String cached = readConfigCache();
            if (!TextUtils.isEmpty(cached)) {
                try {
                    fallback = parse(cached);
                } catch (Exception e2) {
                    fallbackError = "缓存:" + e2.getMessage();
                }
            }
            if (fallback == null || fallback.lists == null || fallback.lists.isEmpty()) {
                try (InputStream in = App.get().getAssets().open("lab_template.json")) {
                    fallback = parse(readAll(in));
                } catch (Exception e2) {
                    fallbackError = "内置模板:" + e2.getMessage();
                }
            }
            if (fallback == null || fallback.lists == null || fallback.lists.isEmpty()) {
                fallback = new LabModels.LabRoot();
                loadError = "配置源与内置模板均失败：" + reason + " / " + fallbackError;
            }
            mergeImported(fallback);
            if (fallback.lists != null) {
                for (LabModels.Item item : fallback.lists) {
                    if (item != null) applyCommandOverrides(item);
                }
            }
            return fallback;
        }
    }

    private LabModels.LabRoot loadSyncInner() throws IOException {
        loadError = "";
        int source = getSource();
        LabModels.LabRoot root = null;
        if (source == SOURCE_LOCAL) {
            seedTemplate();
            InputStream local = openLocalConfig();
            if (local != null) {
                try {
                    root = parse(readAll(local));
                } finally {
                    local.close();
                }
            } else {
                if (!TextUtils.isEmpty(getLocalPath())) {
                    android.util.Log.w("LabConfig", "本地配置读取失败，改用 APK 内置模板: " + getLocalPath());
                }
                root = loadBuiltinTemplate();
            }
        } else if (source == SOURCE_URL) {
            String url = getUrl();
            if (!TextUtils.isEmpty(url)) {
                String text = download(url);
                saveConfigCache(text);
                root = parse(text);
            }
        }
        // 没有配置源就保持空清单，只保留已导入的包——不再回落到内置 lab.json。
        if (root == null) root = new LabModels.LabRoot();
        mergeImported(root);
        if (root.lists != null) {
            for (LabModels.Item item : root.lists) {
                if (item != null) applyCommandOverrides(item);
            }
        }
        return root;
    }

    /**
     * 内置配置源：直接读 APK 内的 lab_template.json，不依赖外部存储权限或落盘。
     * 上游把「内置配置(0)」删掉后只靠 seedTemplate() 写 /storage/emulated/0/WebHTV/lab.json，
     * 未授「所有文件访问」时写失败被静默吞掉，列表就永远是空的。这里补回兜底。
     */
    private LabModels.LabRoot loadBuiltinTemplate() throws IOException {
        try (InputStream in = App.get().getAssets().open("lab_template.json")) {
            LabModels.LabRoot builtin = parse(readAll(in));
            if (builtin != null && builtin.lists != null && !builtin.lists.isEmpty()) {
                if (TextUtils.isEmpty(configRoot)) configRoot = TextUtils.isEmpty(builtin.root) ? TEMPLATE_ROOT : builtin.root;
                sourceName = App.get().getString(com.fongmi.android.tv.R.string.lab_source_local);
                return builtin;
            }
        } catch (Exception e) {
            throw new IOException("内置模板读取失败: " + e.getMessage());
        }
        return new LabModels.LabRoot();
    }

    private File configCacheFile() {
        return new File(App.get().getFilesDir(), "lab_cache/lab_config_cache.json");
    }

    private void saveConfigCache(String text) {
        try {
            File file = configCacheFile();
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private String readConfigCache() {
        File file = configCacheFile();
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 首次使用本地配置且一个候选位置都没有 lab.json 时，把内置模板写到磁盘。
     * 写出来的是普通文件，用户可以直接改或替换——不同于"内置配置源"那种改不动的隐式来源。
     */
    private void seedTemplate() {
        if (!TextUtils.isEmpty(getLocalPath())) return;
        try {
            File target = new File(templateRoot(), "lab.json");
            if (target.exists()) return;
            for (String candidate : localCandidates()) {
                if (new File(candidate).exists()) return;
            }
            if (target.getParentFile() != null) target.getParentFile().mkdirs();
            try (InputStream in = App.get().getAssets().open("lab_template.json")) {
                Path.copy(in, target);
            }
        } catch (Exception e) {
            android.util.Log.w("LabConfig", "seedTemplate 落盘失败，改用 APK 内置模板", e);
        }
    }

    /**
     * 模板落地目录：优先用户手填的根目录，否则用模板自己声明的 /storage/emulated/0/WebHTV，
     * 这样用户在文件管理器里能直接找到并编辑它。
     */
    private File templateRoot() {
        String override = getRootOverride();
        return new File(TextUtils.isEmpty(override) ? TEMPLATE_ROOT : override);
    }

    private java.util.List<String> localCandidates() {
        String localPath = getLocalPath();
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (!TextUtils.isEmpty(localPath)) candidates.add(localPath);
        candidates.add(new File(getRoot(), "lab.json").getAbsolutePath());
        candidates.add(new File(TEMPLATE_ROOT, "lab.json").getAbsolutePath());
        candidates.add("/storage/emulated/0/Download/lab.json");
        candidates.add("/storage/emulated/0/Downloads/lab.json");
        candidates.add("/storage/emulated/0/Documents/lab.json");
        candidates.add("/storage/emulated/0/lab.json");
        return candidates;
    }

    private InputStream openLocalConfig() throws IOException {
        for (String candidate : localCandidates()) {
            try {
                if (candidate.startsWith("content://")) {
                    InputStream in = App.get().getContentResolver().openInputStream(Uri.parse(candidate));
                    if (in != null) return in;
                } else {
                    File file = new File(candidate);
                    if (file.exists()) return new FileInputStream(file);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void mergeImported(LabModels.LabRoot root) {
        List<LabModels.Item> imported = loadImported();
        if (imported.isEmpty()) return;
        if (root.lists == null) root.lists = new java.util.ArrayList<>();
        for (LabModels.Item item : imported) {
            boolean exists = false;
            for (LabModels.Item other : root.lists) {
                if (other.name != null && other.name.equalsIgnoreCase(item.name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                item.show = true;
                item.available = true;
                root.lists.add(item);
            }
        }
    }

    public void saveImported(String packageJson) {
        try {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(packageJson).getAsJsonObject();
            String name = object.has("name") ? object.get("name").getAsString() : String.valueOf(System.currentTimeMillis());
            Map<String, String> map = importedMap();
            map.put(name, packageJson);
            sp().edit().putString(KEY_IMPORTED, gson.toJson(map)).apply();
        } catch (Exception ignored) {
        }
    }

    public List<LabModels.Item> loadImported() {
        List<LabModels.Item> list = new java.util.ArrayList<>();
        for (String json : importedMap().values()) {
            try {
                LabModels.Item item = gson.fromJson(json, LabModels.Item.class);
                if (item != null && item.name != null) list.add(item);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private Map<String, String> importedMap() {
        String json = sp().getString(KEY_IMPORTED, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void saveUserSettings(String pkg, Map<String, String> values) {
        sp().edit().putString("user_settings_" + pkg, gson.toJson(values)).apply();
    }

    public Map<String, String> loadUserSettings(String pkg) {
        String json = sp().getString("user_settings_" + pkg, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void saveCommandOverride(String pkg, String id, String commandJson) {
        Map<String, String> map = commandOverrides(pkg);
        map.put(id, commandJson);
        sp().edit().putString("cmd_overrides_" + pkg, gson.toJson(map)).apply();
    }

    public String getCommandOverride(String pkg, String id) {
        return commandOverrides(pkg).get(id);
    }

    public void removeCommandOverride(String pkg, String id) {
        Map<String, String> map = commandOverrides(pkg);
        map.remove(id);
        sp().edit().putString("cmd_overrides_" + pkg, gson.toJson(map)).apply();
    }

    public void clearCommandOverrides(String pkg) {
        sp().edit().remove("cmd_overrides_" + pkg).apply();
    }

    private Map<String, String> commandOverrides(String pkg) {
        String json = sp().getString("cmd_overrides_" + pkg, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void applyCommandOverrides(LabModels.Item item) {
        if (item == null || item.commands == null) return;
        Map<String, String> overrides = commandOverrides(item.name);
        if (!overrides.isEmpty()) {
            for (LabModels.Command command : item.commands) {
                String json = overrides.get(command.id);
                if (json == null) continue;
                try {
                    LabModels.Command override = gson.fromJson(json, LabModels.Command.class);
                    if (override == null) continue;
                    if (override.name != null) command.name = override.name;
                    if (override.description != null) command.description = override.description;
                    if (override.command != null) command.command = override.command;
                    command.auto_execute = override.auto_execute;
                } catch (Exception ignored) {
                }
            }
        }
        Map<String, String> caches = commandCaches(item.name);
        if (!caches.isEmpty()) {
            for (LabModels.Command command : item.commands) {
                String json = caches.get(command.id);
                if (json == null) continue;
                try {
                    com.google.gson.JsonObject cache = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    if (cache.has("command") && !cache.get("command").getAsString().isEmpty()) {
                        command.command = cache.get("command").getAsString();
                    }
                    if (cache.has("values") && cache.get("values").isJsonObject()) {
                        Map<String, String> values = gson.fromJson(cache.getAsJsonObject("values"), new TypeToken<Map<String, String>>() {
                        }.getType());
                        if (values != null) command.cachedVariableValues = values;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void saveCommandCache(String pkg, String id, Map<String, String> values, String expandedCommand) {
        Map<String, String> map = commandCaches(pkg);
        com.google.gson.JsonObject cache = new com.google.gson.JsonObject();
        cache.addProperty("command", expandedCommand == null ? "" : expandedCommand);
        cache.add("values", gson.toJsonTree(values == null ? new java.util.HashMap<String, String>() : values));
        map.put(id, cache.toString());
        sp().edit().putString("cmd_cache_" + pkg, gson.toJson(map)).apply();
    }

    public void clearCommandCache(String pkg) {
        sp().edit().remove("cmd_cache_" + pkg).apply();
    }

    private Map<String, String> commandCaches(String pkg) {
        String json = sp().getString("cmd_cache_" + pkg, "");
        if (json.isEmpty()) return new java.util.HashMap<>();
        try {
            Map<String, String> map = gson.fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }

    public void saveInstalledVersion(String pkg, String version) {
        sp().edit().putString("installed_" + pkg, version).apply();
    }

    public String getInstalledVersion(String pkg) {
        return sp().getString("installed_" + pkg, "");
    }

    private LabModels.LabRoot parse(String text) {
        String json = extractJson(text);
        if (json == null) throw new IllegalArgumentException("配置解析失败");
        LabModels.LabRoot parsed = null;
        try {
            parsed = gson.fromJson(json, LabModels.LabRoot.class);
        } catch (Throwable ignored) {
        }
        // release 包开启 minifyEnabled 后，一旦 keep/注解规则被改坏，R8 会把
        // LabRoot.lists 混淆成 a，Gson 反射拿到的一定是 lists=null 的空壳。
        // 这里用原始 JSON 键手工映射兜底，保证内置清单在任何混淆配置下都能加载。
        if (parsed == null || parsed.lists == null || parsed.lists.isEmpty()
                || !(parsed.lists.get(0) instanceof LabModels.Item)) {
            parsed = parseLenient(json);
        }
        if (parsed == null || parsed.lists == null || parsed.lists.isEmpty()) {
            throw new IllegalArgumentException("配置解析失败");
        }
        if (parsed.root != null && !parsed.root.isEmpty()) configRoot = parsed.root;
        else detectRoot(parsed);
        return parsed;
    }

    private LabModels.LabRoot parseLenient(String json) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            LabModels.LabRoot root = new LabModels.LabRoot();
            root.version = str(o, "version");
            root.root = str(o, "root");
            root.groups = new java.util.ArrayList<>();
            com.google.gson.JsonArray gs = arr(o, "groups");
            if (gs != null) {
                for (com.google.gson.JsonElement e : gs) {
                    if (!e.isJsonObject()) continue;
                    com.google.gson.JsonObject g = e.getAsJsonObject();
                    LabModels.Group group = new LabModels.Group();
                    group.id = str(g, "id");
                    group.name = str(g, "name");
                    group.order = integer(g, "order");
                    root.groups.add(group);
                }
            }
            root.lists = new java.util.ArrayList<>();
            com.google.gson.JsonArray ls = arr(o, "lists");
            if (ls != null) {
                for (com.google.gson.JsonElement e : ls) {
                    if (e.isJsonObject()) root.lists.add(parseItem(e.getAsJsonObject()));
                }
            }
            return root;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private LabModels.Item parseItem(com.google.gson.JsonObject o) {
        LabModels.Item it = new LabModels.Item();
        it.name = str(o, "name");
        it.group = str(o, "group");
        it.version = str(o, "version");
        it.min_version = integer(o, "min_version");
        it.icon = str(o, "icon");
        it.info = str(o, "info");
        it.auto_install = bool(o, "auto_install");
        it.available = bool(o, "available");
        it.show = bool(o, "show");
        it.binary_path = str(o, "binary_path");
        it.cmd_name = str(o, "cmd_name");
        it.rootfs = boolObj(o, "rootfs");
        it.mkdir = valueOf(o.get("mkdir"));
        it.var_path = strMap(o, "var_path");
        com.google.gson.JsonArray ds = arr(o, "downloads");
        if (ds != null) {
            it.downloads = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement e : ds) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject d = e.getAsJsonObject();
                LabModels.Download dl = new LabModels.Download();
                dl.arch = str(d, "arch");
                dl.version = str(d, "version");
                dl.url = str(d, "url");
                dl.liburl = str(d, "liburl");
                dl.size = str(d, "size");
                dl.mirrors = mirrorsOf(d.get("mirrors"));
                it.downloads.add(dl);
            }
        }
        com.google.gson.JsonArray cs = arr(o, "commands");
        if (cs != null) {
            it.commands = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement e : cs) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject c = e.getAsJsonObject();
                LabModels.Command cmd = new LabModels.Command();
                cmd.id = str(c, "id");
                cmd.name = str(c, "name");
                cmd.description = str(c, "description");
                cmd.command = str(c, "command");
                cmd.auto_execute = bool(c, "auto_execute");
                cmd.background = bool(c, "background");
                cmd.show_output = boolObj(c, "show_output");
                cmd.min_version = integer(c, "min_version");
                com.google.gson.JsonArray vs = arr(c, "variables");
                if (vs != null) {
                    cmd.variables = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement v : vs) {
                        if (!v.isJsonObject()) continue;
                        com.google.gson.JsonObject vo = v.getAsJsonObject();
                        LabModels.Variable var = new LabModels.Variable();
                        var.key = str(vo, "key");
                        var.name = str(vo, "name");
                        var.type = str(vo, "type");
                        var.defaultValue = str(vo, "default");
                        var.hint = str(vo, "hint");
                        var.filter = str(vo, "filter");
                        var.required = bool(vo, "required");
                        var.min = str(vo, "min");
                        var.max = str(vo, "max");
                        var.options = optionsOf(arr(vo, "options"));
                        cmd.variables.add(var);
                    }
                }
                com.google.gson.JsonArray ks = arr(c, "clicks");
                if (ks != null) {
                    cmd.clicks = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement k : ks) {
                        if (!k.isJsonObject()) continue;
                        com.google.gson.JsonObject ko = k.getAsJsonObject();
                        LabModels.Click click = new LabModels.Click();
                        click.title = str(ko, "title");
                        click.action = str(ko, "action");
                        click.value = str(ko, "value");
                        click.style = str(ko, "style");
                        click.content = str(ko, "content");
                        click.width = integer(ko, "width");
                        click.height = integer(ko, "height");
                        cmd.clicks.add(click);
                    }
                }
                com.google.gson.JsonObject cd = obj(c, "download");
                if (cd != null) {
                    LabModels.CommandDownload d = new LabModels.CommandDownload();
                    d.title = str(cd, "title");
                    d.url = str(cd, "url");
                    d.size = str(cd, "size");
                    d.check_file = str(cd, "check_file");
                    d.save_to = str(cd, "save_to");
                    d.extract_to = str(cd, "extract_to");
                    d.required = bool(cd, "required");
                    d.mirrors = mirrorsOf(cd.get("mirrors"));
                    cmd.download = d;
                }
                it.commands.add(cmd);
            }
        }
        com.google.gson.JsonArray ss = arr(o, "settings");
        if (ss != null) {
            it.settings = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement e : ss) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject so = e.getAsJsonObject();
                LabModels.Setting st = new LabModels.Setting();
                st.key = str(so, "key");
                st.name = str(so, "name");
                st.type = str(so, "type");
                st.defaultValue = str(so, "default");
                st.hint = str(so, "hint");
                st.editable = boolObj(so, "editable");
                st.options = optionsOf(arr(so, "options"));
                it.settings.add(st);
            }
        }
        return it;
    }

    private static List<LabModels.Option> optionsOf(com.google.gson.JsonArray a) {
        if (a == null) return null;
        List<LabModels.Option> list = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            com.google.gson.JsonObject o = e.getAsJsonObject();
            LabModels.Option op = new LabModels.Option();
            op.label = str(o, "label");
            op.value = str(o, "value");
            list.add(op);
        }
        return list;
    }

    private static Object mirrorsOf(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, String> map = new java.util.HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> en : e.getAsJsonObject().entrySet()) {
                try { map.put(en.getKey(), en.getValue().getAsString()); } catch (Exception ignored) {}
            }
            return map;
        }
        if (e.isJsonArray()) {
            List<String> list = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                try { list.add(x.getAsString()); } catch (Exception ignored) {}
            }
            return list;
        }
        return null;
    }

    private static Object valueOf(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonArray()) {
            List<String> list = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                try { list.add(x.getAsString()); } catch (Exception ignored) {}
            }
            return list;
        }
        try { return e.getAsString(); } catch (Exception ignored) { return null; }
    }

    private static Map<String, String> strMap(com.google.gson.JsonObject o, String key) {
        com.google.gson.JsonObject m = obj(o, key);
        if (m == null) return null;
        Map<String, String> map = new java.util.HashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> en : m.entrySet()) {
            try { map.put(en.getKey(), en.getValue().getAsString()); } catch (Exception ignored) {}
        }
        return map;
    }

    private static String str(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception ignored) { return null; }
    }

    private static int integer(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try { return o.get(key).getAsInt(); } catch (Exception ignored) { return 0; }
    }

    private static boolean bool(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        try { return o.get(key).getAsBoolean(); } catch (Exception ignored) { return false; }
    }

    private static Boolean boolObj(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsBoolean(); } catch (Exception ignored) { return null; }
    }

    private static com.google.gson.JsonArray arr(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        return o.getAsJsonArray(key);
    }

    private static com.google.gson.JsonObject obj(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```").matcher(trimmed);
        if (matcher.find()) return matcher.group(1).trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return null;
    }

    private void detectRoot(LabModels.LabRoot parsed) {
        if (parsed == null || parsed.lists == null) return;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (LabModels.Item item : parsed.lists) {
            if (item.downloads != null) {
                for (LabModels.Download download : item.downloads) {
                    score(counts, download.url);
                    score(counts, download.liburl);
                }
            }
            if (item.commands != null) {
                for (LabModels.Command command : item.commands) {
                    score(counts, command.command);
                    if (command.download != null) score(counts, command.download.url);
                    if (command.variables != null) {
                        for (LabModels.Variable variable : command.variables) score(counts, variable.defaultValue);
                    }
                }
            }
        }
        String best = null;
        int bestScore = 0;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        if (best != null) configRoot = "/storage/emulated/0/" + best;
    }

    private void score(java.util.Map<String, Integer> counts, String text) {
        if (text == null) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:/file/|/storage/emulated/0/)([A-Za-z0-9_\\-]+)/").matcher(text);
        while (matcher.find()) {
            String base = matcher.group(1);
            if (isCommonDir(base)) continue;
            counts.put(base, counts.getOrDefault(base, 0) + 1);
        }
    }

    private boolean isCommonDir(String base) {
        return "Download".equals(base)
                || "Downloads".equals(base)
                || "Documents".equals(base)
                || "TV".equals(base)
                || "DCIM".equals(base)
                || "Android".equals(base)
                || "Pictures".equals(base)
                || "Music".equals(base)
                || "Movies".equals(base);
    }

    private String describeSource() {
        int key = getSource() == SOURCE_URL ? com.fongmi.android.tv.R.string.lab_source_url : com.fongmi.android.tv.R.string.lab_source_local;
        return App.get().getString(key);
    }

    public static String serverPort() {
        int port = Proxy.getPort();
        if (port <= 0) {
            Server.get().startManage();
            port = Proxy.getPort();
        }
        return String.valueOf(port > 0 ? port : 9978);
    }

    public static String dataPath() {
        return LabConfig.get().getRoot();
    }

    private static String download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "WebHTV-Lab");
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
