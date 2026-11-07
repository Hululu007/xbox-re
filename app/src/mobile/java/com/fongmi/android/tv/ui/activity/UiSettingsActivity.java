package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityUiSettingsBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.xingchen.tv.theme.ThemeManager;
import com.xingchen.tv.theme.XingChenTheme;

/**
 * 星辰皮肤 · UI 设置页
 *
 * 四块：主题壁纸 / 封面尺寸 / 封面方向 / 封面比例 + 毛玻璃开关与透明度。
 * 壁纸和毛玻璃是真实生效的；封面三项写入 xingchen 偏好，供首页消费方读取。
 */
public class UiSettingsActivity extends BaseActivity {
    private ActivityUiSettingsBinding binding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, UiSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityUiSettingsBinding.inflate(getLayoutInflater());
        return binding;
    }

    /** 本页自己贴窗口壁纸，不能再叠一层 CustomWallView，否则壁纸被盖住。 */
    @Override
    protected boolean customWall() {
        return !ThemeManager.isSkinWallpaperActive();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        initSkinSwitch();
        initWallpaperSection();
        initCoverSizeSection();
        initOrientSection();
        initRatioSection();
    }


    /** 星辰皮肤总开关：写入 skin_wall_enabled，并重建 Activity 栈让两套壁纸状态一致。 */
    private void initSkinSwitch() {
        binding.switchSkinEnable.setChecked(prefs().getBoolean("skin_wall_enabled", true));
        binding.switchSkinEnable.setOnCheckedChangeListener((btn, checked) -> {
            prefs().edit().putBoolean("skin_wall_enabled", checked).apply();
            ThemeManager.get().refresh(this);
            Intent intent = new Intent(this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        });
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("xingchen", MODE_PRIVATE);
    }

    private String getPref(String key, String def) {
        return prefs().getString(key, def);
    }

    private void setPref(String key, String value) {
        prefs().edit().putString(key, value).apply();
        if (key.startsWith("cover_")) markCoverChanged();
    }

    /** 封面变更代次：只递增不清零，各列表碎片比对自己已应用的代次，避免一次性布尔被抢先消费。 */
    private void markCoverChanged() {
        android.content.SharedPreferences sp = prefs();
        sp.edit().putLong("cover_gen", sp.getLong("cover_gen", 0) + 1).apply();
    }

    // ---------------- 壁纸 ----------------

    private void initWallpaperSection() {
        binding.segWpDefault.setOnClickListener(v -> setWallpaper(XingChenTheme.WP_BUILTIN, "shanjian"));
        binding.segWpLocal.setOnClickListener(v -> pickLocalWallpaper());
        binding.segWpUrl.setOnClickListener(v -> inputUrlWallpaper());
        binding.thumbDefault.setOnClickListener(v -> setWallpaper(XingChenTheme.WP_BUILTIN, "shanjian"));
        binding.thumbLocal.setOnClickListener(v -> pickLocalWallpaper());
        binding.thumbUrl.setOnClickListener(v -> inputUrlWallpaper());

        boolean blur = prefs().getBoolean("wallpaper_blur", false);
        binding.switchBlur.setChecked(blur);
        binding.switchBlur.setOnCheckedChangeListener((btn, isChecked) -> {
            try {
                XingChenTheme theme = XingChenTheme.load(this);
                theme.wallpaperBlur = isChecked;
                theme.save(this);
                ThemeManager.get().setTheme(theme);
                ThemeManager.get().setWallpaperBlur(this, isChecked);
            } catch (Exception ignored) {}
        });
        updateWallpaperUI();
        updateThumbnails();
    }

    private void updateWallpaperUI() {
        String type = ThemeManager.get().getTheme().wallpaperType;
        setSegSelected(binding.segWpDefault, XingChenTheme.WP_BUILTIN.equals(type));
        setSegSelected(binding.segWpLocal, XingChenTheme.WP_LOCAL.equals(type));
        setSegSelected(binding.segWpUrl, XingChenTheme.WP_URL.equals(type));
        String desc = "当前：";
        if (XingChenTheme.WP_LOCAL.equals(type)) desc += "本地壁纸";
        else if (XingChenTheme.WP_URL.equals(type)) desc += "网络壁纸";
        else desc += "默认壁纸";
        binding.tvWpDesc.setText(desc);
    }

    private void updateThumbnails() {
        try {
            XingChenTheme theme = XingChenTheme.load(this);
            if (XingChenTheme.WP_LOCAL.equals(theme.wallpaperType) && theme.wallpaperValue != null) {
                com.bumptech.glide.Glide.with(this).load(theme.wallpaperValue).into(binding.thumbLocal);
            }
            if (XingChenTheme.WP_URL.equals(theme.wallpaperType) && theme.wallpaperValue != null) {
                com.bumptech.glide.Glide.with(this).load(theme.wallpaperValue).into(binding.thumbUrl);
            }
        } catch (Exception ignored) {}
    }

    private void setWallpaper(String type, String value) {
        XingChenTheme theme = XingChenTheme.load(this);
        theme.wallpaperType = type;
        theme.wallpaperValue = value;
        theme.save(this);
        try {
            ThemeManager.get().setTheme(theme);
            ThemeManager.get().setWallpaper(this, type, value);
        } catch (Exception ignored) {}
        updateWallpaperUI();
        updateThumbnails();
    }

    private void pickLocalWallpaper() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, 1001);
    }

    private void inputUrlWallpaper() {
        final android.widget.EditText input = new android.widget.EditText(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("输入图片 URL")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) downloadAndSetWallpaper(url);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadAndSetWallpaper(String url) {
        new Thread(() -> {
            try {
                java.io.InputStream in = new java.net.URL(url).openStream();
                java.io.File outFile = new java.io.File(getFilesDir(), "wallpaper_url.jpg");
                java.io.FileOutputStream out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                out.close();
                in.close();
                runOnUiThread(() -> setWallpaper(XingChenTheme.WP_URL, outFile.getAbsolutePath()));
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "下载失败：" + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            try {
                android.net.Uri uri = data.getData();
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.File outFile = new java.io.File(getFilesDir(), "wallpaper_local.jpg");
                java.io.FileOutputStream out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                out.close();
                in.close();
                setWallpaper(XingChenTheme.WP_LOCAL, outFile.getAbsolutePath());
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "读取失败：" + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------------- 封面 ----------------

    private void initCoverSizeSection() {
        binding.segCoverSmall.setOnClickListener(v -> setCoverSize(0));
        binding.segCoverMedium.setOnClickListener(v -> setCoverSize(1));
        binding.segCoverLarge.setOnClickListener(v -> setCoverSize(2));
        updateCoverSizeUI();
    }

    private void setCoverSize(int size) {
        com.fongmi.android.tv.setting.PlayerSetting.putSize(size);
        markCoverChanged();
        updateCoverSizeUI();
    }

    private void updateCoverSizeUI() {
        int size = com.fongmi.android.tv.setting.PlayerSetting.getSize();
        setSegSelected(binding.segCoverSmall, size == 0);
        setSegSelected(binding.segCoverMedium, size == 1);
        setSegSelected(binding.segCoverLarge, size == 2);
        String name = size == 0 ? "小" : (size == 2 ? "大" : "中");
        int column = com.fongmi.android.tv.Product.getColumn(this);
        binding.tvCoverDesc.setText(name + "封面：一行显示 " + column + " 个海报");
    }

    private void initOrientSection() {
        binding.segOrientPortrait.setOnClickListener(v -> { setPref("cover_orient", "portrait"); updateOrientUI(); });
        binding.segOrientLandscape.setOnClickListener(v -> { setPref("cover_orient", "landscape"); updateOrientUI(); });
        updateOrientUI();
    }

    private void updateOrientUI() {
        String orient = getPref("cover_orient", "portrait");
        setSegSelected(binding.segOrientPortrait, "portrait".equals(orient));
        setSegSelected(binding.segOrientLandscape, "landscape".equals(orient));
    }

    private void initRatioSection() {
        binding.segRatio23.setOnClickListener(v -> { setPref("cover_ratio", "2:3"); updateRatioUI(); });
        binding.segRatio34.setOnClickListener(v -> { setPref("cover_ratio", "3:4"); updateRatioUI(); });
        binding.segRatio916.setOnClickListener(v -> { setPref("cover_ratio", "9:16"); updateRatioUI(); });
        updateRatioUI();
    }

    private void updateRatioUI() {
        String ratio = getPref("cover_ratio", "2:3");
        setSegSelected(binding.segRatio23, "2:3".equals(ratio));
        setSegSelected(binding.segRatio34, "3:4".equals(ratio));
        setSegSelected(binding.segRatio916, "9:16".equals(ratio));
    }

    // ---------------- 通用 ----------------

    private void setSegSelected(TextView tv, boolean selected) {
        if (tv == null) return;
        if (selected) {
            tv.setBackgroundResource(R.drawable.seg_selected);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            tv.setBackgroundResource(R.drawable.seg_unselected);
            tv.setTextColor(0xFF333333);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding == null) return;
        updateCoverSizeUI();
        updateOrientUI();
        updateRatioUI();
        updateWallpaperUI();
    }
}
