package com.fongmi.android.tv.api.hk.state;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** A 一级 json：一个文件 = 一个平行接口；文件名 = 接口名，只由用户添加/改名。
 *  字段必须 @SerializedName 固定：R8 会把 public 字段混淆成 a/b/c，
 *  Gson 反射按运行时字段名读写，混淆后 json 变成 {"a":..,"b":[],"c":[..]}
 *  且泛型签名丢失会让 c 的元素被读成 LinkedTreeMap（ClassCastException）。 */
public class HkDoc {
    @SerializedName("name") public String name = "";
    @SerializedName("categories") public List<String> categories = new ArrayList<>();
    @SerializedName("feeds") public List<HkFeed> feeds = new ArrayList<>();

    public HkDoc() {}
    public HkDoc(String name) { this.name = name; }
}
