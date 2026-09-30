package com.ycfs.openvideosaver;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LinksParserTest {
    @Test public void kuaishouFirstRangeOnIndependentSegmentsIsPreserved() throws Exception {
        String playlist="#EXTM3U\n#EXT-X-BYTERANGE:100@0\nfirst.ts\nsecond.ts\n#EXT-X-ENDLIST\n";
        assertEquals(2,Hls.segments(playlist,"https://v1.kwaicdn.com/path/video.m3u8",true).size());
        try{Hls.segments(playlist,"https://v1.kwaicdn.com/path/video.m3u8",false);fail();}catch(java.io.IOException expected){}
        try{Hls.segments("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128\nfirst.ts\n#EXT-X-ENDLIST","https://v1.kwaicdn.com/path/video.m3u8",true);fail();}catch(java.io.IOException expected){}
    }
    @Test public void frameRateHandlesRationalSourceValueWithoutInventingMissingRate() {
        assertEquals(29.97,Parsers.frameRate("30000/1001"),0.001);
        assertEquals(60,Parsers.frameRate("60"),0);assertEquals(0,Parsers.frameRate("0/0"),0);assertEquals(0,Parsers.frameRate(""),0);
    }
    @Test public void recognizesShareTextAndRejectsSimilarDomain() {
        assertEquals("https://v.douyin.com/8z4lDiQ1MYY/",Links.extract("0.00 #cs https://v.douyin.com/8z4lDiQ1MYY/ 复制打开"));
        assertEquals("bilibili",Links.platform("https://b23.tv/demo"));
        assertEquals("xiaohongshu",Links.platform("https://www.xiaohongshu.com/discovery/item/demo?xsec_token=example"));
        assertEquals("kuaishou",Links.platform("https://www.kuaishou.com/f/demo"));
        try{Links.platform("https://www.douyin.com.example.org/video/123");fail();}catch(IllegalArgumentException expected){}
        assertFalse(Links.media("https://127.0.0.1/private"));assertFalse(Links.media("https://xhscdn.com.fake.org/test"));
        assertTrue(Links.media("https://sns-webpic-qc.xhscdn.com/test"));
    }
    @Test public void balancedJsonHonorsEscapedQuotesAndBraces() throws Exception {
        String json="{\"caption\":\"a } \\\" { b\",\"data\":{\"count\":3}}";
        assertEquals(json,Parsers.jsonObjectAfter("window.INIT_STATE = "+json+"; trailing{}","window.INIT_STATE"));
        assertEquals("",Parsers.jsonObjectAfter("no state","window.INIT_STATE"));
        assertEquals(3,new JSONObject(json).getJSONObject("data").getInt("count"));
    }
    @Test public void douyinGalleryPreservesOrderAndCaption() throws Exception {
        JSONObject item=new JSONObject("{\"aweme_id\":\"123\",\"desc\":\"测试文案\",\"images\":[{\"url_list\":[\"https://p3.douyinpic.com/one.webp\"]},{\"url_list\":[\"https://p3.douyinpic.com/two.webp\"]}]} ");
        Work work=Parsers.douyin(item,"https://www.douyin.com/note/123");
        assertTrue(work.isGallery());assertEquals(2,work.pictures.size());assertEquals("测试文案",work.caption);
        assertTrue(work.pictures.get(0).url.endsWith("one.webp"));assertTrue(work.formats.isEmpty());
    }
    @Test public void videoUsesSourceDimensionsAndCodec() throws Exception {
        JSONObject item=new JSONObject("{\"video\":{\"bit_rate\":[{\"is_h265\":1,\"FPS\":30,\"play_addr\":{\"url_list\":[\"https://v3.douyinvod.com/a.mp4\"],\"width\":1440,\"height\":1080,\"data_size\":500}}]}}");
        Work work=Parsers.douyin(item,"https://www.douyin.com/video/123");
        Work.Format format=work.formats.get(0);assertEquals(1440,format.width);assertEquals(1080,format.height);assertEquals("H.265",format.codec);assertEquals(30,format.fps,0);
    }
    @Test public void kuaishouAtlasSupportsCdnList() throws Exception {
        Work work=Parsers.kuaishou(new JSONObject("{\"photoData\":{\"atlas\":{\"cdnList\":[{\"cdn\":\"p1.yximgs.com\"}],\"list\":[\"/ufile/atlas/1.jpg\",\"/ufile/atlas/2.jpg\"]},\"photo\":{\"caption\":\"图文\"}}}"),"https://v.kuaishou.com/demo","demo");
        assertEquals(2,work.pictures.size());assertEquals("图文",work.caption);
    }
    @Test public void bilibiliOpusKeepsOriginalPictureAddress() throws Exception {
        Work work=Parsers.opus(new JSONObject("{\"id_str\":\"123\",\"modules\":[{\"module_top\":{\"display\":{\"album\":{\"pics\":[{\"url\":\"https://i0.hdslb.com/bfs/album/original.png\"}]}}}}]}"),"https://www.bilibili.com/opus/123");
        assertEquals(1,work.pictures.size());assertTrue(work.pictures.get(0).url.endsWith("original.png"));
    }
}
