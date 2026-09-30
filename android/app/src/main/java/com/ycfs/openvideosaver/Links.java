package com.ycfs.openvideosaver;

import java.net.URI;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Links {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    static String extract(String text) {
        Matcher match = URL.matcher(text);
        if (!match.find()) throw new IllegalArgumentException("没有找到作品链接");
        String url = match.group().replaceAll("[.,;:!?，。；：！？]+$", "");
        platform(url);
        return url;
    }
    static String platform(String url) {
        String host = URI.create(url).getHost();
        if (host == null) throw new IllegalArgumentException("作品链接格式不正确");
        host = host.toLowerCase(java.util.Locale.ROOT);
        if (Arrays.asList("v.douyin.com", "www.douyin.com", "m.douyin.com", "www.iesdouyin.com").contains(host)) return "douyin";
        if (Arrays.asList("bilibili.com", "www.bilibili.com", "m.bilibili.com", "t.bilibili.com", "b23.tv", "bili2233.cn").contains(host)) return "bilibili";
        if (Arrays.asList("xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com", "www.xhslink.com").contains(host)) return "xiaohongshu";
        if (Arrays.asList("kuaishou.com", "www.kuaishou.com", "v.kuaishou.com").contains(host)) return "kuaishou";
        throw new IllegalArgumentException("支持抖音、哔哩哔哩、小红书和快手作品链接");
    }
    static String id(String url, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }
    static boolean media(String address) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null) return false;
            for (String domain : Arrays.asList("douyinvod.com", "douyinpic.com", "hdslb.com", "bilivideo.com", "bilivideo.cn", "bilivideo.net", "xhscdn.com", "kwaicdn.com", "gifshow.com", "yximgs.com", "ksyun.com")) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
        } catch (IllegalArgumentException ignored) { }
        return false;
    }
    static String secure(String address) {
        if (address.startsWith("//")) return "https:" + address;
        return address.startsWith("http://") ? "https://" + address.substring(7) : address;
    }
}
