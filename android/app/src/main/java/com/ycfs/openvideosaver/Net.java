package com.ycfs.openvideosaver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Net {
    static final String DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
    static final String MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 Version/16.6 Mobile/15E148 Safari/604.1";
    interface Progress { void update(String phase, long done, long total); }
    static final class Response {
        final byte[] data;
        final String url, mime;
        Response(byte[] data, String url, String mime) { this.data=data; this.url=url; this.mime=mime; }
        String text() { return new String(data, StandardCharsets.UTF_8); }
    }
    static HttpURLConnection open(String url, String referer, String agent) throws IOException {
        URL target = new URL(url);
        if (!target.getProtocol().equals("https")) throw new IOException("仅访问 HTTPS 地址");
        HttpURLConnection connection = (HttpURLConnection)target.openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(35000);
        connection.setRequestProperty("User-Agent", agent);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
        return connection;
    }
    static Response read(String url, String referer, String agent) throws IOException {
        return read(url, referer, agent, null);
    }
    static Response read(String url, String referer, String agent, Map<String,String> headers) throws IOException {
        HttpURLConnection connection = open(url, referer, agent);
        try {
            if (headers != null) for (Map.Entry<String,String> entry:headers.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("Accept-Encoding")) connection.setRequestProperty(entry.getKey(),entry.getValue());
            }
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("网页请求返回 HTTP " + code);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[32768]; int count;
                while ((count=input.read(buffer))!=-1) {
                    interrupted(); output.write(buffer,0,count);
                    if (output.size()>20*1024*1024) throw new IOException("页面数据过大");
                }
            }
            return new Response(output.toByteArray(),connection.getURL().toString(),connection.getContentType());
        } finally { connection.disconnect(); }
    }
    static String download(String address, String referer, File target, Progress progress, String phase) throws IOException {
        if (!Links.media(address)) throw new IOException("返回的媒体地址不属于支持的平台");
        HttpURLConnection connection=open(address,referer,DESKTOP);
        try {
            int code=connection.getResponseCode();
            if (code!=200) throw new IOException("媒体请求返回 HTTP "+code);
            String mime=connection.getContentType(); long total=connection.getContentLengthLong(),written=0;
            try (InputStream input=connection.getInputStream(); FileOutputStream output=new FileOutputStream(target)) {
                byte[] buffer=new byte[131072]; int count;
                while ((count=input.read(buffer))!=-1) {
                    interrupted();output.write(buffer,0,count);written+=count;progress.update(phase,written,total);
                }
            }
            if (written<100 || (total>0 && written!=total)) throw new IOException("媒体文件未完整下载");
            return mime==null?"":mime.split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        } finally { connection.disconnect(); }
    }
    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
    }
}
