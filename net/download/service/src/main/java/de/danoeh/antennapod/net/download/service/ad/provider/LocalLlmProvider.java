package de.danoeh.antennapod.net.download.service.ad.provider;

import android.util.Log;

import java.io.Closeable;
import java.io.File;

import de.danoeh.antennapod.net.download.service.llama.LlamaCpp;
import de.danoeh.antennapod.net.download.service.llama.LlamaGenerationSession;
import de.danoeh.antennapod.net.download.service.llama.LlamaModel;

/**
 * Provider for local LLM inference using llama.cpp.
 * Wraps the llama.cpp JNI wrapper for on-device text generation.
 */
public class LocalLlmProvider implements Closeable {
    private static final String TAG = "LocalLlmProvider";

    private final String modelPath;
    private LlamaModel model;
    private LlamaGenerationSession session;

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
            LlamaCpp llamaCpp = LlamaCpp.getInstance();

            // Load model with default parameters
            this.model = llamaCpp.loadModel(
                    modelPath,
                    "", // prefix
                    "", // suffix
                    new String[0], // antiPrompts
                    new LlamaCpp.ProgressCallback() {
                        @Override
                        public void onProgress(float progress) {
                            Log.d(TAG, "Model loading progress: " + (int)(progress * 100) + "%");
                        }
                    }
            );

            // Create session
            this.session = model.createSession();

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
        if (model == null || session == null) {
            throw new IllegalStateException("Model not loaded");
        }

        Log.d(TAG, "Starting analysis with prompt length: " + prompt.length());

        try {
            session.addMessage(prompt);

            final StringBuilder response = new StringBuilder();
            int tokenCount = 0;

            // Stream tokens until end of generation
            while (session.generate(new LlamaGenerationSession.GenerationCallback() {
                @Override
                public void onToken(String token) {
                    response.append(token);
                    Log.i(TAG, "Generated token: " + token);
                }
            }) == 0) {
                tokenCount++;
            }

            String result = response.toString();
            Log.d(TAG, "Analysis complete, response length: " + result.length() + ", tokens: " + tokenCount);

            return result;
        } catch (Exception e) {
            Log.e(TAG, "LLM inference failed: " + e.getMessage(), e);
            throw new Exception("LLM inference failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate text with custom parameters.
     * Note: Custom parameters not yet fully supported, uses default greedy sampling.
     *
     * @param prompt The prompt
     * @param maxTokens Maximum tokens to generate (not enforced yet)
     * @param temperature Sampling temperature (not used yet)
     * @return Generated text
     */
    public String generate(String prompt, int maxTokens, float temperature) throws Exception {
        Log.w(TAG, "Custom parameters (maxTokens, temperature) not yet fully supported, using defaults");
        return analyze(prompt);
    }

    /**
     * Check if the model is loaded and ready.
     */
    public boolean isReady() {
        return model != null && session != null;
    }

    /**
     * Get the path to the loaded model.
     */
    public String getModelPath() {
        return modelPath;
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                Log.i(TAG, "Closing LLM session");
                session.destroy();
                session = null;
            } catch (Exception e) {
                Log.w(TAG, "Error closing session: " + e.getMessage(), e);
            }
        }

        if (model != null) {
            try {
                Log.i(TAG, "Unloading LLM model");
                model.unloadModel();
                model = null;
            } catch (Exception e) {
                Log.w(TAG, "Error unloading model: " + e.getMessage(), e);
            }
        }
    }
}
