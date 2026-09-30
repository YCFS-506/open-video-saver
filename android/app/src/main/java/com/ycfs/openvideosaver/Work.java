package com.ycfs.openvideosaver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Work {
    String platform, id = "", caption = "", author = "", source, referer;
    final List<Format> formats = new ArrayList<>();
    final List<Picture> pictures = new ArrayList<>();
    Work(String platform, String source) {
        this.platform = platform; this.source = source; this.referer = source;
    }
    boolean isGallery() { return !pictures.isEmpty(); }
    String platformName() {
        switch (platform) {
            case "douyin": return "抖音";
            case "bilibili": return "哔哩哔哩";
            case "xiaohongshu": return "小红书";
            default: return "快手";
        }
    }
    void sort() {
        formats.sort((a, b) -> {
            int area = Long.compare((long)b.width*b.height, (long)a.width*a.height);
            return area != 0 ? area : Double.compare(b.fps, a.fps);
        });
    }
    static final class Format {
        String url, audioUrl = "", codec = "H.264", label = "";
        int width, height;
        double fps;
        long size;
        boolean hls;
        Format(String url) { this.url = url; }
        String description() {
            String dimensions = width > 0 && height > 0 ? width + " × " + height : "分辨率待检测";
            String motion = fps > 0 ? String.format(Locale.ROOT, " · %.0f 帧/秒", fps) : "";
            String kind = codec.equals("H.264") ? "通用格式（H.264）" :
                    codec.equals("H.265") ? "省空间格式（H.265）" : "新格式（AV1）";
            return dimensions + motion + "\n" + kind + (hls ? " · HLS" : "") +
                    (size > 0 ? String.format(Locale.ROOT, " · 约 %.1f MB", size/1048576.0) : "");
        }
    }
    static final class Picture {
        final String url;
        File cached;
        String mime = "";
        int width, height;
        boolean selected = true;
        Picture(String url) { this.url = url; }
    }
}
