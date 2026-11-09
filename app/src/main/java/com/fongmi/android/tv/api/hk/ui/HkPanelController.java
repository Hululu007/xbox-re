package com.fongmi.android.tv.api.hk.ui;

import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.state.HkDoc;
import com.fongmi.android.tv.api.hk.state.HkFeed;
import com.fongmi.android.tv.api.hk.state.HkFeedParser;
import com.fongmi.android.tv.api.hk.state.HkInterface;
import com.fongmi.android.tv.api.hk.state.HkSiteEntry;
import com.fongmi.android.tv.api.hk.state.HkState;
import com.fongmi.android.tv.api.hk.state.HkStateStore;
import com.fongmi.android.tv.api.hk.state.HkStateUpdater;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「海阔脚本」tab 的完整控制器。与 ScriptManageDialog 共外壳，本地脚本那段一行不动。
 * 所有状态读写都经 HkStateStore / HkStateUpdater（A 静态、B 动态），页面只读 B。
 */
public final class HkPanelController {

    private final FragmentActivity activity;
    private final boolean tv;
    private View root;
    private LinearLayout docList, catRow, actionLeft, actionCenter, actionRight;
    private RecyclerView feedRecycler, siteRecycler;
    private FeedAdapter feedAdapter;
    private SiteAdapter siteAdapter;

    private String doc = "";
    private String mode = "全部";          // 全部 / 分类名 / 黑名单
    private String focusFeed = "";
    private String lastDocTap = "";
    private String lastCatTap = "";
    private final Set<String> feedSel = new LinkedHashSet<>();
    private final Set<String> siteSel = new LinkedHashSet<>();

