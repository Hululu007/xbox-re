package com.fongmi.android.tv.api.hk;

import android.util.Log;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 海阔订阅 → xbox-re 原生点播/详情/播放链路的统一网关。
 *
 * <p><b>为什么只需要改 SiteApi 一个文件</b>：SiteViewModel 的 homeContent /
 * categoryContent / detailContent / searchContent / playerContent 全部经由
 * {@link com.fongmi.android.tv.api.SiteApi} 的静态方法，因此在这五个方法开头
 * 按 {@code hk_} 前缀分流即可，VodFragment / FolderFragment / VideoActivity
 * 等 UI 层<b>一行都不用改</b>，详情页天然是原生 View，不是 HTML/WebView 页面。</p>
 *
 * <p><b>数据流</b>：
 * <pre>
 *   HkRuleManager (plugins/hk/*.json)
 *     └─ HkSiteInjector.inject()  → VodConfig.getSites() 注入 hk_&lt;规则名&gt; 站点
 *   SiteApi.homeContent/categoryContent/searchContent/detailContent/playerContent
 *     └─ HkGateway.*  → HkRouter.home/search/detail/play
 *          ├─ HkItem / SearchResult → Vod → Result.list()
 *          ├─ HkDetail → HkDetailBridge.toVod() → Vod/Flag/Episode → Result.vod()
 *          └─ HkPlay → Result(url/header/subs/parse/flag/key)
 *     ↓ SiteViewModel LiveData
 *   原生 VodFragment(RecyclerView) / VideoActivity.setDetail(Vod) / Players
 * </pre>
 *
 * <p>本类不引用任何星辰 UI 资源；{@link HkWebkitFetcher} 的后台 WebView 只由
 * {@code webRule://} 嗅探间接触发，与界面渲染无关。</p>
 */
public final class HkGateway {

    public static final String PREFIX = "hk_";
    private static final String TAG = "HkGateway";

    /** HkRouter 进程内缓存：避免每次点选集都重跑 preRule（Rhino 上下文很贵）。 */
    private static final int MAX_ROUTERS = 3;
    private static final long ROUTER_TTL = 15 * 60 * 1000L;
    private static final LinkedHashMap<String, CachedRouter> ROUTERS = new LinkedHashMap<>();

    /** 列表项元数据缓存：detailContent 只有 key+id，需要回查标题/海报/简介构造 HkItem。 */
    private static final int MAX_ITEMS = 500;
    private static final LinkedHashMap<String, HkItem> ITEMS =
            new LinkedHashMap<String, HkItem>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HkItem> eldest) {
                    return size() > MAX_ITEMS;
                }
            };

    private HkGateway() {
    }

    /* ---------------- 前缀判定 ---------------- */

    public static boolean isHk(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    public static boolean isHk(Site site) {
        return site != null && isHk(site.getKey());
    }

    /** 从 hk_&lt;规则名&gt; 取回规则名。 */
    public static String titleOf(String key) {
        return isHk(key) ? key.substring(PREFIX.length()) : "";
    }

    /* ---------------- 站点注入 ---------------- */

    /**
     * 把已启用的海阔规则注入 VodConfig 的站点列表，让原生「站点切换」能看到它们。
     * 幂等：先移除旧的 hk_ 站点再按当前启用规则重建。
     *
     * <p>调用时机：VodConfig.load() 成功之后（App 初始化回调 / 配置重载回调 /
     * 脚本管理「确定」之后）。若 getSites() 返回不可变集合，会静默跳过并在日志提示。</p>
     */
    public static void injectSites() {
        try {
            // 站点一律从 B（hk.state.json）整体重建；管理页与首页同源。
            // 不可变空表不能原位 remove/add，统一交给 HkStateUpdater 构造新 List 写回。
            com.fongmi.android.tv.api.hk.state.HkStateUpdater.applyHomeSites();
        } catch (Throwable e) {
            Log.w(TAG, "injectSites skipped: " + e);
        }
    }

    private static List<String> classNames(HkRule rule) {
        List<String> out = new ArrayList<>();
        try {
            for (String[] pair : rule.getClassPairs()) {
                if (pair.length > 0 && pair[0] != null && !pair[0].isEmpty()) out.add(pair[0]);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ---------------- 五个内容入口 ---------------- */

    /** SiteApi.homeContent(Site) 的 hk_ 分支。 */
    public static Result home(Site site) {
        String title = titleOf(site.getKey());
        try {
            HkRule rule = HkRuleManager.get().getRule(title);
            HkRouter router = router(title);
            List<HkItem> items = router.home(1, "", "", "", "");
            Result result = Result.list(toVods(items, site));
            result.setTypes(typesOf(rule));
            return result;
        } catch (Throwable e) {
            Log.e(TAG, "home failed: " + title, e);
            return Result.error("海阔首页失败：" + e.getMessage());
        }
    }

    /** SiteApi.categoryContent(key, tid, page, filter, extend) 的 hk_ 分支。 */
    public static Result category(String key, String tid, String page, boolean filter,
                                  HashMap<String, String> extend) {
        String title = titleOf(key);
        try {
            int p = parseInt(page, 1);
            String area = extend == null ? "" : nvl(extend.get("area"));
            String year = extend == null ? "" : nvl(extend.get("year"));
            String sort = extend == null ? "" : nvl(extend.get("sort"));
            List<HkItem> items = router(title).home(p, nvl(tid), area, year, sort);
            return Result.list(toVods(items, siteOf(key, title)));
        } catch (Throwable e) {
            Log.e(TAG, "category failed: " + title, e);
            return Result.error("海阔分类失败：" + e.getMessage());
        }
    }

    /** SiteApi.searchContent(Site, keyword, quick, page) 的 hk_ 分支。 */
    public static Result search(Site site, String keyword, boolean quick, String page) {
        String title = titleOf(site.getKey());
        try {
            int p = parseInt(page, 1);
            List<SearchResult> list = router(title).search(keyword, p);
            List<Vod> vods = new ArrayList<>();
            for (SearchResult sr : list) {
                if (sr == null) continue;
                Vod vod = new Vod();
                vod.setId(nvl(sr.getUrl()));
                vod.setName(nvl(sr.getTitle()));
                vod.setPic(nvl(sr.getImg()));
                vod.setContent(firstNonEmpty(sr.getDesc(), sr.getContent()));
                vod.setSite(site);
                vods.add(vod);
                remember(new HkItem(vod.getName(), vod.getId(), vod.getPic(), vod.getContent()));
            }
            return Result.list(vods);
        } catch (Throwable e) {
            Log.e(TAG, "search failed: " + title, e);
            return Result.error("海阔搜索失败：" + e.getMessage());
        }
    }

    /** SiteApi.detailContent(key, id) 的 hk_ 分支 —— 产出原生 VideoActivity 需要的 Vod。 */
    public static Result detail(String key, String id) {
        String title = titleOf(key);
        try {
            HkRule rule = HkRuleManager.get().getRule(title);
            if (rule == null) return Result.error("海阔规则不存在：" + title);
            HkItem item = ITEMS.get(id);
            if (item == null) item = new HkItem("", id, "", "");
            HkDetail detail = router(title).detail(id, item, false);
            Vod vod = HkDetailBridge.toVod(rule, id, detail);
            // 详情桥接可能没带站点信息，补上，保证后续 playerContent 的 key 仍是 hk_
            vod.setSite(siteOf(key, title));
            return Result.vod(vod);
        } catch (Throwable e) {
            Log.e(TAG, "detail failed: " + title, e);
            return Result.error("海阔详情失败：" + e.getMessage());
        }
    }

    /** SiteApi.playerContent(key, flag, id, playerType) 的 hk_ 分支。 */
    public static Result player(String key, String flag, String id, int playerType) {
        String title = titleOf(key);
        try {
            HkPlay play = router(title).play(id);
            int line = HkRouter.lineIndex(title, id);
            String url = play.getUrl(line);
            Result result = new Result();
            result.setUrl(url);
            result.setParse(0);                       // 海阔已给出真实地址，不需要解析
            result.setFlag(flag);
            result.setKey(key);
            Map<String, String> headers = play.getHeaders(line);
            if (headers != null && !headers.isEmpty()) result.setHeader(headers);  // Referer/Cookie 不能丢
            String subtitle = play.getSubtitle();
            if (subtitle != null && !subtitle.isEmpty())
                result.setSubs(Collections.singletonList(Sub.from(subtitle)));
            if (HkRouter.isP2pPlayLink(url))          // magnet/ed2k/thunder/ftp
                result.setUrl(Source.get().fetch(result, playerType));
            return result;
        } catch (Throwable e) {
            Log.e(TAG, "player failed: " + title, e);
            return Result.error("海阔播放失败：" + e.getMessage());
        }
    }

    /* ---------------- 内部工具 ---------------- */

    private static List<Vod> toVods(List<HkItem> items, Site site) {
        List<Vod> vods = new ArrayList<>();
        if (items == null) return vods;
        for (HkItem item : items) {
            if (item == null) continue;
            Vod vod = new Vod();
            vod.setId(nvl(item.getUrl()));
            vod.setName(nvl(item.getTitle()));
            vod.setPic(firstNonEmpty(item.getImageUrl(), item.getPic()));
            vod.setContent(firstNonEmpty(item.getDesc(), item.getContent()));
            vod.setSite(site);
            vods.add(vod);
            remember(item);
        }
        return vods;
    }

    private static void remember(HkItem item) {
        if (item == null || item.getUrl().isEmpty()) return;
        synchronized (ITEMS) {
            ITEMS.put(item.getUrl(), item);
        }
    }

    private static List<com.fongmi.android.tv.bean.Class> typesOf(HkRule rule) {
        List<com.fongmi.android.tv.bean.Class> types = new ArrayList<>();
        try {
            for (String[] pair : rule.getClassPairs()) {
                if (pair == null || pair.length == 0) continue;
                com.fongmi.android.tv.bean.Class c = new com.fongmi.android.tv.bean.Class();
                c.setTypeName(nvl(pair[0]));
                c.setTypeId(pair.length > 1 ? nvl(pair[1]) : nvl(pair[0]));
                types.add(c);
            }
        } catch (Throwable ignored) {
        }
        if (types.isEmpty()) {
            com.fongmi.android.tv.bean.Class c = new com.fongmi.android.tv.bean.Class();
            c.setTypeId("");
            c.setTypeName("全部");
            types.add(c);
        }
        return types;
    }

    private static Site siteOf(String key, String title) {
        Site site = VodConfig.get().getSite(key);
        if (site == null || site.isEmpty()) return Site.get(key, "海阔·" + title);
        return site;
    }

    /** 带 LRU + TTL 的 HkRouter 复用，避免重复初始化 Rhino。 */
    private static synchronized HkRouter router(String title) throws Exception {
        long now = System.currentTimeMillis();
        CachedRouter cached = ROUTERS.get(title);
        if (cached != null && now - cached.time < ROUTER_TTL) {
            ROUTERS.remove(title);
            ROUTERS.put(title, cached);       // 移到队尾
            return cached.router;
        }
        if (cached != null) {
            try {
                cached.router.destroy();
            } catch (Throwable ignored) {
            }
        }
        while (ROUTERS.size() >= MAX_ROUTERS) {
            String oldest = ROUTERS.keySet().iterator().next();
            CachedRouter drop = ROUTERS.remove(oldest);
            try {
                drop.router.destroy();
            } catch (Throwable ignored) {
            }
        }
        HkRouter router = HkRouter.open(title);
        ROUTERS.put(title, new CachedRouter(router, now));
        return router;
    }

    /** 规则重载/删除后调用，避免拿着旧上下文。 */
    public static void invalidate(String title) {
        synchronized (ROUTERS) {
            CachedRouter drop = ROUTERS.remove(title);
            if (drop != null) {
                try {
                    drop.router.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void invalidateAll() {
        synchronized (ROUTERS) {
            for (CachedRouter c : ROUTERS.values()) {
                try {
                    c.router.destroy();
                } catch (Throwable ignored) {
                }
            }
            ROUTERS.clear();
        }
        synchronized (ITEMS) {
            ITEMS.clear();
        }
    }

    private static final class CachedRouter {
        final HkRouter router;
        long time;

        CachedRouter(HkRouter router, long time) {
            this.router = router;
            this.time = time;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            return def;
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return "";
    }
}
