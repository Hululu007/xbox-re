package com.fongmi.android.tv.api.hk.state;

import com.google.gson.annotations.SerializedName;

/** B 里的站点索引记录。不存规则本体，只存稳定键、展示名与状态。 */
public class HkSiteEntry {
    @SerializedName("doc") public String doc = "";
    @SerializedName("feedId") public String feedId = "";
    /** 稳定键 = doc|feedId|指纹；作者改 title 仍认得。 */
    @SerializedName("id") public String id = "";
    @SerializedName("title") public String title = "";
    /** |1 只加这里，不写回 A，也不写回规则本体。 */
    @SerializedName("displayName") public String displayName = "";
    /** 正常 / 拉黑 / 失效。 */
    @SerializedName("mark") public String mark = "正常";
    /** auto / user：区分远程消失与用户手动拉黑，两者都要手动恢复。 */
    @SerializedName("origin") public String origin = "auto";
    /** 谁先留谁，固定不跳。 */
    @SerializedName("firstSeen") public long firstSeen = 0L;
}
