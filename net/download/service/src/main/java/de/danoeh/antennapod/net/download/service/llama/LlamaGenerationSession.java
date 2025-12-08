package de.danoeh.antennapod.net.download.service.llama;

import android.util.Log;

/**
 * Represents a text generation session with a loaded model.
 */
public class LlamaGenerationSession {
    private static final String TAG = "LlamaGenerationSession";
    private long nativeHandle = 0;

    LlamaGenerationSession(long modelHandle) {
        nativeHandle = nativeCreateSession(modelHandle);
        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create session");
        }
    }

    /**
     * Add a message to the session context.
     *
     * @param message The message text to add
     */
    public void addMessage(String message) {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Session destroyed");
        }
        nativeAddMessage(nativeHandle, message);
    }

    /**
     * Generate the next token(s).
     * Call this in a loop until it returns non-zero.
     *
     * @param callback Callback to receive generated tokens
     * @return 0 to continue, 1 for end-of-generation, -1 for error
     */
    public int generate(GenerationCallback callback) {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Session destroyed");
        }
        return nativeGenerate(nativeHandle, new InternalCallback(callback));
    }

    /**
     * Get generation report (performance metrics).
     *
     * @return Report string
     */
    public String getReport() {
        if (nativeHandle == 0) {
            return "";
        }
        return nativeGetReport(nativeHandle);
    }

    /**
     * Print report to logcat.
     */
    public void printReport() {
        String report = getReport();
        if (!report.isEmpty()) {
            Log.i(TAG, "Generation report:\n" + report);
        }
    }

    /**
     * Destroy the session and free resources.
     */
    public void destroy() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
            Log.i(TAG, "Session destroyed");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    // Native methods
    private native long nativeCreateSession(long modelHandle);
    private native void nativeAddMessage(long sessionHandle, String message);
    private native int nativeGenerate(long sessionHandle, InternalCallback callback);
    private native String nativeGetReport(long sessionHandle);
    private native void nativeDestroy(long sessionHandle);

    /**
     * User-facing callback interface for receiving generated tokens.
     */
    public interface GenerationCallback {
        /**
         * Called when new tokens are generated.
         *
         * @param token The generated token as a String
         */
        void onToken(String token);
    }

    /**
     * Internal callback that converts bytes to String for the user callback.
     */
    private static class InternalCallback {
        private final GenerationCallback userCallback;

        InternalCallback(GenerationCallback userCallback) {
            this.userCallback = userCallback;
        }

        void onNewTokens(byte[] tokens) {
            String text = new String(tokens, java.nio.charset.StandardCharsets.UTF_8);
            userCallback.onToken(text);
        }
    }
}
