package com.ycfs.openvideosaver;

import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class Resolver {
    interface Callback { void ready(Work work); void error(String message); default void browserOpened(){} }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService qualities=Executors.newFixedThreadPool(2);
    private Future<?> job;
    private volatile boolean done;
    private WebView web;
    private Callback callback;
    private String source,platform,id;
    private int attempts;
    private volatile int generation;
    private int imageCount,stableImages;
    private int browserGeneration;private boolean pollingStarted;
    private String documentAddress="";

    void read(String text,WebView browser,Callback callback) {
        cancel();final int requestGeneration=generation;done=false;attempts=0;imageCount=0;stableImages=0;this.web=browser;this.callback=callback;
        try{source=Links.extract(text);platform=Links.platform(source);}catch(Exception e){fail(e);return;}
        job=worker.submit(()->{
            try {
                if(platform.equals("bilibili")) {
                    String url=source;
                    if(Links.id(url,"(BV[0-9A-Za-z]{10})").isEmpty()&&!url.contains("/opus/")&&!url.contains("t.bilibili.com"))url=Net.shareTarget(url,"https://www.bilibili.com/",Net.DESKTOP);
                    id=Links.id(url,"(BV[0-9A-Za-z]{10})");
                    if(!id.isEmpty()){finish(biliVideo(url),requestGeneration);return;}
                    String opus=Links.id(url,"/(?:opus|dynamic)/(\\d{15,20})");
                    if(opus.isEmpty())opus=Links.id(url,"t\\.bilibili\\.com/(\\d{15,20})");
                    if(opus.isEmpty())throw new IOException("未识别到 BV 号或图文 ID，请保留完整链接");
                    id=opus;openBrowser("https://www.bilibili.com/opus/"+id,requestGeneration);return;
                }
                if(platform.equals("kuaishou")) {
                    Net.Response response=Net.read(source,"https://m.gifshow.com/",Net.MOBILE);
                    id=Links.id(response.url,"/(?:fw/photo|short-video)/([0-9a-z]+)");
                    String json=Parsers.jsonObjectAfter(response.text(),"window.INIT_STATE");
                    if(!json.isEmpty()) {
                        Work work=Parsers.kuaishou(new JSONObject(json),source,id);
                        if(work.isGallery()||!work.formats.isEmpty()){finish(work,requestGeneration);return;}
                    }
                    openBrowser(response.url,requestGeneration);return;
                }
                if(platform.equals("douyin")) {
                    String url=source;id=Links.id(url,"/(?:share/)?(?:video|note|slides)/(\\d{17,19})");
                    if(id.isEmpty()){url=Net.shareTarget(url,"https://www.douyin.com/",Net.DESKTOP);id=Links.id(url,"/(?:share/)?(?:video|note|slides)/(\\d{17,19})");}
                    if(id.isEmpty())throw new IOException("未识别到抖音作品 ID，请检查链接");
                    boolean gallery=url.contains("/note/")||url.contains("/slides/")||url.contains("schema_type=37");
                    openBrowser("https://www.douyin.com/"+(gallery?"note/":"video/")+id,requestGeneration);return;
                }
                id="";openBrowser(source,requestGeneration);
            }catch(Exception error){if(generation==requestGeneration)fail(error);}
        });
    }
    private JSONObject api(String path) throws Exception {
        JSONObject response=new JSONObject(Net.read("https://api.bilibili.com"+path,"https://www.bilibili.com/",Net.DESKTOP).text());
        if(response.optInt("code",-1)!=0)throw new IOException("哔哩哔哩接口暂不可用："+response.optString("message"));
        return Parsers.object(response,"data");
    }
    private Work biliVideo(String url) throws Exception {
        Work work=new Work("bilibili",source);work.id=id;work.referer="https://www.bilibili.com/video/"+id+"/";
        JSONObject view=api("/x/web-interface/view?bvid="+id);work.caption=Parsers.caption(view.optString("title"),view.optString("desc"));work.author=Parsers.object(view,"owner").optString("name");
        JSONArray pages=Parsers.array(view,"pages");String p=Links.id(url,"[?&]p=(\\d+)");int page=p.isEmpty()?1:Integer.parseInt(p);
        if(page<1||page>Math.max(1,pages.length()))throw new IOException("分 P 不存在");
        long cid=pages.length()>0?pages.getJSONObject(page-1).getLong("cid"):view.getLong("cid");
        String query="?bvid="+id+"&cid="+cid+"&qn=127&fourk=1";
        Future<JSONObject> dashReply=qualities.submit(()->api("/x/player/playurl"+query+"&fnval=4048&try_look=1"));
        Future<JSONObject> singleReply=qualities.submit(()->api("/x/player/playurl"+query+"&fnval=0"));
        try {
        JSONObject dash=Parsers.object(dashReply.get(),"dash");
        String audio="";long highest=0;
        JSONArray audios=Parsers.array(dash,"audio");
        for(int i=0;i<audios.length();i++) {
            JSONObject item=audios.getJSONObject(i);long rate=item.optLong("bandwidth");
            if(item.optString("codecs").startsWith("mp4a")&&rate>highest&&Links.media(biliAddress(item))){audio=biliAddress(item);highest=rate;}
        }
        JSONArray videos=Parsers.array(dash,"video");
        if(!audio.isEmpty())for(int i=0;i<videos.length();i++) {
            JSONObject item=videos.getJSONObject(i);Work.Format f=new Work.Format(biliAddress(item));f.audioUrl=audio;
            f.width=item.optInt("width");f.height=item.optInt("height");f.fps=Parsers.frameRate(item.optString("frame_rate"));
            String codec=item.optString("codecs");f.codec=codec.startsWith("hev")||codec.startsWith("hvc")?"H.265":codec.startsWith("av01")?"AV1":"H.264";
            f.size=(item.optLong("bandwidth")+highest)*view.optLong("duration")/8;Parsers.format(work,f);
        }
        try {
            JSONObject single=singleReply.get();JSONArray parts=Parsers.array(single,"durl");
            if(parts.length()==1&&single.optString("format").contains("mp4")) {
                JSONObject item=parts.getJSONObject(0);Work.Format f=new Work.Format(biliAddress(item));f.size=item.optLong("size");Parsers.format(work,f);
            }
        }catch(Exception ignored){ }
        work.sort();return work;
        }finally{dashReply.cancel(true);singleReply.cancel(true);}
    }
    private String biliAddress(JSONObject item) {
        return Links.secure(item.optString("baseUrl",item.optString("base_url",item.optString("url"))));
    }
    private void openBrowser(String address,int requestGeneration) {
        main.post(()->{
            if(done||generation!=requestGeneration)return;
            final int pageGeneration=++browserGeneration;pollingStarted=false;attempts=0;imageCount=0;stableImages=0;documentAddress=address;
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setMediaPlaybackRequiresUserGesture(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setUserAgentString(platform.equals("kuaishou")?Net.MOBILE:Net.DESKTOP);
            web.setWebViewClient(new WebViewClient(){
                @Override public void onPageStarted(WebView view,String url,Bitmap favicon){
                    if(done||generation!=requestGeneration||browserGeneration!=pageGeneration)return;
                    documentAddress=url;beginPolling(requestGeneration,pageGeneration);
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                    if(done||generation!=requestGeneration)return null;
                    String uri=request.getUrl().toString();
                    if(platform.equals("douyin")&&uri.contains("/aweme/v1/web/aweme/detail/")&&uri.contains("aweme_id="+id)&&"www.douyin.com".equals(request.getUrl().getHost())) {
                        try {
                            HashMap<String,String> headers=new HashMap<>(request.getRequestHeaders());
                            String cookies=CookieManager.getInstance().getCookie(uri);if(cookies!=null)headers.put("Cookie",cookies);
                            Net.Response data=Net.read(uri,address,Net.DESKTOP,headers);
                            JSONObject item=Parsers.object(new JSONObject(data.text()),"aweme_detail");
                            if(item.optString("aweme_id").equals(id)) {
                                Work work=Parsers.douyin(item,source);if(work.isGallery()||!work.formats.isEmpty())finish(work,requestGeneration);
                            }
                            return new WebResourceResponse("application/json","UTF-8",new ByteArrayInputStream(data.data));
                        }catch(Exception ignored){ }
                    }
                    return null;
                }
                @Override public void onPageCommitVisible(WebView view,String url){pageReady(url,requestGeneration,pageGeneration);}
                @Override public void onPageFinished(WebView view,String url){pageReady(url,requestGeneration,pageGeneration);}
            });
            callback.browserOpened();
            Runnable navigate=()->{
                if(done||generation!=requestGeneration||browserGeneration!=pageGeneration)return;
                web.loadUrl(address);beginPolling(requestGeneration,pageGeneration);
            };
            if(web.getUrl()==null)navigate.run();
            else web.evaluateJavascript("window.__ovsPreviousDocument=true",ignored->navigate.run());
            if(platform.equals("douyin")&&!address.contains("/note/"))main.postDelayed(()->{
                if(!done&&generation==requestGeneration){attempts=0;openBrowser("https://www.douyin.com/note/"+id,requestGeneration);}
            },20000);
            main.postDelayed(()->{if(!done&&generation==requestGeneration)fail(new IOException("读取超时；请检查公开页面是否要求登录或验证，再重试"));},60000);
        });
    }
    private void pageReady(String url,int requestGeneration,int pageGeneration) {
        if(done||generation!=requestGeneration||browserGeneration!=pageGeneration)return;
        documentAddress=url;beginPolling(requestGeneration,pageGeneration);
    }
    private void beginPolling(int requestGeneration,int pageGeneration) {
        if(done||generation!=requestGeneration||browserGeneration!=pageGeneration||pollingStarted)return;
        pollingStarted=true;poll(requestGeneration,pageGeneration);
    }
    private void poll(int requestGeneration,int pageGeneration) {
        if(done||generation!=requestGeneration||browserGeneration!=pageGeneration)return;
        // Navigation may start while the old DOM is still alive. Only read the current document.
        String script="(function(){try{if(window.__ovsPreviousDocument||location.href!==new URL("+JSONObject.quote(documentAddress)+").href)return null;var s=window.__INITIAL_STATE__,p="+JSONObject.quote(platform)+";"+
            "if(p==='xiaohongshu'&&s&&s.note){var m=s.note.noteDetailMap||{};for(var k in m)if(m[k].note)return JSON.stringify({kind:'xhs',id:k,data:m[k].note});}"+
            "if(p==='bilibili'&&s&&s.detail)return JSON.stringify({kind:'opus',data:s.detail});"+
            "if(p==='kuaishou'&&window.INIT_STATE)return JSON.stringify({kind:'ks',data:window.INIT_STATE});"+
            "if(p==='douyin'){var a=Array.from(document.querySelectorAll('img[src*=\"tplv-dy-aweme-images\"]')).map(x=>x.currentSrc||x.src);"+
            "var st=Array.from(document.querySelectorAll('span')).find(x=>x.textContent.startsWith('发布时间：'));var r=st&&st.parentElement&&st.parentElement.parentElement;"+
            "var meta=document.querySelector('meta[name=description]');var caption=r&&r.firstElementChild&&r.firstElementChild.innerText||meta&&meta.content||'';"+
            "if(a.length)return JSON.stringify({kind:'images',data:{images:a,caption:caption}});"+
            "var v=document.querySelector('video');if(v&&v.videoWidth)return JSON.stringify({kind:'fallback',data:{url:v.currentSrc||v.src,width:v.videoWidth,height:v.videoHeight,caption:caption}});}"+
            "}catch(e){}return null;})()";
        web.evaluateJavascript(script,value->{
            if(done||generation!=requestGeneration||browserGeneration!=pageGeneration)return;
            try {
                Object decoded=new JSONTokener(value).nextValue();
                if(decoded instanceof String) {
                    JSONObject payload=new JSONObject((String)decoded),data=Parsers.object(payload,"data");Work work=null;
                    switch(payload.optString("kind")) {
                        case "xhs":work=Parsers.xhs(data,source,payload.optString("id"));break;
                        case "opus":work=Parsers.opus(data,source);break;
                        case "ks":work=Parsers.kuaishou(data,source,id);break;
                        case "images":
                            work=Parsers.douyinDom(data,source,id);
                            if(work.pictures.size()==imageCount)stableImages++;else{imageCount=work.pictures.size();stableImages=0;}
                            if(stableImages<4)work=null;break;
                        case "fallback":
                            if(Links.media(data.optString("url"))&&attempts>5){work=new Work("douyin",source);work.id=id;work.caption=data.optString("caption");work.referer=web.getUrl();Work.Format f=new Work.Format(data.optString("url"));f.width=data.optInt("width");f.height=data.optInt("height");Parsers.format(work,f);}break;
                    }
                    if(work!=null&&(work.isGallery()||!work.formats.isEmpty())){finish(work,requestGeneration);return;}
                }
            }catch(Exception ignored){ }
            if(++attempts<100)main.postDelayed(()->poll(requestGeneration,pageGeneration),500);
        });
    }
    private void finish(Work work,int requestGeneration) {
        if(generation!=requestGeneration)return;
        if(!work.isGallery()&&work.formats.isEmpty()){fail(new IOException("没有取得公开图片或视频流"));return;}
        main.post(()->{if(done||generation!=requestGeneration)return;done=true;main.removeCallbacksAndMessages(null);web.stopLoading();callback.ready(work);});
    }
    private void fail(Exception error) {
        main.post(()->{if(done)return;done=true;main.removeCallbacksAndMessages(null);if(web!=null)web.stopLoading();callback.error(error.getMessage()==null?"读取失败，请重试":error.getMessage());});
    }
    void cancel() {done=true;generation++;if(job!=null)job.cancel(true);main.removeCallbacksAndMessages(null);}
    void destroy() {cancel();worker.shutdownNow();qualities.shutdownNow();}
}
