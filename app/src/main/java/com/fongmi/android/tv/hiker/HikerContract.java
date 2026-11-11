package com.fongmi.android.tv.hiker;

/** 常量集中放置，便于兼容性核对与混淆保留。 */
public final class HikerContract {

    public static final String UA = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36";

    /** 本地推送播放：Action.onPush(params.get("url")) -> ServerEvent.push -> VideoActivity.push */
    public static final String PUSH_PATH = "/action?do=push&url=";

    /** 端口探测用的轻量端点（返回即可，不关心内容）。 */
    public static final String PING_PATH = "/action?do=search&wd=";

    /** 回传给页面的事件类型。 */
    public static final String EV_RESULT = "hiker.result";
    public static final String EV_PLAY = "hiker.play";
    public static final String EV_LOG = "hiker.log";

    private HikerContract() {
    }
}
