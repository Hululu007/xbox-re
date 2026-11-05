package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterScriptItemBinding;
import com.fongmi.android.tv.databinding.DialogScriptManageBinding;
import com.fongmi.android.tv.databinding.DialogScriptUrlBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.LocalScriptSource;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 脚本管理：外观与按钮风格参照「登录态学习窗口」，两栏站点交换参照 AVBox xbox 的
 * SiteManagePage。支持多个脚本文件夹、分类筛选、上下栏全选/反选、上移/下移、
 * 位置互换、在线 html 录入与编辑（名称 / url / 代理）。
 *
 * 所有改动即时写回目录下「脚本扫描.json」，脚本文件本身一个字节不动。
 */
public final class ScriptManageDialog extends BaseAlertDialog {

    private DialogScriptManageBinding binding;
    private Runnable callback;
    private String currentDir = "";
    private String filter = "all";
    private boolean dirty;
    private List<JSONObject> sites = new ArrayList<>();
    private List<JSONObject> bac = new ArrayList<>();
    private final Set<String> keepSel = new LinkedHashSet<>();
    private final Set<String> bacSel = new LinkedHashSet<>();
    private final List<TextView> filterChips = new ArrayList<>();
    private final String[] filters = {"all", "py", "js", "html", "url"};
    private ItemAdapter keepAdapter;
    private ItemAdapter bacAdapter;

