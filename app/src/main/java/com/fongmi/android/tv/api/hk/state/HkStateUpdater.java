package com.fongmi.android.tv.api.hk.state;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.hk.HkGateway;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 唯一更新器：刷新 / 失效 / 拉黑 / 删除 / 归类 / 更名 一律只写 A、B 两份 json。
 * 管理页与首页都只读 B；全量刷新整体覆盖，改口令只按 feed 增量替换。
 */
public final class HkStateUpdater {

    private static final String TAG = "HkStateUpdater";

    private HkStateUpdater() {}

    public interface Done { void run(); }

    /** 全量刷新：所有 doc 重新解析，B 整体覆盖。冷启动 / 首页前台 / 顶部刷新走这里。 */
    public static void refreshAll(Done done) {
        new Thread(() -> {
            HkState state = HkStateStore.readState();
            Map<String, List<HkSiteEntry>> old = snapshot(state);
            List<HkSiteEntry> all = new ArrayList<>();
            for (String doc : HkStateStore.docNames()) {
                all.addAll(refreshDocInternal(doc, state, old, false));
            }
            state.sites = all;
            HkStateStore.writeState(state);
            App.post(() -> { publishNow(); if (done != null) done.run(); });
        }, "hk-refresh").start();
    }

    /** 按 feed 增量：只重拉这一条、只替换 B 里该 feedId 段，不碰其它 doc。 */
    public static void refreshFeed(String doc, String feedId, Done done) {
        new Thread(() -> {
            HkState state = HkStateStore.readState();
            Map<String, List<HkSiteEntry>> old = snapshot(state);
            HkDoc d = HkStateStore.readDoc(doc);
            HkFeed target = null;
            for (HkFeed f : d.feeds) if (feedId.equals(f.feedId)) { target = f; break; }
            if (target != null) {
                List<HkSiteEntry> fresh = resolveOne(doc, target, state, old);
                HkStateStore.replaceFeed(state, doc, feedId, fresh);
                HkStateStore.writeState(state);
            }
            App.post(() -> { publishNow(); if (done != null) done.run(); });
        }, "hk-feed").start();
    }

    /** 写完 A/B 后统一发布：清引擎缓存 → 重建首页海阔站点 → 通知 UI 刷新。 */
    public static void publish() {
        App.post(HkStateUpdater::publishNow);
    }

    /** 主线程执行；refreshAll / refreshFeed 的收尾也直接调这里，避免双重 post。 */
    private static void publishNow() {
        try { HkGateway.invalidateAll(); } catch (Throwable e) { Log.e(TAG, "invalidateAll failed", e); }
        applyHomeSites();
        try { ConfigEvent.vod(); } catch (Throwable e) { Log.e(TAG, "vod event failed", e); }
    }

    private static List<HkSiteEntry> refreshDocInternal(String doc, HkState state,
                                                        Map<String, List<HkSiteEntry>> old, boolean write) {
        HkDoc d = HkStateStore.readDoc(doc);
        // 同口令直接合并：不解析、不重复落盘
        Set<String> seenRaw = new LinkedHashSet<>();
        List<HkFeed> unique = new ArrayList<>();
        boolean docChanged = false;
        for (HkFeed f : d.feeds) {
            String n = HkFeedParser.normalize(f.raw);
            if (n.isEmpty() || !seenRaw.add(n)) { docChanged = true; continue; }
            unique.add(f);
        }
        if (docChanged) { d.feeds = unique; HkStateStore.writeDoc(d); }

        List<HkSiteEntry> out = new ArrayList<>();
        Set<String> fp = new HashSet<>();
        int order = 0;
        for (HkFeed f : unique) {
            List<HkSiteEntry> one = resolveOne(doc, f, state, old);
            // 跨口令配置相同：单接口内谁先留谁
            for (HkSiteEntry e : one) {
                String k = e.id.substring(e.id.lastIndexOf('|') + 1);
                if (!fp.add(k)) continue;
                e.firstSeen = ++order;
                out.add(e);
            }
            // 口令状态：拉取失败 / 0 有效站点 = 失效（自动回）；拉黑保持（手动回）
            boolean anyValid = false;
            for (HkSiteEntry e : one) if ("正常".equals(e.mark)) { anyValid = true; break; }
            String next = "拉黑".equals(f.mark) ? "拉黑" : (anyValid ? "正常" : "失效");
            if (!next.equals(f.mark)) { f.mark = next; docChanged = true; }
        }
        if (docChanged) HkStateStore.writeDoc(d);
        applyDisplayNames(out);
        return out;
    }

