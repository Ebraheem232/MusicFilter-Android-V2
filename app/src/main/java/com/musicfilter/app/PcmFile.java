package com.musicfilter.app;

import java.io.*;
import java.nio.*;

/** Stereo float32 little-endian PCM on disk; memory use is independent of video length. */
final class PcmFile {
    static final int FRAME_BYTES = 8;
    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Processing cancelled.");
    }
    static final class Writer implements Closeable {
        private final OutputStream out;
        private final ByteBuffer buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        long frames;
        Writer(File file) throws IOException { out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024); }
        void frame(float left, float right) throws IOException {
            if (!Float.isFinite(left) || !Float.isFinite(right)) throw new IOException("Invalid audio at sample " + frames);
            if (buffer.remaining() < FRAME_BYTES) flush();
            buffer.putFloat(left).putFloat(right); frames++;
        }
        private void flush() throws IOException { try (PerformanceTrace.Scope write = PerformanceTrace.stage("pcm_file_write")) { out.write(buffer.array(), 0, buffer.position()); buffer.clear(); } }
        @Override public void close() throws IOException { try { flush(); } finally { out.close(); } }
    }
    static final class Reader implements Closeable {
        private final RandomAccessFile file;
        final long frames;
        private final byte[] bytes = new byte[DttNetEngine.CHUNK_SAMPLES * FRAME_BYTES];
        Reader(File path) throws IOException {
            file = new RandomAccessFile(path, "r");
            if (file.length() % FRAME_BYTES != 0) { file.close(); throw new IOException("Incomplete PCM file."); }
            frames = file.length() / FRAME_BYTES;
        }
        float[][] chunk(long start) throws IOException {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("pcm_file_read")) {
            PcmFile.checkCancelled();
            float[][] chunk = new float[2][DttNetEngine.CHUNK_SAMPLES];
            
            long fileStart = Math.max(0, start);
            int padding = (int) Math.max(0, -start);
            int count = (int) Math.min(DttNetEngine.CHUNK_SAMPLES - padding, Math.max(0, frames - fileStart));
            
            if (count > 0) {
                file.seek(fileStart * FRAME_BYTES); 
                file.readFully(bytes, 0, count * FRAME_BYTES);
                ByteBuffer b = ByteBuffer.wrap(bytes, 0, count * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < count; i++) { 
                    chunk[0][padding + i] = b.getFloat(); 
                    chunk[1][padding + i] = b.getFloat(); 
                }
            }
            return chunk;
        }
    }
        @Override public void close() throws IOException { file.close(); }
    }
}
