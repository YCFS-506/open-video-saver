package com.ycfs.openvideosaver;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class Hls {
    static List<String> segments(String list,String address,boolean kuaishou) throws IOException {
        if(!list.trim().startsWith("#EXTM3U")||!list.contains("#EXT-X-ENDLIST"))throw new IOException("不是完整的点播 HLS 播放列表");
        List<String> output=new ArrayList<>(),media=new ArrayList<>();int ranges=0;boolean firstRange=false;
        for(String raw:list.split("\\r?\\n")) {
            String line=raw.trim();
            if(line.startsWith("#EXT-X-KEY")&&!line.contains("METHOD=NONE"))throw new IOException("不支持加密 HLS");
            if(line.startsWith("#EXT-X-BYTERANGE")){ranges++;if(media.isEmpty())firstRange=true;}
            if(line.startsWith("#EXT-X-DISCONTINUITY"))throw new IOException("暂不支持包含不连续轨道的 HLS");
            if(line.startsWith("#EXT-X-MAP")) {
                String path=Links.id(line,"URI=\"([^\"]+)\"");if(path.isEmpty()||line.contains("BYTERANGE"))throw new IOException("HLS 初始化段不可用");
                output.add(resolve(address,path));
            }else if(!line.isEmpty()&&!line.startsWith("#")){String url=resolve(address,line);output.add(url);media.add(url);}
        }
        // Kuaishou marks only its first independent fragment as a range. Each
        // URL is a separate complete file; preserve the desktop adapter's fix.
        boolean independentFirstRange=kuaishou&&ranges==1&&firstRange&&new HashSet<>(media).size()==media.size();
        if(ranges>0&&!independentFirstRange)throw new IOException("暂不支持使用字节范围的 HLS");
        if(media.isEmpty())throw new IOException("没有可保存的 HLS 分段");return output;
    }
    private static String resolve(String address,String path) throws IOException {
        String url=URI.create(address).resolve(path).toString();if(!Links.media(url))throw new IOException("HLS 返回了不支持的媒体地址");return url;
    }
}
