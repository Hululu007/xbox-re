package com.fongmi.android.tv.api.hk.state;

import java.util.ArrayList;
import java.util.List;

/** A 一级 json：一个文件 = 一个平行接口；文件名 = 接口名，只由用户添加/改名。 */
public class HkDoc {
    public String name = "";
    public List<String> categories = new ArrayList<>();
    public List<HkFeed> feeds = new ArrayList<>();

    public HkDoc() {}
    public HkDoc(String name) { this.name = name; }
}
