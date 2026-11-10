package com.fongmi.android.tv.api.hk.state;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** B 二级 json：全局一份，内部按 doc 分段；自动覆盖、不参与备份。 */
public class HkState {
    @SerializedName("version") public long version = 1L;
    @SerializedName("sites") public List<HkSiteEntry> sites = new ArrayList<>();
}
