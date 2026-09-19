package com.musicfilter.app;

import android.content.Context;
import android.util.Log;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Owns strict, atomic preparation and integrity checks for the bundled offline model. */
final class OfflineModel {
    private static final String DESCRIPTOR = "models/active-model.json";

    static final class Info {
        final String name, path, sha256;
        final long bytes;
        Info(String name, String path, long bytes, String sha256) {
            this.name = name; this.path = path; this.bytes = bytes; this.sha256 = sha256;
        }
    }

    static Info info(Context context) throws Exception {
        String json;
        try (InputStream in = context.getAssets().open(DESCRIPTOR);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            json = out.toString("UTF-8");
        }
        JSONObject value = new JSONObject(json);
        String name = value.getString("name").trim();
        String path = value.getString("path").trim();
        long bytes = value.getLong("bytes");
        String sha256 = value.getString("sha256").trim().toUpperCase(java.util.Locale.US);
        if (name.isEmpty() || path.isEmpty() || bytes <= 0 || !sha256.matches("[0-9A-F]{64}"))
            throw new IOException("The bundled model descriptor is invalid.");
        return new Info(name, path, bytes, sha256);
    }

    static File target(Context context, Info info) throws Exception {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IOException("App storage is unavailable.");
        File target = new File(root, info.path);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath + File.separator))
            throw new IOException("The bundled model path is invalid.");
        return target;
    }

    static boolean isReady(Context context) {
        try { Info info = info(context); return valid(target(context, info), info); }
        catch (Exception error) { return false; }
    }

    static long storageBytes(Context context) {
        try { Info info = info(context); File file = target(context, info); return file.isFile() ? file.length() : 0; }
        catch (Exception error) { return 0; }
    }

    static File prepare(Context context, boolean verifyOrt) throws Exception {
        Info info = info(context);
        File target = target(context, info);
        if (!valid(target, info)) {
            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
                throw new IOException("Could not create model storage.");
            File partial = new File(parent, target.getName() + ".partial");
            if (partial.exists() && !partial.delete()) throw new IOException("Could not replace an incomplete model.");
            try {
                try (InputStream in = new BufferedInputStream(context.getAssets().open(info.path), 64 * 1024);
                     FileOutputStream raw = new FileOutputStream(partial);
                     OutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.flush(); raw.getFD().sync();
                }
                if (!valid(partial, info)) throw new IOException("The bundled model failed its integrity check.");
                try {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception atomicError) {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (partial.exists() && !partial.delete()) Log.w("MusicFilterModel", "Could not remove partial model " + partial);
            }
        }
        if (!valid(target, info)) throw new IOException("The prepared model failed its integrity check.");
        if (verifyOrt) {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(1);
                try (OrtSession ignored = OrtEnvironment.getEnvironment().createSession(target.getAbsolutePath(), options)) {
                    Log.i("MusicFilterModel", "Bundled model integrity and ONNX Runtime session verified.");
                }
            }
        }
        return target;
    }

    private static boolean valid(File file, Info info) throws Exception {
        return file.isFile() && file.length() == info.bytes && info.sha256.equals(sha256(file));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.US, "%02X", value & 0xff));
        return text.toString();
    }

    private OfflineModel() {}
}
