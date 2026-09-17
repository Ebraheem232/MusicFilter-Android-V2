package com.musicfilter.app;
import android.content.Context;
import android.net.Uri;
import java.io.File;
final class OfflineVideoProcessor {
    interface Progress {
        void report(String message);
        default void update(String message, int percent) { report(message); }
    }
    private OfflineVideoProcessor() {}
    static File process(Context context, Uri source, File modelDir, File output, Progress progress) throws Exception {
        return FullMediaProcessor.process(context, source, modelDir, output, progress);
    }
}