    private static List<HkSiteEntry> resolveOne(String doc, HkFeed feed, HkState state,
                                                Map<String, List<HkSiteEntry>> old) {
        HkFeedParser.Parsed p = HkFeedParser.parse(doc, feed);
        Map<String, HkSiteEntry> before = new HashMap<>();
        for (HkSiteEntry e : old.getOrDefault(doc + "|" + feed.feedId, new ArrayList<>()))
            before.put(e.id, e);
        List<HkSiteEntry> out = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (HkSiteEntry e : p.sites) {
            now.add(e.id);
            HkSiteEntry prev = before.get(e.id);
            if (prev != null) {
                e.firstSeen = prev.firstSeen;
                if ("拉黑".equals(prev.mark)) { e.mark = "拉黑"; e.origin = prev.origin; }  // 手动恢复制
                // 失效 → 重新有效：自动置回正常（prev 是失效时不继承）
            }
            out.add(e);
        }
        for (HkSiteEntry e : p.invalidNames.isEmpty() ? new ArrayList<HkSiteEntry>() : invalidEntries(doc, feed, p.invalidNames, before))
            out.add(e);
        // 本次不再出现：远程消失 → 拉黑(auto)，保留墓碑索引便于回收站显示与手动恢复
        for (Map.Entry<String, HkSiteEntry> en : before.entrySet()) {
            if (now.contains(en.getKey()) || "失效".equals(en.getValue().mark)) continue;
            HkSiteEntry tomb = en.getValue();
            if (!"拉黑".equals(tomb.mark)) { tomb.mark = "拉黑"; tomb.origin = "auto"; }
            out.add(tomb);
        }
        return out;
    }

    private static List<HkSiteEntry> invalidEntries(String doc, HkFeed feed, List<String> names,
                                                    Map<String, HkSiteEntry> before) {
        List<HkSiteEntry> out = new ArrayList<>();
        for (String n : names) {
            String id = doc + "|" + feed.feedId + "|invalid:" + n;
            HkSiteEntry e = before.containsKey(id) ? before.get(id) : new HkSiteEntry();
            e.doc = doc; e.feedId = feed.feedId; e.id = id;
            e.title = n; e.displayName = n; e.mark = "失效"; e.origin = "auto";
            out.add(e);
        }
        return out;
    }

    private static void applyDisplayNames(List<HkSiteEntry> list) {
        Map<String, Integer> seen = new HashMap<>();
        for (HkSiteEntry e : list) {
            int n = seen.getOrDefault(e.title, 0);
            seen.put(e.title, n + 1);
            e.displayName = n == 0 ? e.title : e.title + "|" + n;
        }
    }

    private static Map<String, List<HkSiteEntry>> snapshot(HkState state) {
        Map<String, List<HkSiteEntry>> out = new HashMap<>();
        for (HkSiteEntry e : state.sites)
            out.computeIfAbsent(e.doc + "|" + e.feedId, k -> new ArrayList<>()).add(e);
        return out;
    }

    /* ---------------- 用户动作（都通过更新 json 处理） ---------------- */

    public static void setMark(String doc, String feedId, List<String> siteIds, String mark, String origin) {
        HkState s = HkStateStore.readState();
        for (HkSiteEntry e : s.sites)
            if (doc.equals(e.doc) && feedId.equals(e.feedId) && siteIds.contains(e.id)) {
                e.mark = mark; e.origin = origin;
            }
        HkStateStore.writeState(s);
        publish();
    }

    public static void blacklistFeed(String doc, String feedId) {
        HkDoc d = HkStateStore.readDoc(doc);
        for (HkFeed f : d.feeds) if (feedId.equals(f.feedId)) f.mark = "拉黑";
        HkStateStore.writeDoc(d);
        publish();
    }

    public static void restoreFeed(String doc, String feedId) {
        HkDoc d = HkStateStore.readDoc(doc);
        for (HkFeed f : d.feeds) if (feedId.equals(f.feedId)) f.mark = "正常";
        HkStateStore.writeDoc(d);
        publish();
    }

    /** 删除 = 物理删 A 里那条，并清掉 B 里该 feedId 段（站点无物理删）。 */
    public static void deleteFeed(String doc, String feedId) {
        HkDoc d = HkStateStore.readDoc(doc);
        List<HkFeed> keep = new ArrayList<>();
        for (HkFeed f : d.feeds) if (!feedId.equals(f.feedId)) keep.add(f);
        d.feeds = keep;
        HkStateStore.writeDoc(d);
        HkState s = HkStateStore.readState();
        HkStateStore.removeFeed(s, doc, feedId);
        HkStateStore.writeState(s);
        publish();
    }

