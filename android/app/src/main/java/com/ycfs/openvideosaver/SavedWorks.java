package com.ycfs.openvideosaver;

import android.content.Context;

/** App-private receipts are written only after every requested file is saved. */
final class SavedWorks {
    static String key(Work work) {
        return work.platform+":"+(work.id.isEmpty()?work.source:work.id);
    }
    static boolean contains(Context context,Work work) {
        return context.getSharedPreferences("saved_works",0).contains(key(work));
    }
    static String summary(Context context,Work work) {
        return context.getSharedPreferences("saved_works",0).getString(key(work),"");
    }
    static String describe(Work work) {
        if(!work.isGallery())return "已保存 1 个视频到 Movies/OpenVideoSaver。\n文案与来源已保存到 Download/OpenVideoSaver。";
        int count=0;for(Work.Picture picture:work.pictures)if(picture.selected)count++;
        return "已保存 "+count+" 张图片到 Pictures/OpenVideoSaver，可在系统图库查看。\n文案与来源已保存到 Download/OpenVideoSaver。";
    }
    static void record(Context context,Work work,String summary) {
        context.getSharedPreferences("saved_works",0).edit().putString(key(work),summary).apply();
    }
}
