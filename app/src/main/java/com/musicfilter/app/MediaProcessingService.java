package com.musicfilter.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.util.UUID;

/** Owns imported-file work independently of the activity and its player. */
public final class MediaProcessingService extends Service {
    static final String ACTION_STATUS = "com.musicfilter.app.PROCESSING_STATUS";
    static final String ACTION_CANCEL = "com.musicfilter.app.CANCEL_PROCESSING";
    static final String EXTRA_START_US = "startUs";
    static final String EXTRA_END_US = "endUs";
    static final String EXTRA_SELECTION_START_US = "selectionStartUs";
    static final String EXTRA_SELECTION_END_US = "selectionEndUs";
    static final String EXTRA_SAMPLE = "sample";
    static final String PREFS = "processing";
    private static final String CHANNEL = "music_removal";
    private static final int PROCESSING_NOTIFICATION = 401;
    static final int COMPLETION_NOTIFICATION = 402;
    private volatile boolean running;
    private String sourceString;
    private volatile String sourceDisplayName;
    private long previousCpuMs;
    private long jobStartedMs;
    private volatile String baseMessage = "Preparing the full video";
    private volatile boolean cancellationRequested;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (completionPending) return;
            if (worker == null || !worker.isAlive()) { finish("failed", "Processing stopped unexpectedly. Please try again.", null); return; }
            long cpuMs = android.os.Process.getElapsedCpuTime();
            long delta = cpuMs - previousCpuMs;
            android.util.Log.i("MusicFilterService", "Worker alive=" + worker.isAlive() + " state=" + worker.getState()
                    + " cpuDeltaMs=" + (cpuMs - previousCpuMs) + " progress=" + lastPercent);
            previousCpuMs = cpuMs;
            if (performance != null) performance.sample();
            refreshActivityStatus(delta);
            main.postDelayed(this, 10000);
        }
    };
    private Thread worker;
    private PerformanceTrace performance;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long lastUpdate;
    private volatile int lastPercent = -1;
    private boolean finishing;
    private volatile boolean completionPending;
    private volatile String cancelMessage = "Processing cancelled. Your original file is unchanged.";

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Music removal", NotificationManager.IMPORTANCE_LOW));
    }
    private String jobId;
    private int attempt = 1;
    private long rangeStartUs = -1, rangeEndUs = -1, selectionStartUs = -1, selectionEndUs = -1;
    private boolean sampleJob;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancellationRequested = true;
            if (worker != null) { worker.interrupt(); refreshActivityStatus(0); } else stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_REDELIVER_INTENT;
        if (intent == null || intent.getData() == null) { stopSelf(); return START_NOT_STICKY; }
        
        Uri source = intent.getData();
        sourceString = source.toString();
        rangeStartUs = intent.getLongExtra(EXTRA_START_US, -1);
        rangeEndUs = intent.getLongExtra(EXTRA_END_US, -1);
        selectionStartUs = intent.getLongExtra(EXTRA_SELECTION_START_US, -1);
        selectionEndUs = intent.getLongExtra(EXTRA_SELECTION_END_US, -1);
        sampleJob = intent.getBooleanExtra(EXTRA_SAMPLE, false);
        
        // Every attempt has a new identity so a late callback from an older attempt
        // cannot overwrite or complete the replacement job.
        ProcessingState.Snapshot state = ProcessingState.read(this);
        attempt = sourceString.equals(state.getString("source", "")) ? state.getInt("attempt", 0) + 1 : 1;
        jobId = UUID.randomUUID().toString();
        
        running = true;
        finishing = false; completionPending = false; cancellationRequested = false;
        jobStartedMs = SystemClock.elapsedRealtime();
        
        String preparing = sampleJob ? "Preparing 15-second test" : rangeStartUs >= 0 ? "Preparing selected section" : "Preparing the full video";
        ProcessingState.write(this, jobId, "RUNNING", sourceString, preparing, 0, null, attempt);
        ProcessingState.setMetadata(this, jobId, null, rangeStartUs, rangeEndUs, selectionStartUs, selectionEndUs, sampleJob);
        int type = Build.VERSION.SDK_INT >= 35 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        try {
            getSystemService(NotificationManager.class).cancel(COMPLETION_NOTIFICATION);
            startForeground(PROCESSING_NOTIFICATION, processingNotification(preparing, 0), type);
            performance = new PerformanceTrace(this);
            worker = new Thread(() -> execute(source), "Music Filter-processing");
            worker.setUncaughtExceptionHandler((thread, error) -> {
                android.util.Log.e("MusicFilterService", "Worker terminated", error);
                completionPending = true;
                main.post(() -> finish("FAILED", "Processing stopped unexpectedly. Please try again.", null));
            });
            worker.start();
            previousCpuMs = android.os.Process.getElapsedCpuTime();
            main.postDelayed(heartbeat, 10000);
        } catch (RuntimeException error) { finish("FAILED", "Could not start processing: " + detail(error), null); }
        return START_REDELIVER_INTENT;
    }
    private void execute(Uri source) {
        boolean success = false;
        try (android.database.Cursor c=getContentResolver().query(source,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) { if(c!=null && c.moveToFirst()) sourceDisplayName=c.getString(0); } catch(Exception ignored) {}
        File output = null, folder = null;
        if (performance != null) { performance.attach(); performance.sample(); }
        try {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Music Filter:processing");
            wakeLock.acquire(6 * 60 * 60 * 1000L); // Sensible 6h limit for safety
            
            folder = new File(getCacheDir(), "processing/" + UUID.randomUUID());
            output = new File(folder, "result.mp4");
            Uri processingSource = source;
            if (rangeStartUs >= 0 && rangeEndUs > rangeStartUs) {
                MediaSection.Result section = MediaSection.remux(this, source, new File(folder, "original-section.mp4"), rangeStartUs, rangeEndUs, sampleJob);
                processingSource = Uri.fromFile(section.file);
                ProcessingState.setMetadata(this, jobId, processingSource.toString(), section.startUs, section.endUs, selectionStartUs, selectionEndUs, sampleJob);
            }
            OfflineVideoProcessor.process(this, processingSource, getExternalFilesDir(null), output, new OfflineVideoProcessor.Progress() {
                @Override public void report(String message) { update(message, lastPercent); }
                @Override public void update(String message, int percent) { reportProgress(message, percent); }
            });
            PcmFile.checkCancelled();
            reportProgress("Saving to your phone", 99);
            boolean hasVideo = FullMediaProcessor.inspect(this, Uri.fromFile(output)).video >= 0;
            Uri saved = Uri.fromFile(output);
            completionPending = true;
            success = true;
            main.post(() -> finish("COMPLETED", sampleJob ? "Your test is ready" : hasVideo ? "Your video is ready" : "Your audio is ready", saved));
        } catch (Exception error) {
            boolean cancelled = Thread.currentThread().isInterrupted() || error instanceof InterruptedIOException;
            android.util.Log.e("Music FilterMedia", "Processing ended", error);
            completionPending = true;
            main.post(() -> finish(cancelled ? "CANCELLED" : "FAILED", cancelled ? cancelMessage : userMessage(error), null));
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (!success && folder != null) deleteTree(folder);
            PerformanceTrace.detach();
        }
    }
    private static void deleteTree(File file) { File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete(); }
    private synchronized void reportProgress(String message, int percent) {
        if (!running || completionPending || Thread.currentThread().isInterrupted()) return;
        long now = SystemClock.elapsedRealtime();
        if (percent == lastPercent && now - lastUpdate < 750) return;
        lastUpdate = now; lastPercent = percent;
        baseMessage = message;
        if (ProcessingState.writeIfCurrent(this, jobId, "RUNNING", sourceString, message, percent, null, attempt)) {
            getSystemService(NotificationManager.class).notify(PROCESSING_NOTIFICATION, processingNotification(message, percent));
            sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
        }
    }
    private synchronized void refreshActivityStatus(long cpuDeltaMs) {
        if (!running || completionPending) return;
        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - jobStartedMs) / 1000);
        String activity = cancellationRequested ? "Cancelling after the current audio step" : cpuDeltaMs > 0 ? "Working" : "Waiting for Android";
        String message = baseMessage.replace(" You can use other apps.", "") + "\n" + activity + "  " + seconds / 60 + "m " + seconds % 60 + "s elapsed.";
        if (ProcessingState.writeIfCurrent(this, jobId, "RUNNING", sourceString, message, lastPercent, null, attempt)) {
            getSystemService(NotificationManager.class).notify(PROCESSING_NOTIFICATION, processingNotification(message, lastPercent));
            sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
        }
    }
    private Uri publish(File output, boolean video) throws Exception {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("publish_output_file")) {
        ContentValues values = new ContentValues();
        String originalName = sourceDisplayName != null ? sourceDisplayName : "video";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) originalName = originalName.substring(0, dot);
        String finalName = "MusicFilter_" + originalName + (video ? ".mp4" : ".m4a");
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "audio/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC) + "/Music Filter");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create the saved result.");
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(output)); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Could not open the output file.");
                byte[] bytes = new byte[64 * 1024]; int count;
                while ((count = in.read(bytes)) != -1) { PcmFile.checkCancelled(); out.write(bytes, 0, count); }
            }
            PcmFile.checkCancelled();
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) { getContentResolver().delete(uri, null, null); throw error; }
    
        }
    }
    private Notification processingNotification(String message, int percent) {
        Intent open = new Intent(this, MainActivity.class).setAction("com.musicfilter.app.SHOW_RESULT").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean arabic=getSharedPreferences("interface",MODE_PRIVATE).getString("language","en").equals("ar");
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Music Filter")
                .setSubText(sourceDisplayName).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(content).setOnlyAlertOnce(true).setOngoing(true).setAutoCancel(false)
                .setProgress(100, Math.max(0, percent), percent <= 0);
        PendingIntent cancel = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class).setAction("com.musicfilter.app.SHOW_CANCEL").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(null, arabic?"":"Cancel", cancel).build());
        return builder.build();
    }
    private Notification completionNotification() {
        Intent open = new Intent(this, MainActivity.class).setAction("com.musicfilter.app.SHOW_RESULT").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_done)
                .setContentTitle("Music Filter")
                .setSubText(sourceDisplayName)
                .setContentText("Your video is ready")
                .setStyle(new Notification.BigTextStyle().bigText("Your video is ready"))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .build();
    }
    private void finish(String state, String message, Uri result) {
        if (finishing) return;
        finishing = true; running = false;
        if (performance != null) performance.finish(state);
        main.removeCallbacks(heartbeat);
        boolean current = ProcessingState.writeIfCurrent(this, jobId, state, sourceString, message, result == null ? lastPercent : 100, result == null ? null : result.toString(), attempt);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(PROCESSING_NOTIFICATION);
        if (current && state.equals("COMPLETED")) getSystemService(NotificationManager.class).notify(COMPLETION_NOTIFICATION, completionNotification());
        if (current) sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
        stopSelf();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        cancelMessage = "Processing stopped because Android's time limit was reached. Please try again with the app open.";
        if (worker != null) worker.interrupt();
        // Android requires the service to stop within seconds, even if a model chunk is finishing.
        finish("FAILED", cancelMessage, null);
    }
    @Override public void onDestroy() {
        main.removeCallbacks(heartbeat);
        if (worker != null && worker.isAlive()) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        running = false;
        if (!finishing) {
            if (performance != null) performance.finish("interrupted");
            ProcessingState.writeIfCurrent(this, jobId, "INTERRUPTED", sourceString, "Processing was interrupted. Please try again.", lastPercent, null, attempt);
            getSystemService(NotificationManager.class).cancel(PROCESSING_NOTIFICATION);
        }
        super.onDestroy();
    }
    private static String detail(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String userMessage(Exception error) {
        String detail = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.US);
        if (detail.contains("no audio track") || detail.contains("has no audio")) return "This video does not contain an audio track.";
        if (detail.contains("permission denied") || detail.contains("filenotfound") || detail.contains("could not open")) return "This video could not be opened. Select it again and retry.";
        if (detail.contains("not enough") || detail.contains("no space")) return "There is not enough storage space to process this video.";
        if (detail.contains("model")) return "The offline AI model could not be prepared. Open Settings and tap Offline AI model to retry.";
        if (detail.contains("decode") || detail.contains("codec")) return "This video's audio format is not supported on this phone.";
        return "This video could not be processed. Your original file is unchanged.";
    }
}
