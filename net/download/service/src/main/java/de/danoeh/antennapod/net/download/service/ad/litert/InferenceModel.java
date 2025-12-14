package de.danoeh.antennapod.net.download.service.ad.litert;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions;
import com.google.mediapipe.tasks.genai.llminference.ProgressListener;

import java.io.File;

import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

/**
 * Singleton class that manages the LLM inference engine.
 * Loading the model is expensive (~20-30 seconds), so we keep a single instance
 * alive
 * to avoid reloading for each analysis request.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class InferenceModel {
    private static final String TAG = "InferenceModel";

    /** Default max tokens for model context. */
    public static final int MAX_TOKENS = 2048;

    /** Reduced max tokens for manual imports to prevent runaway generation. */
    public static final int MANUAL_MAX_TOKENS = 512;

    /** Offset to ensure model can always respond. */
    public static final int DECODE_TOKEN_OFFSET = 256;

    private static InferenceModel instance;
    private static LlmModel currentModel;
    private static String currentModelId;

    private final Context context;
    private final LlmModel modelConfig;
    private final String modelId;
    private LlmInference llmInference;
    private LlmInferenceSession llmSession;

    /**
     * Private constructor - use getInstance() instead.
     */
    private InferenceModel(Context context, String modelId, @Nullable LlmModel modelConfig)
            throws ModelLoadFailException {
        this.context = context.getApplicationContext();
        this.modelId = modelId;
        this.modelConfig = modelConfig;

        LiteRtLLMManager manager = new LiteRtLLMManager(context);
        File modelFile = manager.getModelPath(modelId);

        if (!modelFile.exists()) {
            throw new ModelLoadFailException("Model file not found: " + modelFile.getAbsolutePath());
        }

        // Clear stale cache
        manager.clearXnnpackCache(modelId);

        createEngine(modelFile);
        createSession();
    }

    /**
     * Get the singleton instance, creating it if necessary.
     * If the model has changed, the old instance is closed and a new one is
     * created.
     */
    public static synchronized InferenceModel getInstance(Context context) throws ModelLoadFailException {
        String modelId = LocalAiPreferences.getLocalAdAnalysisModel(context);
        LlmModel modelConfig = resolveModelConfig(context, modelId);

        // Check if we need to create a new instance
        if (instance == null || !modelId.equals(currentModelId)) {
            if (instance != null) {
                Log.i(TAG, "Model changed from " + currentModelId + " to " + modelId + ", recreating instance");
                instance.close();
            }

            Log.i(TAG, "Creating new InferenceModel instance for: " + modelId);
            instance = new InferenceModel(context, modelId, modelConfig);
            currentModelId = modelId;
            currentModel = modelConfig;
        }

        return instance;
    }

    /**
     * Force recreation of the instance, useful after model changes or errors.
     */
    public static synchronized InferenceModel resetInstance(Context context) throws ModelLoadFailException {
        if (instance != null) {
            instance.close();
            instance = null;
            currentModelId = null;
            currentModel = null;
        }
        return getInstance(context);
    }

    /**
     * Close the singleton instance and release resources.
     */
    public static synchronized void closeInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
            currentModelId = null;
            currentModel = null;
            Log.i(TAG, "Instance closed");
        }
    }

    /**
     * Check if an instance is currently loaded.
     */
    public static synchronized boolean isInstanceLoaded() {
        return instance != null;
    }

    /**
     * Get the currently loaded model config, or null if no instance.
     */
    @Nullable
    public static synchronized LlmModel getCurrentModel() {
        return currentModel;
    }

    /**
     * Resolve model config, detecting known models for manual imports.
     */
    private static LlmModel resolveModelConfig(Context context, String modelId) {
        LlmModel config = LlmModel.fromId(modelId);
        Log.d(TAG, "resolveModelConfig: " + modelId + " -> " + (config != null ? config.getDisplayName() : "null"));

        if (config == null && LocalAiPreferences.MANUAL_MODEL_ID.equals(modelId)) {
            // Try to detect from filename
            LiteRtLLMManager manager = new LiteRtLLMManager(context);
            File modelFile = manager.getModelPath(modelId);
            config = detectKnownModelFromPath(modelFile);
            if (config != null) {
                Log.i(TAG, "Auto-detected manual import as: " + config.getDisplayName());
            }
        }

        return config;
    }

    /**
     * Try to detect if a manually imported model matches a known model.
     */
    @Nullable
    private static LlmModel detectKnownModelFromPath(File modelFile) {
        if (modelFile == null || !modelFile.exists()) {
            return null;
        }

        String filename = modelFile.getName().toLowerCase();
        Log.d(TAG, "Attempting to auto-detect model from filename: " + filename);

        for (LlmModel model : LlmModel.values()) {
            if (model == LlmModel.MANUAL_IMPORT)
                continue;

            String knownFilename = model.getFilename().toLowerCase();
            if (filename.equals(knownFilename) ||
                    filename.replace("-", "").replace("_", "").equals(
                            knownFilename.replace("-", "").replace("_", ""))) {
                Log.i(TAG, "Matched manual import to known model: " + model.getDisplayName());
                return model;
            }
        }

        return null;
    }

    private void createEngine(File modelFile) throws ModelLoadFailException {
        Log.i(TAG, "Creating LLM engine with model: " + modelId);

        LlmInference.Backend backend = getBackend();
        int maxTokens = getMaxTokens();

        LlmInferenceOptions options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.getAbsolutePath())
                .setMaxTokens(maxTokens)
                .setPreferredBackend(backend)
                .build();

        try {
            llmInference = LlmInference.createFromOptions(context, options);
            Log.i(TAG, "LLM engine created successfully with backend: " + backend);
        } catch (Exception e) {
            Log.e(TAG, "Engine creation failed with backend " + backend + ": " + e.getMessage(), e);

            // If GPU failed, try CPU fallback
            if (backend == LlmInference.Backend.GPU) {
                Log.i(TAG, "Retrying with CPU backend...");
                try {
                    LlmInferenceOptions cpuOptions = options.toBuilder()
                            .setPreferredBackend(LlmInference.Backend.CPU)
                            .build();
                    llmInference = LlmInference.createFromOptions(context, cpuOptions);

                    // Update preference if manual model
                    if (modelConfig == null) {
                        LocalAiPreferences.setManualModelBackend(context, "CPU");
                    }
                    Log.i(TAG, "LLM engine created successfully with CPU fallback");
                    return;
                } catch (Exception cpuError) {
                    Log.e(TAG, "CPU fallback also failed: " + cpuError.getMessage(), cpuError);
                    throw new ModelLoadFailException("Failed to load model: " + cpuError.getMessage());
                }
            }

            throw new ModelLoadFailException("Failed to load model: " + e.getMessage());
        }
    }

    private void createSession() throws ModelLoadFailException {
        if (llmInference == null) {
            throw new ModelLoadFailException("LLM engine not initialized");
        }

        float temperature = modelConfig != null ? modelConfig.getTemperature() : 0.3f;
        int topK = modelConfig != null ? modelConfig.getTopK() : 20;
        float topP = modelConfig != null ? modelConfig.getTopP() : 0.9f;

        LlmInferenceSessionOptions sessionOptions = LlmInferenceSessionOptions.builder()
                .setTemperature(temperature)
                .setTopK(topK)
                .setTopP(topP)
                .build();

        try {
            llmSession = LlmInferenceSession.createFromOptions(llmInference, sessionOptions);
            Log.d(TAG, "Session created with temp=" + temperature + ", topK=" + topK + ", topP=" + topP);
        } catch (Exception e) {
            Log.e(TAG, "Session creation failed: " + e.getMessage(), e);
            throw new ModelLoadFailException("Failed to create session: " + e.getMessage());
        }
    }

    /**
     * Reset the session (clears context) without reloading the model.
     */
    public void resetSession() throws ModelLoadFailException {
        if (llmSession != null) {
            try {
                llmSession.close();
            } catch (Exception ignored) {
            }
        }
        createSession();
    }

    /**
     * Generate a response asynchronously.
     */
    public ListenableFuture<String> generateResponseAsync(String prompt, ProgressListener<String> progressListener) {
        llmSession.addQueryChunk(prompt);
        return llmSession.generateResponseAsync(progressListener);
    }

    /**
     * Format a prompt using the model's chat template.
     */
    public String formatPrompt(String userMessage) {
        if (modelConfig != null) {
            return modelConfig.formatPrompt(userMessage);
        }
        // Default to Gemma format
        return "<start_of_turn>user\n" + userMessage + "<end_of_turn>\n<start_of_turn>model\n";
    }

    /**
     * Get the model configuration.
     */
    @Nullable
    public LlmModel getModelConfig() {
        return modelConfig;
    }

    /**
     * Get the model ID.
     */
    public String getModelId() {
        return modelId;
    }

    private LlmInference.Backend getBackend() {
        if (modelConfig != null) {
            switch (modelConfig.getPreferredBackend()) {
                case GPU:
                    return LlmInference.Backend.GPU;
                case CPU:
                default:
                    return LlmInference.Backend.CPU;
            }
        }

        // For manual imports, use preference
        String backendPref = LocalAiPreferences.getManualModelBackend(context);
        return "CPU".equalsIgnoreCase(backendPref)
                ? LlmInference.Backend.CPU
                : LlmInference.Backend.GPU;
    }

    private int getMaxTokens() {
        if (modelConfig != null) {
            return modelConfig.getMaxTokens();
        }
        // For manual imports, use user-configured value from preferences
        return LocalAiPreferences.getManualModelMaxTokens(context);
    }

    /**
     * Close this instance and release resources.
     */
    public void close() {
        if (llmSession != null) {
            try {
                llmSession.close();
            } catch (Exception ignored) {
            }
            llmSession = null;
        }
        if (llmInference != null) {
            try {
                llmInference.close();
            } catch (Exception ignored) {
            }
            llmInference = null;
        }
        Log.i(TAG, "InferenceModel resources released");
    }

    /**
     * Exception thrown when model fails to load.
     */
    public static class ModelLoadFailException extends Exception {
        public ModelLoadFailException(String message) {
            super(message);
        }
    }
}
