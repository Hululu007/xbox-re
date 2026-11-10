/* =============================================================
   xbox-re · 统一外观设置入口  v1.0
   -------------------------------------------------------------
   职责：把项目里分散的「外观 / 主题 / 皮肤」入口收敛到同一个面板。
   页面只需引入本文件，并在任意按钮上写 data-theme-entry，点击即打开本面板；
   面板内可再跳转原生设置页（通过 fongmiBridge 的 app.openSetting）。

   零依赖（仅依赖同目录 theme-engine.js）、不改动宿主页面 DOM 结构、
   所有类名 ap- 前缀，与 ui.css / theme.css 完全隔离。
   ============================================================= */
(function (global) {
    "use strict";

    var E = global.ThemeEngine;
    if (!E) return;

    var GROUPS = [
        { key: "btnShape", label: "按钮形态", options: [["solid", "实心", "9px"], ["pill", "胶囊", "999px"], ["square", "方角", "3px"], ["outline", "描边", "9px"], ["soft", "柔底", "12px"]] },
        { key: "tileShape", label: "宫格形状", options: [["soft", "圆角", "12px"], ["square", "方形", "3px"], ["rounded", "大圆角", "18px"], ["circle", "圆形", "50%"], ["poster", "竖海报", "8px"], ["wide", "宽幅", "8px"]] },
        { key: "modalStyle", label: "弹窗风格", options: [["blur", "模糊遮罩", "9px"], ["solid", "纯色遮罩", "9px"], ["outline", "描边通透", "9px"], ["glass", "玻璃拟态", "9px"]] },
        { key: "modalPos", label: "弹出位置", options: [["center", "居中", "9px"], ["bottom", "底部上弹", "9px"], ["top", "顶部", "9px"], ["side", "侧滑", "9px"]] }
    ];

    var COLORS = [
        ["textPrimary", "主文字"], ["textSecondary", "次文字"], ["accent", "强调色"], ["border", "描边"],
        ["bgBase", "页面背景"], ["bgSurface", "卡片表面"], ["bgSubtle", "次级底色"], ["bgElevated", "浮层底色"]
    ];

    var RANGES = [
        ["scale", "全局大小比例", 0.85, 1.3, 0.05, function (v) { return Number(v).toFixed(2) + "×"; }],
        ["fontSize", "基础字号", 12, 18, 0.5, function (v) { return Number(v).toFixed(1) + "px"; }],
        ["radius", "圆角基值", 0, 28, 1, function (v) { return Number(v).toFixed(0) + "px"; }],
        ["duration", "动效时长", 0, 400, 10, function (v) { return Number(v).toFixed(0) + "ms"; }],
        ["blur", "遮罩模糊", 0, 24, 1, function (v) { return Number(v).toFixed(0) + "px"; }]
    ];

    var PRESET_META = {
        aurora: ["柔雾青", "推荐默认 · 最护眼", "#f4f6f5", "#2e8b68"],
        paper: ["纸白", "黑字白底 · 印刷感", "#ffffff", "#1b1b1b"],
        ink: ["夜墨", "低亮度深色 · 夜间", "#14181a", "#6ccfa4"],
        glass: ["流光玻璃", "流行拟态 · 克制版", "linear-gradient(135deg,#1b1d3a,#2b2450)", "#a99bff"]
    };

    /* 入口统筹地图：所有原生 / Web 入口都指向同一份配置 */
    var ENTRY_MAP = [
        ["首页「外观」按钮", "WebHome 首页 / 管理页", "直接打开本面板"],
        ["手机 设置 → 外观", "mobile SettingFragment", "外观弹窗 → 皮肤与定制"],
        ["TV 设置 → 外观", "leanback SettingActivity", "皮肤弹窗 → 内置默认主题"],
        ["增强设置 → 全局 WebHome 皮肤", "mobile / leanback", "禁用 / 内置柔雾青 / 自定义网址"],
        ["首页按钮「设置」", "HomeButton id 7", "设置页 → 外观"],
        ["管理页「设定」标签", "assets/index.html", "外观按钮 → 本面板"],
        ["主题色", "mobile AppearanceDialog", "原生 ThemeDialog（保留，不冲突）"]
    ];

    function h(s) {
        return String(s).replace(/[&<>"]/g, function (m) {
            return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[m];
        });
    }

    function presetCards() {
        return '<div class="ap-group"><div class="ap-group-title">配色方案</div><div class="ap-presets">' +
            Object.keys(PRESET_META).map(function (k) {
                var m = PRESET_META[k];
                return '<button class="ap-preset" data-preset="' + k + '">' +
                    '<div class="ap-swatch" style="background:' + m[2] + ';border-left:6px solid ' + m[3] + '"></div>' +
                    '<b>' + h(m[0]) + '</b><small>' + h(m[1]) + '</small></button>';
            }).join("") + '</div><span class="ap-hint">切换预设会重置自定义颜色；形态与比例保持你的设置。</span></div>';
    }

    function shapeGroup(g) {
        return '<div class="ap-item"><span class="ap-label">' + h(g.label) + '</span><div class="ap-options" data-group="' + g.key + '">' +
            g.options.map(function (o) {
                var css = g.key === "btnShape" ? "border-radius:" + o[2] + ";" : (g.key === "tileShape" && o[0] === "circle" ? "border-radius:50%;" : (g.key === "tileShape" && o[0] === "poster" ? "width:18px;height:28px;" : (g.key === "tileShape" && o[0] === "wide" ? "width:30px;height:18px;" : "border-radius:" + o[2] + ";")));
                return '<button class="ap-option" data-val="' + o[0] + '"><span class="ap-shape" style="' + css + '"></span>' + h(o[1]) + '</button>';
            }).join("") + '</div></div>';
    }

    function segGroup(key, label, values) {
        return '<div class="ap-item"><span class="ap-label">' + h(label) + '</span><div class="ap-seg" data-seg="' + key + '">' +
            values.map(function (o) { return '<button data-val="' + o[0] + '">' + h(o[1]) + '</button>'; }).join("") +
            '</div></div>';
    }

    function build() {
        var html = '';
        html += presetCards();
        html += '<div class="ap-group"><div class="ap-group-title">元素形态</div>' +
            shapeGroup(GROUPS[0]) + shapeGroup(GROUPS[1]) + '</div>';
        html += '<div class="ap-group"><div class="ap-group-title">弹窗</div>' +
            shapeGroup(GROUPS[2]) + shapeGroup(GROUPS[3]) +
            '<div class="ap-item"><div class="ap-row-between"><span class="ap-label" style="margin:0">遮罩模糊</span><span class="ap-value" data-out="blur">12px</span></div>' +
            '<input class="ap-range" type="range" min="0" max="24" step="1" data-num="blur"></div></div>';
        html += '<div class="ap-group"><div class="ap-group-title">颜色 · 字体</div><div class="ap-colors">' +
            COLORS.map(function (c) {
                return '<label class="ap-color"><span>' + h(c[1]) + '</span><div class="ap-color-row">' +
                    '<input type="color" data-color="' + c[0] + '"><code data-hex="' + c[0] + '"></code></div></label>';
            }).join("") + '</div>' +
            segGroup("font", "字体族", [["system", "系统"], ["geometric", "几何"], ["humanist", "人文"], ["mono", "等宽"]]) + '</div>';
        html += '<div class="ap-group"><div class="ap-group-title">比例与动效</div>' +
            RANGES.slice(0, 4).map(function (r) {
                return '<div class="ap-item"><div class="ap-row-between"><span class="ap-label" style="margin:0">' + h(r[1]) + '</span>' +
                    '<span class="ap-value" data-out="' + r[0] + '"></span></div>' +
                    '<input class="ap-range" type="range" min="' + r[2] + '" max="' + r[3] + '" step="' + r[4] + '" data-num="' + r[0] + '"></div>';
            }).join("") +
            segGroup("density", "界面密度", [["compact", "紧凑"], ["normal", "标准"], ["relaxed", "宽松"]]) +
            '<div class="ap-item ap-row-between"><span class="ap-label" style="margin:0">启用动效</span>' +
            '<button class="ap-switch" role="switch" aria-checked="true" data-toggle="motion"></button></div></div>';
        html += '<div class="ap-group"><div class="ap-group-title">设置入口地图</div>' +
            '<table class="ap-map"><thead><tr><th>入口</th><th>位置</th><th>到达路径</th></tr></thead><tbody>' +
            ENTRY_MAP.map(function (r) { return '<tr><td>' + h(r[0]) + '</td><td>' + h(r[1]) + '</td><td>' + h(r[2]) + '</td></tr>'; }).join("") +
            '</tbody></table><div class="ap-note" style="margin-top:12px">所有入口共用同一份配置（localStorage: xboxre.webhome.theme.v1），不会出现两套互相覆盖的状态。' +
            '内置主题即 <b>柔雾青</b>，由 assets/webhome/theme.json 声明；原生皮肤弹窗的「内置」选项指向同一份文件。</div></div>';
        html += '<div class="ap-group"><div class="ap-group-title">配置</div><div class="ap-row" style="flex-wrap:wrap">' +
            '<button class="ap-btn ap-btn-soft" data-act="native">打开原生设置</button>' +
            '<button class="ap-btn ap-btn-soft" data-act="export">查看 CSS</button>' +
            '<button class="ap-btn ap-btn-ghost" data-act="reset">恢复默认</button>' +
            '</div><pre class="ap-code" data-code style="display:none;margin-top:12px"></pre></div>';
        return html;
    }

    var overlay, toast, toastTimer;

    function ensure() {
        if (overlay) return overlay;
        overlay = document.createElement("div");
        overlay.className = "ap-overlay";
        overlay.setAttribute("role", "dialog");
        overlay.setAttribute("aria-modal", "true");
        overlay.setAttribute("aria-hidden", "true");
        overlay.innerHTML = '<div class="ap-dialog">' +
            '<div class="ap-head"><div><div class="ap-title">外观与主题</div>' +
            '<div class="ap-desc">柔雾青为默认主题。所有改动即时生效并保存在本机。</div></div>' +
            '<button class="ap-close" data-act="close" aria-label="关闭">×</button></div>' +
            '<div class="ap-body">' + build() + '</div>' +
            '<div class="ap-foot"><button class="ap-btn ap-btn-primary" data-act="close">完成</button></div>' +
            '</div>';
        document.body.appendChild(overlay);

        toast = document.createElement("div");
        toast.className = "ap-toast";
        document.body.appendChild(toast);

        overlay.addEventListener("click", onClick);
        overlay.addEventListener("input", onInput);
        document.addEventListener("keydown", function (e) { if (e.key === "Escape") close(); });
        E.on(sync);
        sync();
        return overlay;
    }

    function open() {
        ensure();
        overlay.classList.add("is-open");
        overlay.setAttribute("aria-hidden", "false");
        sync();
    }
    function close() {
        if (!overlay) return;
        overlay.classList.remove("is-open");
        overlay.setAttribute("aria-hidden", "true");
    }
    function notify(msg) {
        ensure();
        toast.textContent = msg;
        toast.classList.add("is-open");
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { toast.classList.remove("is-open"); }, 2400);
    }

    function sync() {
        if (!overlay) return;
        var c = E.get();
        overlay.querySelectorAll("[data-preset]").forEach(function (el) {
            el.classList.toggle("is-active", el.getAttribute("data-preset") === c.theme);
        });
        overlay.querySelectorAll("[data-group]").forEach(function (g) {
            var k = g.getAttribute("data-group");
            g.querySelectorAll("[data-val]").forEach(function (b) {
                b.classList.toggle("is-active", b.getAttribute("data-val") === c[k]);
            });
        });
        overlay.querySelectorAll("[data-seg]").forEach(function (g) {
            var k = g.getAttribute("data-seg");
            g.querySelectorAll("[data-val]").forEach(function (b) {
                b.classList.toggle("is-active", b.getAttribute("data-val") === c[k]);
            });
        });
        RANGES.forEach(function (r) {
            var inp = overlay.querySelector('[data-num="' + r[0] + '"]');
            var out = overlay.querySelector('[data-out="' + r[0] + '"]');
            if (inp) inp.value = c[r[0]];
            if (out) out.textContent = r[5](c[r[0]]);
        });
        var sw = overlay.querySelector('[data-toggle="motion"]');
        if (sw) sw.setAttribute("aria-checked", c.motion ? "true" : "false");
        var colors = c.colors || E.presets[c.theme].colors;
        COLORS.forEach(function (d) {
            var inp = overlay.querySelector('[data-color="' + d[0] + '"]');
            var code = overlay.querySelector('[data-hex="' + d[0] + '"]');
            if (code) code.textContent = colors[d[0]];
            if (inp) {
                if (/^#[0-9a-fA-F]{6}$/i.test(colors[d[0]])) { inp.value = colors[d[0]]; inp.style.opacity = "1"; }
                else { inp.style.opacity = ".4"; inp.title = "半透明色：" + colors[d[0]]; }
            }
        });
    }

    function onClick(e) {
        var preset = e.target.closest("[data-preset]");
        if (preset) { E.usePreset(preset.getAttribute("data-preset")); notify("已切换主题：" + PRESET_META[preset.getAttribute("data-preset")][0]); return; }

        var opt = e.target.closest("[data-val]");
        if (opt) {
            var wrap = opt.closest("[data-group]") || opt.closest("[data-seg]");
            var key = wrap.getAttribute("data-group") || wrap.getAttribute("data-seg");
            var patch = {}; patch[key] = opt.getAttribute("data-val");
            E.set(patch);
            if (key === "modalPos") open();   /* 位置改动后立即用当前位置重开面板，所见即所得 */
            return;
        }

        var toggle = e.target.closest('[data-toggle="motion"]');
        if (toggle) { E.set({ motion: toggle.getAttribute("aria-checked") !== "true" }); return; }

        var act = e.target.closest("[data-act]");
        if (act) {
            var a = act.getAttribute("data-act");
            if (a === "close") { close(); return; }
            if (a === "reset") { E.reset(); notify("已恢复默认主题「柔雾青」"); return; }
            if (a === "native") {
                var ok = E.openNativeSetting();
                notify(ok ? "正在打开原生设置页" : "当前页面无原生桥接，请直接使用本面板调整");
                return;
            }
            if (a === "export") {
                var pre = overlay.querySelector("[data-code]");
                pre.style.display = pre.style.display === "none" ? "block" : "none";
                pre.textContent = E.exportCSS();
                return;
            }
        }
        if (e.target === overlay) close();
    }

    function onInput(e) {
        var num = e.target.closest("[data-num]");
        if (num) {
            var patch = {}; patch[num.getAttribute("data-num")] = Number(num.value);
            E.set(patch);
            return;
        }
        var color = e.target.closest("[data-color]");
        if (color) {
            var cur = E.get();
            var base = cur.colors || E.presets[cur.theme].colors;
            var next = Object.assign({}, base);
            next[color.getAttribute("data-color")] = color.value;
            E.set({ colors: next });
        }
    }

    /* 入口按钮事件委托：任意 [data-theme-entry] 都打开同一个面板 */
    document.addEventListener("click", function (e) {
        var entry = e.target.closest("[data-theme-entry]");
        if (entry) { e.preventDefault(); open(); }
    });

    /* 可选：页面用 ?appearance=1 直接打开（便于从原生设置页深链） */
    function boot() {
        if (/[?&]appearance=1(?:&|$)/.test(global.location.search)) open();
        global.XboxAppearance = { open: open, close: close };
    }
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
    else boot();
})(window);
