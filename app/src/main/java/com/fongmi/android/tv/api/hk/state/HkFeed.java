package com.fongmi.android.tv.api.hk.state;

/** A 里的口令记录。raw 是原串（口令集=多行文本），二次点击弹框读写它，不脱敏。 */
public class HkFeed {
    public String feedId = "";
    public String raw = "";
    public String category = "";
    /** 正常 / 拉黑 / 失效；口令失效自动回，拉黑手动回，删除=物理删。 */
    public String mark = "正常";

    public HkFeed() {}
    public HkFeed(String feedId, String raw, String category) {
        this.feedId = feedId; this.raw = raw; this.category = category;
    }
}