    public HkPanelController(FragmentActivity activity) {
        this.activity = activity;
        this.tv = activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    public void attach(FrameLayout host, String configUrl) {
        root = LayoutInflater.from(activity).inflate(R.layout.dialog_hk_panel, host, true);
        docList = root.findViewById(R.id.docList);
        catRow = root.findViewById(R.id.catRow);
        actionLeft = root.findViewById(R.id.actionLeft);
        actionCenter = root.findViewById(R.id.actionCenter);
        actionRight = root.findViewById(R.id.actionRight);
        feedRecycler = root.findViewById(R.id.feedRecycler);
        siteRecycler = root.findViewById(R.id.siteRecycler);
        feedAdapter = new FeedAdapter();
        siteAdapter = new SiteAdapter();
        feedRecycler.setAdapter(feedAdapter);
        siteRecycler.setAdapter(siteAdapter);
        // 点什么接口就编辑什么接口
        doc = HkInterface.isHkUrl(configUrl) ? HkInterface.docOfUrl(configUrl) : HkStateStore.currentDoc();
        if (TextUtils.isEmpty(doc)) {
            List<String> docs = HkInterface.docs();
            doc = docs.isEmpty() ? "" : docs.get(0);
        }
        HkStateStore.setCurrentDoc(doc);
        buildActions();
        refresh();
    }

    public void onShown() { refresh(); }

    private boolean blackMode() { return "黑名单".equals(mode); }

    /* ---------------- 渲染 ---------------- */

    private void refresh() {
        renderDocs();
        renderCats();
        buildActions();
        HkState state = HkStateStore.readState();
        HkDoc d = HkStateStore.readDoc(doc);
        List<HkFeed> feeds = new ArrayList<>();
        for (HkFeed f : d.feeds) {
            boolean bad = !"正常".equals(f.mark);
            if (blackMode() != bad) continue;
            if (!blackMode() && !"全部".equals(mode) && !mode.equals(f.category)) continue;
            feeds.add(f);
        }
        feedAdapter.submit(feeds);
        List<HkSiteEntry> sites = new ArrayList<>();
        if (!TextUtils.isEmpty(focusFeed)) {
            for (HkSiteEntry e : state.sites) {
                if (!doc.equals(e.doc) || !focusFeed.equals(e.feedId)) continue;
                boolean bad = !"正常".equals(e.mark);
                if (blackMode() == bad) sites.add(e);
            }
        }
        siteAdapter.submit(sites);
    }

    private void renderDocs() {
        docList.removeAllViews();
        List<String> docs = HkInterface.docs();
        for (String name : docs) {
            MaterialButton chip = chip(name, name.equals(doc), false);
            chip.setOnClickListener(v -> {
                if (!tv && name.equals(doc) && name.equals(lastDocTap)) { renameDoc(name); return; }
                doc = name; lastDocTap = name; mode = "全部"; focusFeed = "";
                feedSel.clear(); siteSel.clear();
                HkStateStore.setCurrentDoc(name);
                refresh();
            });
            docList.addView(chip);
        }
        if (!tv) {
            MaterialButton add = chip("+ 添加接口", false, false);
            add.setOnClickListener(v -> inputDialog("新建一级接口", "", name -> {
                if (TextUtils.isEmpty(name) || HkInterface.docs().contains(name)) { toast("名称无效或已存在"); return; }
                HkInterface.create(name);
                doc = name; HkStateStore.setCurrentDoc(name);
                refresh();
            }));
            docList.addView(add);
        }
    }

    private void renderCats() {
        catRow.removeAllViews();
        for (String name : new String[]{"全部", "黑名单"}) {
            MaterialButton chip = chip(name, name.equals(mode), true);
            chip.setOnClickListener(v -> { mode = name; lastCatTap = ""; refresh(); });
            catRow.addView(chip);
        }
        HkDoc d = HkStateStore.readDoc(doc);
        Set<String> used = new LinkedHashSet<>();
        for (HkFeed f : d.feeds) if (!TextUtils.isEmpty(f.category)) used.add(f.category);
        for (String name : used) {
            MaterialButton chip = chip(name, name.equals(mode), true);
            chip.setOnClickListener(v -> {
                if (!tv && name.equals(mode) && name.equals(lastCatTap)) { renameCategory(name); return; }
                mode = name; lastCatTap = name; refresh();
            });
            catRow.addView(chip);
        }
        if (!tv) {
            MaterialButton add = chip("+ 口令", false, true);
            add.setOnClickListener(v -> inputDialog("录入口令（支持口令集多行）", "", raw -> {
                if (TextUtils.isEmpty(raw)) return;
                addFeed(raw, mode.equals("全部") || mode.equals("黑名单") ? "" : mode);
            }));
            catRow.addView(add);
        }
        MaterialButton refresh = chip("刷新", false, true);
        refresh.setOnClickListener(v -> HkStateUpdater.refreshAll(this::refresh));
        catRow.addView(refresh);
        if (!tv) {
            MaterialButton dir = chip("设置目录", false, true);
            dir.setOnClickListener(v -> inputDialog("一级/二级 json 存放目录", HkStateStore.rootDir(), path -> {
                java.io.File f = new java.io.File(path);
                if (!f.isDirectory() && !f.mkdirs()) { toast("目录不可用"); return; }
                HkStateStore.setRootDir(path); refresh();
            }));
            catRow.addView(dir);
        }
    }

    private void buildActions() {
        actionLeft.removeAllViews(); actionCenter.removeAllViews(); actionRight.removeAllViews();
        addText(actionLeft, "全", v -> selectAllFeeds(true));
        addLabel(actionLeft, "口令");
        addText(actionLeft, "反", v -> invertFeeds());
        addText(actionLeft, blackMode() ? "恢" : "黑", v -> batchMark(blackMode() ? "正常" : "拉黑"));
        addText(actionLeft, "删", v -> confirmDelete());
        addText(actionCenter, "挪", v -> moveCategory());
        addText(actionRight, "全", v -> selectAllSites(true));
        addLabel(actionRight, "站点");
        addText(actionRight, "反", v -> invertSites());
    }

    /* ---------------- 用户动作 ---------------- */

    private void addFeed(String raw, String category) {
        if (TextUtils.isEmpty(doc)) { toast("请先添加一级接口"); return; }
        HkDoc d = HkStateStore.readDoc(doc);
        String norm = HkFeedParser.normalize(raw);
        for (HkFeed f : d.feeds) if (HkFeedParser.normalize(f.raw).equals(norm)) { toast("同口令已存在，直接合并"); return; }
        HkFeed f = new HkFeed("f" + System.currentTimeMillis(), raw, category);
        d.feeds.add(f);
        if (!TextUtils.isEmpty(category) && !d.categories.contains(category)) d.categories.add(category);
        HkStateStore.writeDoc(d);
        HkStateUpdater.refreshFeed(doc, f.feedId, this::refresh);
    }

    private void editFeed(HkFeed f) {
        if (tv) { showFeedReadOnly(f); return; }   // TV 二维码输入由第 4 批接入
        inputDialog("修改口令（保存后只刷新这一条）", f.raw, raw -> {
            HkStateUpdater.editFeed(doc, f.feedId, raw);
            toast("已更新，按 feed 增量刷新");
        });
    }

    private void showFeedReadOnly(HkFeed f) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("口令原文")
                .setMessage(f.raw)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void renameDoc(String oldName) {
        inputDialog("重命名一级接口", oldName, name -> {
            if (TextUtils.isEmpty(name) || name.equals(oldName)) return;
            if (HkInterface.docs().contains(name)) { toast("已存在同名接口"); return; }
            HkStateUpdater.renameDoc(oldName, name);
            doc = name; HkStateStore.setCurrentDoc(name);
            refresh();
        });
    }

    private void renameCategory(String oldName) {
        inputDialog("重命名分类", oldName, name -> {
            if (TextUtils.isEmpty(name) || "全部".equals(name) || "黑名单".equals(name)) return;
            HkStateUpdater.renameCategory(doc, oldName, name);
            if (oldName.equals(mode)) mode = name;
            refresh();
        });
    }

    private void moveCategory() {
        if (feedSel.isEmpty()) { toast("请先勾选口令"); return; }
        HkDoc d = HkStateStore.readDoc(doc);
        List<String> opts = new ArrayList<>(d.categories);
        opts.add("＋ 新分类");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("移动分类")
                .setItems(opts.toArray(new String[0]), (dlg, which) -> {
                    if (which == opts.size() - 1) {
                        inputDialog("新分类名称", "", name -> {
                            HkStateUpdater.moveFeeds(doc, new ArrayList<>(feedSel), name);
                            feedSel.clear(); refresh();
                        });
                    } else {
                        HkStateUpdater.moveFeeds(doc, new ArrayList<>(feedSel), opts.get(which));
                        feedSel.clear(); refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void batchMark(String mark) {
        HkState state = HkStateStore.readState();
        boolean changed = false;
        for (HkSiteEntry e : state.sites) {
            if (!doc.equals(e.doc)) continue;
            boolean hit = siteSel.contains(e.id) || (focusFeed.equals(e.feedId) && feedSel.contains(e.feedId));
            if (!hit) continue;
            if ("正常".equals(mark)) {
                if (!"拉黑".equals(e.mark)) continue;   // 失效不靠恢，刷新变有效自动回
            } else if (!"正常".equals(e.mark)) continue;
            e.mark = mark; e.origin = "user"; changed = true;
        }
        for (String fid : feedSel) {
            if ("拉黑".equals(mark)) HkStateUpdater.blacklistFeed(doc, fid);
            else HkStateUpdater.restoreFeed(doc, fid);
            changed = true;
        }
        if (changed) { HkStateStore.writeState(state); HkStateUpdater.injectHomeSites(); }
        feedSel.clear(); siteSel.clear(); refresh();
    }

    private void confirmDelete() {
        int n = feedSel.size();
        if (n == 0) { toast("站点仅支持拉黑/恢复，请先勾选口令"); return; }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除口令")
                .setMessage("将删除 " + n + " 条，不可恢复")
                .setPositiveButton("删除", (dlg, w) -> {
                    for (String fid : new ArrayList<>(feedSel)) HkStateUpdater.deleteFeed(doc, fid);
                    feedSel.clear(); HkStateUpdater.injectHomeSites(); refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectAllFeeds(boolean all) {
        feedSel.clear();
        if (all) for (HkFeed f : feedAdapter.items) feedSel.add(f.feedId);
        feedAdapter.notifyDataSetChanged();
    }

    private void invertFeeds() {
        Set<String> next = new HashSet<>();
        for (HkFeed f : feedAdapter.items) if (!feedSel.contains(f.feedId)) next.add(f.feedId);
        feedSel.clear(); feedSel.addAll(next);
        feedAdapter.notifyDataSetChanged();
    }

    private void selectAllSites(boolean all) {
        siteSel.clear();
        if (all) for (HkSiteEntry e : siteAdapter.items) siteSel.add(e.id);
        siteAdapter.notifyDataSetChanged();
    }

    private void invertSites() {
        Set<String> next = new HashSet<>();
        for (HkSiteEntry e : siteAdapter.items) if (!siteSel.contains(e.id)) next.add(e.id);
        siteSel.clear(); siteSel.addAll(next);
        siteAdapter.notifyDataSetChanged();
    }

    /* ---------------- 弹框与小工具 ---------------- */

    private void inputDialog(String title, String value, java.util.function.Consumer<String> onOk) {
        EditText et = new EditText(activity);
        et.setText(value == null ? "" : value);
        et.setSingleLine(false);
        et.setMinLines(2);
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(48, 16, 48, 0);
        box.addView(et, lp);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", (dlg, w) -> onOk.accept(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private MaterialButton chip(String text, boolean selected, boolean fill) {
        MaterialButton b = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setCheckable(true);
        b.setChecked(selected);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(12, 8, 12, 8);
        b.setTextSize(11);
        b.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        b.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 6, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void addText(LinearLayout row, String text, View.OnClickListener click) {
        MaterialTextView t = new MaterialTextView(activity);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(8, 8, 8, 8);
        t.setFocusable(true);
        t.setOnClickListener(click);
        row.addView(t);
    }

    private void addLabel(LinearLayout row, String text) {
        MaterialTextView t = new MaterialTextView(activity);
        t.setText(text);
        t.setTextSize(11);
        t.setPadding(4, 8, 4, 8);
        row.addView(t);
    }

    private void toast(String msg) { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show(); }

    /* ---------------- 两列适配器 ---------------- */

    private final class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {
        final List<HkFeed> items = new ArrayList<>();

        void submit(List<HkFeed> list) { items.clear(); items.addAll(list); notifyDataSetChanged(); }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(activity).inflate(R.layout.hk_item_feed, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int pos) {
            HkFeed f = items.get(pos);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(feedSel.contains(f.feedId));
            h.check.setOnCheckedChangeListener((btn, on) -> {
                if (on) feedSel.add(f.feedId); else feedSel.remove(f.feedId);
            });
            h.text.setText(f.raw);                 // start 省略，保尾；完整原串在二次点击弹框
            h.text.setSelected(focusFeed.equals(f.feedId));
            h.text.setOnClickListener(v -> {
                if (focusFeed.equals(f.feedId)) { editFeed(f); return; }
                focusFeed = f.feedId;
                siteSel.clear();
                refresh();
            });
        }

        @Override public int getItemCount() { return items.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final AppCompatCheckBox check;
            final MaterialTextView text;
            Holder(View v) {
                super(v);
                check = v.findViewById(R.id.check);
                text = v.findViewById(R.id.text);
            }
        }
    }

    private final class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.Holder> {
        final List<HkSiteEntry> items = new ArrayList<>();

        void submit(List<HkSiteEntry> list) { items.clear(); items.addAll(list); notifyDataSetChanged(); }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(activity).inflate(R.layout.hk_item_site, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int pos) {
            HkSiteEntry e = items.get(pos);
            h.name.setText(e.displayName);
            h.mark.setText("正常".equals(e.mark) ? "" : e.mark);
            h.itemView.setSelected(siteSel.contains(e.id));
            h.itemView.setOnClickListener(v -> {
                if (siteSel.contains(e.id)) siteSel.remove(e.id); else siteSel.add(e.id);
                notifyItemChanged(pos);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final MaterialTextView name, mark;
            final View itemView;
            Holder(View v) {
                super(v);
                itemView = v;
                name = v.findViewById(R.id.name);
                mark = v.findViewById(R.id.mark);
            }
        }
    }
}
