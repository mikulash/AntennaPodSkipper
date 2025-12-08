package de.danoeh.antennapod.net.download.service.llama;

import android.util.Log;

/**
 * Main entry point for llama.cpp JNI bindings.
 * This is a singleton that auto-initializes on first use.
 */
public class LlamaCpp {
    private static final String TAG = "LlamaCpp";
    private static LlamaCpp instance;
    private boolean initialized = false;

    static {
        System.loadLibrary("llamacpp");
    }

    private LlamaCpp() {
        // Private constructor for singleton - auto-initialize
        try {
            init();
            initialized = true;
            Log.i(TAG, "llama.cpp initialized: " + systemInfo());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize llama.cpp", e);
        }
    }

    /**
     * Get the singleton instance (auto-initializes on first call).
     */
    public static synchronized LlamaCpp getInstance() {
        if (instance == null) {
            instance = new LlamaCpp();
        }
        return instance;
    }

    /**
     * Initialize llama.cpp backend.
     * Called automatically by the constructor.
     *
     * @return 0 on success
     */
    private native int init();

    /**
     * Get system information from llama.cpp.
     *
     * @return System info string
     */
    public native String systemInfo();

    /**
     * Load a GGUF model from file.
     *
     * @param path Path to the GGUF model file
     * @return LlamaModel instance
     */
    public LlamaModel loadModel(String path) {
        return loadModel(path, "", "", new String[0], null);
    }

    /**
     * Load a GGUF model from file with chat template parameters.
     *
     * @param path        Path to the GGUF model file
     * @param prefix      Prefix to add before each message (e.g., "<|im_start|>user\n")
     * @param suffix      Suffix to add after each message (e.g., "<|im_end|>\n")
     * @param antiPrompts Array of stop strings
     * @param progressCallback Progress callback (optional)
     * @return LlamaModel instance
     */
    public LlamaModel loadModel(String path, String prefix, String suffix,
                                String[] antiPrompts, ProgressCallback progressCallback) {
        if (!initialized) {
            init();
            initialized = true;
        }

        if (progressCallback != null) {
            progressCallback.onProgress(0.0f);
        }

        LlamaModel model = new LlamaModel();
        model.load(path, prefix, suffix, antiPrompts);

        if (progressCallback != null) {
            progressCallback.onProgress(1.0f);
        }

        Log.i(TAG, "Model loaded from: " + path);
        return model;
    }

    /**
     * Callback interface for model loading progress.
     */
    public interface ProgressCallback {
        void onProgress(float progress);
    }
}
