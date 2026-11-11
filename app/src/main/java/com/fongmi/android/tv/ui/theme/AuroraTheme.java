package com.fongmi.android.tv.ui.theme;

import android.app.Activity;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;

import com.fongmi.android.tv.R;

/**
 * xbox-re 原生 UI 主题引擎。
 *
 * <p>设计原则：零依赖、零布局改写、不改任何现有方法签名。主题由两套机制组成：
 * <ul>
 *   <li>{@link #applyPreset(Activity)} 在 {@code super.onCreate} 之前 setTheme，
 *       切换四套完整预设（颜色 / 圆角 / 弹窗 / 状态栏全部覆盖 Material3 标准属性）；</li>
 *   <li>{@link #applyOverlay(Activity)} 在 {@code super.onCreate} 之后、
 *       {@code setContentView} 之前用 {@code Theme.applyStyle} 叠加高级定制
 *       （强调色 / 圆角档位 / 字号档位）。</li>
 * </ul>
 * 配置保存在独立 SharedPreferences，不触碰项目既有 Setting 键；选择「跟随系统」时
 * 整个引擎不介入，原有 DynamicColors / 深色主题链路完整保留。
 */
public final class AuroraTheme {

    public static final String PREFS = "xboxre.aurora.theme";

    public static final String KEY_PRESET = "preset";
    public static final String KEY_ACCENT = "accent";
    public static final String KEY_CORNER = "corner";
    public static final String KEY_TEXT = "text";
    public static final String KEY_BUTTON = "button";
    public static final String KEY_TILE = "tile";
    public static final String KEY_DIALOG_STYLE = "dialogStyle";
    public static final String KEY_DIALOG_POS = "dialogPos";

    public static final String[] PRESET_KEYS = {"aurora", "paper", "ink", "glass", "system"};
    public static final String[] ACCENT_KEYS = {"default", "jade", "azure", "amber", "rose", "violet", "cyan", "lime", "slate"};
    public static final String[] CORNER_KEYS = {"default", "small", "large", "pill"};
    public static final String[] TEXT_KEYS = {"default", "small", "large"};
    public static final String[] BUTTON_KEYS = {"solid", "pill", "square", "outline", "soft"};
    public static final String[] TILE_KEYS = {"soft", "square", "large", "circle", "poster", "wide"};
    public static final String[] DIALOG_STYLE_KEYS = {"solid", "outline", "soft", "glass"};
    public static final String[] DIALOG_POS_KEYS = {"center", "bottom", "top", "side"};

