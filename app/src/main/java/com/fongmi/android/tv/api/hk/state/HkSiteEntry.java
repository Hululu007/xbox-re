package com.fongmi.android.tv.api.hk.state;

/** B 里的站点索引记录。不存规则本体，只存稳定键、展示名与状态。 */
public class HkSiteEntry {
    public String doc = "";
    public String feedId = "";
    /** 稳定键 = doc|feedId|指纹；作者改 title 仍认得。 */
    public String id = "";
    public String title = "";
    /** |1 只加这里，不写回 A，也不写回规则本体。 */
    public String displayName = "";
    /** 正常 / 拉黑 / 失效。 */
    public String mark = "正常";
    /** auto / user：区分远程消失与用户手动拉黑，两者都要手动恢复。 */
    public String origin = "auto";
    /** 谁先留谁，固定不跳。 */
    public long firstSeen = 0L;
}