    private final ActivityResultLauncher<Intent> folderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                onFolderPicked(FileChooser.getPathFromUri(uri));
            });

    public static void show(Fragment fragment) {
        show(fragment.requireActivity(), null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments())
            if (f instanceof ScriptManageDialog && f.isAdded() && !f.isRemoving()) return;
        ScriptManageDialog dialog = new ScriptManageDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), "ScriptManageDialog");
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogScriptManageBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(getBinding().getRoot());
    }

    /** 窗口参数在 show 之前定好，避免首帧居中后跳位（A 类 bug） */
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog);
        return dialog;
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        int width = Math.min((int) (ResUtil.getScreenWidth(requireContext()) * (land ? 0.78f : 0.94f)), ResUtil.dp2px(760));
        params.width = Math.max(width, ResUtil.dp2px(320));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog()); // 幂等兜底，同值不产生可见跳变
    }

    @Override
    protected void initView() {
        keepAdapter = new ItemAdapter(false);
        bacAdapter = new ItemAdapter(true);
        binding.keepRecycler.setItemAnimator(null);
        binding.bacRecycler.setItemAnimator(null);
        binding.keepRecycler.setAdapter(keepAdapter);
        binding.bacRecycler.setAdapter(bacAdapter);
        buildRows();
        bindState();
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(v -> {
            if (TextUtils.isEmpty(currentDir)) return;
            dirty = true;
            Task.execute(() -> {
                LocalScriptSource.refresh(currentDir);
                App.post(this::bindState);
            });
        });
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
    }

    /* ---------------- 顶部若干行按钮 ---------------- */

    private void buildRows() {
        int[] tools = {R.string.script_add_url, R.string.script_swap, R.string.script_up, R.string.script_down};
        for (int i = 0; i < tools.length; i++) {
            TextView chip = makeChip(getString(tools[i]), false, true);
            int idx = i;
            chip.setOnClickListener(v -> onTool(idx));
            binding.toolRow.addView(chip);
        }
        String[] labels = {getString(R.string.script_all), "py", "js", "html", "url"};
        for (int i = 0; i < filters.length; i++) {
            TextView chip = makeChip(labels[i], i == 0, true);
            int idx = i;
            chip.setOnClickListener(v -> {
                filter = filters[idx];
                render();
            });
            filterChips.add(chip);
            binding.filterRow.addView(chip);
        }
        int[] selects = {R.string.script_select_keep, R.string.script_invert_keep,
                R.string.script_select_bac, R.string.script_invert_bac};
        for (int i = 0; i < selects.length; i++) {
            TextView chip = makeChip(getString(selects[i]), false, true);
            int idx = i;
            chip.setOnClickListener(v -> onSelect(idx));
            binding.selectRow.addView(chip);
        }
    }

    private TextView makeChip(String text, boolean selected, boolean fill) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(12);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER);
        int p = dp(6);
        view.setPadding(p, dp(7), p, dp(7));
        view.setBackgroundResource(R.drawable.selector_sync_device_item);
        applyChipStyle(view, selected);
        LinearLayoutCompat.LayoutParams lp = fill
                ? new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(4));
        view.setLayoutParams(lp);
        return view;
    }

    private void applyChipStyle(TextView view, boolean selected) {
        // 两个变体都有的对话框色表：选中蓝 + 加粗，未选中灰 + 常规；克制、不刺眼
        view.setTextColor(ContextCompat.getColor(requireContext(),
                selected ? R.color.dialog_text_button : R.color.dialog_outlined_button_text));
        view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }

    /* ---------------- 文件夹切换 ---------------- */

    private void renderDirs() {
        binding.dirList.removeAllViews();
        List<String> dirs = LocalScriptSource.dirs();
        for (String dir : dirs) {
            TextView chip = makeChip(new File(dir).getName(), dir.equals(currentDir), false);
            chip.setOnClickListener(v -> {
                currentDir = dir;
                keepSel.clear();
                bacSel.clear();
                bindState();
            });
            binding.dirList.addView(chip);
        }
        TextView add = makeChip("+ " + getString(R.string.script_add_folder), false, false);
        add.setOnClickListener(v -> FileChooser.from(folderLauncher).showDirectory());
        binding.dirList.addView(add);
    }

    private void onFolderPicked(String path) {
        if (TextUtils.isEmpty(path) || !new File(path).isDirectory()) return;
        if (!LocalScriptSource.addDir(path)) {
            Notify.show(R.string.script_folder_exists);
            return;
        }
        // 登记成点播接口，这样点播左上角接口列表里就能切换到这个文件夹的 json
        Config.create(0, LocalScriptSource.configUrl(path), new File(path).getName());
        int count = LocalScriptSource.scripts(path).size();
        Notify.show(getString(R.string.script_folder_added, count));
        currentDir = path;
        dirty = true;
        bindState();
    }

    /* ---------------- 数据绑定 ---------------- */

    private void bindState() {
        if (binding == null) return;
        List<String> dirs = LocalScriptSource.dirs();
        if (dirs.isEmpty()) currentDir = "";
        else if (TextUtils.isEmpty(currentDir) || !dirs.contains(currentDir)) currentDir = dirs.get(0);
        renderDirs();
        final String dir = currentDir;
        Task.execute(() -> {
            List<JSONObject> s = new ArrayList<>();
            List<JSONObject> b = new ArrayList<>();
            if (!TextUtils.isEmpty(dir)) {
                Pair<List<JSONObject>, List<JSONObject>> pair = LocalScriptSource.load(dir);
                s = pair.first;
                b = pair.second;
            }
            List<JSONObject> fs = s;
            List<JSONObject> fb = b;
            App.post(() -> {
                sites = fs;
                bac = fb;
                render();
            });
        });
    }

    private List<JSONObject> visible(List<JSONObject> list) {
        if ("all".equals(filter)) return new ArrayList<>(list);
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : list) if (filter.equals(LocalScriptSource.kindOf(item))) out.add(item);
        return out;
    }

    private Set<String> keysOf(List<JSONObject> list) {
        Set<String> out = new HashSet<>();
        for (JSONObject item : list) out.add(item.optString("key"));
        return out;
    }

    private void render() {
        if (binding == null) return;
        List<JSONObject> keep = visible(sites);
        List<JSONObject> gone = visible(bac);
        binding.keepTitle.setText(getString(R.string.script_keep, keep.size()));
        binding.bacTitle.setText(getString(R.string.script_bac, gone.size()));
        binding.summary.setText(TextUtils.isEmpty(currentDir)
                ? getString(R.string.script_empty) : currentDir);
        keepAdapter.setItems(keep);
        bacAdapter.setItems(gone);
        for (int i = 0; i < filterChips.size(); i++)
            applyChipStyle(filterChips.get(i), filters[i].equals(filter));
    }

    /* ---------------- 操作 ---------------- */

    private void onTool(int idx) {
        if (TextUtils.isEmpty(currentDir)) return;
        switch (idx) {
            case 0:
                showUrlDialog(null);
                return;
            case 1:
                runOp(() -> {
                    if (LocalScriptSource.swap(currentDir, keepSel, bacSel)) {
                        keepSel.clear();
                        bacSel.clear();
                    }
                });
                break;
            case 2:
                runOp(() -> reorder(true));
                break;
            case 3:
                runOp(() -> reorder(false));
                break;
            default:
                break;
        }
    }

    private void reorder(boolean up) {
        if (!keepSel.isEmpty())
            LocalScriptSource.reorder(currentDir, keepSel, up, false, keysOf(visible(sites)));
        if (!bacSel.isEmpty())
            LocalScriptSource.reorder(currentDir, bacSel, up, true, keysOf(visible(bac)));
    }

    private void onSelect(int idx) {
        List<JSONObject> list = idx < 2 ? visible(sites) : visible(bac);
        Set<String> target = idx < 2 ? keepSel : bacSel;
        boolean invert = idx == 1 || idx == 3;
        Set<String> keys = keysOf(list);
        if (invert) {
            Set<String> next = new LinkedHashSet<>(keys);
            next.removeAll(target);
            target.clear();
            target.addAll(next);
        } else {
            target.clear();
            target.addAll(keys);
        }
        keepAdapter.notifyDataSetChanged();
        bacAdapter.notifyDataSetChanged();
    }

    private void runOp(Runnable op) {
        Task.execute(() -> {
            op.run();
            App.post(() -> {
                dirty = true;
                bindState();
            });
        });
    }

    private void toggle(boolean inBac, String key) {
        Set<String> sel = inBac ? bacSel : keepSel;
        if (!sel.remove(key)) sel.add(key);
        keepAdapter.notifyDataSetChanged();
        bacAdapter.notifyDataSetChanged();
    }

    /* ---------------- 远程 html URL ---------------- */

    private void showUrlDialog(@Nullable JSONObject editing) {
        if (TextUtils.isEmpty(currentDir)) return;
        DialogScriptUrlBinding b = DialogScriptUrlBinding.inflate(getLayoutInflater());
        b.title.setText(editing == null ? R.string.script_add_url : R.string.script_edit_url);
        if (editing != null) {
            String name = editing.optString("name");
            if (name.endsWith("[url]")) name = name.substring(0, name.length() - 5);
            b.name.setText(name);
            String url = editing.optString("homePage");
            b.url.setText(url);
            b.proxy.setChecked("proxy".equals(editing.optString("proxy"))
                    || url.endsWith("#" + LocalScriptSource.PROXY_KEY));
        }
        Dialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(b.getRoot()).create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        b.negative.setOnClickListener(v -> dialog.dismiss());
        b.positive.setOnClickListener(v -> {
            String name = b.name.getText().toString().trim();
            String url = b.url.getText().toString().trim();
            if (url.isEmpty()) {
                Notify.show(R.string.script_url_required);
                return;
            }
            boolean proxy = b.proxy.isChecked();
            dialog.dismiss();
            String key = editing == null ? "" : editing.optString("key");
            Task.execute(() -> {
                if (editing == null) LocalScriptSource.addUrl(currentDir, name, url, proxy);
                else LocalScriptSource.updateUrl(currentDir, key, name, url, proxy);
                App.post(() -> {
                    dirty = true;
                    Notify.show(R.string.script_url_saved);
                    bindState();
                });
            });
        });
        dialog.show();
    }

    private void onPositive() {
        boolean changed = dirty;
        dismiss();
        if (callback != null) callback.run();
        if (changed && LocalScriptSource.isStoreUrl(VodConfig.getUrl())) {
            VodConfig.load(VodConfig.get().getConfig(), new Callback() {
                @Override
                public void success() {
                    Notify.show(R.string.script_reloaded);
                }
            });
        }
    }

    /* ---------------- 两栏列表 ---------------- */

    private class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.Holder> {

        private final boolean inBac;
        private List<JSONObject> items = new ArrayList<>();

        ItemAdapter(boolean inBac) {
            this.inBac = inBac;
        }

        void setItems(List<JSONObject> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterScriptItemBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            JSONObject item = items.get(position);
            String key = item.optString("key");
            boolean selected = (inBac ? bacSel : keepSel).contains(key);
            holder.binding.check.setChecked(selected);
            holder.binding.name.setText(item.optString("name"));
            holder.binding.name.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            holder.binding.badge.setText(LocalScriptSource.kindOf(item));
            holder.binding.proxyBadge.setVisibility("proxy".equals(item.optString("proxy")) ? View.VISIBLE : View.GONE);
            holder.binding.edit.setVisibility(LocalScriptSource.isEditable(item) ? View.VISIBLE : View.GONE);
            holder.binding.check.setOnClickListener(v -> toggle(inBac, key));
            holder.binding.getRoot().setOnClickListener(v -> toggle(inBac, key));
            holder.binding.edit.setOnClickListener(v -> showUrlDialog(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final AdapterScriptItemBinding binding;

            Holder(AdapterScriptItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
