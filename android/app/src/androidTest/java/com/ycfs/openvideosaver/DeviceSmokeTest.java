package com.ycfs.openvideosaver;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

public class DeviceSmokeTest {
    @Test public void shareTextOpensScreenWithDownloadDisabledBeforeReading() {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
        Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,"复制打开 https://v.douyin.com/8z4lDiQ1MYY/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=instrument.startActivitySync(intent);
        try{instrument.runOnMainSync(()->{
            List<View> views=new ArrayList<>();flatten(activity.getWindow().getDecorView(),views);
            EditText input=null;Button read=null,save=null;
            for(View view:views) {
                if(view instanceof EditText)input=(EditText)view;
                if(view instanceof Button&&((Button)view).getText().toString().equals("读取作品"))read=(Button)view;
                if(view instanceof Button&&((Button)view).getText().toString().equals("保存到手机"))save=(Button)view;
            }
            assertNotNull(input);assertTrue(input.getText().toString().contains("8z4lDiQ1MYY"));assertNotNull(read);assertTrue(read.isEnabled());
            assertNotNull(save);assertFalse(save.isEnabled());
        });}finally{instrument.runOnMainSync(activity::finish);}
    }
    @Test public void selectedPicturesKeepBytesAndSourceNumbersInMediaStore() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();MediaFiles files=new MediaFiles(context);File cache=files.cache();
        List<Uri> result=new ArrayList<>();
        try {
            Work work=new Work("douyin","https://www.douyin.com/note/123");work.id="test123";work.caption="原创测试文案";
            byte[] original=null;
            for(int i=0;i<3;i++) {
                Work.Picture picture=new Work.Picture("https://p3.douyinpic.com/test"+i);picture.mime="image/png";picture.selected=i!=1;
                picture.cached=new File(cache,"source"+i+".png");Bitmap image=Bitmap.createBitmap(16,12,Bitmap.Config.ARGB_8888);image.eraseColor(i==0?Color.RED:Color.BLUE);
                try(OutputStream output=new FileOutputStream(picture.cached)){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();
                if(i==0)original=read(new FileInputStream(picture.cached));work.pictures.add(picture);
            }
            result=files.save(work,null,(p,d,t)->{});assertEquals(4,result.size());
            assertArrayEquals(original,read(context.getContentResolver().openInputStream(result.get(0))));
            String[] expected={"01.png","03.png","文案.txt","来源.txt"};
            for(int i=0;i<expected.length;i++)try(android.database.Cursor cursor=context.getContentResolver().query(result.get(i),new String[]{"_display_name"},null,null,null)) {
                assertNotNull(cursor);assertTrue(cursor.moveToFirst());assertEquals(expected[i],cursor.getString(0));
            }
            assertEquals("原创测试文案",new String(read(context.getContentResolver().openInputStream(result.get(2))),java.nio.charset.StandardCharsets.UTF_8));
        }finally{for(Uri uri:result)context.getContentResolver().delete(uri,null,null);MediaFiles.clean(cache);}
    }
    @Test public void dashMuxPreservesVideoFramesAndBothTracks() throws Exception {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();MediaFiles files=new MediaFiles(context);File cache=files.cache();
        try {
            File video=fixture(instrument,"video.mp4",cache),audio=fixture(instrument,"audio.m4a",cache),output=new File(cache,"muxed.mp4");
            MediaFiles.mux(video,audio,output);MediaExtractor extractor=new MediaExtractor();
            try {
                extractor.setDataSource(output.getPath());assertEquals(2,extractor.getTrackCount());int videoIndex=-1;
                for(int i=0;i<extractor.getTrackCount();i++){MediaFormat format=extractor.getTrackFormat(i);
                    if(format.getString(MediaFormat.KEY_MIME).startsWith("video/")){videoIndex=i;assertEquals(160,format.getInteger(MediaFormat.KEY_WIDTH));assertEquals(90,format.getInteger(MediaFormat.KEY_HEIGHT));}}
                assertTrue(videoIndex>=0);extractor.selectTrack(videoIndex);ByteBuffer buffer=ByteBuffer.allocate(1024*1024);int frames=0;
                while(extractor.readSampleData(buffer,0)>=0){frames++;extractor.advance();buffer.clear();}assertEquals(30,frames);
            }finally{extractor.release();}
        }finally{MediaFiles.clean(cache);}
    }
    private static File fixture(Instrumentation instrument,String name,File directory) throws Exception {
        File file=new File(directory,name);try(InputStream input=instrument.getContext().getAssets().open(name);OutputStream out=new FileOutputStream(file)){out.write(read(input));}return file;
    }
    private static byte[] read(InputStream stream) throws IOException {
        assertNotNull(stream);try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)output.write(b,0,n);return output.toByteArray();}
    }
    private static void flatten(View view,List<View> result){result.add(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)flatten(((ViewGroup)view).getChildAt(i),result);}
}
