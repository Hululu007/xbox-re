package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

public record ServerEvent(Type type, String text, String name) {

    public static void search(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.SEARCH, text));
    }

    public static void push(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.PUSH, text));
    }

    public static void setting(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.SETTING, text));
    }

    public static void setting(String text, String name) {
        EventBus.getDefault().post(new ServerEvent(Type.SETTING, text, name));
    }

    /** 海阔口令专用推送：name 以 hk: 开头时由 Action 分流，避免误填 UA / 接口框。 */
    public static void hkFeed(String target, String text) {
        EventBus.getDefault().post(new ServerEvent(Type.HK_FEED, text, target));
    }

    private ServerEvent(Type type, String text) {
        this(type, text, "");
    }

    public enum Type {
        SEARCH, PUSH, SETTING, HK_FEED
    }
}
