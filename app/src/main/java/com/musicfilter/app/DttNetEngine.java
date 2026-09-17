package com.musicfilter.app;

import java.io.File;
import org.jtransforms.fft.FloatFFT_1D;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Collections;
import android.util.Log;

/**
 * Contract for the quality-first DTTNet separator.  The model is deliberately
 * kept separate from the rejected MDX paths while its exact spectral pipeline
 * is ported and verified.
 */
final class DttNetEngine {
    static final int SAMPLE_RATE = 44100;
    static final int CHANNELS = 2;
    static final int FFT_SIZE = 6144;
    static final int HOP_SIZE = 1024;
    static final int RETAINED_BINS = 2048;
    static final int FRAMES = 256;
    static final int CHUNK_SAMPLES = HOP_SIZE * (FRAMES - 1); // 261120
    static final int OVERLAP_SAMPLES = CHUNK_SAMPLES / 2;

    private static final float[] WINDOW = makeWindow();
    private static final ThreadLocal<FloatFFT_1D> FFT = ThreadLocal.withInitial(() -> new FloatFFT_1D(FFT_SIZE));
    private static float[] makeWindow() {
        float[] window = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) window[i] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / FFT_SIZE));
        return window;
    }
    private DttNetEngine() {}

    static File requireModel(File externalFiles) {
        File model = new File(externalFiles, BenchConfig.USE_FP16 ? "models/dttnet/dttnet_fp16_mixed.onnx" : "models/dttnet/dttnet_vocals_core_quality_fp32.onnx");
        if (!model.isFile() || model.length() == 0) {
            android.util.Log.e("MusicFilterBench", "Missing model! externalFiles=" + externalFiles.getAbsolutePath() + " expected=" + model.getAbsolutePath());
            throw new IllegalStateException("DTTNet model is missing from MusicFilter/models/dttnet.");
        }
        return model;
    }

    static void requireStereo44100(float[][] audio) {
        if (audio == null || audio.length != CHANNELS || audio[0] == null || audio[1] == null
                || audio[0].length != audio[1].length)
            throw new IllegalArgumentException("DTTNet requires matching stereo audio.");
    }

    static void requireFinite(float[][] audio, String stage) {
        requireStereo44100(audio);
        for (int c = 0; c < CHANNELS; c++) for (int i = 0; i < audio[c].length; i++)
            if (!Float.isFinite(audio[c][i]))
                throw new IllegalArgumentException(stage + ": nonfinite audio at channel " + c + ", sample " + i);
    }

    private static void requireSpectrum(float[][][][] spectrum, String stage) {
        if (spectrum == null || spectrum.length != 1 || spectrum[0] == null || spectrum[0].length != 4)
            throw new IllegalArgumentException(stage + ": expected [1,4,2048,256].");
        for (int c = 0; c < 4; c++) {
            if (spectrum[0][c] == null || spectrum[0][c].length != RETAINED_BINS)
                throw new IllegalArgumentException(stage + ": incorrect frequency dimension.");
            for (int f = 0; f < RETAINED_BINS; f++) {
                float[] row = spectrum[0][c][f];
                if (row == null || row.length != FRAMES)
                    throw new IllegalArgumentException(stage + ": incorrect time dimension.");
                for (int t = 0; t < FRAMES; t++) if (!Float.isFinite(row[t]))
                    throw new IllegalArgumentException(stage + ": nonfinite value at [0," + c + "," + f + "," + t + "].");
            }
        }
    }

    private static void requireTensor(NodeInfo node, String name) {
        if (node == null || !(node.getInfo() instanceof TensorInfo))
            throw new IllegalArgumentException("DTTNet is missing tensor " + name);
        TensorInfo info = (TensorInfo) node.getInfo();
        if (info.type != OnnxJavaType.FLOAT || !java.util.Arrays.equals(info.getShape(), new long[]{1,4,RETAINED_BINS,FRAMES}))
            throw new IllegalArgumentException("DTTNet " + name + " must be float32 [1,4,2048,256], got " + info);
    }

    /** Exact centered Hann STFT layout expected by the exported DTTNet core. */
    static float[][][][] stftInput(float[][] chunk) {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("stft")) {
        requireFinite(chunk, "Before STFT");
        if (chunk[0].length != CHUNK_SAMPLES) throw new IllegalArgumentException("DTTNet needs one complete quality chunk.");
        float[][][][] out = new float[1][4][RETAINED_BINS][FRAMES];
        FloatFFT_1D fft = FFT.get();
        float[] buffer = new float[FFT_SIZE * 2];
        int pad = FFT_SIZE / 2;
        for (int channel = 0; channel < CHANNELS; channel++) for (int frame = 0; frame < FRAMES; frame++) {
            java.util.Arrays.fill(buffer, 0f);
            int base = frame * HOP_SIZE - pad;
            for (int i = 0; i < FFT_SIZE; i++) {
                int source = base + i;
                // Match torch.stft(center=True, pad_mode="reflect").
                if (source < 0) source = -source;
                if (source >= CHUNK_SAMPLES) source = 2 * CHUNK_SAMPLES - 2 - source;
                buffer[i] = chunk[channel][source] * WINDOW[i];
            }
            fft.realForwardFull(buffer);
            for (int bin = 0; bin < RETAINED_BINS; bin++) { out[0][channel * 2][bin][frame] = buffer[2 * bin]; out[0][channel * 2 + 1][bin][frame] = buffer[2 * bin + 1]; }
        }
        requireSpectrum(out, "After STFT");
        return out;
    
        }
    }

    /** Inverse side of the exported-core contract; omitted high bins are zero padded. */
    static float[][] istftOutput(float[][][][] spectrum) {
        try (PerformanceTrace.Scope measured = PerformanceTrace.stage("istft_overlap_normalization")) {
        requireSpectrum(spectrum, "Before iSTFT");
        float[][] output = new float[CHANNELS][CHUNK_SAMPLES];
        float[][] weight = new float[CHANNELS][CHUNK_SAMPLES];
        FloatFFT_1D fft = FFT.get();
        float[] buffer = new float[FFT_SIZE * 2]; int pad = FFT_SIZE / 2;
        for (int channel = 0; channel < CHANNELS; channel++) for (int frame = 0; frame < FRAMES; frame++) {
            java.util.Arrays.fill(buffer, 0f);
            for (int bin = 0; bin < RETAINED_BINS; bin++) {
                float re = spectrum[0][channel * 2][bin][frame], im = spectrum[0][channel * 2 + 1][bin][frame];
                buffer[2 * bin] = re; buffer[2 * bin + 1] = im;
                if (bin > 0) { int mirror = FFT_SIZE - bin; buffer[2 * mirror] = re; buffer[2 * mirror + 1] = -im; }
            }
            fft.complexInverse(buffer, true);
            int base = frame * HOP_SIZE - pad;
            for (int i = 0; i < FFT_SIZE; i++) { int target = base + i; if (target >= 0 && target < CHUNK_SAMPLES) { float w = WINDOW[i]; output[channel][target] += buffer[2 * i] * w; weight[channel][target] += w * w; } }
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
                int threads = BenchConfig.THREADS > 0 ? BenchConfig.THREADS : cpu.threads;
                options.setIntraOpNumThreads(threads);
                PerformanceTrace.detail("intra_op_threads", threads);
                
                if (BenchConfig.EXECUTION_PROVIDER == 1) {
                    options.addXnnpack(java.util.Collections.emptyMap());
                    PerformanceTrace.detail("execution_provider", "XNNPACK");
                } else if (BenchConfig.EXECUTION_PROVIDER == 2) {
                    options.addNnapi(java.util.EnumSet.noneOf(ai.onnxruntime.providers.NNAPIFlags.class));
                    PerformanceTrace.detail("execution_provider", "NNAPI");
                } else {
                    PerformanceTrace.detail("execution_provider", "CPU");
                }
                PerformanceTrace.detail("thread_policy", cpu.reason);
                org.json.JSONObject capacityReport = new org.json.JSONObject();
                for (java.util.Map.Entry<Integer,Integer> entry : cpu.capacities.entrySet()) capacityReport.put(Integer.toString(entry.getKey()), entry.getValue());
                PerformanceTrace.detail("cpu_capacities", capacityReport);
                // Sleep between parallel tasks instead of spending the background CPU quota spinning.
                options.addConfigEntry("session.intra_op.allow_spinning", "0");
                options.addConfigEntry("session.inter_op.allow_spinning", "0");
                // Avoid retaining peak intermediate allocations across a long background job.
                options.setMemoryPatternOptimization(false);
                options.setCPUArenaAllocator(false);

            Log.i("MusicFilterBench", "=== Step 1: Production Configuration ===");
            Log.i("MusicFilterBench", "Model filename: " + model.getAbsolutePath());
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                java.io.FileInputStream fis = new java.io.FileInputStream(model);
                byte[] dataBytes = new byte[1024];
                int nread = 0;
                while ((nread = fis.read(dataBytes)) != -1) { md.update(dataBytes, 0, nread); }
                byte[] mdbytes = md.digest();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mdbytes.length; i++) { sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1)); }
                Log.i("MusicFilterBench", "Model SHA256: " + sb.toString());
                Log.i("MusicFilterBench", "Model size: " + model.length() + " bytes");
            } catch (Exception e) {}
            Log.i("MusicFilterBench", "ORT version: " + ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion());
            Log.i("MusicFilterBench", "FP16 mode: " + BenchConfig.USE_FP16);
            Log.i("MusicFilterBench", "Execution provider: " + BenchConfig.EXECUTION_PROVIDER);
            Log.i("MusicFilterBench", "Intra-op threads: " + Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
            Log.i("MusicFilterBench", "Inter-op threads: 1");
            Log.i("MusicFilterBench", "Overlap ratio: " + BenchConfig.OVERLAP_RATIO);
            Log.i("MusicFilterBench", "Sample rate: 44100");

                PerformanceTrace trace = PerformanceTrace.current();
                if (trace != null) { options.enableProfiling(trace.operatorPrefix()); operatorProfile = true; }
                try (PerformanceTrace.Scope loading = PerformanceTrace.stage("model_session_load")) {
                    session = env.createSession(model.getAbsolutePath(), options);
                }
            }
            try {
                requireTensor(session.getInputInfo().get("input"), "input");
                requireTensor(session.getOutputInfo().get("output"), "output");
Log.i("MusicFilterBench", "Input shape: [1, 4, " + RETAINED_BINS + ", " + FRAMES + "]");
            } catch (Exception error) { session.close(); throw error; }
        }
        float[][] run(float[][] chunk) throws Exception {
            long wallT0 = android.os.SystemClock.elapsedRealtimeNanos();
            long cpuT0 = android.os.SystemClock.currentThreadTimeMillis();
            long startedMs = android.os.SystemClock.elapsedRealtime();
            Log.i("MusicFilterDTT", "Chunk start tid=" + android.os.Process.myTid());
            PcmFile.checkCancelled();
            float[][][][] features = stftInput(chunk);
            long wallT1 = android.os.SystemClock.elapsedRealtimeNanos();
            Log.i("MusicFilterBench", "Chunk preprocessing (STFT) wall_ms=" + (wallT1-wallT0)/1000000);
            float[] flat;
            try (PerformanceTrace.Scope packing = PerformanceTrace.stage("tensor_pack")) {
            flat = new float[4 * RETAINED_BINS * FRAMES]; int p = 0;
            for (int c=0;c<4;c++) for(int f=0;f<RETAINED_BINS;f++) for(int t=0;t<FRAMES;t++) flat[p++] = features[0][c][f][t];
            }
            long wallT2 = android.os.SystemClock.elapsedRealtimeNanos();
            Log.i("MusicFilterBench", "Chunk tensor_pack wall_ms=" + (wallT2-wallT1)/1000000);
            Log.i("MusicFilterDTT", "STFT done ms=" + (android.os.SystemClock.elapsedRealtime() - startedMs));
            try (OnnxTensor input = createInput(flat);
                 OrtSession.Result result = runModel(input)) {
                PcmFile.checkCancelled();
                Log.i("MusicFilterDTT", "Model done ms=" + (android.os.SystemClock.elapsedRealtime() - startedMs));
                OnnxValue value = result.get("output").orElseThrow(() -> new IllegalArgumentException("DTTNet output is missing."));
                float[][][][] spectrum;
                long t3 = android.os.SystemClock.elapsedRealtimeNanos();
                try (PerformanceTrace.Scope copy = PerformanceTrace.stage("output_tensor_copy")) { spectrum = (float[][][][])((OnnxTensor)value).getValue(); }
                Log.i("MusicFilterBench", "Chunk output_tensor_copy wall_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - t3)/1000000);
                long t4 = android.os.SystemClock.elapsedRealtimeNanos();
                float[][] audio = istftOutput(spectrum);
                Log.i("MusicFilterBench", "Chunk istft wall_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - t4)/1000000);
                long wallTEnd = android.os.SystemClock.elapsedRealtimeNanos();
                long cpuTEnd = android.os.SystemClock.currentThreadTimeMillis();
                Log.i("MusicFilterBench", "Chunk total_wall_ms=" + (wallTEnd - wallT0)/1000000 + " chunk_total_cpu_ms=" + (cpuTEnd - cpuT0));
                if (operatorProfile) {
                    try (PerformanceTrace.Scope report = PerformanceTrace.stage("operator_profile_write")) { PerformanceTrace.operators(session.endProfiling()); }
                    operatorProfile = false;
                }
                Log.i("MusicFilterDTT", "DTT chunk output peak=" + maxAbs(audio) + " totalMs=" + (android.os.SystemClock.elapsedRealtime() - startedMs));
                return audio;
            }
        }
        private OnnxTensor createInput(float[] flat) throws OrtException {
            long t0 = android.os.SystemClock.elapsedRealtimeNanos();
            try (PerformanceTrace.Scope copy = PerformanceTrace.stage("input_tensor_copy")) {
                OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{1,4,RETAINED_BINS,FRAMES});
                Log.i("MusicFilterBench", "Chunk createInput wall_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - t0)/1000000);
                return t;
            }
        }
        private OrtSession.Result runModel(OnnxTensor input) throws OrtException {
            long wallStart = android.os.SystemClock.elapsedRealtimeNanos();
            long cpuStart = android.os.SystemClock.currentThreadTimeMillis();
            try (PerformanceTrace.Scope inference = PerformanceTrace.stage("model_inference")) {
                OrtSession.Result res = session.run(Collections.singletonMap("input", input));
                long wallEnd = android.os.SystemClock.elapsedRealtimeNanos();
                long cpuEnd = android.os.SystemClock.currentThreadTimeMillis();
                Log.i("MusicFilterBench", "pass_wall_ms=" + (wallEnd - wallStart)/1000000 + " pass_cpu_ms=" + (cpuEnd - cpuStart));
                return res;
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
                        int overlap = (int)(CHUNK_SAMPLES * BenchConfig.OVERLAP_RATIO);
            int stride = CHUNK_SAMPLES - overlap;
            if (stride <= 0) stride = CHUNK_SAMPLES;
            long parts = Math.max(1, (input.frames - overlap) / stride + 1);
            PerformanceTrace.detail("inference_passes", parts);
            PerformanceTrace.detail("processed_model_frames", parts * CHUNK_SAMPLES);
            float[][] tail = new float[2][overlap];
            for (long part = 0; part < parts; part++) {
                PcmFile.checkCancelled();
                int percent = 10 + (int)(80 * part / parts);
                progress.update("Removing music — " + percent + "% complete. You can use other apps.", percent);
                long chunkStarted = android.os.SystemClock.elapsedRealtime();
                float[][] clean = separator.run(input.chunk(part * stride));
                PerformanceTrace.chunk(part, android.os.SystemClock.elapsedRealtime() - chunkStarted);
                long overlapT0 = android.os.SystemClock.elapsedRealtimeNanos();
try (PerformanceTrace.Scope join = PerformanceTrace.stage("chunk_overlap_add")) {
                if (parts == 1 || overlap == 0) {
                    for (int i = 0; i < CHUNK_SAMPLES && writer.frames < input.frames; i++) writer.frame(clean[0][i], clean[1][i]);
                } else if (part == 0) {
                    for (int i = 0; i < stride && writer.frames < input.frames; i++) writer.frame(clean[0][i], clean[1][i]);
                } else {
                    for (int i = 0; i < overlap && writer.frames < input.frames; i++) {
                        double oldWeight = (double)(overlap - 1 - i) / overlap, newWeight = (double)i / overlap;
                        writer.frame((float)(tail[0][i] * oldWeight + clean[0][i] * newWeight),
                                (float)(tail[1][i] * oldWeight + clean[1][i] * newWeight));
                    }
                    for (int i = overlap; i < stride && writer.frames < input.frames; i++) {
                        writer.frame(clean[0][i], clean[1][i]);
                    }
                }
                if (overlap > 0) {
                    for (int c = 0; c < 2; c++) System.arraycopy(clean[c], stride, tail[c], 0, overlap);
                }
                if (parts > 1 && part == parts - 1 && overlap > 0 && writer.frames < input.frames) {
                    for (int i = 0; i < overlap && writer.frames < input.frames; i++) writer.frame(tail[0][i], tail[1][i]);
                }
            }
                Log.i("MusicFilterBench", "Overlap_add wall_ms=" + (android.os.SystemClock.elapsedRealtimeNanos() - overlapT0)/1000000);
            }
            // End of processFile logic replacement
            if (writer.frames != input.frames) throw new IllegalStateException("Audio length changed during separation.");
        }
    
        }
    }

    static float maxAbs(float[][] data) { float max = 0; for (float[] row : data) for (float v : row) max = Math.max(max, Math.abs(v)); return max; }

    static float[][] process(File model, float[][] source, OfflineVideoProcessor.Progress progress) throws Exception {
        requireFinite(source, "Before separation"); int length = source[0].length, hop = OVERLAP_SAMPLES;
        float[][] sum = new float[2][length];
        if (length == 0) return sum;
        // Match the reference's 50% chunk schedule, with one padded chunk for short audio.
        int parts = Math.max(1, length / hop);
        for (int part = 0; part < parts; part++) {
            int start = part * hop;
            float[][] chunk = new float[2][CHUNK_SAMPLES]; int count = Math.min(CHUNK_SAMPLES, length - start);
            System.arraycopy(source[0], start, chunk[0], 0, count); System.arraycopy(source[1], start, chunk[1], 0, count);
            progress.report("DTTNet removing music locally — part " + (part + 1) + " of " + parts + "…"); float[][] clean = runQualityChunk(model, chunk);
            for (int i = 0; i < count; i++) {
                double w = 1.0;
                if (part > 0 && i < hop) w = (double)i / hop;
                if (part < parts - 1 && i >= hop) w = (double)(CHUNK_SAMPLES - 1 - i) / hop;
                for (int c = 0; c < CHANNELS; c++) sum[c][start+i] += (float)(clean[c][i] * w);
            }
        }
        requireFinite(sum, "After chunk joining");
        return sum;
    }
}
