package com.fongmi.android.tv.api.hk.state;

import java.util.ArrayList;
import java.util.List;

/** B 二级 json：全局一份，内部按 doc 分段；自动覆盖、不参与备份。 */
public class HkState {
    public long version = 1L;
    public List<HkSiteEntry> sites = new ArrayList<>();
}
