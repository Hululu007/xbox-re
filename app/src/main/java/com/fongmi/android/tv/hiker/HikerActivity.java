package com.fongmi.android.tv.hiker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 海阔独立运行时容器 —— 与首页完全隔离。
 *
 * 「不要与首页混用」的落地：
 *   - 独立 Activity，不复用 HomeActivity.webOverlay；
 *   - 独立 WebView 实例，不复用 HomeWebController / CustomWebView；
 *   - 独立 Java 桥 HikerBridge（同步字符串），不注入首页 fongmiBridge / window.fm SDK；
 *   - 独立 assets 容器页 file:///android_asset/hiker/index.html，不受 WebHomeTarget 同源白名单限制；
 *   - 独立生命周期，onDestroy 里 destroy WebView，不干扰首页 prewarm/fmpause 事件。
 *
 * 兼容性：不引用 R、不引用 BaseActivity / App / SpiderDebug 等工程内部类，
 * 因此可用 ecj + android-all.jar 真实编译（见 build_verify）。这也是"多变兼容"的一部分：
 * 换 flavor、换主题、换皮肤都不影响本 Activity 编译。
 */
public class HikerActivity extends Activity {

    private WebView webView;
    private HikerBridge bridge;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F1420);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(HikerContract.UA);
        s.setMediaPlaybackRequiresUserGesture(false);

        // 关键：只注入同步桥，绝不注入首页 getSdk() 的异步 window.fm / fongmiBridge。
        bridge = new HikerBridge(this);
        webView.addJavascriptInterface(bridge, HikerEntry.BRIDGE_NAME);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // hiker:// 由页面内 HikerCompat 路由表处理，不交给系统浏览器、不进首页白名单
                if (url != null && url.startsWith("hiker://")) return true;
                if (url != null && url.startsWith("http")) return false;
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.loadUrl(HikerEntry.ASSET_URL);
    }

    /** 桥事件回传（结果、播放、日志）；当前仅做日志，后续可接宿主 EventBus / 播放器。 */
    public void onBridgeEvent(String type, String payload) {
        // 刻意不调用首页任何类；保留扩展点，避免与首页生命周期耦合。
        android.util.Log.d("HikerBridge", type + ": "
                + (payload == null ? "" : payload.substring(0, Math.min(160, payload.length()))));
    }

    @Override
    public void onBackPressed() {
        // 页面栈由 HikerCompat 的 hiker:// 路由表管理；无栈时直接退出独立 Activity
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
