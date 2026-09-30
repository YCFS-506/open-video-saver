package com.ycfs.openvideosaver;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class MediaFiles {
    private final Context context;
    MediaFiles(Context context) { this.context=context.getApplicationContext(); }
    File cache() throws IOException {
        File dir=new File(context.getCacheDir(),"job-"+UUID.randomUUID());
        if(!dir.mkdirs())throw new IOException("无法创建临时目录");return dir;
    }
    Bitmap preview(Work.Picture picture,String referer,File directory,int index) throws IOException {
        picture.cached=new File(directory,String.format(Locale.ROOT,"%03d.source",index));
        picture.mime=Net.download(picture.url,referer,picture.cached,(p,d,t)->{},"读取图片");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(picture.cached.getPath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("第 "+index+" 张不是可识别的图片");
        picture.width=bounds.outWidth;picture.height=bounds.outHeight;picture.mime=bounds.outMimeType;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>600)options.inSampleSize*=2;
        return BitmapFactory.decodeFile(picture.cached.getPath(),options);
    }
    List<Uri> save(Work work,Work.Format format,Net.Progress progress) throws Exception {
        File directory=cache();List<Uri> saved=new ArrayList<>();
        String folder=work.platformName()+"_"+work.id.replaceAll("[^a-zA-Z0-9_-]","")+"_"+
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        try {
            if(work.isGallery()) {
                int count=0;for(Work.Picture p:work.pictures)if(p.selected)count++;
                if(count==0)throw new IOException("请至少选择一张图片");
                int done=0;
                for(int i=0;i<work.pictures.size();i++) {
                    Net.interrupted();Work.Picture picture=work.pictures.get(i);if(!picture.selected)continue;
                    if(picture.cached==null||!picture.cached.isFile())throw new IOException("图片预览未完成，请重新读取");
                    String extension=picture.mime.equals("image/png")?"png":picture.mime.equals("image/webp")?"webp":
                            picture.mime.equals("image/gif")?"gif":picture.mime.equals("image/avif")?"avif":"jpg";
                    saved.add(publish(picture.cached,String.format(Locale.ROOT,"%02d.%s",i+1,extension),picture.mime,"pictures",folder));
                    progress.update("保存图片",++done,count);
                }
            } else {
                if(format==null)throw new IOException("请先选择画质");
                File video=new File(directory,"video.source"),output=new File(directory,"result.mp4");
                if(format.hls)hls(format.url,work.referer,video,progress,0,work.platform.equals("kuaishou"));
                else Net.download(format.url,work.referer,video,progress,"下载视频");
                File audio=null;
                if(!format.audioUrl.isEmpty()) {
                    audio=new File(directory,"audio.source");Net.download(format.audioUrl,work.referer,audio,progress,"下载音频");
                }
                progress.update("整理媒体文件",0,-1);mux(video,audio,output);
                saved.add(publish(output,folder+".mp4","video/mp4","videos","") );
            }
            File caption=new File(directory,"caption.txt"),source=new File(directory,"source.txt");
            try(FileOutputStream stream=new FileOutputStream(caption)){stream.write(work.caption.getBytes(StandardCharsets.UTF_8));}
            try(FileOutputStream stream=new FileOutputStream(source)){stream.write(("平台："+work.platformName()+"\n作者："+work.author+"\n作品："+work.source+"\n").getBytes(StandardCharsets.UTF_8));}
            saved.add(publish(caption,"文案.txt","text/plain","downloads",folder));
            saved.add(publish(source,"来源.txt","text/plain","downloads",folder));
            Net.interrupted();return saved;
        }catch(Exception error) {
            for(Uri uri:saved)try{if("content".equals(uri.getScheme()))context.getContentResolver().delete(uri,null,null);
                else if("file".equals(uri.getScheme()))new File(Objects.requireNonNull(uri.getPath())).delete();}catch(Exception ignored){}
            throw error;
        }finally{clean(directory);}
    }
    private void hls(String address,String referer,File target,Net.Progress progress,int depth,boolean kuaishou) throws Exception {
        if(depth>3||!Links.media(address))throw new IOException("HLS 播放列表不受支持");
        String list=Net.read(address,referer,Net.DESKTOP).text();
        if(!list.startsWith("#EXTM3U"))throw new IOException("不是有效的 HLS 播放列表");
        String[] lines=list.split("\\r?\\n");
        for(int i=0;i<lines.length;i++)if(lines[i].startsWith("#EXT-X-STREAM-INF")&&i+1<lines.length) {
            hls(URI.create(address).resolve(lines[i+1].trim()).toString(),referer,target,progress,depth+1,kuaishou);return;
        }
        List<String> segments=Hls.segments(list,address,kuaishou);
        File part=new File(target.getParentFile(),"segment.tmp");
        try(FileOutputStream stream=new FileOutputStream(target)) {
            for(int i=0;i<segments.size();i++) {
                Net.download(segments.get(i),referer,part,(p,d,t)->{},"读取分段");
                try(InputStream input=new FileInputStream(part)){copy(input,stream);}
                progress.update("下载 HLS 分段",i+1,segments.size());
            }
        }finally{part.delete();}
    }
    static void mux(File video,File audio,File output) throws Exception {
        File prepared=TsTail.prepare(video);
        List<MediaExtractor> extractors=new ArrayList<>();MediaMuxer muxer=null;boolean started=false;
        List<Track> tracks=new ArrayList<>();int videoTracks=0,audioTracks=0;
        try {
            muxer=new MediaMuxer(output.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for(File file:audio==null?new File[]{prepared}:new File[]{prepared,audio}) {
                MediaExtractor extractor=new MediaExtractor();extractors.add(extractor);extractor.setDataSource(file.getPath());
                for(int i=0;i<extractor.getTrackCount();i++) {
                    MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                    boolean isVideo=mime!=null&&mime.startsWith("video/"),isAudio=mime!=null&&mime.startsWith("audio/");
                    if(!isVideo&&!isAudio)continue;
                    if(audio!=null&&((file.equals(prepared)&&!isVideo)||(file.equals(audio)&&!isAudio)))continue;
                    if(isVideo&&videoTracks>0||isAudio&&audioTracks>0)continue;
                    extractor.selectTrack(i);tracks.add(new Track(extractor,i,muxer.addTrack(f),isVideo));
                    if(isVideo) {
                        videoTracks++;if(f.containsKey("rotation-degrees"))muxer.setOrientationHint(f.getInteger("rotation-degrees"));
                    } else audioTracks++;
                }
            }
            if(videoTracks==0||(audio!=null&&audioTracks==0))throw new IOException("系统无法读取此媒体格式，请尝试通用格式（H.264）");
            long origin=Long.MAX_VALUE;for(MediaExtractor extractor:extractors)if(extractor.getSampleTime()>=0)origin=Math.min(origin,extractor.getSampleTime());
            if(origin==Long.MAX_VALUE)origin=0;
            muxer.start();started=true;ByteBuffer buffer=ByteBuffer.allocateDirect(8*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            for(MediaExtractor extractor:extractors) {
                Set<Integer> seen=new HashSet<>();
                while(true) {
                    Net.interrupted();buffer.clear();int size=extractor.readSampleData(buffer,0);if(size<0)break;
                    if(size>buffer.capacity())throw new IOException("媒体分段超过系统缓冲区");
                    int index=extractor.getSampleTrackIndex();Track track=null;
                    for(Track candidate:tracks)if(candidate.extractor==extractor&&candidate.source==index){track=candidate;break;}
                    if(track!=null) {
                        if((extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("不能保存加密媒体");
                        info.set(0,size,Math.max(0,extractor.getSampleTime()-origin),(extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                        muxer.writeSampleData(track.target,buffer,info);seen.add(index);
                    }
                    extractor.advance();
                }
                for(Track track:tracks)if(track.extractor==extractor&&!seen.contains(track.source))throw new IOException("媒体轨道为空，未保存");
            }
            muxer.stop();started=false;
        }finally {
            for(MediaExtractor extractor:extractors)extractor.release();
            if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}
            if(!prepared.equals(video))prepared.delete();
        }
    }
    private static final class Track {
        final MediaExtractor extractor;final int source,target;final boolean video;
        Track(MediaExtractor e,int source,int target,boolean video){extractor=e;this.source=source;this.target=target;this.video=video;}
    }
    private Uri publish(File file,String name,String mime,String type,String folder) throws IOException {
        Net.interrupted();
        String base=type.equals("pictures")?Environment.DIRECTORY_PICTURES:type.equals("videos")?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_DOWNLOADS;
        String relative=base+"/OpenVideoSaver"+(folder.isEmpty()?"":"/"+folder);
        if(Build.VERSION.SDK_INT>=29) {
            Uri collection=type.equals("pictures")?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):
                    type.equals("videos")?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,relative);
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri uri=context.getContentResolver().insert(collection,values);if(uri==null)throw new IOException("无法写入手机媒体库");
            try {
                try(InputStream input=new FileInputStream(file);OutputStream stream=context.getContentResolver().openOutputStream(uri)) {
                    if(stream==null)throw new IOException("无法打开保存位置");copy(input,stream);
                }
                values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);return uri;
            }catch(Exception error){context.getContentResolver().delete(uri,null,null);throw error;}
        }
        File directory=new File(Environment.getExternalStorageDirectory(),relative);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建保存目录，请允许存储权限");
        File dest=new File(directory,name);
        try(InputStream input=new FileInputStream(file);OutputStream stream=new FileOutputStream(dest)){copy(input,stream);}
        catch(Exception error){dest.delete();throw error;}
        MediaScannerConnection.scanFile(context,new String[]{dest.getPath()},new String[]{mime},null);return Uri.fromFile(dest);
    }
    private static void copy(InputStream input,OutputStream output) throws IOException {
        byte[] buffer=new byte[131072];int count;while((count=input.read(buffer))!=-1){Net.interrupted();output.write(buffer,0,count);}
    }
    static void clean(File directory) {
        if(directory==null)return;File[] files=directory.listFiles();if(files!=null)for(File file:files){if(file.isDirectory())clean(file);else file.delete();}directory.delete();
    }
}
