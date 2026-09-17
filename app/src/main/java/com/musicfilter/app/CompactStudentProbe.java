package com.musicfilter.app;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Locale;

/** Isolated architecture gate: asks ONNX Runtime to use NNAPI for the student graph. */
final class CompactStudentProbe {
    private static final int FLOATS = 1 * 8 * 96 * 256;
    private CompactStudentProbe() { }
    static String benchmark(File model) throws Exception {
        if (!model.isFile() || model.length() < 20_000_000L) {
            return "Compact student model unavailable: path=" + model.getAbsolutePath()
                    + ", exists=" + model.exists() + ", file=" + model.isFile()
                    + ", bytes=" + model.length();
        }
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        float[] zeros = new float[FLOATS];
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.addNnapi();
            try (OrtSession session = environment.createSession(model.getAbsolutePath(), options)) {
                try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(zeros), new long[]{1, 8, 96, 256});
                     OrtSession.Result ignored = session.run(Collections.singletonMap("log_magnitude_bands", input))) { }
                long start = System.nanoTime();
                try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(zeros), new long[]{1, 8, 96, 256});
                     OrtSession.Result ignored = session.run(Collections.singletonMap("log_magnitude_bands", input))) { }
                double seconds = (System.nanoTime() - start) / 1_000_000_000d;
                return String.format(Locale.US, "Compact student NNAPI test: %.2f seconds per 2.0-second block. Check the device log for delegated-node count.", seconds);
            }
        }
    }
}
