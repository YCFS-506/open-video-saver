package com.ycfs.openvideosaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DeviceSmokeTest {
    @Test public void transportStreamRemuxKeepsAudioAndNormalizesStartTime() throws Exception {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();MediaFiles files=new MediaFiles(instrument.getTargetContext());File cache=files.cache();
        try {
            File source=fixture(instrument,"sample.ts",cache),output=new File(cache,"transport.mp4");
            File drained=TsTail.prepare(source);int preparedFrames=sampleCount(drained,"video/");
            MediaFiles.mux(source,null,output);
            MediaExtractor extractor=new MediaExtractor();
            try {
                extractor.setDataSource(output.getPath());assertEquals(2,extractor.getTrackCount());int video=-1;
                for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/"))video=i;
                assertTrue(video>=0);extractor.selectTrack(video);assertTrue(extractor.getSampleTime()<100000);int count=0;ByteBuffer b=ByteBuffer.allocate(1024*1024);
                while(extractor.readSampleData(b,0)>=0){count++;extractor.advance();b.clear();}assertEquals("native prepared TS frames="+preparedFrames,30,count);
            }finally{extractor.release();}
            assertEquals("preserve generated AAC samples",48,sampleCount(output,"audio/"));
        }finally{MediaFiles.clean(cache);}
    }
    private static int sampleCount(File file,String prefix) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        try{extractor.setDataSource(file.getPath());int index=-1;
            for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix))index=i;
            assertTrue(index>=0);extractor.selectTrack(index);int count=0;ByteBuffer b=ByteBuffer.allocate(1024*1024);
            while(extractor.readSampleData(b,0)>=0){count++;extractor.advance();b.clear();}return count;
        }finally{extractor.release();}
    }
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
    @Test public void browserRemainsLaidOutButHiddenUntilExplicitlyExpanded() {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
        MainActivity activity=(MainActivity)instrument.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrument.waitForIdleSync();instrument.runOnMainSync(()->{
                WebView browser=field(activity,"browser");Button toggle=field(activity,"browserToggle");
                assertEquals(0f,browser.getAlpha(),0f);assertTrue(browser.getWidth()>0);assertTrue(browser.getHeight()>0);
                assertEquals(View.GONE,toggle.getVisibility());assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,browser.getImportantForAccessibility());
                activity.setBrowserExpanded(true);assertEquals(1f,browser.getAlpha(),0f);
                assertSame(field(activity,"browserPanel"),browser.getParent());
                toggle.performClick();assertEquals(0f,browser.getAlpha(),0f);assertSame(field(activity,"browserEngine"),browser.getParent());
            });
        }finally{instrument.runOnMainSync(activity::finish);}
    }
    @Test public void captionCanBeCopiedInFullBeforeMediaIsSaved() {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
        MainActivity activity=(MainActivity)instrument.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        StringBuilder text=new StringBuilder("作品标题\n\n");for(int i=0;i<80;i++)text.append("第 ").append(i).append(" 段：有用的资料🙂 #知识 https://example.org/info\n");String expected=text.toString();
        try {
            instrument.runOnMainSync(()->{
                setField(activity,"reading",true);activity.showCaption(expected);
                Button copy=field(activity,"copyCaption"),save=field(activity,"save");assertTrue(copy.isEnabled());assertFalse(save.isEnabled());copy.performClick();
                ClipboardManager clipboard=activity.getSystemService(ClipboardManager.class);assertNotNull(clipboard.getPrimaryClip());
                assertEquals(expected,clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertEquals("已复制文案",copy.getText().toString());
                activity.showCaption("\n ");assertFalse(copy.isEnabled());copy.performClick();
                assertEquals("empty caption must not replace copied text",expected,clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            });
        }finally{instrument.runOnMainSync(activity::finish);}
    }
    @Test public void hiddenBrowserReadsReadyDataWithoutWaitingForSlowPageImage() throws Exception {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
        MainActivity activity=(MainActivity)instrument.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch imageRelease=new CountDownLatch(1),imageRequested=new CountDownLatch(1),ready=new CountDownLatch(1);
        AtomicReference<Work> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
        AtomicReference<WebView> browser=new AtomicReference<>();Resolver resolver=new Resolver();
        Bitmap png=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);ByteArrayOutputStream bytes=new ByteArrayOutputStream();png.compress(Bitmap.CompressFormat.PNG,100,bytes);png.recycle();byte[] picture=bytes.toByteArray();
        String html="<html><body><div style='height:300px'>已加载的作品</div><script>window.__INITIAL_STATE__={note:{noteDetailMap:{test:{note:{type:'normal',title:'标题',desc:'完整正文',imageList:[{urlDefault:'https://sns-webpic-qc.xhscdn.com/original.jpg'}]}}}}};</script><img width='12' height='12' src='https://www.xiaohongshu.com/fixture-slow.png'></body></html>";
        try {
            instrument.runOnMainSync(()->{
                WebView web=new WebView(activity) {
                    @Override public void loadUrl(String url){loadDataWithBaseURL(url,html,"text/html","UTF-8",null);}
                    @Override public void setWebViewClient(WebViewClient client){super.setWebViewClient(new WebViewClient(){
                        @Override public void onPageStarted(WebView view,String url,Bitmap favicon){client.onPageStarted(view,url,favicon);}
                        @Override public void onPageCommitVisible(WebView view,String url){client.onPageCommitVisible(view,url);}
                        @Override public void onPageFinished(WebView view,String url){client.onPageFinished(view,url);}
                        @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                            if("/fixture-slow.png".equals(request.getUrl().getPath())) {
                                imageRequested.countDown();return new WebResourceResponse("image/png",null,new InputStream(){
                                    final ByteArrayInputStream data=new ByteArrayInputStream(picture);boolean released;
                                    private void awaitImage() throws IOException {if(!released)try{if(!imageRelease.await(30,TimeUnit.SECONDS))throw new IOException("fixture timeout");released=true;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
                                    @Override public int read() throws IOException {awaitImage();return data.read();}
                                    @Override public int read(byte[] b,int offset,int length) throws IOException {awaitImage();return data.read(b,offset,length);}
                                });
                            }
                            return client.shouldInterceptRequest(view,request);
                        }
                    });}
                };
                browser.set(web);web.setAlpha(0f);((ViewGroup)field(activity,"browserEngine")).addView(web,new ViewGroup.LayoutParams(-1,-1));
                resolver.read("https://www.xiaohongshu.com/discovery/item/test",web,new Resolver.Callback(){
                    public void ready(Work work){result.set(work);ready.countDown();}
                    public void error(String message){error.set(message);ready.countDown();}
                });
            });
            assertTrue("controlled page must request its delayed image",imageRequested.await(8,TimeUnit.SECONDS));
            assertTrue("read data before the slow resource finishes",ready.await(8,TimeUnit.SECONDS));assertNull(error.get());assertNotNull(result.get());
            assertEquals("标题\n\n完整正文",result.get().caption);assertEquals(1,result.get().pictures.size());assertEquals("slow resource is still blocked",1,imageRelease.getCount());
        }finally {
            imageRelease.countDown();instrument.runOnMainSync(()->{resolver.destroy();if(browser.get()!=null){((ViewGroup)browser.get().getParent()).removeView(browser.get());browser.get().destroy();}activity.finish();});
        }
    }
    @Test public void successfulSavePromptsOnceAndRequiresConfirmationBeforeRepeating() throws Exception {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
        MainActivity activity=(MainActivity)instrument.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        File cache=new MediaFiles(context).cache();Work work=new Work("douyin","https://www.douyin.com/note/test");
        work.id="receipt-test-"+UUID.randomUUID();work.caption="保存成功提示测试";
        Work.Picture picture=new Work.Picture("https://p3.douyinpic.com/test");picture.mime="image/png";picture.cached=new File(cache,"original.png");work.pictures.add(picture);
        Bitmap image=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);
        try(OutputStream output=new FileOutputStream(picture.cached)){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();
        AtomicReference<DownloadService.State> completed=new AtomicReference<>();CountDownLatch latch=new CountDownLatch(1);
        DownloadService.Observer observer=value->{if(value.saved!=null){completed.set(value);latch.countDown();}};
        try {
            instrument.runOnMainSync(()->{
                setField(activity,"work",work);assertTrue(DownloadService.enqueue(activity,work,null,false));DownloadService.observe(observer);
            });
            assertTrue("actual save must finish",latch.await(20,TimeUnit.SECONDS));instrument.waitForIdleSync();
            assertEquals(3,completed.get().saved.size());assertTrue(SavedWorks.contains(context,work));
            instrument.runOnMainSync(()->{
                AlertDialog dialog=field(activity,"completionDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());
                assertTrue(dialogText(dialog).contains("保存成功"));assertTrue(dialogText(dialog).contains("1 张图片"));
                Button save=field(activity,"save");assertTrue(save.isEnabled());assertEquals("已保存 · 再次保存",save.getText().toString());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            instrument.waitForIdleSync();instrument.runOnMainSync(()->{
                activity.onDownloadState(completed.get());AlertDialog old=field(activity,"completionDialog");assertFalse(old.isShowing());
                Button save=field(activity,"save");save.performClick();AlertDialog repeat=field(activity,"repeatDialog");assertTrue(repeat.isShowing());
                assertTrue(dialogText(repeat).contains("这条作品已保存过"));repeat.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertFalse("backend also rejects an unconfirmed repeat",DownloadService.enqueue(activity,work,null,false));
            });
        }finally {
            DownloadService.unobserve(observer);instrument.runOnMainSync(activity::finish);
            if(completed.get()!=null)for(Uri uri:completed.get().saved)context.getContentResolver().delete(uri,null,null);
            context.getSharedPreferences("saved_works",0).edit().remove(SavedWorks.key(work)).commit();MediaFiles.clean(cache);
        }
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object,String name) {
        try{java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(object);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void setField(Object object,String name,Object value) {
        try{java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static String dialogText(AlertDialog dialog) {
        List<View> views=new ArrayList<>();flatten(dialog.getWindow().getDecorView(),views);StringBuilder text=new StringBuilder();
        for(View view:views)if(view instanceof TextView)text.append(((TextView)view).getText()).append('\n');return text.toString();
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
