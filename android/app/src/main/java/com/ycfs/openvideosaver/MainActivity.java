package com.ycfs.openvideosaver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private final Resolver resolver=new Resolver();
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private EditText input;private TextView status,count,receipt;
    private Button read,save,cancel,browserToggle;private LinearLayout content,browserPanel;
    private FrameLayout browserEngine;
    private AlertDialog completionDialog,repeatDialog;
    private boolean browserExpanded,awaitingPermission,repeatApproved;
    private WebView browser;private ProgressBar progress;
    private Work work;private int selectedFormat;private boolean reading,downloading;
    private File previewDirectory;private Future<?> previewTask;
    private final List<CheckBox> checks=new ArrayList<>();
    private final DownloadService.Observer observer=this::onDownloadState;
    void onDownloadState(DownloadService.State value) {
        downloading=value.busy;if(reading&&!value.busy)return;
        if(!value.message.isEmpty())status.setText(value.message);
        progress.setIndeterminate(value.percent<0);if(value.percent>=0)progress.setProgress(value.percent);
        progress.setVisibility(value.busy?View.VISIBLE:View.GONE);buttons();
        if(value.saved!=null&&value.completion>getPreferences(0).getLong("shownCompletion",0)&&!isFinishing()&&!isDestroyed()) {
            getPreferences(0).edit().putLong("shownCompletion",value.completion).apply();
            receipt.setText("保存成功\n"+value.message);receipt.setVisibility(View.VISIBLE);
            completionDialog=new AlertDialog.Builder(this).setTitle("保存成功").setMessage(value.message)
                    .setPositiveButton("知道了",(dialog,which)->{}).create();completionDialog.show();
        }
    }
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(245,247,250));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(16),insets.getSystemWindowInsetTop()+dp(12),dp(16),insets.getSystemWindowInsetBottom()+dp(12));return insets;});
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        scroll.setFillViewport(true);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        // Keep a real viewport for site scripts and lazy loading while the opaque native screen covers it.
        FrameLayout layers=new FrameLayout(this);browserEngine=new FrameLayout(this);
        layers.addView(browserEngine,new FrameLayout.LayoutParams(-1,-1));layers.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(layers);
        TextView title=text("公开作品保存",26);title.setTextColor(Color.rgb(20,65,110));body.addView(title);
        TextView subtitle=text("抖音 · 哔哩哔哩 · 小红书 · 快手",14);body.addView(subtitle);
        body.addView(text("粘贴分享链接或整段文案，也可以从其他应用分享至这里。",14));
        input=new EditText(this);input.setHint("在这里粘贴作品链接");input.setMinLines(3);input.setMaxLines(6);input.setTextSize(15);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);body.addView(input,new LinearLayout.LayoutParams(-1,-2));
        read=button("读取作品");body.addView(read);read.setOnClickListener(v->load());
        status=text("视频选择画质，图文勾选想保存的图片。",14);status.setTextIsSelectable(true);body.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);body.addView(content);
        save=button("保存到手机");save.setEnabled(false);body.addView(save);save.setOnClickListener(v->save());
        receipt=text("",14);receipt.setTextColor(Color.rgb(20,110,60));receipt.setVisibility(View.GONE);body.addView(receipt);
        cancel=button("取消任务");cancel.setVisibility(View.GONE);body.addView(cancel);cancel.setOnClickListener(v->cancel());
        browserToggle=button("需要登录或验证？查看网页");browserToggle.setVisibility(View.GONE);body.addView(browserToggle);
        browserToggle.setOnClickListener(v->setBrowserExpanded(!browserExpanded));
        browserPanel=new LinearLayout(this);browserPanel.setOrientation(LinearLayout.VERTICAL);browserPanel.setVisibility(View.GONE);
        browserPanel.addView(text("正在读取公开页面。如出现验证，可在下面手动完成。",13));
        browser=new WebView(this);body.addView(browserPanel);setBrowserExpanded(false);
        body.addView(text("视频：Movies/OpenVideoSaver\n图片：Pictures/OpenVideoSaver\n文案与来源：Download/OpenVideoSaver\n预览缩小显示；保存保留下载到的图片文件，不重压缩。",12));
        if(bundle!=null)input.setText(bundle.getString("text",""));else receive(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);receive(intent);}
    private void receive(Intent intent) {
        if(intent!=null&&Intent.ACTION_SEND.equals(intent.getAction())) {
            String shared=intent.getStringExtra(Intent.EXTRA_TEXT);if(shared!=null)input.setText(shared);
        }
    }
    private void load() {
        if(reading||downloading)return;
        reading=true;work=null;selectedFormat=0;content.removeAllViews();checks.clear();MediaFiles.clean(previewDirectory);previewDirectory=null;
        status.setText("正在识别平台并读取公开作品…");progress.setIndeterminate(true);progress.setVisibility(View.VISIBLE);
        receipt.setVisibility(View.GONE);setBrowserExpanded(false);browserToggle.setVisibility(View.GONE);buttons();
        resolver.read(input.getText().toString(),browser,new Resolver.Callback(){
            @Override public void ready(Work result) {
                work=result;setBrowserExpanded(false);browserToggle.setVisibility(View.GONE);
                if(result.isGallery())loadPictures(result);else {
                    reading=false;progress.setVisibility(View.GONE);status.setText(result.platformName()+" · "+result.formats.size()+" 种可用画质");showVideo();buttons();
                }
            }
            @Override public void error(String message){reading=false;progress.setVisibility(View.GONE);status.setText(message);buttons();}
            @Override public void browserOpened(){browserToggle.setVisibility(View.VISIBLE);}
        });
    }
    private void showVideo() {
        content.addView(text(work.caption,16));RadioGroup group=new RadioGroup(this);content.addView(group);
        // Prefer a widely compatible source without changing its resolution or frame rate.
        int preferred=0;
        for(int i=0;i<work.formats.size();i++){Work.Format f=work.formats.get(i);if(f.codec.equals("H.264")){preferred=i;break;}}
        selectedFormat=preferred;
        for(int i=0;i<work.formats.size();i++) {
            RadioButton row=new RadioButton(this);row.setId(View.generateViewId());row.setText(work.formats.get(i).description());row.setTextSize(15);
            group.addView(row);if(i==preferred)group.check(row.getId());final int index=i;
            row.setOnClickListener(v->selectedFormat=index);
        }
    }
    private void loadPictures(Work result) {
        status.setText(result.platformName()+" · 正在读取 "+result.pictures.size()+" 张图片预览及尺寸…");
        previewTask=previews.submit(()->{
            File directory=null;
            try {
                MediaFiles files=new MediaFiles(this);directory=files.cache();List<Bitmap> images=new ArrayList<>();
                for(int i=0;i<result.pictures.size();i++) {
                    Net.interrupted();images.add(files.preview(result.pictures.get(i),result.referer,directory,i+1));
                    final int n=i+1;main.post(()->{if(reading)status.setText("已读取图片 "+n+" / "+result.pictures.size());});
                }
                Net.interrupted();File completed=directory;
                main.post(()->{
                    if(isDestroyed()||!reading||work!=result){MediaFiles.clean(completed);return;}
                    previewDirectory=completed;reading=false;progress.setVisibility(View.GONE);
                    status.setText(result.platformName()+" · 图文作品");showPictures(images);buttons();
                });
            }catch(Exception error) {
                MediaFiles.clean(directory);main.post(()->{
                    if(!reading||isDestroyed()||work!=result)return;reading=false;work=null;progress.setVisibility(View.GONE);
                    status.setText("图片读取失败："+error.getMessage()+"。请重新读取作品。");buttons();
                });
            }
        });
    }
    private void showPictures(List<Bitmap> bitmaps) {
        content.addView(text(work.caption,15));count=text("",15);content.addView(count);
        LinearLayout actions=new LinearLayout(this);Button all=button("全选"),none=button("全不选");
        actions.addView(all,new LinearLayout.LayoutParams(0,-2,1));actions.addView(none,new LinearLayout.LayoutParams(0,-2,1));content.addView(actions);
        all.setOnClickListener(v->{for(CheckBox check:checks)check.setChecked(true);});
        none.setOnClickListener(v->{for(CheckBox check:checks)check.setChecked(false);});
        LinearLayout row=null;
        for(int i=0;i<work.pictures.size();i++) {
            if(i%2==0){row=new LinearLayout(this);content.addView(row);}
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(dp(4),dp(8),dp(4),dp(4));
            row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(bitmaps.get(i));cell.addView(image,new LinearLayout.LayoutParams(-1,dp(165)));
            Work.Picture picture=work.pictures.get(i);CheckBox check=new CheckBox(this);check.setText("第 "+(i+1)+" 张 · "+picture.width+"×"+picture.height);check.setTextSize(12);check.setChecked(true);
            check.setOnCheckedChangeListener((button,checked)->{picture.selected=checked;updateCount();});cell.addView(check);checks.add(check);
            image.setOnClickListener(v->check.setChecked(!check.isChecked()));
        }updateCount();
    }
    private void updateCount(){if(work==null)return;int n=0;for(Work.Picture picture:work.pictures)if(picture.selected)n++;count.setText("已选 "+n+" / "+work.pictures.size()+" 张");}
    private void save() {
        if(reading||downloading||awaitingPermission||work==null||(repeatDialog!=null&&repeatDialog.isShowing()))return;
        if(work.isGallery()){int n=0;for(Work.Picture p:work.pictures)if(p.selected)n++;if(n==0){status.setText("请至少选择一张图片");return;}}
        repeatApproved=false;
        if(SavedWorks.contains(this,work)) {
            repeatDialog=new AlertDialog.Builder(this).setTitle("这条作品已保存过")
                    .setMessage("再次保存会在手机中新增一份文件。确定再次保存当前选择的内容吗？")
                    .setNegativeButton("取消",(dialog,which)->{})
                    .setPositiveButton("仍要保存",(dialog,which)->{repeatApproved=true;requestSave();}).create();repeatDialog.show();return;
        }
        requestSave();
    }
    private void requestSave() {
        if(Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            awaitingPermission=true;buttons();
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},1);return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)) {
            awaitingPermission=true;buttons();getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);return;
        }
        startSaving();
    }
    private void startSaving() {
        Work.Format format=work.isGallery()?null:work.formats.get(selectedFormat);
        if(DownloadService.enqueue(this,work,format,repeatApproved)){downloading=true;receipt.setVisibility(View.GONE);status.setText("准备保存…");progress.setIndeterminate(true);progress.setVisibility(View.VISIBLE);buttons();}
        else status.setText("有任务正在保存，或系统未允许启动下载服务，请稍后重试。");
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        awaitingPermission=false;buttons();
        if(work==null||downloading||reading)return;
        if(code==1&&(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)){status.setText("需要存储权限才能在此系统保存文件");return;}
        if(code==1||code==2)startSaving();
    }
    private void cancel() {
        if(downloading){startService(new Intent(this,DownloadService.class).setAction(DownloadService.CANCEL));return;}
        resolver.cancel();browser.stopLoading();if(previewTask!=null)previewTask.cancel(true);
        reading=false;work=null;setBrowserExpanded(false);browserToggle.setVisibility(View.GONE);progress.setVisibility(View.GONE);status.setText("已取消读取");buttons();
    }
    private void buttons() {
        if(read==null)return;read.setEnabled(!reading&&!downloading&&!awaitingPermission);input.setEnabled(!reading&&!downloading&&!awaitingPermission);
        save.setEnabled(work!=null&&!reading&&!downloading&&!awaitingPermission);cancel.setVisibility(reading||downloading?View.VISIBLE:View.GONE);
        boolean saved=work!=null&&SavedWorks.contains(this,work);
        save.setText(downloading?"保存中，请稍候…":saved?"已保存 · 再次保存":"保存到手机");
        if(saved&&!downloading){receipt.setText("已保存\n"+SavedWorks.summary(this,work));receipt.setVisibility(View.VISIBLE);}
        interactive(content,!downloading);
    }
    void setBrowserExpanded(boolean expanded) {
        browserExpanded=expanded;
        if(browser.getParent()!=null)((ViewGroup)browser.getParent()).removeView(browser);
        browser.setAlpha(expanded?1f:0f);browser.setFocusable(expanded);browser.setFocusableInTouchMode(expanded);
        browser.setImportantForAccessibility(expanded?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(expanded)browserPanel.addView(browser,new LinearLayout.LayoutParams(-1,dp(330)));
        else browserEngine.addView(browser,new FrameLayout.LayoutParams(-1,-1));
        browserPanel.setVisibility(expanded?View.VISIBLE:View.GONE);
        browserToggle.setText(expanded?"隐藏网页":"需要登录或验证？查看网页");
    }
    private void interactive(View view,boolean enabled){if(view instanceof Button||view instanceof ImageView)view.setEnabled(enabled);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)interactive(((ViewGroup)view).getChildAt(i),enabled);}
    private TextView text(String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(Color.rgb(40,50,65));text.setPadding(0,dp(6),0,dp(6));return text;}
    private Button button(String label){Button button=new Button(this);button.setText(label);button.setAllCaps(false);return button;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onStart(){super.onStart();DownloadService.observe(observer);}
    @Override protected void onStop(){DownloadService.unobserve(observer);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("text",input.getText().toString());super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){resolver.destroy();if(previewTask!=null)previewTask.cancel(true);previews.shutdownNow();main.removeCallbacksAndMessages(null);if(completionDialog!=null)completionDialog.dismiss();if(repeatDialog!=null)repeatDialog.dismiss();browser.destroy();super.onDestroy();}
}
