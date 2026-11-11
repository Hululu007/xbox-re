package com.fongmi.android.tv.hiker;

import android.app.Activity;
import android.content.Intent;

/**
 * 海阔独立入口 —— 唯一对外出口。
 *
 * 设计约束（见《海阔独立入口_兼容性分析与构建前置》）：
 *  1. 首页只调用 {@link #start(Activity)}，不做任何初始化、不持有 WebView；
 *  2. 真正的运行时在 {@link HikerActivity} 的独立 WebView + 独立同步桥，
 *     不复用 HomeWebController / webOverlay / fongmiBridge（首页 SDK 是异步 Promise，
 *     与海阔要求的同步字符串 fetch 冲突，A 组实证 INCOMPATIBLE）；
 *  3. 本类放在 app/src/main，leanback 与 mobile 两个 flavor 共用。
 *
 * 兼容性：新增按钮同时注册进 Func（实际渲染的横排功能按钮）与 HomeButton
 * （设置里可显隐排序的按钮组），两条路径最终都汇聚到这里。
 */
public final class HikerEntry {

    /** 独立容器页，随 APK 打包，不依赖任何远程 URL，也不受首页 URL 白名单限制。 */
    public static final String ASSET_URL = "file:///android_asset/hiker/index.html";

    /** 注入到独立 WebView 的 Java 桥名（同步字符串语义）。 */
    public static final String BRIDGE_NAME = "HikerBridge";

    /** 本地服务端口范围，与 Server.start() 的 9978~9998 一致；运行时逐个探测。 */
    public static final int PORT_MIN = 9978;
    public static final int PORT_MAX = 9998;

    private HikerEntry() {
    }

    /** 首页横按钮点击时调用；不传站点、不传配置、不碰首页状态。 */
    public static void start(Activity activity) {
        if (activity == null) return;
        activity.startActivity(new Intent(activity, HikerActivity.class));
    }
}
