package com.fongmi.android.tv.ui.theme;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * xbox-re 原生 UI 主题引擎。
 *
 * <p>启动期唯一动作：在 setContentView 之前用 Activity.setTheme 切换到
 * parent="Theme.App" 的完整预设链（与 oktv-cat 基线同源）。不再叠加裸 style ——
 * 那会改写 inflate 期的 theme 并把 framework attr 覆盖成字面色，导致
 * BottomNavigationView 构造时 Resources.getValue(0) -> Resources$NotFoundException #0x0。
 *
 * <p>2026-11-12 修复「配置不生效 / 应用后窗口不关」：
 * <ol>
 *   <li>旧 themeUsable 把 framework 颜色 attr「解析不到」也当成失败，
 *       于是每次都静默回落 Theme.App，用户怎么改配置界面都不变。
 *       现在只拦截真实的崩溃源（framework 颜色 attr 被写成字面色）。</li>
 *   <li>配置变更用进程内 epoch 广播，所有 Activity 恢复时按需重建，
 *       不销毁任务栈、不清栈、不销毁弹窗宿主。</li>
 *   <li>细项（文字大小 / 圆角 / 按钮形态 / 宫格形状 / 弹窗风格与位置）通过
 *       App 的 ActivityLifecycleCallbacks 全局装饰，不再只作用于预览面板；
 *       默认配置下零侵入，任何异常都被吞掉，绝不影响业务界面。</li>
 * </ol>
 */
public final class AuroraTheme {

    private static final String TAG = "AuroraTheme";

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

