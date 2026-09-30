package com.ycfs.openvideosaver;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

final class Parsers {
    static double frameRate(String value) {
        try{String[] parts=value.split("/",2);double rate=Double.parseDouble(parts[0]);if(parts.length==2)rate/=Double.parseDouble(parts[1]);return Double.isFinite(rate)&&rate>0?rate:0;}
        catch(NumberFormatException error){return 0;}
    }
    static JSONObject object(JSONObject node,String key) {
        JSONObject value=node.optJSONObject(key);return value==null?new JSONObject():value;
    }
    static JSONArray array(JSONObject node,String key) {
        JSONArray value=node.optJSONArray(key);return value==null?new JSONArray():value;
    }
    static String first(JSONArray array) {
        for(int i=0;i<array.length();i++) {
            String url=Links.secure(array.optString(i)); if(Links.media(url))return url;
        }
        return "";
    }
    static void picture(Work work,String address) {
        String url=Links.secure(address);if(!Links.media(url))return;
        for(Work.Picture existing:work.pictures)if(existing.url.equals(url))return;
        work.pictures.add(new Work.Picture(url));
    }
    static void format(Work work,Work.Format item) {
        if(!Links.media(item.url))return;
        for(Work.Format existing:work.formats)if(existing.url.equals(item.url))return;
        work.formats.add(item);
    }
    static Work douyin(JSONObject item,String source) {
        Work work=new Work("douyin",source);work.id=item.optString("aweme_id");
        work.caption=item.optString("desc");work.author=object(item,"author").optString("nickname");
        work.referer="https://www.douyin.com/video/"+work.id;
        JSONArray images=array(item,"images");
        for(int i=0;i<images.length();i++) {
            JSONObject image=images.optJSONObject(i);if(image==null)continue;
            String url=first(array(image,"url_list"));
            if(url.isEmpty())url=first(array(object(image,"display_image"),"url_list"));
            picture(work,url);
        }
        JSONObject video=object(item,"video");JSONArray rates=array(video,"bit_rate");
        for(int i=0;i<rates.length();i++) {
            JSONObject rate=rates.optJSONObject(i);if(rate==null)continue;
            JSONObject address=object(rate,"play_addr");Work.Format f=new Work.Format(first(array(address,"url_list")));
            f.width=address.optInt("width");f.height=address.optInt("height");f.fps=rate.optDouble("FPS",0);
            f.codec=rate.optInt("is_h265",0)!=0?"H.265":"H.264";f.size=address.optLong("data_size");format(work,f);
        }
        if(work.formats.isEmpty()&&!work.isGallery()) {
            JSONObject address=object(video,"play_addr");Work.Format f=new Work.Format(first(array(address,"url_list")));
            f.width=video.optInt("width");f.height=video.optInt("height");f.size=address.optLong("data_size");format(work,f);
        }
        work.sort();return work;
    }
    static Work douyinDom(JSONObject data,String source,String id) {
        Work work=new Work("douyin",source);work.id=id;work.referer="https://www.douyin.com/note/"+id;
        work.caption=data.optString("caption");JSONArray images=array(data,"images");
        for(int i=0;i<images.length();i++)picture(work,images.optString(i));return work;
    }
    static Work xhs(JSONObject note,String source,String id) {
        Work work=new Work("xiaohongshu",source);work.id=id;work.referer="https://www.xiaohongshu.com/";
        work.caption=note.optString("title")+"\n\n"+note.optString("desc");work.author=object(note,"user").optString("nickname");
        if(!note.optString("type").equals("video")) {
            JSONArray images=array(note,"imageList");
            for(int i=0;i<images.length();i++) {
                JSONObject image=images.optJSONObject(i);if(image!=null)picture(work,image.optString("urlDefault",image.optString("url",image.optString("urlPre"))));
            }
        } else {
            JSONObject video=object(note,"video"),stream=object(object(video,"media"),"stream");
            Iterator<String> keys=stream.keys();
            while(keys.hasNext()) {
                JSONArray variants=array(stream,keys.next());
                for(int i=0;i<variants.length();i++) {
                    JSONObject variant=variants.optJSONObject(i);if(variant==null)continue;
                    Work.Format f=new Work.Format(Links.secure(variant.optString("masterUrl")));
                    if(!Links.media(f.url))f.url=first(array(variant,"backupUrls"));
                    f.width=variant.optInt("width");f.height=variant.optInt("height");f.fps=variant.optDouble("fps",0);f.size=variant.optLong("size");
                    String codec=variant.optString("videoCodec");f.codec=codec.equals("h265")||codec.equals("hevc")?"H.265":codec.equals("av1")?"AV1":"H.264";
                    format(work,f);
                }
            }
            JSONObject v2=video.optJSONObject("mediaV2");
            if(v2==null)try{v2=new JSONObject(video.optString("mediaV2","{}"));}catch(Exception ignored){v2=new JSONObject();}
            String hd=object(object(v2,"video"),"opaque1").optString("hd_screencast_stream");
            if(!hd.isEmpty()){Work.Format f=new Work.Format(Links.secure(hd));f.label="高清投屏流，实际规格待检测";format(work,f);}
        }
        work.sort();return work;
    }
    static Work opus(JSONObject detail,String source) {
        Work work=new Work("bilibili",source);work.id=detail.optString("id_str");work.referer="https://www.bilibili.com/opus/"+work.id;
        StringBuilder text=new StringBuilder();JSONArray modules=array(detail,"modules");
        for(int i=0;i<modules.length();i++) {
            JSONObject module=modules.optJSONObject(i);if(module==null)continue;
            JSONObject top=object(object(object(module,"module_top"),"display"),"album");
            JSONArray pics=array(top,"pics");for(int j=0;j<pics.length();j++)if(pics.optJSONObject(j)!=null)picture(work,pics.optJSONObject(j).optString("url"));
            JSONObject title=object(module,"module_title");if(!title.optString("text").isEmpty())text.append(title.optString("text")).append('\n');
            JSONObject author=object(module,"module_author");if(!author.optString("name").isEmpty())work.author=author.optString("name");
            JSONArray paragraphs=array(object(module,"module_content"),"paragraphs");
            for(int j=0;j<paragraphs.length();j++) {
                JSONObject para=paragraphs.optJSONObject(j);if(para==null)continue;
                JSONArray inline=array(object(para,"pic"),"pics");
                for(int k=0;k<inline.length();k++)if(inline.optJSONObject(k)!=null)picture(work,inline.optJSONObject(k).optString("url"));
                JSONArray nodes=array(object(para,"text"),"nodes");
                for(int k=0;k<nodes.length();k++)if(nodes.optJSONObject(k)!=null)text.append(object(nodes.optJSONObject(k),"word").optString("words"));
                text.append('\n');
            }
        }
        work.caption=text.toString().trim();return work;
    }
    static Work kuaishou(JSONObject state,String source,String id) {
        Work work=new Work("kuaishou",source);work.id=id;work.referer="https://www.kuaishou.com/";
        Iterator<String> keys=state.keys();
        while(keys.hasNext()) {
            JSONObject entry=state.optJSONObject(keys.next());if(entry==null)continue;
            JSONObject atlas=object(entry,"atlas"),photo=object(entry,"photo");
            if(!photo.optString("caption").isEmpty())work.caption=photo.optString("caption");
            work.author=photo.optString("userName",work.author);
            JSONArray cdns=atlas.optJSONArray("cdn");if(cdns==null){cdns=new JSONArray();cdns.put(atlas.optString("cdn"));}
            JSONArray extra=array(atlas,"cdnList");for(int i=0;i<extra.length();i++)if(extra.optJSONObject(i)!=null)cdns.put(extra.optJSONObject(i).optString("cdn"));
            JSONArray paths=array(atlas,"list");
            for(int i=0;i<paths.length();i++)for(int j=0;j<cdns.length();j++) {
                String address="https://"+cdns.optString(j)+paths.optString(i);
                if(Links.media(address)){picture(work,address);break;}
            }
            JSONArray adaptations=array(object(photo,"manifest"),"adaptationSet");
            for(int i=0;i<adaptations.length();i++) {
                JSONObject adaptation=adaptations.optJSONObject(i);if(adaptation==null)continue;
                JSONArray reps=array(adaptation,"representation");
                for(int j=0;j<reps.length();j++) {
                    JSONObject rep=reps.optJSONObject(j);if(rep==null)continue;
                    Work.Format f=new Work.Format(Links.secure(rep.optString("url")));f.width=rep.optInt("width");f.height=rep.optInt("height");
                    f.codec=rep.optString("videoCodec").equals("hevc")?"H.265":"H.264";f.fps=rep.optDouble("frameRate",0);f.hls=f.url.contains(".m3u8");
                    f.size=(long)(rep.optDouble("avgBitrate",0)*photo.optDouble("duration",0)/8);format(work,f);
                }
            }
            for(String field:new String[]{"photoUrl","photoH265Url"}) {
                Work.Format f=new Work.Format(Links.secure(photo.optString(field)));if(field.contains("H265"))f.codec="H.265";format(work,f);
            }
        }
        work.sort();return work;
    }
    static String jsonObjectAfter(String text,String marker) {
        int start=text.indexOf(marker);if(start<0)return "";start=text.indexOf('{',start+marker.length());if(start<0)return "";
        boolean quoted=false,escape=false;int depth=0;
        for(int i=start;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;else if(c=='{')depth++;else if(c=='}'&&--depth==0)return text.substring(start,i+1);
        }
        return "";
    }
}
