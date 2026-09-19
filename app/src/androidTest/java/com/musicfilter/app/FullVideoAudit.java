package com.musicfilter.app;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.lang.reflect.*;

/** On-device integration check, compiled only into the separate test APK. */
public final class FullVideoAudit extends Instrumentation {
    private String match, mode;
    @Override public void onCreate(Bundle args) { super.onCreate(args); match = args.getString("match", "Skyfall"); mode = args.getString("mode", "full"); start(); }
    private void log(String text) { Bundle b = new Bundle(); b.putString("stream", text + "\n"); sendStatus(0, b); }
    @Override public void onStart() { if ("lifecycle".equals(mode)) { LifecycleAudit.execute(this); return; }
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            Uri source = null;
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                try (Cursor c = context.getContentResolver().query(permission.getUri(), new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0).contains(match)) { source = permission.getUri(); log("SOURCE " + c.getString(0)); break; }
                }
            }
            if (source == null) throw new AssertionError("No existing document grant for " + match);
            FullMediaProcessor.Tracks tracks = FullMediaProcessor.inspect(context, source);
            log("TRACKS audio=" + tracks.audioFormat + " video=" + tracks.videoFormat + " durationUs=" + tracks.durationUs);
            if ("media".equals(mode)) {
                java.io.File pcm = new java.io.File(context.getCacheDir(), "audit-media.f32");
                java.io.File mp4 = new java.io.File(context.getCacheDir(), "audit-media.mp4");
                OfflineVideoProcessor.Progress progress = text -> {};
                Method decode = FullMediaProcessor.class.getDeclaredMethod("decode", Context.class, Uri.class, FullMediaProcessor.Tracks.class, java.io.File.class, OfflineVideoProcessor.Progress.class);
                decode.setAccessible(true);
                long first = (Long)decode.invoke(null, context, source, tracks, pcm, progress);
                Method mux = FullMediaProcessor.class.getDeclaredMethod("mux", Context.class, Uri.class, FullMediaProcessor.Tracks.class, java.io.File.class, long.class, java.io.File.class, OfflineVideoProcessor.Progress.class);
                mux.setAccessible(true); mux.invoke(null, context, source, tracks, pcm, first, mp4, progress);
                FullMediaProcessor.Tracks saved = FullMediaProcessor.inspect(context, Uri.fromFile(mp4));
                if (saved.video < 0 || Math.abs(saved.durationUs - tracks.durationUs) > 200000) throw new AssertionError("Mux length mismatch " + saved.durationUs);
                log("PASS media decode+remux frames=" + pcm.length()/8 + " durationUs=" + saved.durationUs);
                result.putString("stream", "PASS media\n"); finish(Activity.RESULT_OK, result); return;
            }
            Activity activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Uri selected = source;
            runOnMainSync(() -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("selectedVideo"); field.setAccessible(true); field.set(activity, selected);
                    Method method = MainActivity.class.getDeclaredMethod("processVideo"); method.setAccessible(true); method.invoke(activity);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Thread.sleep(2000);
            getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
            log("BACKGROUND home pressed; full-video processing continues");
            long start = SystemClock.elapsedRealtime(), report = 0;
            ProcessingState.Snapshot prefs;
            while (true) {
                prefs = ProcessingState.read(context);
                String state = prefs.getString("status", "");
                if (!"RUNNING".equals(state)) {
                    if (!"COMPLETED".equals(state)) throw new AssertionError(state + ": " + prefs.getString("message", ""));
                    String output = prefs.getString("output", "");
                    FullMediaProcessor.Tracks saved = FullMediaProcessor.inspect(context, Uri.parse(output));
                    if (saved.video < 0 || Math.abs(saved.durationUs - tracks.durationUs) > 200000) throw new AssertionError("Output duration/video mismatch " + saved.durationUs);
                    log("PASS full video output=" + output + " durationUs=" + saved.durationUs + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
                    result.putString("stream", "PASS full video/background\n" + output); finish(Activity.RESULT_OK, result); return;
                }
                if (SystemClock.elapsedRealtime() - report > 30000) { log("PROGRESS " + prefs.getInt("percent", 0) + "% " + prefs.getString("message", "")); report = SystemClock.elapsedRealtime(); }
                if (SystemClock.elapsedRealtime() - start > 3600000) throw new AssertionError("One hour test timeout");
                Thread.sleep(1000);
            }
        } catch (Throwable error) { result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result); }
    }
}

