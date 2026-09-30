package com.ycfs.openvideosaver;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DownloadService extends Service {
    static final String CANCEL="com.ycfs.openvideosaver.CANCEL";
    interface Observer { void changed(State state); }
    static final class State {
        final boolean busy;final String message;final int percent;final List<Uri> saved;
        State(boolean busy,String message,int percent,List<Uri> saved){this.busy=busy;this.message=message;this.percent=percent;this.saved=saved;}
    }
    private static final CopyOnWriteArrayList<Observer> observers=new CopyOnWriteArrayList<>();
    private static volatile State state=new State(false,"",-1,null);
    private static Work pending;private static Work.Format choice;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> task;private volatile Thread activeThread;private long lastUpdate;private volatile boolean cancelling;
    static synchronized boolean enqueue(android.content.Context context,Work work,Work.Format format) {
        if(state.busy)return false;
        Work snapshot=new Work(work.platform,work.source);snapshot.id=work.id;snapshot.caption=work.caption;snapshot.author=work.author;snapshot.referer=work.referer;
        for(Work.Picture p:work.pictures){Work.Picture copy=new Work.Picture(p.url);copy.selected=p.selected;copy.cached=p.cached;copy.mime=p.mime;snapshot.pictures.add(copy);}
        pending=snapshot;choice=format;state=new State(true,"准备保存",-1,null);
        try{context.startForegroundService(new Intent(context,DownloadService.class));return true;}
        catch(RuntimeException error){pending=null;state=new State(false,"不能启动下载："+error.getMessage(),-1,null);return false;}
    }
    static void observe(Observer observer){observers.addIfAbsent(observer);observer.changed(state);}
    static void unobserve(Observer observer){observers.remove(observer);}
    @Override public void onCreate() {
        super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads","作品保存进度",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&CANCEL.equals(intent.getAction())){cancelling=true;if(activeThread!=null)activeThread.interrupt();return START_NOT_STICKY;}
        if(task!=null)return START_NOT_STICKY;
        Work work;Work.Format format;
        synchronized(DownloadService.class){work=pending;format=choice;pending=null;choice=null;}
        if(work==null){complete("没有等待保存的作品",null);return START_NOT_STICKY;}
        startForeground(1,notification("正在保存作品",-1,true));
        task=worker.submit(()->{
            activeThread=Thread.currentThread();if(cancelling)activeThread.interrupt();
            try {
                List<Uri> saved=new MediaFiles(this).save(work,format,(phase,done,total)->{
                    long now=SystemClock.elapsedRealtime();if(now-lastUpdate<250&&done!=total)return;lastUpdate=now;
                    int percent=total>0?(int)Math.min(100,done*100/total):-1;
                    main.post(()->{if(!cancelling){publish(new State(true,phase,percent,null));getSystemService(NotificationManager.class).notify(1,notification(phase,percent,true));}});
                });
                main.post(()->complete("保存完成：视频在 Movies，图片在 Pictures，文案在 Download / OpenVideoSaver",saved));
            }catch(Exception error){main.post(()->complete(cancelling?"已取消保存":"保存失败："+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()),null));}
            finally{activeThread=null;}
        });return START_NOT_STICKY;
    }
    private void publish(State value){state=value;for(Observer observer:observers)observer.changed(value);}
    private void complete(String text,List<Uri> saved) {
        publish(new State(false,text,-1,saved));stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).notify(2,notification(text,-1,false));stopSelf();
    }
    private Notification notification(String text,int percent,boolean running) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,"downloads").setSmallIcon(R.drawable.ic_app)
                .setContentTitle("公开作品保存").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(running).setOnlyAlertOnce(true);
        if(running) {
            builder.setProgress(100,Math.max(0,percent),percent<0);
            PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,DownloadService.class).setAction(CANCEL),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null,"取消",cancel).build());
        }return builder.build();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){if(task!=null)task.cancel(true);worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
