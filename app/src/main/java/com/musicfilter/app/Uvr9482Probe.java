package com.musicfilter.app;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;

/** Measures the actual mobile UVR core once, before we spend time building video handling around it. */
final class Uvr9482Probe {
    private static final int FLOATS = 4 * 2048 * 256;
    private Uvr9482Probe() { }
    static String benchmark(File model) throws Exception {
        if (!model.isFile() || model.length() < 20_000_000L) return "UVR model is missing. The 30 MB model was not copied to the phone.";
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        float[] zeros = new float[FLOATS];
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            try (OrtSession session = environment.createSession(model.getAbsolutePath(), options)) {
                // Warm-up is intentionally excluded: a real long-video run loads the model only once.
                try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(zeros), new long[]{1, 4, 2048, 256});
                     OrtSession.Result ignored = session.run(Collections.singletonMap("input", input))) { }
                long start = System.nanoTime();
                try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(zeros), new long[]{1, 4, 2048, 256});
                     OrtSession.Result ignored = session.run(Collections.singletonMap("input", input))) { }
                double seconds = (System.nanoTime() - start) / 1_000_000_000d;
                String verdict = seconds <= 5 ? "PASS — fast enough to continue" : "FAIL — too slow for the 4-minute-under-1-minute target";
                return String.format(java.util.Locale.US, "UVR mobile benchmark: %.2f seconds per 5.9-second block. %s.", seconds, verdict);
            }
        }
    }
}
