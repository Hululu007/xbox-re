package com.xingchen.tv.theme;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.R;

/**
 * 星辰皮肤 · 全局壁纸机（适配 webhtv）
 *
 * 原理：在每个 Activity 的 Window 上贴一张壁纸 Drawable，页面内容根布局保持透明，
 * 壁纸就透出来；毛玻璃面板半透明后叠在壁纸上，形成磨砂效果。
 *
 * 与 webhtv 原有的 CustomWallView 的关系：
 *   CustomWallView 是往 android.R.id.content 里塞一层 View，会盖住 Window 背景。
 *   所以启用星辰皮肤后，必须让 BaseActivity#customWall() 返回 false，两套壁纸不叠加。
 *   （见 patches/04-disable-custom-wall.patch）
 */
public class ThemeManager {

    private static final String TAG = "XcTheme";
    /** 调试日志开关：默认关。开的时候只打 Logcat，不写文件。 */
    public static boolean DEBUG = false;

    // 壁纸缓存只存「图源」（解码后的位图 / 纯色值），不存 Drawable 本体：
    // 每个窗口贴图时按图源新建一份 Drawable，避免全 App 共用同一个
    // Drawable 实例时跨窗口 detach/回调互相污染（一处 detach 满屏黑）。
    private static Bitmap sCachedBitmap = null;
    private static Integer sCachedColor = null;
    private static String sCachedKey = "";
    // 上一张成功贴出的壁纸图源：真图拿不到时兜底用，永不落透明黑。
    private static Bitmap sLastBitmap = null;
    private static Integer sLastColor = null;
    // 连上一张都没有时的深色占位（深蓝黑）
    private static final int PLACEHOLDER_COLOR = 0xFF0E1622;

    private static ThemeManager instance;
    private XingChenTheme theme;
    private static Activity currentActivity;
    /** 供静态查询用；init 时赋值。 */
    private static Application sApp;

    private static void log(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }

    private ThemeManager() {}

    public static synchronized ThemeManager get() {
        if (instance == null) instance = new ThemeManager();
        return instance;
    }

