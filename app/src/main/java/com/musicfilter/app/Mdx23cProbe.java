package com.musicfilter.app;

import java.io.File;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** First offline gate: prove that the exact exported model and its weights load on-device. */
final class Mdx23cProbe {
    private static final String MODEL = "MDX23C-8KFFT-InstVoc_HQ.onnx";
    private static final String WEIGHTS = MODEL + ".data";

    private Mdx23cProbe() { }

    static String verify(File directory) throws Exception {
        File model = new File(directory, MODEL);
        File weights = new File(directory, WEIGHTS);
        if (!model.isFile() || !weights.isFile()) {
            return "Offline model missing. Copy both MDX23C model files to MusicFilter/models/mdx23c first.";
        }
        long start = System.currentTimeMillis();
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession session = environment.createSession(model.getAbsolutePath(), options)) {
            long elapsed = System.currentTimeMillis() - start;
            return "Offline MDX23C loaded successfully in " + elapsed + " ms. Next: run the first audio block.";
        }
    }
}
