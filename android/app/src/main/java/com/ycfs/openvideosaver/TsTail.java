package com.ycfs.openvideosaver;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.*;
import java.util.Arrays;

/** Drain the AVC access unit held at EOF by Android's TS extractor. */
final class TsTail {
    static File prepare(File source) throws IOException {
        try(RandomAccessFile input=new RandomAccessFile(source,"r")) {
            if(input.length()<376||input.length()%188!=0||input.read()!=0x47)return source;
            input.seek(188);if(input.read()!=0x47)return source;
        }
        MediaExtractor probe=new MediaExtractor();boolean avc=false;
        try{probe.setDataSource(source.getPath());for(int i=0;i<probe.getTrackCount();i++)
            if("video/avc".equals(probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME)))avc=true;
        }finally{probe.release();}
        if(!avc)return source;
        int pid=-1,continuity=0,streamId=0xe0;byte[] packet=new byte[188];
        try(DataInputStream input=new DataInputStream(new FileInputStream(source))) {
            for(long left=source.length();left>0;left-=188) {
                Net.interrupted();input.readFully(packet);if((packet[0]&255)!=0x47)throw new IOException("TS 分段同步字节不正确");
                int currentPid=((packet[1]&31)<<8)|(packet[2]&255),control=(packet[3]>>4)&3;
                int offset=4;if((control&2)!=0)offset+=1+(packet[4]&255);
                if(pid<0&&(control&1)!=0&&(packet[1]&64)!=0&&offset+6<188&&packet[offset]==0&&packet[offset+1]==0&&packet[offset+2]==1&&(packet[offset+3]&240)==224) {
                    pid=currentPid;streamId=packet[offset+3]&255;
                }
                if(currentPid==pid&&(control&1)!=0)continuity=packet[3]&15;
            }
        }
        if(pid<0)throw new IOException("无法识别 TS 视频轨道");
        // Add a bounded PES containing only an access unit delimiter, not a
        // video frame. Its boundary releases the final existing AVC sample.
        Arrays.fill(packet,(byte)255);packet[0]=0x47;packet[1]=(byte)(0x40|(pid>>8));packet[2]=(byte)pid;
        packet[3]=(byte)(0x30|((continuity+1)&15));packet[4]=(byte)169;packet[5]=0;
        byte[] pes={0,0,1,(byte)streamId,0,8,(byte)0x80,0,0,0,0,1,9,(byte)0xf0};
        System.arraycopy(pes,0,packet,174,pes.length);
        File prepared=new File(source.getParentFile(),source.getName()+".drained.ts");
        try(InputStream input=new FileInputStream(source);OutputStream output=new FileOutputStream(prepared)) {
            byte[] buffer=new byte[131072];int n;while((n=input.read(buffer))!=-1){Net.interrupted();output.write(buffer,0,n);}output.write(packet);
        }catch(IOException error){prepared.delete();throw error;}
        return prepared;
    }
}
