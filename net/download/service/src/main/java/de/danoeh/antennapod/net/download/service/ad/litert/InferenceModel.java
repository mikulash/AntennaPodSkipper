package de.danoeh.antennapod.net.download.service.ad.litert;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;

import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

/**
 * Singleton class that manages the LLM inference engine using LiteRT-LM 0.8.0.
 * Loading the model is expensive (~20-30 seconds), so we keep a single instance
 * alive to avoid reloading for each analysis request.
 * Uses the new .litertlm model format with Engine and Conversation APIs.
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
    private final File cacheDir;
    private Engine engine;
    private Conversation conversation;
    private String currentSystemMessage;

    /**
     * Private constructor - use getInstance() instead.
     */
    private InferenceModel(Context context, String modelId, @Nullable LlmModel modelConfig)
            throws ModelLoadFailException {
        this.context = context.getApplicationContext();
        this.modelId = modelId;
        this.modelConfig = modelConfig;
        this.cacheDir = context.getCacheDir();

        LiteRtLLMManager manager = new LiteRtLLMManager(context);
        File modelFile = manager.getModelPath(modelId);

        if (!modelFile.exists()) {
            throw new ModelLoadFailException("Model file not found: " + modelFile.getAbsolutePath());
        }

        // Clear stale cache
        manager.clearXnnpackCache(modelId);

        createEngine(modelFile);
        createConversation();
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
        Log.i(TAG, "Creating LiteRT-LM engine with model: " + modelId);

        Backend backend = getBackend();

        try {
            // EngineConfig(modelPath, backend, visionBackend, audioBackend, maxCacheSize, cacheDir)
            EngineConfig config = new EngineConfig(
                    modelFile.getAbsolutePath(),
                    backend,
                    null,  // visionBackend - not needed for text-only
                    null,  // audioBackend - not needed for text-only
                    null,  // maxNumTokens - use default
                    cacheDir.getAbsolutePath()
            );
            engine = new Engine(config);
            engine.initialize();
            Log.i(TAG, "LiteRT-LM engine created successfully with backend: " + backend);
        } catch (Exception e) {
            Log.e(TAG, "Engine creation failed with backend " + backend + ": " + e.getMessage(), e);

            // If GPU failed, try CPU fallback
            if (backend == Backend.GPU) {
                Log.i(TAG, "Retrying with CPU backend...");
                try {
                    EngineConfig cpuConfig = new EngineConfig(
                            modelFile.getAbsolutePath(),
                            Backend.CPU,
                            null,
                            null,
                            null,
                            cacheDir.getAbsolutePath()
                    );
                    engine = new Engine(cpuConfig);
                    engine.initialize();

                    // Update preference if manual model
                    if (modelConfig == null) {
                        LocalAiPreferences.setManualModelBackend(context, "CPU");
                    }
                    Log.i(TAG, "LiteRT-LM engine created successfully with CPU fallback");
                    return;
                } catch (Exception cpuError) {
                    Log.e(TAG, "CPU fallback also failed: " + cpuError.getMessage(), cpuError);
                    throw new ModelLoadFailException("Failed to load model: " + cpuError.getMessage());
                }
            }

            throw new ModelLoadFailException("Failed to load model: " + e.getMessage());
        }
    }

    private void createConversation() throws ModelLoadFailException {
        createConversation(null);
    }

    private void createConversation(@Nullable String systemMessage) throws ModelLoadFailException {
        if (engine == null) {
            throw new ModelLoadFailException("LiteRT-LM engine not initialized");
        }

        try {
            double temperature = modelConfig != null ? modelConfig.getTemperature() : 0.3;
            int topK = modelConfig != null ? modelConfig.getTopK() : 20;
            double topP = modelConfig != null ? modelConfig.getTopP() : 0.9;
            int seed = 42;  // Fixed seed for reproducibility

            // SamplerConfig(topK, topP, temperature, seed)
            SamplerConfig samplerConfig = new SamplerConfig(topK, topP, temperature, seed);

            // Create system message if provided
            Message sysMsg = systemMessage != null ? Message.Companion.of(systemMessage) : null;
            this.currentSystemMessage = systemMessage;

            // ConversationConfig(systemMessage, tools, samplerConfig)
            ConversationConfig convConfig = new ConversationConfig(
                    sysMsg,
                    java.util.Collections.emptyList(),  // tools - empty list (non-null required)
                    samplerConfig
            );
            conversation = engine.createConversation(convConfig);
            Log.d(TAG, "Conversation created with temp=" + temperature + ", topK=" + topK + ", topP=" + topP
                    + ", systemMessage=" + (systemMessage != null ? "yes (" + systemMessage.length() + " chars)" : "none"));
        } catch (Exception e) {
            Log.e(TAG, "Conversation creation failed: " + e.getMessage(), e);
            throw new ModelLoadFailException("Failed to create conversation: " + e.getMessage());
        }
    }

    /**
     * Reset the conversation (clears context) without reloading the model.
     * Preserves the current system message.
     */
    public void resetSession() throws ModelLoadFailException {
        resetSession(currentSystemMessage);
    }

    /**
     * Reset the conversation with a new system message.
     * @param systemMessage The system instruction for the model, or null for none.
     */
    public void resetSession(@Nullable String systemMessage) throws ModelLoadFailException {
        if (conversation != null) {
            try {
                conversation.close();
            } catch (Exception ignored) {
            }
        }
        createConversation(systemMessage);
    }

    /**
     * Generate a response synchronously.
     * The user message is sent directly - use resetSession(systemMessage) first to set instructions.
     */
    public String generateResponse(String userPrompt) throws Exception {
        if (conversation == null) {
            throw new IllegalStateException("Conversation not initialized");
        }
        Message userMessage = Message.Companion.of(userPrompt);
        Message response = conversation.sendMessage(userMessage);
        return response.toString();
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

    private Backend getBackend() {
        if (modelConfig != null) {
            switch (modelConfig.getPreferredBackend()) {
                case GPU:
                    return Backend.GPU;
                case CPU:
                default:
                    return Backend.CPU;
            }
        }

        // For manual imports, use preference
        String backendPref = LocalAiPreferences.getManualModelBackend(context);
        return "CPU".equalsIgnoreCase(backendPref)
                ? Backend.CPU
                : Backend.GPU;
    }

    /**
     * Close this instance and release resources.
     */
    public void close() {
        if (conversation != null) {
            try {
                conversation.close();
            } catch (Exception ignored) {
            }
            conversation = null;
        }
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {
            }
            engine = null;
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
