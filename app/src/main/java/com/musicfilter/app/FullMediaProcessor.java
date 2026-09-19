package com.musicfilter.app;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.inspector.MediaExtractorCompat;
import androidx.media3.muxer.Mp4Muxer;
import java.io.*;
import java.nio.*;

/** Full-duration disk-backed audio separation with encoded video passthrough. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class FullMediaProcessor {
    private static final int RATE = 44100;
    static MediaExtractorCompat open(Context context, Uri source) throws IOException {
        MediaExtractorCompat ex = context == null
                ? new MediaExtractorCompat(new DefaultExtractorsFactory(), () -> new FileDataSource())
                : new MediaExtractorCompat(context);
        try {
            if ("file".equals(source.getScheme())) ex.setDataSource(source.getPath());
            // Use the URI-backed data source, which owns its descriptor for the reader's lifetime.
            // The Context/Uri overload in Media3 1.10.1 closes its asset descriptor after probing.
            else ex.setDataSource(source.toString());
            return ex;
        } catch (IOException | RuntimeException error) { ex.release(); throw error; }
    }
    static final class Tracks {
        int audio = -1, video = -1;
        MediaFormat audioFormat, videoFormat;
        long durationUs;
    }
    static Tracks inspect(Context context, Uri source) throws Exception {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("track_inspection")) {
        MediaExtractorCompat ex = open(context, source);
        try {
            Tracks t = new Tracks();
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                android.util.Log.i("MusicFilterMedia", "Track " + i + ": " + f);
                if (mime != null && mime.startsWith("audio/") && t.audio < 0) { t.audio = i; t.audioFormat = f; }
                if (mime != null && mime.startsWith("video/") && t.video < 0) { t.video = i; t.videoFormat = f; }
                t.durationUs = Math.max(t.durationUs, f.getLong(MediaFormat.KEY_DURATION, 0));
            }
            if (t.audio < 0) throw new IOException("This file has no audio track to process.");
            if (new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(t.audioFormat) == null)
                throw new IOException("This phone cannot decode the file's audio format (" + t.audioFormat.getString(MediaFormat.KEY_MIME) + ").");
            if (t.video >= 0) {
                String mime = t.videoFormat.getString(MediaFormat.KEY_MIME);
                if (!Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES.contains(mime))
                    throw new IOException("This video's codec cannot be saved in MP4: " + mime);
            }
            return t;
        } finally { ex.release(); }
    
        }
    }
    static File process(Context context, Uri source, File modelDir, File output, OfflineVideoProcessor.Progress progress) throws Exception {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("full_pipeline")) {
        if (output.exists()) throw new IOException("An output with this name already exists.");
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the working folder.");
        progress.update("Reading the complete file…", 0);
        Tracks tracks = inspect(context, source);
        PerformanceTrace.detail("source_duration_us", tracks.durationUs);
        PerformanceTrace.detail("audio_mime", tracks.audioFormat.getString(MediaFormat.KEY_MIME));
        PerformanceTrace.detail("video_mime", tracks.videoFormat == null ? "none" : tracks.videoFormat.getString(MediaFormat.KEY_MIME));
        long required = (long)(tracks.durationUs / 1_000_000.0 * RATE * 16) + 64 * 1024 * 1024;
        if (parent.getUsableSpace() < required) throw new IOException("Not enough free storage. Free at least " + ((required + 1048575) / 1048576) + " MB and try again.");
        File decoded = File.createTempFile("decoded-", ".f32", parent);
        File cleaned = File.createTempFile("cleaned-", ".f32", parent);
        try {
                        long startMs = android.os.SystemClock.elapsedRealtime();
            long audioStartUs = decode(context, source, tracks, decoded, progress);
            File model = OfflineModel.prepare(context, false);
            PerformanceTrace.model(model);
            DttNetEngine.processFile(model, decoded, cleaned, progress);
            mux(context, source, tracks, cleaned, audioStartUs, output, progress);
            long totalMs = android.os.SystemClock.elapsedRealtime() - startMs;
            android.util.Log.i("MusicFilterBench", "TOTAL PROCESSING TIME: " + totalMs + " ms");
            progress.update("Full video ready.", 100);
            return output;
        } finally { decoded.delete(); cleaned.delete(); }
    
        }
    }
    private static long decode(Context context, Uri source, Tracks tracks, File target, OfflineVideoProcessor.Progress progress) throws Exception {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("audio_decode")) {
        MediaExtractorCompat ex = open(context, source);
        MediaCodec decoder = null;
        boolean started = false;
        long firstOutputUs = Long.MIN_VALUE;
        try (PcmFile.Writer writer = new PcmFile.Writer(target)) {
            ex.selectTrack(tracks.audio);
            MediaFormat f = tracks.audioFormat;
            int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            decoder = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            PerformanceTrace.detail("decoder_name", decoder.getName());
            decoder.configure(f, null, null, 0); decoder.start(); started = true;
            Resampler resampler = new Resampler(writer, rate);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long moved = SystemClock.elapsedRealtime(), reported = 0;
            while (!outputDone) {
                PcmFile.checkCancelled();
                if (SystemClock.elapsedRealtime() - moved > 30000) throw new IOException("Audio decoding stopped responding.");
                if (!inputDone) {
                    int index;
                    try (PerformanceTrace.Scope wait = PerformanceTrace.stage("decoder_input_wait")) { index = decoder.dequeueInputBuffer(10000); }
                    if (index >= 0) {
                        ByteBuffer b = decoder.getInputBuffer(index); b.clear();
                        int size;
                        try (PerformanceTrace.Scope read = PerformanceTrace.stage("audio_extraction")) { size = ex.readSampleData(b, 0); }
                        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                        else {
                            if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IOException("Protected audio cannot be processed.");
                            decoder.queueInputBuffer(index, 0, size, ex.getSampleTime(), 0); ex.advance();
                        }
                        moved = SystemClock.elapsedRealtime();
                    }
                }
                int index;
                    try (PerformanceTrace.Scope wait = PerformanceTrace.stage("decoder_output_wait")) { index = decoder.dequeueOutputBuffer(info, 10000); }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = decoder.getOutputFormat();
                    int newRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = actual.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                    if (channels < 1 || channels > 8 || newRate <= 0) throw new IOException("Unsupported decoded audio layout.");
                    if (newRate != rate) { resampler.finish(); resampler = new Resampler(writer, newRate); rate = newRate; }
                    PerformanceTrace.detail("decoded_sample_rate", rate); PerformanceTrace.detail("decoded_channels", channels); PerformanceTrace.detail("decoded_pcm_encoding", encoding);
                    android.util.Log.i("MusicFilterMedia", "Decoded PCM: " + actual);
                    moved = SystemClock.elapsedRealtime();
                } else if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (firstOutputUs == Long.MIN_VALUE) firstOutputUs = info.presentationTimeUs;
                        ByteBuffer b = decoder.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                        b.position(info.offset); b.limit(info.offset + info.size);
                        int bytes = sampleBytes(encoding);
                        if (info.size % (channels * bytes) != 0) throw new IOException("Incomplete decoded audio frame.");
                        try (PerformanceTrace.Scope conversion = PerformanceTrace.stage("pcm_convert_downmix_resample")) {
                        while (b.remaining() >= channels * bytes) {
                            float l = sample(b, encoding), r = channels > 1 ? sample(b, encoding) : l;
                            float weightL = 1, weightR = 1;
                            for (int c = 2; c < channels; c++) {
                                float v = sample(b, encoding);
                                // Standard decoder channel order: L, R, centre, LFE, surrounds.
                                if (c == 2) { l += v * .7071f; r += v * .7071f; weightL += .7071f; weightR += .7071f; }
                                else if (c >= 4 && c % 2 == 0) { l += v * .7071f; weightL += .7071f; }
                                else if (c >= 4) { r += v * .7071f; weightR += .7071f; }
                            }
                            resampler.frame(l / weightL, r / weightR);
                        }
                        }
                        if (SystemClock.elapsedRealtime() - reported > 500) {
                            progress.update("Reading the complete audio…", tracks.durationUs > 0 ? (int)Math.min(9, 9 * Math.max(0, info.presentationTimeUs) / tracks.durationUs) : 0);
                            reported = SystemClock.elapsedRealtime();
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(index, false); moved = SystemClock.elapsedRealtime();
                }
            }
            resampler.finish();
            if (writer.frames == 0) throw new IOException("The file did not decode to usable audio.");
            long expectedEndUs = tracks.audioFormat.getLong(MediaFormat.KEY_DURATION, 0);
            long decodedEndUs = Math.max(0, firstOutputUs) + writer.frames * 1_000_000L / RATE;
            // Readers can report a failed/truncated read as EOS. Never publish a short teaser as success.
            if (expectedEndUs > 0 && decodedEndUs + 500000 < expectedEndUs)
                throw new IOException("The audio ended before the file's reported duration. Download or copy the complete file and try again.");
            PerformanceTrace.detail("decoded_frames", writer.frames);
            android.util.Log.i("MusicFilterMedia", "Decoded frames=" + writer.frames + " firstUs=" + firstOutputUs);
            return Math.max(0, firstOutputUs);
        } finally { if (decoder != null) { try { if (started) decoder.stop(); } finally { decoder.release(); } } ex.release(); }
    
        }
    }
    private static int sampleBytes(int encoding) throws IOException {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT: return 1;
            case AudioFormat.ENCODING_PCM_16BIT: return 2;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: return 3;
            case AudioFormat.ENCODING_PCM_32BIT: case AudioFormat.ENCODING_PCM_FLOAT: return 4;
            default: throw new IOException("Unsupported decoded PCM encoding: " + encoding);
        }
    }
    private static float sample(ByteBuffer b, int encoding) throws IOException {
        float v;
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT: v = ((b.get() & 255) - 128) / 128f; break;
            case AudioFormat.ENCODING_PCM_16BIT: v = b.getShort() / 32768f; break;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: v = ((b.get() & 255) | ((b.get() & 255) << 8) | (b.get() << 16)) / 8388608f; break;
            case AudioFormat.ENCODING_PCM_32BIT: v = b.getInt() / 2147483648f; break;
            case AudioFormat.ENCODING_PCM_FLOAT: v = b.getFloat(); break;
            default: throw new IOException("Unsupported PCM encoding.");
        }
        if (!Float.isFinite(v)) throw new IOException("The audio decoder returned an invalid sample.");
        return v;
    }
    static final class Resampler {
        final PcmFile.Writer writer; final int rate;
        long inputFrames, outputFrames; float previousL, previousR;
        Resampler(PcmFile.Writer writer, int rate) throws IOException { if (rate <= 0) throw new IOException("Invalid sample rate."); this.writer = writer; this.rate = rate; }
        void frame(float l, float r) throws IOException {
            long index = inputFrames++;
            double pos;
            while ((pos = outputFrames * (double)rate / RATE) <= index) {
                float amount = index == 0 ? 1 : (float)(pos - (index - 1));
                writer.frame(previousL + (l - previousL) * amount, previousR + (r - previousR) * amount); outputFrames++;
            }
            previousL = l; previousR = r;
        }
        void finish() throws IOException {
            long total = Math.round(inputFrames * (double)RATE / rate);
            while (outputFrames < total) { writer.frame(previousL, previousR); outputFrames++; }
        }
    }
    private static void mux(Context context, Uri source, Tracks tracks, File audio, long audioStartUs, File output, OfflineVideoProcessor.Progress progress) throws Exception {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("encode_and_mux")) {
        progress.update("Saving the full video…", 90);
        MediaExtractorCompat ex = open(context, source);
        MediaCodec encoder = null; boolean started = false;
        try (PcmFile.Reader pcm = new PcmFile.Reader(audio);
             FileOutputStream stream = new FileOutputStream(output);
             Mp4Muxer muxer = new Mp4Muxer.Builder(stream).setSampleBatchingEnabled(false).build()) {
            int videoTrack = -1, audioTrack = -1;
            if (tracks.video >= 0) {
                videoTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(tracks.videoFormat));
                int rotation = tracks.videoFormat.getInteger(MediaFormat.KEY_ROTATION, 0);
                if (rotation == 90 || rotation == 180 || rotation == 270) muxer.addMetadataEntry(new Mp4OrientationData(rotation));
                ex.selectTrack(tracks.video);
            }
            VideoCopier video = new VideoCopier(ex, muxer, videoTrack);
            MediaFormat aac = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 2);
            aac.setInteger(MediaFormat.KEY_AAC_PROFILE, 2); aac.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            PerformanceTrace.detail("encoder_name", encoder.getName());
            encoder.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start(); started = true;
            long position = 0, chunkStart = -PolarFormerEngine.CHUNK_SAMPLES, moved = SystemClock.elapsedRealtime();
            float[][] chunk = null;
            boolean inputDone = false, outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                PcmFile.checkCancelled();
                if (SystemClock.elapsedRealtime() - moved > 30000) throw new IOException("Audio encoding stopped responding.");
                if (!inputDone) {
                    int index;
                    try (PerformanceTrace.Scope wait = PerformanceTrace.stage("encoder_input_wait")) { index = encoder.dequeueInputBuffer(0); }
                    if (index >= 0) {
                        ByteBuffer b = encoder.getInputBuffer(index).order(ByteOrder.LITTLE_ENDIAN); b.clear();
                        int frames = (int)Math.min(b.capacity() / 4, pcm.frames - position);
                        long pts = position * 1_000_000L / RATE + audioStartUs;
                        try (PerformanceTrace.Scope conversion = PerformanceTrace.stage("pcm_to_encoder")) {
                        for (int i = 0; i < frames; i++) {
                            if (chunk == null || position >= chunkStart + PolarFormerEngine.CHUNK_SAMPLES) { chunkStart = position; chunk = pcm.chunk(position); }
                            int p = (int)(position - chunkStart);
                            b.putShort((short)(Math.max(-1, Math.min(1, chunk[0][p])) * 32767));
                            b.putShort((short)(Math.max(-1, Math.min(1, chunk[1][p])) * 32767)); position++;
                        }
                        }
                        inputDone = frames == 0;
                        encoder.queueInputBuffer(index, 0, frames * 4, pts, inputDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        moved = SystemClock.elapsedRealtime();
                    }
                }
                int index;
                    try (PerformanceTrace.Scope wait = PerformanceTrace.stage("encoder_output_wait")) { index = encoder.dequeueOutputBuffer(info, 10000); }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { audioTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(encoder.getOutputFormat())); moved = SystemClock.elapsedRealtime(); }
                else if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (audioTrack < 0) throw new IOException("Missing encoded audio format.");
                        video.until(info.presentationTimeUs);
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        data.position(info.offset); data.limit(info.offset + info.size);
                        try (PerformanceTrace.Scope write = PerformanceTrace.stage("mux_audio_packet")) {
                            muxer.writeSampleData(audioTrack, data, new androidx.media3.muxer.BufferInfo(info.presentationTimeUs, info.size, C.BUFFER_FLAG_KEY_FRAME));
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false); moved = SystemClock.elapsedRealtime();
                    progress.update("Saving the full video…", 90 + (int)(9 * position / pcm.frames));
                }
            }
            video.until(Long.MAX_VALUE);
            android.util.Log.i("MusicFilterMedia", "Saved audio frames=" + pcm.frames + " video packets=" + video.count);
        } finally { if (encoder != null) { try { if (started) encoder.stop(); } finally { encoder.release(); } } ex.release(); }
    
        }
    }
    private static final class VideoCopier {
        final MediaExtractorCompat ex; final Mp4Muxer muxer; final int track;
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024); long count;
        VideoCopier(MediaExtractorCompat ex, Mp4Muxer muxer, int track) { this.ex = ex; this.muxer = muxer; this.track = track; }
        void until(long pts) throws Exception {
            try (PerformanceTrace.Scope copy = PerformanceTrace.stage("video_packet_copy_mux")) {
            if (track < 0) return;
            while (ex.getSampleTime() >= 0 && ex.getSampleTime() <= pts) {
                PcmFile.checkCancelled();
                if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IOException("Protected video cannot be processed.");
                long size = ex.getSampleSize();
                if (size > Integer.MAX_VALUE) throw new IOException("Video frame too large.");
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int)size);
                buffer.clear(); int read = ex.readSampleData(buffer, 0); if (read < 0) return;
                buffer.position(0); buffer.limit(read);
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? C.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(track, buffer, new androidx.media3.muxer.BufferInfo(ex.getSampleTime(), read, flags));
                count++; ex.advance();
            }
            }
        }
    }
}
