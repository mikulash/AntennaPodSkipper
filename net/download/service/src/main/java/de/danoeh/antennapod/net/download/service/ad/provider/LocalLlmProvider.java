package de.danoeh.antennapod.net.download.service.ad.provider;

import android.util.Log;

import java.io.Closeable;
import java.io.File;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;

/**
 * Provider for local LLM inference using llama.cpp.
 * Wraps the java-llama.cpp library for on-device text generation.
 */
public class LocalLlmProvider implements Closeable {
    private static final String TAG = "LocalLlmProvider";

    // Default inference parameters optimized for ad analysis
    private static final int DEFAULT_N_PREDICT = 512;  // Max tokens to generate
    private static final float DEFAULT_TEMPERATURE = 0.1f;  // Low temp for deterministic output
    private static final int DEFAULT_TOP_K = 40;
    private static final float DEFAULT_TOP_P = 0.9f;
    private static final int DEFAULT_CTX_SIZE = 2048;  // Context window
    private static final int DEFAULT_THREADS = 4;

    private final LlamaModel model;
    private final String modelPath;

    /**
     * Create a LocalLlmProvider with a model file.
     *
     * @param modelFile The GGUF model file to load
     * @throws RuntimeException if model loading fails
     */
    public LocalLlmProvider(File modelFile) {
        if (modelFile == null || !modelFile.exists()) {
            throw new IllegalArgumentException("Model file does not exist: " + modelFile);
        }

        this.modelPath = modelFile.getAbsolutePath();
        Log.i(TAG, "Loading LLM model from: " + modelPath);

        try {
            // Configure model parameters for mobile inference
            ModelParameters params = new ModelParameters()
                    .setModel(modelPath)
                    .setCtxSize(DEFAULT_CTX_SIZE)
                    .setThreads(DEFAULT_THREADS)
                    .setThreadsBatch(DEFAULT_THREADS)
                    .disableMmap()  // More reliable on Android
                    .setGpuLayers(0);  // CPU only for compatibility

            this.model = new LlamaModel(params);
            Log.i(TAG, "LLM model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load LLM model: " + e.getMessage(), e);
            throw new RuntimeException("Failed to load LLM model: " + e.getMessage(), e);
        }
    }

    /**
     * Analyze text using the local LLM.
     *
     * @param prompt The prompt to send to the model
     * @return The model's response
     * @throws Exception if inference fails
     */
    public String analyze(String prompt) throws Exception {
        if (model == null) {
            throw new IllegalStateException("Model not loaded");
        }

        Log.d(TAG, "Starting analysis with prompt length: " + prompt.length());

        try {
            InferenceParameters inferParams = new InferenceParameters(prompt)
                    .setNPredict(DEFAULT_N_PREDICT)
                    .setTemperature(DEFAULT_TEMPERATURE)
                    .setTopK(DEFAULT_TOP_K)
                    .setTopP(DEFAULT_TOP_P)
                    .setRepeatPenalty(1.1f);

            String result = model.complete(inferParams);
            Log.d(TAG, "Analysis complete, response length: " + result.length());

            return result;
        } catch (Exception e) {
            Log.e(TAG, "LLM inference failed: " + e.getMessage(), e);
            throw new Exception("LLM inference failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate text with custom parameters.
     *
     * @param prompt The prompt
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0-2.0)
     * @return Generated text
     */
    public String generate(String prompt, int maxTokens, float temperature) throws Exception {
        if (model == null) {
            throw new IllegalStateException("Model not loaded");
        }

        InferenceParameters inferParams = new InferenceParameters(prompt)
                .setNPredict(maxTokens)
                .setTemperature(temperature)
                .setTopK(DEFAULT_TOP_K)
                .setTopP(DEFAULT_TOP_P);

        return model.complete(inferParams);
    }

    /**
     * Check if the model is loaded and ready.
     */
    public boolean isReady() {
        return model != null;
    }

    /**
     * Get the path to the loaded model.
     */
    public String getModelPath() {
        return modelPath;
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                Log.i(TAG, "Closing LLM model");
                model.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing model: " + e.getMessage(), e);
            }
        }
    }
}
