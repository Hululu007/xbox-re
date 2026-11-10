package com.fongmi.android.tv.lab;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LabModels {

    public static class LabRoot {
        @SerializedName("version")
        public String version;
        @SerializedName("groups")
        public List<Group> groups;
        @SerializedName("lists")
        public List<Item> lists;
        @SerializedName("root")
        public String root;
    }

    public static class Group {
        @SerializedName("id")
        public String id;
        @SerializedName("name")
        public String name;
        @SerializedName("order")
        public int order;
    }

    public static class Item {
        @SerializedName("name")
        public String name;
        @SerializedName("group")
        public String group;
        @SerializedName("version")
        public String version;
        @SerializedName("min_version")
        public int min_version;
        @SerializedName("icon")
        public String icon;
        @SerializedName("info")
        public String info;
        @SerializedName("auto_install")
        public boolean auto_install;
        @SerializedName("available")
        public boolean available;
        @SerializedName("show")
        public boolean show;
        @SerializedName("binary_path")
        public String binary_path;
        @SerializedName("cmd_name")
        public String cmd_name;
        /** 显式声明这是 Linux rootfs 包（解压到 rootfs/ 而非包根目录）；不填则按包名推断。 */
        @SerializedName("rootfs")
        public Boolean rootfs;
        @SerializedName("mkdir")
        public Object mkdir;
        @SerializedName("var_path")
        public Map<String, String> var_path;
        @SerializedName("downloads")
        public List<Download> downloads;
        @SerializedName("commands")
        public List<Command> commands;
        @SerializedName("settings")
        public List<Setting> settings;

        public boolean hasSettings() {
            return settings != null && !settings.isEmpty();
        }

        public boolean isSupported(int versionCode) {
            return min_version <= 0 || versionCode >= min_version;
        }

        public List<String> mkdirList() {
            List<String> list = new java.util.ArrayList<>();
            if (mkdir == null) return list;
            if (mkdir instanceof String) {
                list.add((String) mkdir);
            } else if (mkdir instanceof List) {
                for (Object value : (List<?>) mkdir) {
                    if (value instanceof String) list.add((String) value);
                }
            }
            return list;
        }
    }

    public static class Setting {
        @SerializedName("key")
        public String key;
        @SerializedName("name")
        public String name;
        @SerializedName("type")
        public String type;
        @SerializedName("default")
        public String defaultValue;
        @SerializedName("hint")
        public String hint;
        @SerializedName("editable")
        public Boolean editable;
        @SerializedName("options")
        public List<Option> options;
    }

    public static class Download {
        @SerializedName("arch")
        public String arch;
        @SerializedName("version")
        public String version;
        @SerializedName("url")
        public String url;
        @SerializedName("liburl")
        public String liburl;
        @SerializedName("size")
        public String size;
        @SerializedName("mirrors")
        public Object mirrors;

        public List<String> getMirrorNames() {
            List<String> names = new java.util.ArrayList<>();
            names.add("默认");
            if (mirrors instanceof Map) {
                names.addAll(((Map<String, String>) mirrors).keySet());
            }
            return names;
        }

        public String getUrlByMirror(String name) {
            if (name == null || "默认".equals(name) || !(mirrors instanceof Map)) return url;
            String mirror = ((Map<String, String>) mirrors).get(name);
            return mirror == null ? url : mirror;
        }
    }

    public static class Command {
        @SerializedName("id")
        public String id;
        @SerializedName("name")
        public String name;
        @SerializedName("description")
        public String description;
        @SerializedName("command")
        public String command;
        @SerializedName("auto_execute")
        public boolean auto_execute;
        @SerializedName("background")
        public boolean background;
        @SerializedName("show_output")
        public Boolean show_output;
        @SerializedName("min_version")
        public int min_version;
        @SerializedName("variables")
        public List<Variable> variables;
        @SerializedName("clicks")
        public List<Click> clicks;
        @SerializedName("download")
        public CommandDownload download;
        @SerializedName("cachedVariableValues")
        public Map<String, String> cachedVariableValues;
        @SerializedName("cachedCommand")
        public String cachedCommand;

        public boolean hasVariables() {
            return variables != null && !variables.isEmpty();
        }

        public List<Variable> getVariables() {
            return variables != null ? variables : Collections.emptyList();
        }

        public List<Click> getClicks() {
            return clicks != null ? clicks : Collections.emptyList();
        }

        public boolean isSupported(int versionCode) {
            return min_version <= 0 || versionCode >= min_version;
        }

        public boolean hasMinVersion() {
            return min_version > 0;
        }

        public boolean isBackground() {
            return background;
        }

        public boolean isShowOutput() {
            return show_output == null || show_output;
        }
    }

    public static class CommandDownload {
        @SerializedName("title")
        public String title;
        @SerializedName("url")
        public String url;
        @SerializedName("size")
        public String size;
        @SerializedName("check_file")
        public String check_file;
        @SerializedName("save_to")
        public String save_to;
        @SerializedName("extract_to")
        public String extract_to;
        @SerializedName("required")
        public boolean required;
        @SerializedName("mirrors")
        public Object mirrors;

        public List<String> getMirrorNames() {
            List<String> names = new java.util.ArrayList<>();
            names.add("默认");
            if (mirrors instanceof Map) {
                names.addAll(((Map<String, String>) mirrors).keySet());
            }
            return names;
        }

        public String getUrlByMirror(String name) {
            if (name == null || "默认".equals(name) || !(mirrors instanceof Map)) return url;
            String mirror = ((Map<String, String>) mirrors).get(name);
            return mirror == null ? url : mirror;
        }
    }

    public static class Variable {
        @SerializedName("key")
        public String key;
        @SerializedName("name")
        public String name;
        @SerializedName("type")
        public String type;
        @SerializedName("default")
        public String defaultValue;
        @SerializedName("hint")
        public String hint;
        @SerializedName("filter")
        public String filter;
        @SerializedName("required")
        public boolean required;
        @SerializedName("min")
        public String min;
        @SerializedName("max")
        public String max;
        @SerializedName("options")
        public List<Option> options;
    }

    public static class Option {
        @SerializedName("label")
        public String label;
        @SerializedName("value")
        public String value;
    }

    public static class Click {
        @SerializedName("title")
        public String title;
        @SerializedName("action")
        public String action;
        @SerializedName("value")
        public String value;
        @SerializedName("style")
        public String style;
        @SerializedName("content")
        public String content;
        @SerializedName("width")
        public int width;
        @SerializedName("height")
        public int height;
    }
}
