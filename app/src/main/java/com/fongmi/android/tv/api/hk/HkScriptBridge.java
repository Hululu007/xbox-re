package com.fongmi.android.tv.api.hk;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「脚本管理界面」与「海阔规则」之间的桥：
 *
 * <ul>
 *   <li>{@link #importText(String, String)} —— 海阔导入按钮的四种输入分流
 *       （云口令 / http(s) 订阅 / 规则 JSON / 本地包），成功后写一条
 *       {@code hk_&lt;规则名&gt;[hk]} 指针条目到「脚本扫描.json」；</li>
 *   <li>{@link #syncFromStore(String)} —— 脚本管理点「确定」时，把两栏/回收站里
 *       海阔条目的启停、删除、排序同步回 {@link HkRuleManager}；</li>
 *   <li>{@link #refresh()} —— 规则变更后重新注入站点并清空 HkRouter 缓存。</li>
 * </ul>
 *
 * <p>规则本体始终落在 {@code filesDir/plugins/hk/*.json}（由 HkRuleManager 负责）；
 * 「脚本扫描.json」里只有一条轻量的指针条目，用于复用现成的两栏/回收站/排序管理。</p>
 */
public final class HkScriptBridge {

    private static final String TAG = "HkScriptBridge";

    private HkScriptBridge() {
    }

    /**
     * 导入入口。请在后台线程调用（Task.execute）。
     *
     * @param dir   当前脚本文件夹路径（ScriptManageDialog.currentDir）
     * @param input 用户在「海阔导入」输入框里粘贴的内容 / 或本地包绝对路径
     * @return 导入后的规则名列表，供 UI 提示
     */
    public static List<String> importText(String dir, String input) throws Exception {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("请输入订阅内容");

        List<HkRule> rules = new ArrayList<>();

        // ① 本地包（.zip / .hkzip / .json 文件的绝对路径）
        File local = new File(text);
        if (local.isFile()) {
            String n = local.getName().toLowerCase();
            if (n.endsWith(".zip") || n.endsWith(".hkzip")) {
                rules.addAll(HkRuleManager.get().importZip(local));
            } else {
                String json = new String(java.nio.file.Files.readAllBytes(local.toPath()), StandardCharsets.UTF_8);
                HkRule r = HkRuleManager.get().importJson(json);
                if (r != null) rules.add(r);
            }
        }
        // ② 云口令（云Noooole/xxx、http://.../paste/?password= 等）
        else if (HkRuleManager.isCloudCode(text)) {
            HkRule r = HkRuleManager.get().importByCloudCode(text);
            if (r != null) rules.add(r);
        }
        // ③ 订阅 URL：GET 文本后再按 JSON / JS 规则导入
        else if (text.startsWith("http://") || text.startsWith("https://")) {
            String body = httpGet(text);
            if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
                rules.addAll(HkRuleManager.get().importJsonList(body));
            } else if (HkRuleManager.isJsRuleText(body)) {
                HkRule r = HkRuleManager.get().importJsRule(body, "subscribe.js");
                if (r != null) rules.add(r);
            } else {
                HkRule r = HkRuleManager.get().importJson(body);
                if (r != null) rules.add(r);
            }
        }
        // ④ 内联 JSON / JSON 数组 / JS 规则文本
        else if (text.startsWith("{") || text.startsWith("[")) {
            rules.addAll(HkRuleManager.get().importJsonList(text));
        } else if (HkRuleManager.isJsRuleText(text)) {
            HkRule r = HkRuleManager.get().importJsRule(text, "inline.js");
            if (r != null) rules.add(r);
        } else {
            throw new IllegalArgumentException("无法识别的订阅格式（支持云口令 / URL / JSON / 本地包路径）");
        }

        List<String> titles = new ArrayList<>();
        for (HkRule rule : rules) {
            if (rule == null) continue;
            String title = rule.getTitle();
            if (title == null || title.isEmpty()) continue;
            titles.add(title);
            // 写指针条目：key = hk_<规则名>[hk]，让 ScriptManageDialog 能列出/停用/删除/排序
            com.fongmi.android.tv.utils.LocalScriptSource.addHkItem(dir, title);
        }
        return titles;
    }

    /**
     * 脚本管理「确定」时调用：把工作区/回收站里的 hk_ 指针同步为规则启停，
     * 并把已从 store 中彻底删除的规则从 plugins/hk 里删掉。
     *
     * <p>约定：工作区 = 启用，回收站 = 停用；不在 store 里的已导入规则
     * （用户点了「删除」）= 物理删除。</p>
     */
    public static void syncFromStore(String dir) {
        try {
            com.fongmi.android.tv.utils.LocalScriptSource.HkStore store =
                    com.fongmi.android.tv.utils.LocalScriptSource.readHkStore(dir);
            Set<String> enabled = new HashSet<>(store.enabled);

            for (HkRule rule : HkRuleManager.get().getRules()) {
                String title = rule.getTitle();
                if (title == null || title.isEmpty()) continue;
                if (store.known.contains(title) && !store.enabled.contains(title)) {
                    // 在 store 里但被移到回收站 → 停用
                    HkRuleManager.get().setEnabled(title, false);
                } else if (enabled.contains(title)) {
                    HkRuleManager.get().setEnabled(title, true);
                } else if (!store.known.contains(title)) {
                    // 曾导入过、现在 store 里已无该条目 → 删除规则本体
                    HkRuleManager.get().delete(title);
                }
            }
            refresh();
        } catch (Throwable e) {
            Log.w(TAG, "syncFromStore: " + e);
        }
    }

    /** 规则发生变化后：重新注入站点 + 清空 Router/条目缓存。 */
    public static void refresh() {
        HkGateway.invalidateAll();
        HkGateway.injectSites();
    }

    /** 简易 GET：不引入额外网络库，10s 超时，UTF-8。 */
    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/128 Mobile");
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