    /** 在 Application#onCreate 里调一次。 */
    public static void init(Application app) {
        sApp = app;
        get().theme = XingChenTheme.load(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) { currentActivity = a; get().apply(a); }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {
                if (currentActivity == a) currentActivity = null;
            }
        });
    }

    /**
     * 星辰皮肤是否接管壁纸。
     * 为 true 时，WebHTV 原生的 CustomWallView（BaseActivity 里那层 View）必须让位，
     * 否则会盖住 Window 壁纸，两套壁纸互相打架。
     * 默认 true；设置页「星辰皮肤」总开关写 "skin_wall_enabled"，关闭后恢复原生壁纸。
     */
    public static boolean isSkinWallpaperActive() {
        if (sApp == null) return false;
        try {
            return sApp.getSharedPreferences("xingchen", android.content.Context.MODE_PRIVATE)
                    .getBoolean("skin_wall_enabled", true);
        } catch (Exception e) {
            return false;
        }
    }

    public XingChenTheme getTheme() {
        if (theme == null) theme = new XingChenTheme();
        return theme;
    }

    public void refresh(android.content.Context ctx) {
        theme = XingChenTheme.load(ctx);
    }

    public void setTheme(XingChenTheme t) {
        this.theme = t;
    }

    public void setWallpaper(android.content.Context ctx, String type, String value) {
        theme.wallpaperType = type;
        theme.wallpaperValue = value;
        theme.save(ctx);
        // 清缓存：本地/URL 壁纸复用固定文件名，不清就会贴到上一张
        sCachedKey = "";
        sCachedBitmap = null;
        sCachedColor = null;
        applyAll();
    }

    public void setWallpaperBlur(android.content.Context ctx, boolean blur) {
        theme.wallpaperBlur = blur;
        theme.save(ctx);
        sCachedKey = "";
        sCachedBitmap = null;
        sCachedColor = null;
        applyAll();
    }

    public void applyAll() {
        try {
            if (currentActivity != null && !currentActivity.isFinishing()) apply(currentActivity);
        } catch (Exception e) { log("applyAll failed: " + e); }
    }

    public void apply(Activity activity) {
        if (activity == null) return;
        // 关闭「星辰皮肤」总开关后不再贴 Window 壁纸，让原生 CustomWallView 接管。
        if (!isSkinWallpaperActive()) return;
        // 第一贴（同步）：命中缓存或有上一张图时先把窗口底贴上，不等 post。
        try {
            applyCachedNow(activity);
        } catch (Exception e) {
            log("sync wallpaper apply failed: " + e);
        }
        // 第二贴（异步）：全量校验/解码，在其后覆盖同步贴的结果。
        try {
            activity.getWindow().getDecorView().post(new Runnable() {
                @Override public void run() {
                    if (!activity.isFinishing() && !activity.isDestroyed()) doApply(activity, false);
                }
            });
        } catch (Exception e) {
            log("async wallpaper apply failed: " + e);
        }
    }

    /**
     * 不跟全局壁纸走的页面：这些页面自带底色（如音频播放页、独立沉浸页）。
     * 需要加的时候按类名后缀补进来，其他页面不用动。
     */
    protected boolean isExcluded(Activity activity) {
        String name = activity.getClass().getName();
        return name.endsWith(".ui.audio.AudioActivity");
    }

    // 同步贴：只用已有图源（当前缓存优先，其次上一张），不解码。
    private void applyCachedNow(Activity activity) {
        if (isExcluded(activity)) return;
        if (theme == null) theme = XingChenTheme.load(activity);
        Drawable dw = null;
        if (currentCacheKey().equals(sCachedKey)) dw = freshDrawableFromCache(activity);
        if (dw == null) dw = freshDrawableFromLast(activity);
        if (dw != null) {
            try {
                activity.getWindow().setBackgroundDrawable(dw);
                log("sync-pasted for " + activity.getClass().getSimpleName());
            } catch (Exception ignored) {}
        }
    }

    // 按当前缓存图源新建一份本窗口独立的 Drawable（位图本体可共享，状态不共享）。
    private Drawable freshDrawableFromCache(Activity activity) {
        if (sCachedColor != null) return new ColorDrawable(sCachedColor);
        if (sCachedBitmap != null && !sCachedBitmap.isRecycled()) {
            BitmapDrawable bd = new BitmapDrawable(activity.getResources(), sCachedBitmap);
            bd.setTintList(null);
            return bd;
        }
        return null;
    }

    private Drawable freshDrawableFromLast(Activity activity) {
        if (sLastColor != null) return new ColorDrawable(sLastColor);
        if (sLastBitmap != null && !sLastBitmap.isRecycled()) {
            BitmapDrawable bd = new BitmapDrawable(activity.getResources(), sLastBitmap);
            bd.setTintList(null);
            return bd;
        }
        return null;
    }

    // 兜底：上一张也没有就落深色占位，绝不落透明（透明＝黑）。
    private Drawable fallbackDrawable(Activity activity) {
        Drawable dw = freshDrawableFromLast(activity);
        if (dw != null) return dw;
        return new ColorDrawable(PLACEHOLDER_COLOR);
    }

    private String currentCacheKey() {
        if (theme == null) return "";
        String type = theme.wallpaperType;
        String value = theme.wallpaperValue;
        boolean blur = theme.wallpaperBlur;
        String cacheKey = type + ":" + value + (blur ? ":blur" : "");
        // 本地/URL 壁纸复用固定文件名，带上 lastModified 才能识别换图
        if ((XingChenTheme.WP_LOCAL.equals(type) || XingChenTheme.WP_URL.equals(type)) && value != null) {
            try {
                java.io.File f = new java.io.File(value);
                if (f.exists()) cacheKey += ":" + f.lastModified();
            } catch (Exception ignored) {}
        }
        return cacheKey;
    }

    private void doApply(Activity activity, boolean isRetry) {
        try {
            if (isExcluded(activity)) return;
            if (theme == null) theme = XingChenTheme.load(activity);
            Drawable dw = obtainWallpaper(activity);
            boolean real = dw != null;
            // 真图拿不到也绝不落透明黑：上一张优先，没有就深色占位。
            if (!real) dw = fallbackDrawable(activity);
            try {
                activity.getWindow().setBackgroundDrawable(dw);
            } catch (Exception e) {
                log("setting window background failed: " + e);
            }
            if (!real && !isRetry) {
                // 已落兜底，再约一次延迟重试；下一次 onActivityResumed 也会再走 apply。
                try {
                    activity.getWindow().getDecorView().postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!activity.isFinishing() && !activity.isDestroyed()) doApply(activity, true);
                        }
                    }, 1500L);
                } catch (Exception ignored) {}
            }
            clearOpaqueCoverViews(activity);
        } catch (Exception e) {
            log("doApply failed: " + e);
        }
    }

    /**
     * 清掉 DecorView 上直接挂着的不透明纯色 View，否则壁纸透不出来。
     * 只动普通 View：ViewGroup（内容）和 ImageView（壁纸层）不碰。
     */
    private void clearOpaqueCoverViews(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup dg = (ViewGroup) decor;
            for (int i = 0; i < dg.getChildCount(); i++) {
                View child = dg.getChildAt(i);
                if (child == null) continue;
                if (child instanceof ViewGroup) continue;
                if (child instanceof android.widget.ImageView) continue;
                if ("xc_wallpaper".equals(child.getTag())) continue;
                Drawable bg = child.getBackground();
                if (bg instanceof ColorDrawable && android.graphics.Color.alpha(((ColorDrawable) bg).getColor()) == 255) {
                    child.setBackground(null);
                }
            }
        } catch (Exception e) {
            log("clear cover views failed: " + e);
        }
    }

    // 拿到真壁纸时返回一份本窗口独立的 Drawable，并把图源存进缓存/上一张；
    // 拿不到（解码失败等）返回 null，由 doApply 落兜底并约重试。
    private Drawable obtainWallpaper(Activity activity) {
        try {
            String type = theme.wallpaperType;
            String value = theme.wallpaperValue;
            boolean blur = theme.wallpaperBlur;
            String cacheKey = currentCacheKey();
            if (cacheKey.equals(sCachedKey)) {
                Drawable hit = freshDrawableFromCache(activity);
                if (hit != null) return hit;
            }
            if (XingChenTheme.WP_COLOR.equals(type)) {
                try {
                    int color = android.graphics.Color.parseColor(value);
                    cacheColor(cacheKey, color);
                    return new ColorDrawable(color);
                } catch (Exception e) {
                    return null;
                }
            } else if (XingChenTheme.WP_LOCAL.equals(type) || XingChenTheme.WP_URL.equals(type)) {
                try {
                    Bitmap bm = android.graphics.BitmapFactory.decodeFile(value);
                    if (bm != null) {
                        if (blur) bm = WallpaperBlur.blurredWallpaper(bm);
                        cacheBitmap(cacheKey, bm);
                        return freshDrawableFromCache(activity);
                    }
                } catch (Exception e) {
                    log("file decode: " + e);
                }
            }
            boolean preBlurred = blur && "shanjian".equals(value);
            int id = getBuiltinRes(activity, value, blur);
            if (id != 0) {
                try {
                    // 采样解码，避免整张大图进内存（黑屏/内存峰值的老坑）
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeResource(activity.getResources(), id, opts);
                    int screenW = activity.getResources().getDisplayMetrics().widthPixels;
                    int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                    int sample = 1;
                    while ((opts.outWidth / sample) > screenW * 2 || (opts.outHeight / sample) > screenH * 2) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    Bitmap bm = android.graphics.BitmapFactory.decodeResource(activity.getResources(), id, opts);
                    if (bm != null) {
                        if (blur && !preBlurred) bm = WallpaperBlur.blurredWallpaper(bm);
                        cacheBitmap(cacheKey, bm);
                        return freshDrawableFromCache(activity);
                    }
                } catch (Exception e) {
                    log("builtin decode: " + e);
                }
                Drawable fallback = activity.getResources().getDrawable(id, null);
                if (fallback != null) {
                    Bitmap fbm = drawableToBitmap(activity, fallback);
                    if (fbm != null) {
                        if (blur && !preBlurred) fbm = WallpaperBlur.blurredWallpaper(fbm);
                        cacheBitmap(cacheKey, fbm);
                        return freshDrawableFromCache(activity);
                    }
                    return fallback;
                }
            }
            return null;
        } catch (Exception e) {
            log("obtainWallpaper: " + e);
            return null;
        }
    }

    private void cacheBitmap(String key, Bitmap bm) {
        sCachedBitmap = bm;
        sCachedColor = null;
        sCachedKey = key;
        sLastBitmap = bm;
        sLastColor = null;
    }

    private void cacheColor(String key, int color) {
        sCachedColor = color;
        sCachedBitmap = null;
        sCachedKey = key;
        sLastColor = color;
        sLastBitmap = null;
    }

    private Bitmap drawableToBitmap(Activity activity, Drawable d) {
        try {
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : activity.getResources().getDisplayMetrics().widthPixels;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : activity.getResources().getDisplayMetrics().heightPixels;
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    private int getBuiltinRes(android.content.Context ctx, String name, boolean blur) {
        try {
            if ("shanjian".equals(name)) {
                // 模糊开 -> 用预模糊图（不再叠加运行时模糊）；关 -> 原图
                return blur ? R.drawable.poster_shanjian_blur_soft : R.drawable.poster_shanjian;
            }
            return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        } catch (Exception e) {
            return 0;
        }
    }
}
