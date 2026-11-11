package com.fongmi.android.tv.ui.theme;

import android.app.Activity;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
 * <p>启动期唯一动作：在 setContentView 之前用 {@link Activity#setTheme(int)} 切换到
 * parent="Theme.App" 的完整预设链（与 oktv-cat 基线同源）。不再用
 * {@code Theme.applyStyle} 叠加 parent="" 的裸 style —— 那会改写 inflate 期的 theme
 * 并把 framework attr 覆盖成字面色，导致 BottomNavigationView 构造时
 * Resources.getValue(0) -> Resources$NotFoundException #0x0。
 *
 * <p>切换后跑 themeUsable() 探针；任一关键 attr 解析不出就回落 Theme.App，
 * 保证 Activity 一定能起来。强调色 / 圆角 / 字号细项由代码直读偏好提供，
 * 不再叠加任何 style。
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

    /**
     * 在 super.onCreate 之后、setContentView 之前调用。
     * 用完整 parent 链的 setTheme，不再 applyStyle；失败一律回落基线主题。
     */
    public static void apply(@NonNull Activity activity) {
        try {
            if (isSystem(activity)) return;
            int preset = presetTheme(get(activity, KEY_PRESET, "aurora"));
            if (preset == 0) return;
            activity.setTheme(preset);
            if (!themeUsable(activity)) {
                activity.setTheme(R.style.Theme_App);
                return;
            }
            activity.getWindow().setBackgroundDrawable(
                    new ColorDrawable(color(activity, R.attr.auroraBackdrop, 0xFF0E1512)));
        } catch (Throwable ignored) {
            try {
                activity.setTheme(R.style.Theme_App);
            } catch (Throwable ignoredToo) {
                // 保持 Manifest 基线主题，绝不阻塞启动。
            }
        }
    }

    /**
     * 切换后检查关键 attr 能否解析；任一为 0/NULL 就不允许用这套 theme 去 inflate。
     *
     * <p>额外精确拦截 #0x0 的回归：Material 的 NavigationBarView / MaterialTextView 会取
     * {@code android:textColorSecondary} 等 framework 颜色 attr 的 {@code value.resourceId}
     * 去 {@code AppCompatResources.getColorStateList()}。一旦这些 attr 被写成字面色
     * （{@code #AARRGGBB}），resourceId 就是 0，构造 BottomNavigationView 时抛
     * {@code Resources$NotFoundException: Resource ID #0x0}。
     *
     * <p>用 {@code resolveRefs=false} 读原始类型即可识别字面色，且不会把正常的
     * {@code @color/} 引用误判为失败（误判会导致主题静默回退、界面回到原生观感）。
     */
    private static boolean themeUsable(@NonNull Activity activity) {
        int[] required = {
                android.R.attr.textColorPrimary,
                android.R.attr.selectableItemBackground,
                android.R.attr.windowBackground,
                R.attr.auroraBackdrop,
                R.attr.auroraSurface,
                R.attr.auroraAccent,
        };
        for (int id : required) {
            TypedValue tv = new TypedValue();
            if (!activity.getTheme().resolveAttribute(id, tv, true)) return false;
            if (tv.type == TypedValue.TYPE_NULL) return false;
            if (tv.type == TypedValue.TYPE_REFERENCE && tv.data == 0) return false;
        }
        int[] frameworkColorRefs = {
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                android.R.attr.textColorTertiary,
                android.R.attr.textColorHint,
                android.R.attr.colorControlHighlight,
        };
        for (int id : frameworkColorRefs) {
            TypedValue raw = new TypedValue();
            if (!activity.getTheme().resolveAttribute(id, raw, false)) return false;
            if (raw.type == TypedValue.TYPE_NULL) return false;
            if (raw.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && raw.type <= TypedValue.TYPE_LAST_COLOR_INT) return false;
        }
        return true;
    }

    /** 预览用 Context：完整链 style + 代码直读细项，与 apply() 行为一致。 */
    public static Context previewContext(Context base) {
        int res = presetTheme(get(base, KEY_PRESET, "aurora"));
        if (res == 0) res = R.style.Theme_App_Aurora;
        return new ContextThemeWrapper(base, res);
    }

    /** 强调色：不再叠加 overlay，直接按偏好返回色值。 */
    private static int accentColor(String key, boolean soft) {
        switch (key) {
            case "jade": return soft ? 0xFF173A2C : 0xFF7FE0B4;
            case "azure": return soft ? 0xFF14293D : 0xFF7CC4FF;
            case "amber": return soft ? 0xFF3B2A0E : 0xFFFFC46B;
            case "rose": return soft ? 0xFF3D1B2A : 0xFFFFA8C8;
            case "violet": return soft ? 0xFF2C2547 : 0xFFC9B6FF;
            case "cyan": return soft ? 0xFF0F3038 : 0xFF6EE7F5;
            case "lime": return soft ? 0xFF263713 : 0xFFC6E88F;
            case "slate": return soft ? 0xFF28343A : 0xFFCFD8DC;
            default: return 0;
        }
    }

    private static float cornerDp(String key, boolean large) {
        switch (key) {
            case "small": return large ? 8f : 4f;
            case "large": return large ? 28f : 20f;
            case "pill": return large ? 36f : 28f;
            default: return 0f;
        }
    }

    private static float textSp(String key) {
        switch (key) {
            case "small": return 13f;
            case "large": return 17f;
            default: return 0f;
        }
    }

    public static int color(Context context, @AttrRes int attr, int fallback) {
        if (attr == R.attr.auroraAccent || attr == R.attr.auroraAccentSoft) {
            int override = accentColor(get(context, KEY_ACCENT, "default"),
                    attr == R.attr.auroraAccentSoft);
            if (override != 0) return override;
        }
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
        return fallback;
    }

    /** 按背景色亮度返回可读前景色（浅底配深字、深底配浅字）。 */
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

    /** 返回像素值；圆角/字号细项按偏好覆盖，不再叠加 style。 */
    public static float dimension(Context context, @AttrRes int attr, float fallbackDp) {
        float density = context.getResources().getDisplayMetrics().density;
        if (attr == R.attr.auroraCorner || attr == R.attr.auroraCornerLarge) {
            float dp = cornerDp(get(context, KEY_CORNER, "default"), attr == R.attr.auroraCornerLarge);
            if (dp > 0f) return dp * density;
        } else if (attr == R.attr.auroraTextSize) {
            float sp = textSp(get(context, KEY_TEXT, "default"));
            if (sp > 0f) return sp * context.getResources().getDisplayMetrics().scaledDensity;
        }
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true) && tv.type == TypedValue.TYPE_DIMENSION)
            return tv.getDimension(context.getResources().getDisplayMetrics());
        return fallbackDp * density;
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
