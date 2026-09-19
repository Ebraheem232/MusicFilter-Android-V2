package com.musicfilter.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.*;
import java.util.Locale;

/** Focused offline workspace. Processing remains owned by MediaProcessingService. */
public final class MainActivity extends Activity {
    private Uri selectedVideo, cleanedVideo, playing;
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout root, body;
    private TextView progressText;
    private ProgressBar progress;
    private String screen = "", lastState = "";
    private boolean ar, dark, fresh, fullscreen, preparing;
    private volatile String modelPreparationError;
    private Runnable pendingModelAction;
    private Button originalButton, removedButton;
    private int bg, surface, ink, muted, accent;
    private Uri exportSource;
    private String exportMime = "video/mp4";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { refresh(); }
    };
    private SharedPreferences prefs() { return getSharedPreferences("interface", MODE_PRIVATE); }
    private String t(String en, String arabic) { return ar ? arabic : en; }
    @Override protected void attachBaseContext(Context base) {
        String language=base.getSharedPreferences("interface",MODE_PRIVATE).getString("language","en");
        Configuration config=new Configuration(base.getResources().getConfiguration());
        Locale locale=new Locale(language);config.setLocale(locale);config.setLayoutDirection(locale);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); }
    @Override protected void onCreate(Bundle state) {
        setTheme(getSharedPreferences("interface", MODE_PRIVATE).getString("theme", "system").equals("dark") || (getSharedPreferences("interface", MODE_PRIVATE).getString("theme", "system").equals("system") && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (!ProcessingState.isRunning(this)) { File p = new File(getCacheDir(), "processing"); if (p.exists()) { try { clearInside(p, p.getCanonicalPath()); } catch (Exception error) { android.util.Log.w("MusicFilterCache", "Startup cleanup failed", error); } } }
        if (!"en".equals(prefs().getString("language", "en"))) prefs().edit().putString("language", "en").apply();
        ar = false;
        String theme = prefs().getString("theme", "system");
        dark = theme.equals("dark") || (theme.equals("system") && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        bg = Color.parseColor(dark ? "#101C1B" : "#F6F7F2");
        surface = Color.parseColor(dark ? "#1B2B29" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#E5EEEA" : "#182D29");
        muted = Color.parseColor(dark ? "#AFBFB9" : "#596C65");
        accent = Color.parseColor(dark ? "#86D9BC" : "#127C6F");
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ProcessingState.Snapshot snapshot = ProcessingState.read(this);
        String source = state == null ? prefs().getString("selected", snapshot.getString("source", null)) : state.getString("selected");
        if (source != null) selectedVideo = Uri.parse(source);
        fresh = state != null ? state.getBoolean("fresh") : prefs().getBoolean("fresh", false);
        if(state!=null && state.getString("export")!=null) exportSource=Uri.parse(state.getString("export"));
        player = new ExoPlayer.Builder(this).build();
        player.setPlaybackSpeed(prefs().getFloat("speed", 1f));
        player.addListener(new Player.Listener() { public void onPlayerError(PlaybackException error) { problem(t("This preview cannot be played. Try another video or open the saved file in another player.", "This preview cannot be played. Try another video or open the saved file in another player."), error); } });
        if(state!=null && state.getString("playing")!=null){showMedia(Uri.parse(state.getString("playing")),false);player.seekTo(state.getLong("position",0));}restoreNotification(getIntent()); home();
        if (false) {
            android.util.Log.i("Music FilterBench", "");
            try {
                Intent job = new Intent(this, MediaProcessingService.class)
                    .setData(selectedVideo)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                job.setClipData(android.content.ClipData.newRawUri("Video", selectedVideo));
                startForegroundService(job);
            } catch (Exception e) {
                android.util.Log.e("Music FilterBench", "Failed to auto-start", e);
            }
        }
        if(prefs().getBoolean("return_settings",false)){prefs().edit().remove("return_settings").apply();settings();}
        ensureModel(false, null);
        if (!prefs().getBoolean("welcomed", false)) new AlertDialog.Builder(this).setTitle(t("Keep the voice.", "Keep the voice."))
            .setMessage(t("Remove background music from imported videos on your phone. Your imported videos stay on this device. Processing can take time, depending on your phone and video.", "Remove background music from imported videos on your phone. Your imported videos stay on this device. Processing can take time, depending on your phone and video."))
            .setPositiveButton(t("Get started", "Get started"), (d,w) -> prefs().edit().putBoolean("welcomed", true).apply()).setCancelable(false).show();
    }
    private void restoreNotification(Intent intent) {
        if(intent.getAction()!=null && intent.getAction().startsWith("com.musicfilter.app.SHOW")) {
            fresh=false; prefs().edit().putBoolean("fresh",false).apply();
            String source=ProcessingState.read(this).getString("source",null);if(source!=null)selectedVideo=Uri.parse(source);
            if(intent.getAction().endsWith("CANCEL"))handler.postDelayed(()->{if(ProcessingState.isRunning(this))cancel();},400);
        }
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);restoreNotification(intent);home();}
    @Override protected void onSaveInstanceState(Bundle b) { if (selectedVideo != null) b.putString("selected", selectedVideo.toString()); b.putBoolean("fresh", fresh); if(exportSource!=null)b.putString("export",exportSource.toString());if(playing!=null)b.putString("playing",playing.toString());b.putLong("position",player.getCurrentPosition()); super.onSaveInstanceState(b); }
    @Override protected void onResume() { super.onResume(); androidx.core.content.ContextCompat.registerReceiver(this, receiver, new IntentFilter(MediaProcessingService.ACTION_STATUS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); refresh(); }
    @Override protected void onPause() { unregisterReceiver(receiver); player.pause(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); player.release(); super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String s, int size, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(4),0,dp(4));v.setLineSpacing(dp(2),1.08f); return v; }
    private void label(LinearLayout parent, String s, int size, int color) { parent.addView(text(s,size,color)); }
    private android.graphics.drawable.Drawable touchBackground(int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dark?0x2286D9BC:0x22127C6F),shape(color,radius),null);
    }
    private Button button(String title, boolean primary, Runnable click) {
        Button v=new Button(this);v.setText(title);v.setAllCaps(false);v.setTextSize(15);v.setTypeface(null,Typeface.NORMAL);
        v.setMinHeight(dp(48));v.setMinimumHeight(dp(48));v.setPadding(dp(16),dp(8),dp(16),dp(8));v.setStateListAnimator(null);v.setElevation(0);
        v.setTextColor(primary?(dark?bg:Color.WHITE):accent);v.setBackground(touchBackground(primary?accent:(dark?0xFF203532:0xFFE9EFEB),16));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));v.setLayoutParams(p);v.setOnClickListener(x->click.run());return v;
    }
    private ImageButton icon(int resource,String description,Runnable action,int tint) {
        ImageButton v=new ImageButton(this);v.setImageResource(resource);v.setColorFilter(tint);v.setContentDescription(description);v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setBackground(touchBackground(Color.TRANSPARENT,24));v.setOnClickListener(x->action.run());return v;
    }
    private void gap(int height) {body.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}

    private LinearLayout card() { LinearLayout v = new LinearLayout(this); v.setOrientation(1); v.setPadding(dp(20),dp(16),dp(20),dp(16)); v.setBackground(shape(surface,20)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(12),0,dp(12)); v.setLayoutParams(p); body.addView(v); return v; }
    private void page(String title,String name) {
        screen=name;if(playerView!=null)playerView.setPlayer(null);
        root=new LinearLayout(this);root.setPadding(dp(16),0,dp(16),0);root.setOrientation(1);root.setBackgroundColor(bg);root.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(16),i.getSystemWindowInsetTop(),dp(16),i.getSystemWindowInsetBottom());return i;});
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setMinimumHeight(dp(64));
        if(!name.equals("home"))bar.addView(icon(R.drawable.ui_back,t("Back", "Back"),this::navigateBack,ink),new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView heading=text(title,name.equals("home")?28:26,ink);heading.setTypeface(null,Typeface.BOLD);heading.setMaxLines(1);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.setPaddingRelative(dp(8),0,dp(8),0);heading.setTextDirection(View.TEXT_DIRECTION_LOCALE);heading.setGravity((ar?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
        bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(name.equals("home"))bar.addView(icon(R.drawable.ui_settings,t("Settings", "Settings"),this::settings,ink),new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(bar);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(8),dp(8),dp(8),dp(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void navigateBack(){if(screen.equals("why")||screen.equals("privacy")||screen.equals("licences"))about();else if(screen.equals("about")||screen.equals("model"))settings();else home();}

    private void home() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        page("Music Filter","home"); progress = null; progressText = null;

        ProcessingState.Snapshot s = ProcessingState.read(this);
        boolean running = ProcessingState.isRunning(this);
        lastState = s.getString("status", "");
        String output = s.getString("output",null);
        cleanedVideo = !fresh && output != null && !output.equals("null") ? Uri.parse(output) : null;
        if (running) {
            String source = s.getString("source",null); if(source != null) selectedVideo = Uri.parse(source);
            LinearLayout c = card(); label(c,t("Removing music", "Removing music"),24,ink); label(c,fileName(selectedVideo),14,muted);
            progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent)); c.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
            progressText = text("",16,ink);progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); c.addView(progressText);
            label(c,t("You can leave this screen. Android battery restrictions may slow background processing.", "You can leave this screen. Android battery restrictions may slow background processing."),14,muted);
            c.addView(button(t("Cancel", "Cancel"),true,this::cancel)); updateProgress(s); return;
        } else if (lastState.equals("RUNNING") && !running) {
            String source = s.getString("source",null); if(source != null) selectedVideo = Uri.parse(source);
            LinearLayout c = card(); label(c,t("Processing interrupted", "Processing interrupted"),24,ink); label(c,fileName(selectedVideo),14,muted);
            label(c,t("The previous processing job stopped before it could finish.", "The previous processing job stopped before it could finish."),16,dark?0xFFFFB4AB:0xFFB3261E);
            c.addView(button(t("Try again", "Try again"),true,this::processVideo)); return;
        }
        if (selectedVideo == null) {
            label(body,t("Keep the voice. Remove the music.", "Keep the voice. Remove the music."),24,ink);gap(16);LinearLayout c=card();
            label(c,t("Choose a video to remove its background music while keeping speech and vocals.", "Choose a video to remove its background music while keeping speech and vocals."),16,muted);
            c.addView(button(t("Select Video", "Select Video"),true,this::chooseVideo));
            label(body,t("Private • Offline processing", "Private • Offline processing"),14,muted); return;
        }
        playerView = new PlayerView(this);
        FrameLayout preview = mediaPanel(playerView, this::fullScreen, false);
        body.addView(preview, new LinearLayout.LayoutParams(-1, dp(220)));
        gap(12);
        label(body,fileName(selectedVideo),14,muted);
        gap(8);
        if (playing == null || (!playing.equals(selectedVideo) && (cleanedVideo == null || !playing.equals(cleanedVideo)))) showMedia(selectedVideo, false);
        if (cleanedVideo != null) {
            LinearLayout compare = new LinearLayout(this);compare.setPadding(dp(3),dp(3),dp(3),dp(3));compare.setBackground(shape(dark?0xFF203532:0xFFE3EBE6,16));
            originalButton=button(t("Original", "Original"),false,()->{showMedia(selectedVideo,true);markComparison();});compare.addView(originalButton,new LinearLayout.LayoutParams(0,dp(48),1));
            removedButton=button(t("Music removed", "Music removed"),false,()->{showMedia(cleanedVideo,true);markComparison();});
            originalButton.setSingleLine(true); removedButton.setSingleLine(true); originalButton.setMinWidth(0); removedButton.setMinWidth(0);
            originalButton.setPadding(dp(8),0,dp(8),0); removedButton.setPadding(dp(8),0,dp(8),0);
            compare.addView(removedButton,new LinearLayout.LayoutParams(0,dp(48),1)); body.addView(compare);markComparison();
            gap(12);label(body,t("Preview ready", "Preview ready"),14,accent);gap(8);
            body.addView(button(t("Save", "Save"),true,()->saveToGallery(cleanedVideo)));
            body.addView(button(t("Share", "Share"),false,()->share(cleanedVideo,"video/mp4")));
        } else {
            if (lastState.equals("FAILED") || lastState.equals("CANCELLED") || lastState.equals("INTERRUPTED")) {
                label(body, t("Processing could not finish. ", "Processing could not finish. ") + s.getString("message", ""), 14, dark ? 0xFFFFB4AB : 0xFFB3261E);
                body.addView(button(t("Try again", "Try again"), true, this::processVideo));
            } else {
                body.addView(button(t("Remove Music", "Remove Music"),true,this::processVideo));
            }
            label(body,t("The entire video is processed locally. Longer videos can take several minutes or more.", "The entire video is processed locally. Longer videos can take several minutes or more."),13,muted);
        }
        Button another=button(cleanedVideo != null ? t("Process another video", "Process another video") : t("Choose another video", "Choose another video"),false,this::chooseVideo);another.setBackground(touchBackground(Color.TRANSPARENT,16));body.addView(another);
    }
    private void markComparison() {
        if(originalButton==null||removedButton==null)return;
        boolean original=selectedVideo!=null && selectedVideo.equals(playing);
        originalButton.setText(t("Original","Original"));removedButton.setText(t("Music removed","Music removed"));
        originalButton.setSelected(original);removedButton.setSelected(!original);
        originalButton.setBackground(touchBackground(original?surface:Color.TRANSPARENT,12));removedButton.setBackground(touchBackground(original?Color.TRANSPARENT:surface,12));
        originalButton.setTextColor(original?ink:muted);removedButton.setTextColor(original?muted:ink);
    }
    private void showMedia(Uri uri, boolean retain) { if (uri == null || uri.equals(playing)) return; long position = retain ? player.getCurrentPosition() : 0; boolean play = retain && player.getPlayWhenReady(); playing = uri; player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.seekTo(position); player.setPlayWhenReady(play); }
    private void refresh() { if (!screen.equals("home") || fullscreen) return; ProcessingState.Snapshot s = ProcessingState.read(this); boolean running = ProcessingState.isRunning(this); if (!lastState.equals(s.getString("status","")) || running != (progress != null)) { if (!running) fresh = false; home(); } else if(running) updateProgress(s); }
    private void updateProgress(ProcessingState.Snapshot s) { int p = s.getInt("percent",0); progress.setProgress(p); progressText.setText(p+"%  "+(ar ? arabicStage(s.getString("message","")) : s.getString("message","Working"))); if(prefs().getBoolean("awake",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    static String arabicStage(String message) {
        return message;
    }
    private void chooseVideo() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),1); }
    private void processVideo() {
        if(selectedVideo == null || ProcessingState.isRunning(this)) return;
        player.pause(); 
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=0) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4); 
        fresh=false; prefs().edit().putBoolean("fresh",false).apply(); 
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); 
        ensureModel(true, this::startProcessing);
    }
    private void startProcessing() {
        Uri source=selectedVideo;
        try { Intent job = new Intent(this,MediaProcessingService.class).setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); job.setClipData(ClipData.newRawUri("Video",source)); startForegroundService(job); handler.postDelayed(this::home,300); }
        catch(Exception error) { problem(t("Could not start processing. Please keep this screen open and try again.", "Could not start processing. Please keep this screen open and try again."),error); }
    }
    private void ensureModel(boolean userInitiated, Runnable afterReady) {
        if (OfflineModel.isReady(this)) { modelPreparationError=null; if(afterReady!=null)afterReady.run(); return; }
        if (afterReady != null) pendingModelAction = afterReady;
        if (preparing) { if(userInitiated)Toast.makeText(this,t("Preparing offline AI model…", "Preparing offline AI model…"),Toast.LENGTH_SHORT).show(); return; }
        preparing=true; modelPreparationError=null;
        if(userInitiated)Toast.makeText(this,t("Preparing offline AI model…", "Preparing offline AI model…"),Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try {
                OfflineModel.prepare(this, true);
                runOnUiThread(()->{ preparing=false; modelPreparationError=null; Runnable action=pendingModelAction; pendingModelAction=null; refreshModelScreen(); if(action!=null)action.run(); });
            } catch(Exception error) {
                android.util.Log.e("MusicFilterModel", "Bundled model preparation failed", error);
                runOnUiThread(()->{ preparing=false; modelPreparationError=error.getMessage(); pendingModelAction=null; refreshModelScreen(); if(userInitiated||afterReady!=null)problem(t("The offline AI model could not be prepared. Tap to retry.", "The offline AI model could not be prepared. Tap to retry."),error); });
            }
        },"Model-prepare").start();
    }
    private void refreshModelScreen() {
        if("model".equals(screen))modelPage(); else if("settings".equals(screen))settings();
    }
    private void cancel() {
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(t("Stop processing?", "Stop processing?")).setMessage(t("Current processing progress will be lost.", "Current processing progress will be lost."))
            .setNegativeButton(t("Keep processing", "Keep processing"),null)
            .setPositiveButton(t("Stop", "Stop"),(d,w)->{root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);startService(new Intent(this,MediaProcessingService.class).setAction(MediaProcessingService.ACTION_CANCEL));}).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(dark?0xFFFFB4AB:0xFFB3261E));dialog.show();
    }
    private String fileName(Uri uri) { if(uri==null)return ""; try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){} return t("Selected video", "Selected video"); }
    private void speed() {
        String[] values={"0.5×","0.75×","1×","1.25×","1.5×","2×"};float[] rates={.5f,.75f,1,1.25f,1.5f,2};int selected=2;
        for(int i=0;i<rates.length;i++)if(Math.abs(player.getPlaybackParameters().speed-rates[i])<.01)selected=i;
        choices(t("Playback speed", "Playback speed"),values,selected,i->{player.setPlaybackSpeed(rates[i]);prefs().edit().putFloat("speed",rates[i]).apply();if(screen.equals("settings"))settings();});
    }
    private FrameLayout mediaPanel(PlayerView view,Runnable expand,boolean full) {
        FrameLayout frame=new FrameLayout(this);frame.setBackground(shape(Color.BLACK,16));frame.setClipToOutline(true);view.setPlayer(player);view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);view.setControllerShowTimeoutMs(3000);frame.addView(view,new FrameLayout.LayoutParams(-1,-1));
        View gear=view.findViewById(androidx.media3.ui.R.id.exo_settings);if(gear!=null)gear.setVisibility(View.GONE);
        view.setShowNextButton(false);view.setShowPreviousButton(false);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);tools.setBackground(shape(0xBB101C1B,12));tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button rate=button(speedLabel(),false,()->{speed();});rate.setTextColor(Color.WHITE);rate.setBackgroundColor(Color.TRANSPARENT);tools.addView(rate,new LinearLayout.LayoutParams(dp(64),dp(48)));
        tools.addView(icon(R.drawable.ui_volume,t("Volume", "Volume"),this::volumeSheet,Color.WHITE),new LinearLayout.LayoutParams(dp(48),dp(48)));
        tools.addView(icon(full?R.drawable.ui_close:R.drawable.ui_fullscreen,full?t("Exit full screen", "Exit full screen"):t("Full screen", "Full screen"),expand,Color.WHITE),new LinearLayout.LayoutParams(dp(48),dp(48)));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);p.setMargins(dp(8),dp(8),dp(8),0);frame.addView(tools,p);
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{tools.setVisibility(visibility);rate.setText(speedLabel());});return frame;
    }
    private String speedLabel(){return String.format(Locale.US,"%s×",new java.text.DecimalFormat("0.##",java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(player.getPlaybackParameters().speed));}
    private void fullScreen() {
        fullscreen=true;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);playerView.setPlayer(null);
        Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);PlayerView full=new PlayerView(this);dialog.setContentView(mediaPanel(full,dialog::dismiss,true));
        dialog.setOnDismissListener(d->{full.setPlayer(null);playerView.setPlayer(player);fullscreen=false;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);refresh();});dialog.show();dialog.getWindow().setLayout(-1,-1);dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }
    private Dialog bottomSheet(String title,LinearLayout content) {
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(dp(24),dp(12),dp(24),dp(24));panel.setBackground(shape(surface,24));panel.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        View handle=new View(this);handle.setBackground(shape(muted,3));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(12);panel.addView(handle,hp);TextView heading=text(title,22,ink);heading.setTypeface(null,Typeface.BOLD);panel.addView(heading);panel.addView(content);
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);dialog.setContentView(scroll);Window w=dialog.getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.4f);w.setGravity(Gravity.BOTTOM);dialog.show();w.setLayout(-1,-2);w.setNavigationBarColor(surface);panel.setTranslationY(dp(32));panel.setAlpha(0);panel.animate().translationY(0).alpha(1).setDuration(180).start();return dialog;
    }
    private void choices(String title,String[] values,int selected,java.util.function.IntConsumer action) {
        LinearLayout items=new LinearLayout(this);items.setOrientation(1);final Dialog[] sheet=new Dialog[1];
        for(int i=0;i<values.length;i++){final int index=i;RadioButton row=new RadioButton(this);row.setText(values[i]);row.setTextSize(16);row.setTextColor(ink);row.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);row.setMinHeight(dp(52));row.setPaddingRelative(dp(8),0,dp(8),0);row.setChecked(i==selected);row.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));row.setBackground(touchBackground(i==selected?(dark?0xFF203532:0xFFE9EFEB):Color.TRANSPARENT,12));row.setOnClickListener(v->{sheet[0].dismiss();action.accept(index);});items.addView(row,new LinearLayout.LayoutParams(-1,dp(56)));}
        sheet[0]=bottomSheet(title,items);
    }
    private void volumeSheet(){LinearLayout c=new LinearLayout(this);c.setOrientation(1);SeekBar v=new SeekBar(this);v.setMinHeight(dp(48));v.setMax(100);v.setProgress(Math.round(player.getVolume()*100));v.setContentDescription(t("Preview volume", "Preview volume"));v.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){if(u)player.setVolume(p/100f);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});c.addView(v);bottomSheet(t("Volume", "Volume"),c);}

    private void export(Uri source,String mime,int code,String suffix) { exportSource=source;exportMime=mime;String name=fileName(selectedVideo).replaceFirst("\\.[^.]+$","");startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name+suffix),code); }

    private void saveToGallery(Uri source) {
        new Thread(()->{
            try {
                String originalName = fileName(selectedVideo);
                int dot = originalName.lastIndexOf('.');
                if (dot > 0) originalName = originalName.substring(0, dot);
                String finalName = "MusicFilter_" + originalName + ".mp4";
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, finalName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/MusicFilter");
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new java.io.IOException("Could not create the saved result.");
                try (java.io.InputStream in = getContentResolver().openInputStream(source);
                     java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                }
                values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                runOnUiThread(()->new android.app.AlertDialog.Builder(MainActivity.this).setTitle(t("Saved", "Saved")).setMessage(t("Video saved to Gallery", "Video saved to Gallery")).setPositiveButton(t("Open", "Open"),(d,w)->{
                    try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception ignored){}
                }).show());
            } catch (Exception e) {
                runOnUiThread(()->problem(t("Could not save the video.", "Could not save the video."), e));
            }
        }).start();
    }
    private void share(Uri uri,String mime) { try{ Uri contentUri = uri; if (uri.getScheme() != null && uri.getScheme().equals("file")) { java.io.File file = new java.io.File(uri.getPath()); contentUri = androidx.core.content.FileProvider.getUriForFile(this, "com.musicfilter.app.v2.provider", file); } Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,contentUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(android.content.ClipData.newRawUri("Media",contentUri));startActivity(Intent.createChooser(send,t("Share", "Share")));}catch(Exception e){problem(t("No app is available to share this file.", "No app is available to share this file."),e);} }
    @Override protected void onActivityResult(int request,int result,Intent data) { 
        super.onActivityResult(request,result,data); 
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData(); 
        if(request==1){
            deleteTemporaryResult(cleanedVideo);
            selectedVideo=uri;fresh=true;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            prefs().edit().putString("selected",uri.toString()).putBoolean("fresh",true).apply();home();
        }else if(request==2||request==3||request==5){ 
            final Uri source=exportSource; 
            new Thread(()->{
                try{
                    if(request==3) throw new UnsupportedOperationException("");
                    else try(InputStream in=request==5?new android.util.AtomicFile(new File(getFilesDir(),"diagnostics/latest-performance.json")).openRead():getContentResolver().openInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                    }
                    runOnUiThread(()->new AlertDialog.Builder(this).setTitle(t("Saved", "Saved")).setMessage(fileName(uri)).setPositiveButton(t("Open", "Open"),(d,w)->{
                        try{if(request==3){previewAudio(uri);return;}startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,request==3?"audio/mp4":request==5?"application/json":"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
                        catch(Exception e){problem(t("No compatible player is installed.", "No compatible player is installed."),e);}
                    }).setNeutralButton(t("Share", "Share"),(d,w)->share(uri,request==3?"audio/mp4":request==5?"application/json":"video/mp4")).setNegativeButton(t("Done", "Done"),null).show());
                }catch(Exception e){
                    runOnUiThread(()->problem(t("Could not save. Check the destination and available storage.", "Could not save. Check the destination and available storage."),e));
                }
            },"Music Filter-export").start();
        } 
    }
    private void deleteTemporaryResult(Uri uri) {
        if(uri==null || !"file".equals(uri.getScheme()))return;
        try {
            File file=new File(uri.getPath());File root=new File(getCacheDir(),"processing");
            String filePath=file.getCanonicalPath(),rootPath=root.getCanonicalPath();
            if(filePath.startsWith(rootPath+File.separator)){File parent=file.getParentFile();if(file.exists()&&!file.delete())android.util.Log.w("MusicFilterCache","Could not delete old result");if(parent!=null)parent.delete();}
        } catch(Exception error){android.util.Log.w("MusicFilterCache","Could not clean old result",error);}
    }
    private void previewAudio(Uri uri) {
        player.pause();ExoPlayer audio=new ExoPlayer.Builder(this).build();PlayerView view=new PlayerView(this);view.setPlayer(audio);view.setControllerShowTimeoutMs(0);view.setBackgroundColor(Color.BLACK);view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);FrameLayout audioFrame=new FrameLayout(this);audioFrame.addView(view,new FrameLayout.LayoutParams(-1,dp(240)));audio.setMediaItem(MediaItem.fromUri(uri));audio.prepare();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(t("Processed audio", "Processed audio")).setView(audioFrame).setPositiveButton(t("Done", "Done"),null).setNeutralButton(t("Share", "Share"),(d,w)->share(uri,"audio/mp4")).create();dialog.setOnDismissListener(d->{view.setPlayer(null);audio.release();});dialog.show();
    }
    private void problem(String message,Exception error) { android.util.Log.e("MusicFilterUI",message,error);if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle(t("Something needs attention", "Something needs attention")).setMessage(message).setPositiveButton(t("OK", "OK"),null).show(); }
    private void section(String title){gap(16);TextView v=text(title,14,accent);v.setTypeface(null,Typeface.BOLD);body.addView(v);}
    private void settingRow(String title,String value,Runnable action){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(value.isEmpty()?56:72));row.setPaddingRelative(dp(4),dp(8),dp(4),dp(8));
        LinearLayout words=new LinearLayout(this);words.setOrientation(1);TextView name=text(title,16,ink);name.setPadding(0,0,0,0);words.addView(name);if(!value.isEmpty()){TextView sub=text(value,14,muted);sub.setPadding(0,dp(4),0,0);if(value.contains("")||value.contains(" / ")){sub.setTextDirection(View.TEXT_DIRECTION_LTR);sub.setGravity(ar?Gravity.RIGHT:Gravity.LEFT);}words.addView(sub);}row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){ImageView arrow=new ImageView(this);arrow.setImageResource(R.drawable.ic_chevron_right);arrow.setColorFilter(muted);arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);if(ar)arrow.setRotation(180f);row.addView(arrow,new LinearLayout.LayoutParams(dp(32),dp(48)));row.setBackground(touchBackground(Color.TRANSPARENT,12));row.setFocusable(true);row.setOnClickListener(v->action.run());}
        body.addView(row);
    }
    private void settings(){
        page(t("Settings", "Settings"),"settings");section(t("General", "General"));String theme=prefs().getString("theme","system");
        settingRow(t("Theme", "Theme"),theme.equals("dark")?t("Dark", "Dark"):theme.equals("light")?t("Light", "Light"):t("System default", "System default"),()->choices(t("Theme", "Theme"),new String[]{t("System default", "System default"),t("Light", "Light"),t("Dark", "Dark")},theme.equals("dark")?2:theme.equals("light")?1:0,i->{prefs().edit().putString("theme",new String[]{"system","light","dark"}[i]).apply();recreateSettings();}));
        settingRow(t("Language", "Language"),"English",()->choices(t("Language", "Language"),new String[]{"English"},0,i->{}));
        section(t("Playback", "Playback"));settingRow(t("Default playback speed", "Default playback speed"),speedLabel(),this::speed);
        LinearLayout awakeRow=new LinearLayout(this);awakeRow.setGravity(Gravity.CENTER_VERTICAL);awakeRow.setPadding(0,dp(12),0,dp(12));LinearLayout words=new LinearLayout(this);words.setOrientation(1);label(words,t("Keep screen awake", "Keep screen awake"),16,ink);label(words,t("While Music Filter is open and processing.", "While Music Filter is open and processing."),14,muted);awakeRow.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        Switch awake=new Switch(this);awake.setMinHeight(dp(48));awake.setContentDescription(t("Keep screen awake while processing", "Keep screen awake while processing"));awake.setChecked(prefs().getBoolean("awake",false));awake.setOnCheckedChangeListener((v,on)->prefs().edit().putBoolean("awake",on).apply());awakeRow.addView(awake);body.addView(awakeRow);
        section(t("Processing", "Processing"));settingRow(t("Background processing", "Background processing"),t("Continues when you leave the app; battery restrictions may slow it.", "Continues when you leave the app; battery restrictions may slow it."),()->new AlertDialog.Builder(this).setTitle(t("Background processing", "Background processing")).setMessage(t("Processing runs in a foreground service. Android battery restrictions may slow or interrupt it. Review Music Filter's battery settings if needed.", "Processing runs in a foreground service. Android battery restrictions may slow or interrupt it. Review Music Filter's battery settings if needed.")).setPositiveButton(t("Open settings", "Open settings"),(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton(t("Done", "Done"),null).show());
        settingRow(t("Notifications", "Notifications"),t("Progress and completion", "Progress and completion"),()->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        section(t("Model", "Model"));settingRow(t("Offline AI model", "Offline AI model"),modelSummary(),this::modelPage);
        section(t("Storage", "Storage"));settingRow(t("Save location", "Save location"),"Movies/MusicFilter",()->new AlertDialog.Builder(this).setTitle(t("Save location", "Save location")).setMessage(t("Videos are saved to Movies/MusicFilter only after you tap Save.", "Videos are saved to Movies/MusicFilter only after you tap Save.")).setPositiveButton(t("Done", "Done"),null).show());
        settingRow(t("Temporary files", "Temporary files"),android.text.format.Formatter.formatShortFileSize(this,cacheSize(new File(getCacheDir(),"processing"))),this::clearTemporary);
        settingRow(t("Save processing report", "Save processing report"),t("Device information and timings only", "Device information and timings only"),()->export(null,"application/json",5,"_performance.json"));
        section(t("About", "About"));settingRow(t("About Music Filter", "About Music Filter"),"",this::about);settingRow(t("Why Music Filter was created", "Why Music Filter was created"),"",this::why);settingRow(t("Privacy", "Privacy"),"",this::privacy);settingRow(t("Licences", "Licences"),"",this::licences);settingRow(t("App version", "App version"),appVersion(),null);
    }
    private void recreateSettings(){prefs().edit().putBoolean("return_settings",true).apply();recreate();}
    private String modelSummary(){
        if(OfflineModel.isReady(this))return t("Ready", "Ready");
        if(modelPreparationError!=null)return t("Preparation failed • Tap to retry", "Preparation failed • Tap to retry");
        return t("Bundled • Preparing…", "Bundled • Preparing…");
    }
    private void modelPage(){
        page(t("Offline AI model", "Offline AI model"),"model");
        label(body,modelSummary(),20,ink);
        settingRow(t("Storage used", "Storage used"),android.text.format.Formatter.formatShortFileSize(this,OfflineModel.storageBytes(this)),null);
        label(body,t("Included with this app. No download is required. Model updates are delivered with app updates.", "Included with this app. No download is required. Model updates are delivered with app updates."),15,muted);
        if(!OfflineModel.isReady(this))settingRow(t("Retry preparation", "Retry preparation"), "", () -> ensureModel(true,null));
    }
    private long cacheSize(File f){if(f.isFile())return f.length();long size=0;File[] children=f.listFiles();if(children!=null)for(File child:children)size+=cacheSize(child);return size;}
    private void clearTemporary(){if(ProcessingState.isRunning(this)){problem(t("Wait for processing to finish before clearing temporary files.", "Wait for processing to finish before clearing temporary files."),new IOException("Processing is active"));return;}AlertDialog d=new AlertDialog.Builder(this).setTitle(t("Clear temporary files?", "Clear temporary files?")).setMessage(t("Only leftover processing files will be removed. Your videos, saved results and model are kept.","Only leftover processing files will be removed. Your videos, saved results and model are kept." )).setNegativeButton(t("Cancel", "Cancel"),null).setPositiveButton(t("Clear", "Clear"),(dialog,w)->new Thread(()->{try{File folder=new File(getCacheDir(),"processing");clearInside(folder,folder.getCanonicalPath());runOnUiThread(this::settings);}catch(Exception e){runOnUiThread(()->problem(t("Temporary files could not be cleared.", "Temporary files could not be cleared."),e));}},"Clear-processing-cache").start()).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(dark?0xFFFFB4AB:0xFFB3261E));d.show();}
    private void clearInside(File f,String rootPath)throws Exception{if(ProcessingState.isRunning(this))throw new IOException("Processing started; cleanup stopped");String path=f.getCanonicalPath();if(!path.equals(rootPath)&&!path.startsWith(rootPath+File.separator))throw new IOException("Outside processing cache");File[] children=f.listFiles();if(children!=null)for(File child:children)clearInside(child,rootPath);if(!path.equals(rootPath)&&f.exists()&&!f.delete())throw new IOException("Could not clear temporary file");}
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void about(){page(t("About Music Filter", "About Music Filter"),"about");label(body,t("Keep the voice. Remove the music.", "Keep the voice. Remove the music."),24,accent);gap(12);label(body,t("Music Filter removes background music from imported videos while preserving useful speech and vocals as much as possible.", "Music Filter removes background music from imported videos while preserving useful speech and vocals as much as possible."),17,ink);gap(16);settingRow(t("Why Music Filter was created", "Why Music Filter was created"),"",this::why);gap(16);label(body,t("Designed & Developed by\nEbrahim Al-Shami", "Designed & Developed by\nEbrahim Al-Shami"),16,accent);gap(16);settingRow(t("Privacy", "Privacy"),"",this::privacy);settingRow(t("Licences", "Licences"),"",this::licences);label(body,"Music Filter • "+appVersion(),13,muted);}
    private void why(){page(t("Why Music Filter", "Why Music Filter"),"why");label(body,t("Why Music Filter was created", "Why Music Filter was created"),24,accent);
        label(body,t("Music Filter was created from a desire to make it easier to benefit from, save, and share useful videos without keeping the background music.", "Music Filter was created from a desire to make it easier to benefit from, save, and share useful videos without keeping the background music."),17,ink);gap(12);
        label(body,t("Sometimes we come across an educational, meaningful, useful, funny, or memorable video that we would like to keep or share, but it contains music in the background. Instead of leaving the entire video, Music Filter aims to remove the music while preserving the speech and useful content as much as possible.", "Sometimes we come across an educational, meaningful, useful, funny, or memorable video that we would like to keep or share, but it contains music in the background. Instead of leaving the entire video, Music Filter aims to remove the music while preserving the speech and useful content as much as possible."),17,ink);gap(12);
        label(body,t("The motivation behind this application comes from the developer's Islamic belief regarding musical instruments.\n\nThe Prophet Muhammad ﷺ said:", "The motivation behind this application comes from the developer's Islamic belief regarding musical instruments.\n\nThe Prophet Muhammad ﷺ said:"),17,ink);
        LinearLayout quote=card();TextView hadith=text("«لَيَكُونَنَّ مِنْ أُمَّتِي أَقْوَامٌ، يَسْتَحِلُّونَ الحِرَ وَالحَرِيرَ، وَالخَمْرَ وَالمَعَازِفَ»",22,ink);hadith.setTextDirection(View.TEXT_DIRECTION_RTL);hadith.setGravity(Gravity.RIGHT);quote.addView(hadith);label(quote,t("Narrated by al-Bukhari.", "Narrated by al-Bukhari."),14,muted);
        label(body,t("Music Filter was created as a practical tool for those who wish to avoid music while continuing to benefit from useful video content.", "Music Filter was created as a practical tool for those who wish to avoid music while continuing to benefit from useful video content."),17,ink);gap(12);
        label(body,t("This application was not created for personal profit or worldly benefit. The intention behind it is to provide something beneficial for the sake of Allah سبحانه وتعالى.", "This application was not created for personal profit or worldly benefit. The intention behind it is to provide something beneficial for the sake of Allah سبحانه وتعالى."),17,ink);gap(12);label(body,t("May Allah accept it and make it beneficial.", "May Allah accept it and make it beneficial."),17,accent);
    }
    private void privacy(){page(t("Privacy", "Privacy"),"privacy");label(body,t("Your videos stay on your device.", "Your videos stay on your device."),24,accent);gap(12);label(body,t("No video is uploaded for imported-video processing. Saved videos and extracted audio remain until you remove them. Sharing uses the app you choose and its privacy policy. Diagnostic reports contain device and timing information, not your audio or video. Music Filter operates entirely offline without network access.", "No video is uploaded for imported-video processing. Saved videos and extracted audio remain until you remove them. Sharing uses the app you choose and its privacy policy. Diagnostic reports contain device and timing information, not your audio or video. Music Filter operates entirely offline without network access."),17,ink);}
    private void licences(){page(t("Licences", "Licences"),"licences");label(body,t("Music Filter uses AndroidX Media3, ONNX Runtime, and JTransforms.", "Music Filter uses AndroidX Media3, ONNX Runtime, and JTransforms."),16,ink);label(body,t("Open-source component licences are included with their respective distributions.", "Open-source component licences are included with their respective distributions."),15,muted);}
    @Override public void onBackPressed(){if(!screen.equals("home"))navigateBack();else super.onBackPressed();}
}
