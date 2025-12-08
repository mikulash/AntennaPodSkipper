package de.danoeh.antennapod.net.download.service.llama;

import android.util.Log;

/**
 * Represents a loaded GGUF model.
 */
public class LlamaModel {
    private static final String TAG = "LlamaModel";
    private long nativeHandle = 0;

    LlamaModel() {
        // Package-private constructor
    }

    void load(String path, String prefix, String suffix, String[] antiPrompts) {
        if (nativeHandle != 0) {
            throw new IllegalStateException("Model already loaded");
        }

        if (prefix == null) prefix = "";
        if (suffix == null) suffix = "";
        if (antiPrompts == null) antiPrompts = new String[0];

        nativeHandle = nativeLoadModel(path, prefix, suffix, antiPrompts);
        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to load model");
        }
    }

    /**
     * Create a new generation session.
     *
     * @return LlamaGenerationSession instance
     */
    public LlamaGenerationSession createSession() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Model not loaded");
        }
        return new LlamaGenerationSession(nativeHandle);
    }

    /**
     * Get the size of the model in bytes.
     *
     * @return Model size in bytes
     */
    public long getModelSize() {
        if (nativeHandle == 0) {
            return 0;
        }
        return nativeGetModelSize(nativeHandle);
    }

    /**
     * Unload the model and free resources.
     */
    public void unloadModel() {
        if (nativeHandle != 0) {
            nativeUnloadModel(nativeHandle);
            nativeHandle = 0;
            Log.i(TAG, "Model unloaded");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            unloadModel();
        } finally {
            super.finalize();
        }
    }

    // Native methods
    private native long nativeLoadModel(String path, String prefix, String suffix, String[] antiPrompts);
    private native long nativeGetModelSize(long handle);
    private native void nativeUnloadModel(long handle);
}