    /** 进程内配置版本号；改任一配置即 ++，让任务栈里的其它页面恢复时按需重建。 */
    private static volatile long sEpoch = 0L;
    private static final WeakHashMap<Activity, Long> EPOCHS = new WeakHashMap<>();
    /** 最近一次 apply() 的真实结果，供设置面板显示，避免「不知道有没有生效」。 */
    private static volatile String sApplied = "Theme.App (manifest baseline)";
    private static volatile String sProbe = "not-run";

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
        // 同步落盘：选项点击后可能立刻 recreate()，apply() 的异步写会读到旧值。
        prefs(context).edit().putString(key, value).commit();
        sEpoch++;
    }

    public static void reset(Context context) {
        prefs(context).edit().clear().commit();
        sEpoch++;
    }

    /** 「应用并刷新」按钮显式标记一次全量刷新（put 已逐项 ++，这里再兜底一次）。 */
    public static void markChanged() {
        sEpoch++;
    }

    public static long epoch() {
        return sEpoch;
    }

    public static boolean isSystem(Context context) {
        return "system".equals(get(context, KEY_PRESET, "aurora"));
    }

    /** 给设置面板看的真实生效状态：已保存配置 + 实际 setTheme 结果 + 探针结论。 */
    public static String status(Context context) {
        return "已保存：" + get(context, KEY_PRESET, "aurora") + " / " + get(context, KEY_ACCENT, "default")
                + "　实际生效：" + sApplied + "（" + sProbe + "）";
    }

    @StyleRes
    private static int presetTheme(String preset, String accent) {
        if ("system".equals(preset)) return 0;
        String p;
        if ("paper".equals(preset)) p = "Paper";
        else if ("ink".equals(preset)) p = "Ink";
        else if ("glass".equals(preset)) p = "Glass";
        else p = "Aurora";
        if (accent == null || "default".equals(accent)) {
            switch (p) {
                case "Paper": return R.style.Theme_App_Paper;
                case "Ink": return R.style.Theme_App_Ink;
                case "Glass": return R.style.Theme_App_Glass;
                default: return R.style.Theme_App_Aurora;
            }
        }
        // 32 个组合 style 全是 Theme.App.* 完整链，编译期显式引用，R8 不会裁掉。
        switch (p + "_" + accent) {
            case "Aurora_jade": return R.style.Theme_App_Aurora_Jade;
            case "Aurora_azure": return R.style.Theme_App_Aurora_Azure;
            case "Aurora_amber": return R.style.Theme_App_Aurora_Amber;
            case "Aurora_rose": return R.style.Theme_App_Aurora_Rose;
            case "Aurora_violet": return R.style.Theme_App_Aurora_Violet;
            case "Aurora_cyan": return R.style.Theme_App_Aurora_Cyan;
            case "Aurora_lime": return R.style.Theme_App_Aurora_Lime;
            case "Aurora_slate": return R.style.Theme_App_Aurora_Slate;
            case "Paper_jade": return R.style.Theme_App_Paper_Jade;
            case "Paper_azure": return R.style.Theme_App_Paper_Azure;
            case "Paper_amber": return R.style.Theme_App_Paper_Amber;
            case "Paper_rose": return R.style.Theme_App_Paper_Rose;
            case "Paper_violet": return R.style.Theme_App_Paper_Violet;
            case "Paper_cyan": return R.style.Theme_App_Paper_Cyan;
            case "Paper_lime": return R.style.Theme_App_Paper_Lime;
            case "Paper_slate": return R.style.Theme_App_Paper_Slate;
            case "Ink_jade": return R.style.Theme_App_Ink_Jade;
            case "Ink_azure": return R.style.Theme_App_Ink_Azure;
            case "Ink_amber": return R.style.Theme_App_Ink_Amber;
            case "Ink_rose": return R.style.Theme_App_Ink_Rose;
            case "Ink_violet": return R.style.Theme_App_Ink_Violet;
            case "Ink_cyan": return R.style.Theme_App_Ink_Cyan;
            case "Ink_lime": return R.style.Theme_App_Ink_Lime;
            case "Ink_slate": return R.style.Theme_App_Ink_Slate;
            case "Glass_jade": return R.style.Theme_App_Glass_Jade;
            case "Glass_azure": return R.style.Theme_App_Glass_Azure;
            case "Glass_amber": return R.style.Theme_App_Glass_Amber;
            case "Glass_rose": return R.style.Theme_App_Glass_Rose;
            case "Glass_violet": return R.style.Theme_App_Glass_Violet;
            case "Glass_cyan": return R.style.Theme_App_Glass_Cyan;
            case "Glass_lime": return R.style.Theme_App_Glass_Lime;
            case "Glass_slate": return R.style.Theme_App_Glass_Slate;
            default: return R.style.Theme_App_Aurora;
        }
    }

    private static String styleName(Context context, @StyleRes int id) {
        try {
            return context.getResources().getResourceEntryName(id).replace('_', '.');
        } catch (Throwable ignored) {
            return "style#0x" + Integer.toHexString(id);
        }
    }

    /**
     * 在 super.onCreate 之后、setContentView 之前调用。
     * 用完整 parent 链的 setTheme；失败一律回落基线主题，并留下可查的日志。
     */
    public static void apply(@NonNull Activity activity) {
        sApplied = "Theme.App (manifest baseline)";
        sProbe = "not-run";
        try {
            if (isSystem(activity)) {
                sApplied = "跟随系统（原主题）";
                sProbe = "system";
                return;
            }
            int preset = presetTheme(get(activity, KEY_PRESET, "aurora"),
                    get(activity, KEY_ACCENT, "default"));
            if (preset == 0) return;
            activity.setTheme(preset);
            String name = styleName(activity, preset);
            sApplied = name;
            // 探针只作诊断，绝不再回落 Theme.App。历史 #0x0 崩溃的唯一成因是预设 style
            // 把 framework 颜色 attr 写成字面色；编译产物与门禁已保证预设不含这些项。
            // 部分 OEM ROM 的 framework 主题本身会把 textColorSecondary 等解析成字面色，
            // 旧逻辑据此静默回落，用户改任何配置界面都不变 —— 这是「主题没生效」的直接原因之一。
            boolean usable = themeUsable(activity);
            sProbe = usable ? "probe OK" : "probe WARN (kept, no fallback)";
            if (!usable) Log.w(TAG, "theme probe warned for " + name + ", keep applied theme");
            activity.getWindow().setBackgroundDrawable(
                    new ColorDrawable(color(activity, R.attr.auroraBackdrop, 0xFF0E1512)));
            Log.i(TAG, "applied " + name + " on " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            sProbe = "exception: " + t.getClass().getSimpleName();
            Log.w(TAG, "apply failed, fallback", t);
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
     * <p>精确拦截 #0x0 的唯一真实成因：Material 的 NavigationBarView / MaterialTextView
     * 会取 android:textColorSecondary 等 framework 颜色 attr 的 value.resourceId 去
     * AppCompatResources.getColorStateList()。一旦这些 attr 被显式写成字面色，
     * resourceId 就是 0。
     *
     * <p>注意：resolveAttribute(resolveRefs=false) 返回 false 只说明「该 framework attr
     * 没有在当前主题里显式声明」，framework 会回落到自己的默认值，完全安全。旧实现把它
     * 当成失败，导致每次启动都静默 setTheme(Theme.App) —— 用户看到的就是「配置保存了，
     * 但界面永远不变」。这里改为 continue。
     */
    private static boolean themeUsable(@NonNull Activity activity) {
        int[] required = {
                // 基础主题链必须完整（resolveRefs=true，framework 一定给得出值）。
                android.R.attr.textColorPrimary,
                android.R.attr.selectableItemBackground,
                android.R.attr.windowBackground,
                // 预设 style 的自定义 attr 必须解析得到。
                R.attr.auroraBackdrop,
                R.attr.auroraSurface,
                R.attr.auroraAccent,
                R.attr.auroraOnSurface,
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
            if (!activity.getTheme().resolveAttribute(id, raw, false)) continue;
            if (raw.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && raw.type <= TypedValue.TYPE_LAST_COLOR_INT) return false;
        }
        return true;
    }

    /** 预览用 Context：完整链 style + 代码直读细项，与 apply() 行为一致。 */
    public static Context previewContext(Context base) {
        int res = presetTheme(get(base, KEY_PRESET, "aurora"),
                get(base, KEY_ACCENT, "default"));
        if (res == 0) res = R.style.Theme_App_Aurora;
        return new ContextThemeWrapper(base, res);
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

    // ============ 全局生效层（由 App.ActivityLifecycleCallbacks 驱动，默认零侵入） ============

    private static boolean dialogCustomized(Context context) {
        return !"solid".equals(get(context, KEY_DIALOG_STYLE, "solid"))
                || !"center".equals(get(context, KEY_DIALOG_POS, "center"));
    }

    private static boolean detailCustomized(Context context) {
        return !"default".equals(get(context, KEY_CORNER, "default"))
                || !"default".equals(get(context, KEY_TEXT, "default"))
                || !"solid".equals(get(context, KEY_BUTTON, "solid"))
                || !"soft".equals(get(context, KEY_TILE, "soft"));
    }

    /** App.onActivityCreated 调用：登记 epoch、自动装饰弹窗、布局后装饰业务 View 树。 */
    public static void onActivityCreated(@NonNull Activity activity) {
        synchronized (EPOCHS) {
            EPOCHS.put(activity, sEpoch);
        }
        try {
            if (dialogCustomized(activity) && activity instanceof FragmentActivity) {
                ((FragmentActivity) activity).getSupportFragmentManager()
                        .registerFragmentLifecycleCallbacks(new DialogWatcher(), false);
            }
        } catch (Throwable ignored) {
            // 生命周期回调注册失败不能影响 Activity 启动。
        }
        // 只要启用了 Aurora 主题（非「跟随系统」）就把主题色铺到业务界面；细项装饰另判。
        // 旧实现只看 detailCustomized()：用户改「预设 / 强调色」时不触发任何装饰，
        // 加上业务布局几乎不引用 ?attr/aurora*，主界面自然「怎么改都不变」。
        final boolean themed = !isSystem(activity);
        final boolean details = detailCustomized(activity);
        if (!themed && !details) return;
        try {
            View decor = activity.getWindow().getDecorView();
            decor.post(() -> {
                if (themed) applyBackdrop(activity);
                if (details) decorateTree(activity);
            });
        } catch (Throwable ignored) {
            // 装饰是锦上添花，任何异常都不允许影响业务界面。
        }
    }

    /** App.onActivityResumed 调用：配置变更后，让任务栈里仍活着的旧页面重建。 */
    public static void onActivityResumed(@NonNull Activity activity) {
        Long applied;
        synchronized (EPOCHS) {
            applied = EPOCHS.get(activity);
        }
        if (applied == null || applied == sEpoch) return;
        try {
            activity.recreate();
        } catch (Throwable ignored) {
            // 个别系统 Activity 不支持 recreate 时保持原样，不再重试，避免循环。
        }
    }

    private static final class DialogWatcher extends FragmentManager.FragmentLifecycleCallbacks {
        @Override
        public void onFragmentStarted(@NonNull FragmentManager fm, @NonNull Fragment f) {
            if (!(f instanceof DialogFragment)) return;
            try {
                Dialog dialog = ((DialogFragment) f).getDialog();
                if (dialog == null || dialog.getWindow() == null) return;
                Context c = f.getContext();
                if (c != null && dialogCustomized(c)) decorateWindow(dialog.getWindow(), c);
            } catch (Throwable ignored) {
                // 弹窗装饰失败不影响弹窗本身。
            }
        }
    }

    private static void decorateTree(@NonNull Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content != null) applyToView(content, activity);
        } catch (Throwable ignored) {
            // 同上：装饰层绝不能抛到业务代码。
        }
    }

    private static void applyToView(@NonNull View view, @NonNull Context context) {
        try {
            if (view instanceof TextView) scaleText((TextView) view, context);
            if (view instanceof Button) shapeButton((Button) view, context);
            else if (view instanceof ImageView) clipView(view, tileRadius(context));
            else if (!(view instanceof ViewGroup) && !(view instanceof TextView)
                    && view.getBackground() != null) clipView(view, cornerRadius(context));
            if (view instanceof RecyclerView) {
                ((RecyclerView) view).addOnChildAttachStateChangeListener(
                        new RecyclerView.OnChildAttachStateChangeListener() {
                            @Override
                            public void onChildViewAttachedToWindow(@NonNull View child) {
                                applyToView(child, context);
                            }

                            @Override
                            public void onChildViewDetachedFromWindow(@NonNull View child) {
                            }
                        });
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) applyToView(group.getChildAt(i), context);
            }
        } catch (Throwable ignored) {
            // 单个 View 装饰失败直接跳过。
        }
    }

    /** 用 tag 记住原始字号，RecyclerView 复用 / 多次装饰都不会把字越放越大。 */
    private static void scaleText(@NonNull TextView view, @NonNull Context context) {
        float factor = textFactor(context);
        if (factor == 1f) return;
        float base;
        Object tag = view.getTag(R.id.aurora_text_size_orig);
        if (tag instanceof Float) base = (Float) tag;
        else {
            base = view.getTextSize();
            if (base <= 0f) return;
            view.setTag(R.id.aurora_text_size_orig, base);
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * factor);
    }

    private static float textFactor(Context context) {
        String key = get(context, KEY_TEXT, "default");
        if ("small".equals(key)) return 0.86f;
        if ("large".equals(key)) return 1.18f;
        return 1f;
    }

    private static void shapeButton(@NonNull Button view, @NonNull Context context) {
        String shape = get(context, KEY_BUTTON, "solid");
        if ("solid".equals(shape)) return;
        view.setBackground(button(context, shape));
        view.setTextColor(onAccent(color(context, R.attr.auroraAccent, 0xFF5FC79A)));
    }

    private static float cornerRadius(Context context) {
        String key = get(context, KEY_CORNER, "default");
        float dp;
        if ("small".equals(key)) dp = 4f;
        else if ("large".equals(key)) dp = 20f;
        else if ("pill".equals(key)) dp = 999f;
        else dp = 12f;
        return dp * context.getResources().getDisplayMetrics().density;
    }

    private static float tileRadius(Context context) {
        String key = get(context, KEY_TILE, "soft");
        if ("square".equals(key)) return 0f;
        if ("large".equals(key)) return 26f * context.getResources().getDisplayMetrics().density;
        if ("circle".equals(key) || "poster".equals(key)) return 999f;
        if ("wide".equals(key)) return 8f * context.getResources().getDisplayMetrics().density;
        return cornerRadius(context);
    }

    /** 只裁剪轮廓、不替换背景 drawable：不破坏原有 selector / ripple / 图片内容。 */
    private static void clipView(@NonNull View view, float radius) {
        if (radius <= 0f) return;
        final float r = radius;
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        view.setClipToOutline(true);
    }

    // ============ 主题色业务层：让 preset / accent 在真实界面看得见 ============
    //
    // xbox-re 的业务 UI 是「壁纸 + 硬编码 @color / drawable」体系：主界面由
    // CustomWallView 铺满背景，颜色大量写死 @color/white、@color/black，
    // ?attr/aurora* 在所有 layout / drawable 里零引用。因此只靠 setTheme 改 M3 attr
    // 用户看不到任何变化。这里在 View 层（与 inflate / theme 完全无关，不会触发 #0x0）
    // 把主题底色铺到壁纸之上、业务内容之下，并给底部导航上强调色。
    // 全部操作幂等 + try/catch，且「跟随系统」时完全不执行，保持原版零侵入。

    private static final String TAG_BACKDROP = "aurora.backdrop";

    private static void applyBackdrop(@NonNull Activity activity) {
        try {
            String name = activity.getClass().getSimpleName();
            // 播放 / 投屏 / 崩溃页是独立的深色视频层，保持原样，避免破坏播放器可读性。
            if (name.contains("Video") || name.contains("Live") || name.contains("Playback")
                    || name.contains("Cast") || name.contains("Player") || name.contains("Crash")) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) content;
            // 用「预设 Context」取色，而不是 Activity 当前 theme：部分 Activity 不继承
            // BaseActivity（如 CatWebActivity / LabActivity），或首页自己又 setTheme(Theme_App)，
            // 直接读 Activity theme 会拿不到 aurora* 值而退回同一个默认色。
            Context themed = previewContext(activity);
            int backdrop = color(themed, R.attr.auroraBackdrop, 0xFF0E1512);
            int surface = color(themed, R.attr.auroraSurface, 0xFF17211D);
            int accent = color(themed, R.attr.auroraAccent, 0xFF7FE0B4);
            int onSurface = color(themed, R.attr.auroraOnSurface, 0xFFF2F7F4);

            View tint = group.findViewWithTag(TAG_BACKDROP);
            if (tint == null) {
                tint = new View(activity);
                tint.setTag(TAG_BACKDROP);
                tint.setClickable(false);
                tint.setFocusable(false);
                // 壁纸由 BaseActivity.addCustomWall() 插在 content 的 index 0；
                // 主题底色插在它之上、业务内容之下，业务根透明时即可透出主题色。
                int idx = group.getChildCount() > 0
                        && group.getChildAt(0).getClass().getSimpleName().contains("CustomWall") ? 1 : 0;
                group.addView(tint, Math.min(idx, group.getChildCount()),
                        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // 保留一点壁纸质感，但让主题底色成为主色调（约 84% 不透明）。
            tint.setBackgroundColor(Color.argb(0xD6, Color.red(backdrop),
                    Color.green(backdrop), Color.blue(backdrop)));
            tintNavigation(group, surface, accent, onSurface);
            if (isLight(backdrop)) recolorLightText(group, onSurface);
        } catch (Throwable ignored) {
            // 主题底色是锦上添花，绝不允许影响业务界面。
        }
    }

    /** 底部导航是主界面最显眼的元素，随主题 / 强调色一起变色。 */
    private static void tintNavigation(@NonNull View root, int surface, int accent, int onSurface) {
        try {
            List<View> navs = new ArrayList<>();
            collect(root, NavigationBarView.class, navs);
            if (navs.isEmpty()) return;
            int normal = Color.argb(0x9E, Color.red(onSurface),
                    Color.green(onSurface), Color.blue(onSurface));
            ColorStateList states = new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, new int[0]},
                    new int[]{accent, normal});
            for (View v : navs) {
                v.setBackgroundColor(surface);
                NavigationBarView nav = (NavigationBarView) v;
                nav.setItemIconTintList(states);
                nav.setItemTextColor(states);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collect(@NonNull View v, @NonNull Class<?> type, @NonNull List<View> out) {
        try {
            if (type.isInstance(v)) out.add(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), type, out);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 浅色预设（纸白）下把业务里写死的近白文字改成深色，保证可读性。 */
    private static void recolorLightText(@NonNull View root, int onSurface) {
        try {
            walkText(root, onSurface);
        } catch (Throwable ignored) {
        }
    }

    private static void walkText(@NonNull View v, int onSurface) {
        try {
            // 底部导航的 item 文字由 tintNavigation 统一管理，跳过其内部 View。
            if (v instanceof NavigationBarView) return;
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                int c = tv.getCurrentTextColor();
                if (Color.alpha(c) > 200 && Color.red(c) > 200
                        && Color.green(c) > 200 && Color.blue(c) > 200) tv.setTextColor(onSurface);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) walkText(g.getChildAt(i), onSurface);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLight(int c) {
        double l = 0.2126 * linear(Color.red(c)) + 0.7152 * linear(Color.green(c))
                + 0.0722 * linear(Color.blue(c));
        return l > 0.55;
    }
}