    private AuroraTheme() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context context, String key, String def) {
        String value = prefs(context).getString(key, def);
        return value == null ? def : value;
    }

    public static void put(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    public static void reset(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static boolean isSystem(Context context) {
        return "system".equals(get(context, KEY_PRESET, "aurora"));
    }

    @StyleRes
    private static int presetTheme(String key) {
        switch (key) {
            case "paper":
                return R.style.Theme_App_Paper;
            case "ink":
                return R.style.Theme_App_Ink;
            case "glass":
                return R.style.Theme_App_Glass;
            case "system":
                return 0;
            default:
                return R.style.Theme_App_Aurora;
        }
    }

    /** 必须在 Activity.onCreate 的 super.onCreate 之前调用。 */
    public static void applyPreset(@NonNull Activity activity) {
        try {
            if (isSystem(activity)) return;
            int res = presetTheme(get(activity, KEY_PRESET, "aurora"));
            if (res != 0) activity.setTheme(res);
        } catch (Throwable ignored) {
            // 主题资源异常时保持 Manifest 主题，绝不阻塞 Activity 启动。
        }
    }

    /** 必须在 super.onCreate 之后、setContentView 之前调用。 */
    public static void applyOverlay(@NonNull Activity activity) {
        try {
            if (isSystem(activity)) return;
            int palette = paletteOverlay(get(activity, KEY_PRESET, "aurora"));
            if (palette != 0) activity.getTheme().applyStyle(palette, true);
            int accent = accentOverlay(get(activity, KEY_ACCENT, "default"));
            if (accent != 0) activity.getTheme().applyStyle(accent, true);
            int corner = cornerOverlay(get(activity, KEY_CORNER, "default"));
            if (corner != 0) activity.getTheme().applyStyle(corner, true);
            int text = textOverlay(get(activity, KEY_TEXT, "default"));
            if (text != 0) activity.getTheme().applyStyle(text, true);
        } catch (Throwable ignored) {
            // 叠加失败不阻断启动。
        }
    }

    /** 生成一份按当前配置装饰过的 Context，用于设置面板的即时预览。 */
    public static Context previewContext(Context base) {
        int res = presetTheme(get(base, KEY_PRESET, "aurora"));
        if (res == 0) res = R.style.Theme_App_Aurora;
        ContextThemeWrapper wrapper = new ContextThemeWrapper(base, res);
        int palette = paletteOverlay(get(base, KEY_PRESET, "aurora"));
        if (palette != 0) wrapper.getTheme().applyStyle(palette, true);
        int accent = accentOverlay(get(base, KEY_ACCENT, "default"));
        if (accent != 0) wrapper.getTheme().applyStyle(accent, true);
        int corner = cornerOverlay(get(base, KEY_CORNER, "default"));
        if (corner != 0) wrapper.getTheme().applyStyle(corner, true);
        int text = textOverlay(get(base, KEY_TEXT, "default"));
        if (text != 0) wrapper.getTheme().applyStyle(text, true);
        return wrapper;
    }

    /** 预设调色板：只覆盖颜色与圆角属性，不动 widget style / parent 链。 */
    @StyleRes
    private static int paletteOverlay(String key) {
        switch (key) {
            case "paper":
                return R.style.ThemeOverlay_Aurora_Palette_Paper;
            case "ink":
                return R.style.ThemeOverlay_Aurora_Palette_Ink;
            case "glass":
                return R.style.ThemeOverlay_Aurora_Palette_Glass;
            case "system":
                return 0;
            default:
                return R.style.ThemeOverlay_Aurora_Palette_Aurora;
        }
    }

    @StyleRes
    private static int accentOverlay(String key) {
        switch (key) {
            case "jade":
                return R.style.ThemeOverlay_Aurora_Accent_Jade;
            case "azure":
                return R.style.ThemeOverlay_Aurora_Accent_Azure;
            case "amber":
                return R.style.ThemeOverlay_Aurora_Accent_Amber;
            case "rose":
                return R.style.ThemeOverlay_Aurora_Accent_Rose;
            case "violet":
                return R.style.ThemeOverlay_Aurora_Accent_Violet;
            case "cyan":
                return R.style.ThemeOverlay_Aurora_Accent_Cyan;
            case "lime":
                return R.style.ThemeOverlay_Aurora_Accent_Lime;
            case "slate":
                return R.style.ThemeOverlay_Aurora_Accent_Slate;
            default:
                return 0;
        }
    }

    @StyleRes
    private static int cornerOverlay(String key) {
        switch (key) {
            case "small":
                return R.style.ThemeOverlay_Aurora_Corner_Small;
            case "large":
                return R.style.ThemeOverlay_Aurora_Corner_Large;
            case "pill":
                return R.style.ThemeOverlay_Aurora_Corner_Pill;
            default:
                return 0;
        }
    }

    @StyleRes
    private static int textOverlay(String key) {
        switch (key) {
            case "small":
                return R.style.ThemeOverlay_Aurora_Text_Small;
            case "large":
                return R.style.ThemeOverlay_Aurora_Text_Large;
            default:
                return 0;
        }
    }

    public static int color(Context context, @AttrRes int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
        return fallback;
    }

    /**
     * 按背景色亮度返回可读前景色（浅底配深字、深底配浅字）。
     * 用于强调色按钮上的文字，保证四套预设与任意强调色 overlay 下都有足够对比度，
     * 且不依赖任何库声明的 attr（AGP 非传递 R 类下 R.attr 只包含本模块属性）。
     */
    public static int onAccent(int background) {
        double r = linear(Color.red(background));
        double g = linear(Color.green(background));
        double b = linear(Color.blue(background));
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luminance > 0.30 ? 0xFF07130E : 0xFFF7FBF9;
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** 返回像素值。 */
    public static float dimension(Context context, @AttrRes int attr, float fallbackDp) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true) && tv.type == TypedValue.TYPE_DIMENSION)
            return tv.getDimension(context.getResources().getDisplayMetrics());
        return fallbackDp * context.getResources().getDisplayMetrics().density;
    }

    public static int px(Context context, float dp) {
        return Math.max(1, (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f));
    }

    /** 按当前形态与主题色生成按钮背景，供任何原生 View 复用。 */
    public static GradientDrawable button(Context context, String shape) {
        int accent = color(context, R.attr.auroraAccent, 0xFF5FC79A);
        int soft = color(context, R.attr.auroraAccentSoft, 0xFF1E3A30);
        float radius = dimension(context, R.attr.auroraCorner, 12);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if ("pill".equals(shape)) {
            drawable.setCornerRadius(999f);
            drawable.setColor(accent);
        } else if ("square".equals(shape)) {
            drawable.setCornerRadius(0f);
            drawable.setColor(accent);
        } else if ("outline".equals(shape)) {
            drawable.setCornerRadius(radius);
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(px(context, 1.5f), accent);
        } else if ("soft".equals(shape)) {
            drawable.setCornerRadius(radius);
            drawable.setColor(soft);
        } else {
            drawable.setCornerRadius(radius);
            drawable.setColor(accent);
        }
        return drawable;
    }

    /** 按当前弹窗风格生成浮层背景。 */
    public static GradientDrawable surface(Context context, String style) {
        int high = color(context, R.attr.auroraSurfaceHigh, 0xFF232C28);
        int outline = color(context, R.attr.auroraOutline, 0xFF2E3A35);
        float radius = dimension(context, R.attr.auroraCornerLarge, 20);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radius);
        if ("outline".equals(style)) {
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(px(context, 1.5f), outline);
        } else if ("soft".equals(style)) {
            drawable.setColor(high);
            drawable.setStroke(px(context, 1f), outline);
        } else if ("glass".equals(style)) {
            drawable.setColor(Color.argb(242, 26, 25, 48));
            drawable.setStroke(px(context, 1.5f), Color.argb(170, 255, 255, 255));
        } else {
            drawable.setColor(high);
        }
        return drawable;
    }

    /** 按宫格形状生成封面底色。 */
    public static GradientDrawable tile(Context context, String shape) {
        int high = color(context, R.attr.auroraSurfaceHigh, 0xFF232C28);
        float radius = dimension(context, R.attr.auroraCorner, 12);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(high);
        if ("circle".equals(shape)) {
            drawable.setShape(GradientDrawable.OVAL);
        } else {
            drawable.setShape(GradientDrawable.RECTANGLE);
            if ("square".equals(shape)) drawable.setCornerRadius(0f);
            else if ("large".equals(shape)) drawable.setCornerRadius(radius * 1.8f);
            else if ("pill".equals(shape)) drawable.setCornerRadius(999f);
            else drawable.setCornerRadius(radius);
        }
        return drawable;
    }

    /** 弹窗位置 / 风格即时生效；高版本玻璃模糊带降级保护。 */
    public static void decorateWindow(Window window, Context context) {
        if (window == null) return;
        String position = get(context, KEY_DIALOG_POS, "center");
        String style = get(context, KEY_DIALOG_STYLE, "solid");
        if ("bottom".equals(position)) {
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        } else if ("top".equals(position)) {
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        } else if ("side".equals(position)) {
            window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT);
        } else {
            window.setGravity(Gravity.CENTER);
            window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        window.setBackgroundDrawable(surface(context, style));
        window.setDimAmount("glass".equals(style) ? 0.62f : 0.48f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && "glass".equals(style)) {
            try {
                window.setBackgroundBlurRadius(px(context, 18f));
            } catch (Throwable ignored) {
                // 低版本或厂商 ROM 不支持时保持半透明底色，不崩。
            }
        }
    }
}
