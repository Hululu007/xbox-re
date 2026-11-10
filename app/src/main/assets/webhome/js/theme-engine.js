/* =============================================================
   xbox-re · WebHome Theme Engine  v1.0
   -------------------------------------------------------------
   零依赖 / 零构建 / 不改动 DOM 结构 / 不接管事件绑定。
   只做一件事：把一份配置映射成 <html> 上的 data-* 与 CSS 变量。
   因此可以随时卸载、随时回退，对原项目零风险。
   ------------------------------------------------------------- */
(function (global) {
  "use strict";

  var STORAGE_KEY = "xboxre.webhome.theme.v1";

  /* —— 白名单：所有可持久化字段，杜绝脏数据注入 —— */
  var ENUMS = {
    theme: ["aurora", "paper", "ink", "glass"],
    btnShape: ["solid", "pill", "square", "outline", "soft"],
    tileShape: ["soft", "square", "rounded", "circle", "poster", "wide"],
    modalStyle: ["blur", "solid", "outline", "glass"],
    modalPos: ["center", "bottom", "top", "side"],
    density: ["compact", "normal", "relaxed"],
    font: ["system", "geometric", "humanist", "mono"]
  };

  /* 自由变量：[输入键, CSS 变量名, 最小, 最大, 单位/生成方式] */
  var NUMERIC = [
    ["scale", "--scale", 0.85, 1.3, "raw"],
    ["fontSize", "--fs", 12, 18, "px-scaled"],
    ["radius", null, 0, 28, "radius-group"],
    ["duration", "--motion-duration", 0, 400, "ms"],
    ["blur", "--overlay-blur", 0, 24, "px"]
  ];

  var COLORS = [
    ["textPrimary", "--text-primary"],
    ["textSecondary", "--text-secondary"],
    ["accent", "--accent"],
    ["bgBase", "--bg-base"],
    ["bgSurface", "--bg-surface"],
    ["bgSubtle", "--bg-subtle"],
    ["bgElevated", "--bg-elevated"],
    ["border", "--border"]
  ];

  var FONT_STACKS = {
    system: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI Variable", "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif',
    geometric: '"Avenir Next", "Poppins", "Montserrat", "PingFang SC", "Microsoft YaHei", sans-serif',
    humanist: '"Segoe UI", "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif',
    mono: '"SF Mono", "JetBrains Mono", "Cascadia Code", Consolas, "Microsoft YaHei", monospace'
  };

  /* —— 四套预设：只切换配色方案，不覆盖用户的自定义微调 —— */
  var PRESETS = {
    aurora: {
      name: "柔雾青 Aurora", meta: "推荐默认 · 最护眼",
      colors: { textPrimary: "#18231f", textSecondary: "#56675f", accent: "#2e8b68", bgBase: "#f4f6f5", bgSurface: "#ffffff", bgSubtle: "#f0f3f1", bgElevated: "#ffffff", border: "#e2e9e4" }
    },
    paper: {
      name: "纸白 Paper", meta: "严格黑字白底 · 印刷感",
      colors: { textPrimary: "#111111", textSecondary: "#565656", accent: "#1b1b1b", bgBase: "#ffffff", bgSurface: "#ffffff", bgSubtle: "#f5f5f5", bgElevated: "#ffffff", border: "#e6e6e6" }
    },
    ink: {
      name: "夜墨 Ink", meta: "低亮度深色 · 夜间友好",
      colors: { textPrimary: "#e7edeb", textSecondary: "#aab7b3", accent: "#6ccfa4", bgBase: "#14181a", bgSurface: "#1c2225", bgSubtle: "#20282b", bgElevated: "#232b2f", border: "#2c363a" }
    },
    glass: {
      name: "流光玻璃 Glass", meta: "近年流行 · 玻璃拟态",
      colors: { textPrimary: "#f2f3fb", textSecondary: "#c7cbe0", accent: "#a99bff", bgBase: "#10131f", bgSurface: "rgba(255,255,255,0.085)", bgSubtle: "rgba(255,255,255,0.075)", bgElevated: "rgba(28,32,52,0.86)", border: "rgba(255,255,255,0.16)" }
    }
  };

  var DEFAULTS = {
    v: 1,
    theme: "aurora",
    btnShape: "solid",
    tileShape: "soft",
    modalStyle: "blur",
    modalPos: "center",
    density: "normal",
    font: "system",
    motion: true,
    scale: 1,
    fontSize: 14,
    radius: 9,
    duration: 190,
    blur: 12,
    colors: null /* null = 跟随预设；一旦自定义则为用户值 */
  };

  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function clamp(n, lo, hi) { return Math.min(hi, Math.max(lo, n)); }
  function isColor(v) {
    return typeof v === "string" && (
      /^#[0-9a-fA-F]{6}$/.test(v) ||
      /^rgba?\(\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*(,\s*[\d.]+\s*)?\)$/.test(v)
    );
  }

  function defaults() { return clone(DEFAULTS); }

  /* —— 读取：严格校验 + 容错，任何异常都回退到安全默认值 —— */
  function sanitize(raw) {
    var out = defaults();
    if (!raw || typeof raw !== "object" || raw.v !== 1) return out;
    Object.keys(ENUMS).forEach(function (k) {
      if (ENUMS[k].indexOf(raw[k]) !== -1) out[k] = raw[k];
    });
    if (typeof raw.motion === "boolean") out.motion = raw.motion;
    NUMERIC.forEach(function (d) {
      var n = Number(raw[d[0]]);
      if (isFinite(n) && n >= d[2] && n <= d[3]) out[d[0]] = n;
    });
    if (raw.colors && typeof raw.colors === "object") {
      var c = {};
      COLORS.forEach(function (d) { if (isColor(raw.colors[d[0]])) c[d[0]] = raw.colors[d[0]]; });
      if (Object.keys(c).length === COLORS.length) out.colors = c;
    }
    return out;
  }

  function load() {
    try {
      var s = global.localStorage && global.localStorage.getItem(STORAGE_KEY);
      return sanitize(s ? JSON.parse(s) : null);
    } catch (e) { return defaults(); }
  }

  function save(cfg) {
    try { global.localStorage && global.localStorage.setItem(STORAGE_KEY, JSON.stringify(cfg)); } catch (e) { /* 隐私模式下静默失败，不影响使用 */ }
  }

  var state = load();

  /* —— 核心：配置 → DOM —— */
  function apply() {
    var root = document.documentElement;
    var c = state;

    root.setAttribute("data-theme", c.theme);
    root.setAttribute("data-btn-shape", c.btnShape);
    root.setAttribute("data-tile-shape", c.tileShape);
    root.setAttribute("data-modal-style", c.modalStyle);
    root.setAttribute("data-modal-pos", c.modalPos);
    root.setAttribute("data-density", c.density);
    root.setAttribute("data-motion", c.motion ? "on" : "off");

    /* 先清掉引擎自己写过的变量，避免切换预设时残留 */
    ENGINE_VARS.forEach(function (v) { root.style.removeProperty(v); });

    var colors = c.colors || PRESETS[c.theme].colors;
    COLORS.forEach(function (d) { root.style.setProperty(d[1], colors[d[0]]); });
    /* 强调色派生量跟随，避免出现"主色改了但悬停/浅底没变"的割裂感 */
    root.style.setProperty("--accent-soft", tint(colors.accent, colors.bgSurface, 0.88));
    root.style.setProperty("--accent-ring", alpha(colors.accent, 0.28));
    /* 浅色主题 hover 加深，深色主题 hover 提亮，避免深色下越悬停越暗 */
    var isDarkTheme = (c.theme === "ink" || c.theme === "glass");
    root.style.setProperty("--accent-hover", mix(colors.accent, isDarkTheme ? "#ffffff" : "#000000", 0.14));
    root.style.setProperty("--shadow-focus", "0 0 0 3px " + alpha(colors.accent, 0.28));

    root.style.setProperty("--scale", String(c.scale));
    root.style.setProperty("--fs", "calc(" + c.fontSize + "px * var(--scale))");
    root.style.setProperty("--motion-duration", c.duration + "ms");
    root.style.setProperty("--motion-fast", Math.round(c.duration * 0.68) + "ms");
    root.style.setProperty("--overlay-blur", c.blur + "px");
    root.style.setProperty("--font-family", FONT_STACKS[c.font]);

    /* 圆角组：统一基值推导，保持与 --scale 联动 */
    var r = c.radius;
    [["--r-xs", 0.55], ["--r-sm", 1], ["--r-md", 1.33], ["--r-lg", 1.78], ["--r-xl", 2.44]].forEach(function (p) {
      root.style.setProperty(p[0], "calc(" + (r * p[1]).toFixed(2) + "px * var(--scale))");
    });

    document.dispatchEvent(new CustomEvent("theme:change", { detail: clone(c) }));
    updateColorInputs();
    return c;
  }

  var ENGINE_VARS = COLORS.map(function (d) { return d[1]; }).concat([
    "--accent-soft", "--accent-ring", "--accent-hover", "--shadow-focus",
    "--scale", "--fs", "--motion-duration", "--motion-fast", "--overlay-blur", "--font-family",
    "--r-xs", "--r-sm", "--r-md", "--r-lg", "--r-xl"
  ]);

  /* —— 颜色工具：只处理 #RRGGBB，非 hex 输入直接返回安全兜底 —— */
  function hex2rgb(h) {
    var m = /^#([0-9a-f]{6})$/i.exec(h);
    return m ? [parseInt(m[1].slice(0, 2), 16), parseInt(m[1].slice(2, 4), 16), parseInt(m[1].slice(4, 6), 16)] : null;
  }
  function rgb2css(a, alphaVal) {
    return (alphaVal === undefined ? "rgb(" : "rgba(") + a.map(Math.round).join(",") +
      (alphaVal === undefined ? ")" : "," + alphaVal + ")");
  }
  function tint(fg, bg, amount) {
    var a = hex2rgb(fg), b = hex2rgb(bg);
    /* 背景为半透明色（玻璃主题）时无法混色，退回强调色低透明度，保持色相一致 */
    if (a && !b) return rgb2css(a, 0.18);
    if (!a || !b) return "rgba(128,128,128,0.14)";
    return rgb2css(a.map(function (v, i) { return v * (1 - amount) + b[i] * amount; }));
  }
  function mix(fg, bgHex, amount) {
    var a = hex2rgb(fg), b = hex2rgb(bgHex);
    if (!a || !b) return fg;
    return rgb2css(a.map(function (v, i) { return v * (1 - amount) + b[i] * amount; }));
  }
  function alpha(hexOrRgba, a) {
    var v = hex2rgb(hexOrRgba);
    return v ? rgb2css(v, a) : "rgba(128,128,128," + a + ")";
  }

  /* —— 表单回填（演示页用；项目接入时可删） —— */
  function updateColorInputs() {
    if (!global.document) return;
    var colors = state.colors || PRESETS[state.theme].colors;
    COLORS.forEach(function (d) {
      var input = document.querySelector('[data-color="' + d[0] + '"]');
      var hex = document.querySelector('[data-hex="' + d[0] + '"]');
      if (input && /^#[0-9a-fA-F]{6}$/.test(colors[d[0]])) input.value = colors[d[0]];
      if (hex) hex.textContent = colors[d[0]];
    });
  }

  /* —— 公开 API —— */
  var API = {
    presets: PRESETS,
    enums: ENUMS,
    get: function () { return clone(state); },
    /* set 支持局部更新，例如 ThemeEngine.set({ theme: 'ink', scale: 1.1 }) */
    set: function (patch) {
      var next = sanitize(Object.assign({}, state, patch || {}));
      /* colors 允许自定义对象（含 rgba），单独校验 */
      if (patch && patch.colors && typeof patch.colors === "object") {
        var c = {};
        COLORS.forEach(function (d) { if (isColor(patch.colors[d[0]])) c[d[0]] = patch.colors[d[0]]; });
        if (Object.keys(c).length === COLORS.length) next.colors = c;
      }
      state = next;
      save(state);
      return apply();
    },
    /* 切换预设时清空自定义颜色，让预设完整生效 */
    usePreset: function (name) {
      if (!PRESETS[name]) return state;
      var next = sanitize(Object.assign({}, state, { theme: name }));
      next.colors = null;
      state = next;
      save(state);
      return apply();
    },
    reset: function () {
      state = defaults();
      save(state);
      return apply();
    },
    apply: apply,
    /* 导出可直接粘进项目样式表的变量块 */
    exportCSS: function () {
      var root = document.documentElement;
      var lines = ENGINE_VARS.map(function (v) {
        var val = root.style.getPropertyValue(v).trim();
        return val ? "  " + v + ": " + val + ";" : null;
      }).filter(Boolean);
      return ":root {\n" + lines.join("\n") + "\n}";
    },
    exportJSON: function () { return JSON.stringify(state, null, 2); },
    on: function (fn) { document.addEventListener("theme:change", fn); },
    /* 极简浮层控制：仅 classList / aria，不侵入业务逻辑 */
    openModal: function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.classList.add("is-open");
      el.setAttribute("aria-hidden", "false");
    },
    closeModal: function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.classList.remove("is-open");
      el.setAttribute("aria-hidden", "true");
    },
    closeAllModals: function () {
      Array.prototype.forEach.call(document.querySelectorAll(".modal-overlay.is-open"), function (el) {
        el.classList.remove("is-open"); el.setAttribute("aria-hidden", "true");
      });
    },
    /* 打开原生设置页；bridge 或权限不可用时返回 false，调用方降级到内置面板 */
    openNativeSetting: function () {
      try {
        /* 优先走宿主注入的 SDK（HomeWebController 的 window.fongmi） */
        if (global.fongmi && global.fongmi.app && typeof global.fongmi.app.openSetting === "function") {
          global.fongmi.app.openSetting();
          return true;
        }
        var bridge = global.fongmiBridge;
        if (bridge && typeof bridge.invoke === "function") {
          bridge.invoke("theme-" + Date.now(), "app.openSetting", "{}");
          return true;
        }
      } catch (e) { /* 静默降级 */ }
      return false;
    },
    toast: function (msg) {
      var stack = document.getElementById("toastStack");
      if (!stack) return;
      var el = document.createElement("div");
      el.className = "toast";
      el.innerHTML = '<span class="toast-dot"></span><span></span>';
      el.lastChild.textContent = msg;
      stack.appendChild(el);
      setTimeout(function () { el.remove(); }, 2600);
    }
  };

  global.ThemeEngine = API;

  /* —— 启动：DOM 未就绪时等待，保证脚本可放在 <head> —— */
  function boot() {
    var params = new URLSearchParams(global.location.search);
    var t = params.get("theme");
    if (t && PRESETS[t] && !params.get("keep")) {
      /* 效果图模式：按 URL 指定主题，不读本地缓存，保证截图可复现 */
      state = sanitize(Object.assign({}, defaults(), { theme: t }));
    }
    if (params.get("scale")) state.scale = clamp(Number(params.get("scale")) || 1, 0.85, 1.3);
    if (params.get("shape")) state.btnShape = ENUMS.btnShape.indexOf(params.get("shape")) !== -1 ? params.get("shape") : state.btnShape;
    if (params.get("tile")) state.tileShape = ENUMS.tileShape.indexOf(params.get("tile")) !== -1 ? params.get("tile") : state.tileShape;
    if (params.get("pos")) state.modalPos = ENUMS.modalPos.indexOf(params.get("pos")) !== -1 ? params.get("pos") : state.modalPos;
    if (params.get("style")) state.modalStyle = ENUMS.modalStyle.indexOf(params.get("style")) !== -1 ? params.get("style") : state.modalStyle;
    apply();

    if (params.get("shot") === "1") document.body.classList.add("shot-mode");
    if (params.get("view")) document.body.classList.add("view-" + params.get("view"));
    if (params.get("modal")) setTimeout(function () { API.openModal(params.get("modal")); }, 60);

    document.documentElement.setAttribute("data-ready", "1");
    global.__THEME_READY__ = true;
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})(window);
