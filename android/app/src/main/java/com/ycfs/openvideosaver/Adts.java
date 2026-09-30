package com.ycfs.openvideosaver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Android's TS extractor can return several ADTS frames in one sample. */
final class Adts {
    static final class Frame {
        final int offset,length,rate;
        Frame(int offset,int length,int rate){this.offset=offset;this.length=length;this.rate=rate;}
    }
    static List<Frame> split(ByteBuffer buffer,int size) throws IOException {
        int[] rates={96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
        List<Frame> frames=new ArrayList<>();int position=0;
        while(position<size) {
            if(size-position<7||(buffer.get(position)&255)!=255||(buffer.get(position+1)&246)!=240)throw new IOException("AAC ADTS 帧头不完整");
            int index=(buffer.get(position+2)>>2)&15,header=(buffer.get(position+1)&1)!=0?7:9;
            int length=((buffer.get(position+3)&3)<<11)|((buffer.get(position+4)&255)<<3)|((buffer.get(position+5)&224)>>5);
            if(index>=rates.length||length<=header||length>size-position||(buffer.get(position+6)&3)!=0)throw new IOException("AAC ADTS 帧规格不受支持");
            frames.add(new Frame(position+header,length-header,rates[index]));position+=length;
        }
        if(frames.isEmpty())throw new IOException("AAC 音频数据为空");return frames;
    }
}