    public static void editFeed(String doc, String feedId, String raw) {
        HkDoc d = HkStateStore.readDoc(doc);
        for (HkFeed f : d.feeds) if (feedId.equals(f.feedId)) { f.raw = raw; break; }
        HkStateStore.writeDoc(d);
        refreshFeed(doc, feedId, null);   // 按 feed 增量刷新，不全部更新
    }

    public static void moveFeeds(String doc, List<String> feedIds, String category) {
        HkDoc d = HkStateStore.readDoc(doc);
        for (HkFeed f : d.feeds) if (feedIds.contains(f.feedId)) f.category = category;
        if (!d.categories.contains(category)) d.categories.add(category);
        HkStateStore.writeDoc(d);
        publish();
    }

    public static void renameCategory(String doc, String oldName, String newName) {
        HkDoc d = HkStateStore.readDoc(doc);
        if (d.categories.contains(newName) && !newName.equals(oldName)) {
            d.categories.remove(oldName);
        } else {
            int i = d.categories.indexOf(oldName);
            if (i >= 0) d.categories.set(i, newName);
        }
        for (HkFeed f : d.feeds) if (oldName.equals(f.category)) f.category = newName;
        HkStateStore.writeDoc(d);
        publish();
    }

    /** 一级接口更名：6 处连锁一次做完。 */
    public static void renameDoc(String oldName, String newName) {
        HkDoc d = HkStateStore.readDoc(oldName);
        d.name = newName;
        HkStateStore.writeDoc(d);
        HkStateStore.deleteDoc(oldName);
        HkState s = HkStateStore.readState();
        for (HkSiteEntry e : s.sites) {
            if (!oldName.equals(e.doc)) continue;
            e.doc = newName;
            e.id = newName + e.id.substring(oldName.length());
        }
        HkStateStore.writeState(s);
        Config.delete(HkInterface.configUrl(oldName), 0);
        Config.create(0, HkInterface.configUrl(newName), newName);
        if (oldName.equals(HkStateStore.currentDoc())) HkStateStore.setCurrentDoc(newName);
        publish();
    }

    /** 兼容旧调用点；VodConfig.load 用带 url 的重载，避免依赖 this.config 的切换时机。 */
    public static void injectHomeSites() {
        injectHomeSites(null);
    }

    public static void injectHomeSites(String url) {
        applyHomeSites(url);
    }

    public static void applyHomeSites() {
        applyHomeSites(null);
    }

    /** 首页站点：全 doc 全部有效站点，重名 <接口名>|N。
     *  只在当前点播接口是 hk:/ 时重建，避免在标准接口下覆盖原有站点列表；
     *  getSites() 可能是 Collections.emptyList()（clear 之后），一律构造新 ArrayList 整体写回，
     *  绝不原位 remove/add；home 仍有效时不覆盖用户当前选中的站点。 */
    public static void applyHomeSites(String url) {
        String u = url == null ? VodConfig.getUrl() : url;
        if (!HkInterface.isHkUrl(u)) return;
        try {
            List<Site> current = VodConfig.get().getSites();
            List<Site> out = new ArrayList<>();
            if (current != null)
                for (Site item : current)
                    if (!HkInterface.isHkKey(item.getKey())) out.add(item);
            HkState s = HkStateStore.readState();
            Map<String, Integer> nameCount = new HashMap<>();
            for (HkSiteEntry e : s.sites) {
                if (!"正常".equals(e.mark)) continue;
                int n = nameCount.getOrDefault(e.displayName, 0);
                nameCount.put(e.displayName, n + 1);
                String label = n == 0 ? e.displayName : e.displayName + "<" + e.doc + ">|" + n;
                Site site = Site.get("hk_" + e.title, "海阔·" + label);
                site.setType(3);
                site.setApi("hk://" + e.title);
                site.setSearchable(1);
                site.setChangeable(1);
                site.setQuickSearch(1);
                site.setExt("");
                out.add(site);
            }
            VodConfig.get().setSites(out);
            Site home = VodConfig.get().getHome();
            boolean keep = home != null && !TextUtils.isEmpty(home.getKey());
            if (keep) {
                keep = false;
                for (Site item : out) if (item.getKey().equals(home.getKey())) { keep = true; break; }
            }
            if (!keep && !out.isEmpty()) VodConfig.get().setHome(out.get(0));
        } catch (Throwable e) {
            Log.e(TAG, "applyHomeSites failed", e);
        }
    }
}
