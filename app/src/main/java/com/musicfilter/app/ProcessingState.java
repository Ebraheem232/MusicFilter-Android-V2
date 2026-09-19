package com.musicfilter.app;

import android.app.ActivityManager;
import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ProcessingState {
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "processing-job.json"));
    }
    static final class Snapshot {
        final JSONObject data;
        Snapshot(JSONObject data) { this.data = data; }
        String getString(String key, String fallback) { return data.has(key) ? data.optString(key, fallback) : fallback; }
        int getInt(String key, int fallback) { return data.optInt(key, fallback); }
        long getLong(String key, long fallback) { return data.optLong(key, fallback); }
    }
    static synchronized Snapshot read(Context context) {
        try { return new Snapshot(new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8))); }
        catch (Exception ignored) { return new Snapshot(new JSONObject()); }
    }
    static synchronized void write(Context context, String jobId, String status, String source, String message, int percent, String output, int attempt) {
        AtomicFile target = file(context);
        FileOutputStream stream = null;
        try {
            JSONObject data = new JSONObject();
            data.put("jobId", jobId);
            data.put("status", status); 
            data.put("source", source); 
            data.put("message", message);
            data.put("percent", percent); 
            data.put("output", output); 
            data.put("attempt", attempt);
            data.put("updatedAt", System.currentTimeMillis());
            stream = target.startWrite(); stream.write(data.toString().getBytes(StandardCharsets.UTF_8)); target.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) target.failWrite(stream);
        }
    }
    /** Forget only an inactive job; retaining media files is a separate UI decision. */
    static synchronized boolean clearInactive(Context context) {
        if (isRunning(context)) return false;
        file(context).delete();
        context.getSharedPreferences(MediaProcessingService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        return true;
    }
    /** A late callback must never resurrect a dismissed or superseded job. */
    static synchronized boolean writeIfCurrent(Context context, String jobId, String status, String source, String message, int percent, String output, int attempt) {
        Snapshot current = read(context);
        if (jobId == null || !jobId.equals(current.getString("jobId", ""))
                || !"RUNNING".equals(current.getString("status", ""))) return false;
        write(context, jobId, status, source, message, percent, output, attempt);
        return true;
    }
    @SuppressWarnings("deprecation")
    static boolean isRunning(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE))
            if (info.service.getClassName().equals(MediaProcessingService.class.getName()) && info.pid != 0 && info.foreground) return true;
        return false;
    }
}
