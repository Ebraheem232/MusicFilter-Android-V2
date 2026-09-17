package com.musicfilter.app;

import java.io.File;
import org.jtransforms.fft.FloatFFT_1D;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Collections;
import android.util.Log;

final class PolarFormerEngine {
    static final int SAMPLE_RATE = 44100;
    static final int CHANNELS = 2;
    static final int FFT_SIZE = 2048;
    static final int HOP_SIZE = 512;
    static final int RETAINED_BINS = 1025;
    static final int FRAMES = 201; // Reduced from 801 to prevent device OOM kills
    static final int CHUNK_SAMPLES = HOP_SIZE * (FRAMES - 1); // 102400
    static final int OVERLAP_SAMPLES = CHUNK_SAMPLES / 2;

    private static final float[] WINDOW = makeWindow();
    private static final ThreadLocal<FloatFFT_1D> FFT = ThreadLocal.withInitial(() -> new FloatFFT_1D(FFT_SIZE));
    private static float[] makeWindow() {
        float[] window = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) window[i] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / FFT_SIZE));
        return window;
    }
    private PolarFormerEngine() {}

    static File requireModel(File externalFiles) {
        File model = new File(externalFiles, "models/polarformer/polarformer_core_merged.onnx");
        if (!model.isFile() || model.length() == 0)
            throw new IllegalStateException("PolarFormer model is missing from MusicFilter/models/polarformer.");
        return model;
    }

    static void requireStereo44100(float[][] audio) {
        if (audio == null || audio.length != CHANNELS || audio[0] == null || audio[1] == null
                || audio[0].length != audio[1].length)
            throw new IllegalArgumentException("PolarFormer requires matching stereo audio.");
    }

    static void requireFinite(float[][] audio, String stage) {
        requireStereo44100(audio);
        for (int c = 0; c < CHANNELS; c++) for (int i = 0; i < audio[c].length; i++)
            if (!Float.isFinite(audio[c][i]))
                throw new IllegalArgumentException(stage + ": nonfinite audio at channel " + c + ", sample " + i);
    }

    private static void requireTensor(NodeInfo node, String name) {
        if (node == null || !(node.getInfo() instanceof TensorInfo))
            throw new IllegalArgumentException("PolarFormer is missing tensor " + name);
        TensorInfo info = (TensorInfo) node.getInfo();
        if (info.type != OnnxJavaType.FLOAT)
            throw new IllegalArgumentException("PolarFormer " + name + " must be float32, got " + info);
    }

    static float[][][][][] stftInput(float[][] chunk) {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("stft")) {
            requireFinite(chunk, "Before STFT");
            float[][][][][] out = new float[1][2][RETAINED_BINS][FRAMES][2];
            FloatFFT_1D fft = FFT.get();
            float[] buffer = new float[FFT_SIZE * 2];
            int pad = FFT_SIZE / 2;
            for (int channel = 0; channel < CHANNELS; channel++) {
                for (int frame = 0; frame < FRAMES; frame++) {
                    java.util.Arrays.fill(buffer, 0f);
                    int base = frame * HOP_SIZE - pad;
                    for (int i = 0; i < FFT_SIZE; i++) {
                        int source = base + i;
                        if (source < 0) source = -source;
                        if (source >= CHUNK_SAMPLES) source = 2 * CHUNK_SAMPLES - 2 - source;
                        buffer[i] = chunk[channel][source] * WINDOW[i];
                    }
                    fft.realForwardFull(buffer);
                    for (int bin = 0; bin < RETAINED_BINS; bin++) { 
                        out[0][channel][bin][frame][0] = buffer[2 * bin]; 
                        out[0][channel][bin][frame][1] = buffer[2 * bin + 1]; 
                    }
                }
            }
            return out;
        }
    }

    static float[][] istftOutput(float[][][][][] mask, float[][][][][] stft_in) {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("istft_overlap_normalization")) {
            float[][] output = new float[CHANNELS][CHUNK_SAMPLES];
            float[][] weight = new float[CHANNELS][CHUNK_SAMPLES];
            FloatFFT_1D fft = FFT.get();
            float[] buffer = new float[FFT_SIZE * 2]; int pad = FFT_SIZE / 2;
            for (int channel = 0; channel < CHANNELS; channel++) {
                for (int frame = 0; frame < FRAMES; frame++) {
                    java.util.Arrays.fill(buffer, 0f);
                    for (int bin = 1; bin < RETAINED_BINS; bin++) {
                        float s_re = stft_in[0][channel][bin][frame][0];
                        float s_im = stft_in[0][channel][bin][frame][1];
                        
                        int mask_bin = channel * RETAINED_BINS + bin;
                        float m_re = mask[0][0][mask_bin][frame][0];
                        float m_im = mask[0][0][mask_bin][frame][1];
                        
                        float re = s_re * m_re - s_im * m_im;
                        float im = s_re * m_im + s_im * m_re;
                        
                        buffer[2 * bin] = re; buffer[2 * bin + 1] = im;
                        if (bin > 0) { int mirror = FFT_SIZE - bin; buffer[2 * mirror] = re; buffer[2 * mirror + 1] = -im; }
                    }
                    fft.complexInverse(buffer, true);
                    int base = frame * HOP_SIZE - pad;
                    for (int i = 0; i < FFT_SIZE; i++) { 
                        int target = base + i; 
                        if (target >= 0 && target < CHUNK_SAMPLES) { 
                            float w = WINDOW[i]; 
                            output[channel][target] += buffer[2 * i] * w; 
                            weight[channel][target] += w * w; 
                        } 
                    }
                }
            }
            for (int c = 0; c < CHANNELS; c++) for (int i = 0; i < CHUNK_SAMPLES; i++) if (weight[c][i] > 1e-8f) output[c][i] /= weight[c][i];
            requireFinite(output, "After iSTFT");
            return output;
        }
    }

    static float[][] runQualityChunk(File model, float[][] chunk) throws Exception {
        try (Separator separator = new Separator(model)) { return separator.run(chunk); }
    }

    private static final class Separator implements AutoCloseable {
        private final OrtEnvironment env = OrtEnvironment.getEnvironment();
        private final OrtSession session;
        private boolean operatorProfile;
        Separator(File model) throws Exception {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                CpuExecutionPolicy.Choice cpu = CpuExecutionPolicy.detect();
                options.setIntraOpNumThreads(cpu.threads);
                options.addConfigEntry("session.intra_op.allow_spinning", "0");
                options.addConfigEntry("session.inter_op.allow_spinning", "0");
                options.setMemoryPatternOptimization(false);
                options.setCPUArenaAllocator(false);
                PerformanceTrace trace = PerformanceTrace.current();
                if (trace != null) { options.enableProfiling(trace.operatorPrefix()); operatorProfile = true; }
                try (PerformanceTrace.Scope loading = PerformanceTrace.stage("model_session_load")) {
                    session = env.createSession(model.getAbsolutePath(), options);
                }
            }
            try {
                requireTensor(session.getInputInfo().get("stft_repr"), "stft_repr");
                requireTensor(session.getOutputInfo().get("mask"), "mask");
            } catch (Exception error) { session.close(); throw error; }
        }
        float[][] run(float[][] chunk) throws Exception {
            long startedMs = android.os.SystemClock.elapsedRealtime();
            PcmFile.checkCancelled();
            float[][][][][] features = stftInput(chunk);
            float[] flat = new float[2 * 1025 * FRAMES * 2];
            try (PerformanceTrace.Scope packing = PerformanceTrace.stage("tensor_pack")) {
                int p = 0;
                for (int c=0; c<2; c++) for(int f=0; f<1025; f++) for(int t=0; t<FRAMES; t++) {
                    flat[p++] = features[0][c][f][t][0];
                    flat[p++] = features[0][c][f][t][1];
                }
            }
            try (OnnxTensor input = createInput(flat);
                 OrtSession.Result result = runModel(input)) {
                PcmFile.checkCancelled();
                OnnxValue value = result.get("mask").orElseThrow(() -> new IllegalArgumentException("PolarFormer output is missing."));
                float[][][][][] mask;
                try (PerformanceTrace.Scope copy = PerformanceTrace.stage("output_tensor_copy")) { mask = (float[][][][][])((OnnxTensor)value).getValue(); }
                float[][] audio = istftOutput(mask, features);
                if (operatorProfile) {
                    try (PerformanceTrace.Scope report = PerformanceTrace.stage("operator_profile_write")) { PerformanceTrace.operators(session.endProfiling()); }
                    operatorProfile = false;
                }
                return audio;
            }
        }
        private OnnxTensor createInput(float[] flat) throws OrtException {
            try (PerformanceTrace.Scope copy = PerformanceTrace.stage("input_tensor_copy")) {
                return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{1, 2, 1025, FRAMES, 2});
            }
        }
        private OrtSession.Result runModel(OnnxTensor input) throws OrtException {
            try (PerformanceTrace.Scope inference = PerformanceTrace.stage("model_inference")) {
                return session.run(Collections.singletonMap("stft_repr", input));
            }
        }
        @Override public void close() throws OrtException { session.close(); }
    }

    static void processFile(File model, File source, File output, OfflineVideoProcessor.Progress progress) throws Exception {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("separation")) {
        try (PcmFile.Reader input = new PcmFile.Reader(source);
             PcmFile.Writer writer = new PcmFile.Writer(output);
             Separator separator = new Separator(model)) {
            if (input.frames == 0) throw new IllegalArgumentException("The file contains no usable audio.");
            int hop = OVERLAP_SAMPLES;
            long parts = (input.frames + hop - 1) / hop + 1; // +1 for the padding part
            PerformanceTrace.detail("inference_passes", parts);
            PerformanceTrace.detail("processed_model_frames", parts * CHUNK_SAMPLES);
            float[][] tail = new float[2][hop];
            for (long part = 0; part < parts; part++) {
                PcmFile.checkCancelled();
                int percent = 10 + (int)(80 * part / parts);
                progress.update("Removing music — " + percent + "% complete. You can use other apps.", percent);
                
                long chunkStart = part * hop - hop; // -hop, 0, hop, 2*hop...
                
                float[][] chunk = input.chunk(chunkStart);
                if (chunk == null) break;
                float[][] processed = separator.run(chunk);
                float[][] result = new float[2][hop];
                for (int c = 0; c < 2; c++) {
                    for (int i = 0; i < hop; i++) {
                        result[c][i] = tail[c][i] + processed[c][i];
                        tail[c][i] = processed[c][i + hop];
                    }
                }
                if (part > 0) { // Skip writing the negative padding
                    for (int i = 0; i < hop; i++) {
                        writer.frame(result[0][i], result[1][i]);
                    }
                }
            }
            for (int i = 0; i < hop; i++) {
                writer.frame(tail[0][i], tail[1][i]);
            }
        }
        }
    }
}
