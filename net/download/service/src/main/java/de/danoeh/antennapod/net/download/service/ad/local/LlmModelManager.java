package de.danoeh.antennapod.net.download.service.ad.local;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages local LLM models for on-device inference.
 * Handles model discovery, download status, and file paths.
 */
public class LlmModelManager {
    private static final String TAG = "LlmModelManager";
    private static final String MODELS_DIR = "llm_models";

    // Available model configurations
    public static final String MODEL_QWEN3_0_6B = "qwen3-0.6b-instruct";
    public static final String MODEL_QWEN3_1_7B = "qwen3-1.7b-instruct";
    public static final String MODEL_GEMMA3_1B = "gemma-3-1b-it";
    public static final String MODEL_LLAMA_3_2_1B = "llama-3.2-1b-instruct";

    private final Context context;
    private final File modelsDir;
    private final Map<String, ModelInfo> modelInfoMap;

    public LlmModelManager(Context context) {
        this.context = context;
        this.modelsDir = new File(context.getFilesDir(), MODELS_DIR);
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        this.modelInfoMap = initModelInfo();
    }

    private Map<String, ModelInfo> initModelInfo() {
        Map<String, ModelInfo> map = new HashMap<>();
        for (ModelInfo info : buildModelInfos()) {
            map.put(info.id, info);
        }

        return map;
    }

    private static List<ModelInfo> buildModelInfos() {
        List<ModelInfo> models = new ArrayList<>();

        // Qwen 3
        models.add(new ModelInfo(
                MODEL_QWEN3_0_6B,
                "Qwen 3 0.6B Instruct",
                "Qwen3-0.6B-Q4_K_M.gguf",
                "https://huggingface.co/lm-kit/qwen-3-0.6b-instruct-gguf/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
                400_000_000L, // ~400MB
                1_200_000_000L // ~1.2GB RAM
        ));

        models.add(new ModelInfo(
                MODEL_QWEN3_1_7B,
                "Qwen 3 1.7B Instruct",
                "Qwen3-1.7B-Q4_K_M.gguf",
                "https://huggingface.co/lm-kit/qwen-3-1.7b-instruct-gguf/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                1_200_000_000L, // ~1.2GB
                2_500_000_000L  // ~2.5GB RAM
        ));

        // Gemma 3
        models.add(new ModelInfo(
                MODEL_GEMMA3_1B,
                "Gemma 3 1B Instruct",
                "google_gemma-3-1b-it-Q4_K_L.gguf",
                "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_L.gguf",
                900_000_000L, // ~900MB
                2_000_000_000L // ~2GB RAM
        ));

        // Llama 3.2
        models.add(new ModelInfo(
                MODEL_LLAMA_3_2_1B,
                "Llama 3.2 1B Instruct",
                "Llama-3.2-1B-Instruct-Q4_0.gguf",
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf",
                800_000_000L, // ~800MB
                1_800_000_000L // ~1.8GB RAM
        ));

        return models;
    }

    /**
     * Get a list of all available model IDs.
     */
    public List<String> getAvailableModelIds() {
        return new ArrayList<>(modelInfoMap.keySet());
    }

    /**
     * Get a list of all available models (static method for UI convenience).
     */
    public static List<ModelInfo> getAvailableModels() {
        return new ArrayList<>(buildModelInfos());
    }

    /**
     * Get model info for a specific model ID (static version for UI).
     */
    public static ModelInfo getModelInfo(String modelId) {
        for (ModelInfo info : buildModelInfos()) {
            if (info.id.equals(modelId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Get model info for a specific model ID (instance version).
     */
    public ModelInfo getModelInfoInstance(String modelId) {
        return modelInfoMap.get(modelId);
    }

    /**
     * Check if a model is downloaded.
     */
    public boolean isModelDownloaded(String modelId) {
        ModelInfo info = modelInfoMap.get(modelId);
        if (info == null) {
            Log.w(TAG, "Unknown model ID: " + modelId);
            return false;
        }
        File modelFile = new File(modelsDir, info.fileName);
        return modelFile.exists() && modelFile.length() > 0;
    }

    /**
     * Get the file path for a downloaded model.
     */
    public File getModelFile(String modelId) {
        ModelInfo info = modelInfoMap.get(modelId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown model ID: " + modelId);
        }
        return new File(modelsDir, info.fileName);
    }

    /**
     * Get the download URL for a model.
     */
    public String getDownloadUrl(String modelId) {
        ModelInfo info = modelInfoMap.get(modelId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown model ID: " + modelId);
        }
        return info.downloadUrl;
    }

    /**
     * Get the expected file size for a model.
     */
    public long getExpectedFileSize(String modelId) {
        ModelInfo info = modelInfoMap.get(modelId);
        if (info == null) {
            return 0;
        }
        return info.fileSizeBytes;
    }

    /**
     * Get the minimum RAM required to run a model.
     */
    public long getMinMemoryRequired(String modelId) {
        ModelInfo info = modelInfoMap.get(modelId);
        if (info == null) {
            return Long.MAX_VALUE;
        }
        return info.minRamBytes;
    }

    /**
     * Check if the device has enough memory to run a model.
     */
    public boolean hasEnoughMemory(String modelId) {
        long required = getMinMemoryRequired(modelId);
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long availableMemory = maxMemory - usedMemory;

        Log.d(TAG, "Memory check for " + modelId + ": required=" + (required / 1_000_000)
                + "MB, available=" + (availableMemory / 1_000_000) + "MB");

        return availableMemory >= required;
    }

    /**
     * Get the models directory.
     */
    public File getModelsDir() {
        return modelsDir;
    }

    /**
     * Delete a downloaded model.
     */
    public boolean deleteModel(String modelId) {
        File modelFile = getModelFile(modelId);
        if (modelFile.exists()) {
            return modelFile.delete();
        }
        return true;
    }

    /**
     * Download a model with progress callback.
     *
     * @param modelId The model ID to download
     * @param progressCallback Callback for progress updates (percent, bytesDownloaded, totalBytes)
     * @return true if download was successful
     * @throws Exception if download fails
     */
    public boolean downloadModel(String modelId, DownloadProgressCallback progressCallback) throws Exception {
        ModelInfo info = modelInfoMap.get(modelId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown model ID: " + modelId);
        }

        File targetFile = new File(modelsDir, info.fileName);
        File tempFile = new File(modelsDir, info.fileName + ".tmp");

        Log.i(TAG, "Starting download of " + modelId + " from " + info.downloadUrl);

        HttpURLConnection connection = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        try {
            URL url = new URL(info.downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP error: " + responseCode);
            }

            long totalBytes = connection.getContentLengthLong();
            if (totalBytes <= 0) {
                totalBytes = info.fileSizeBytes;
            }

            inputStream = new BufferedInputStream(connection.getInputStream());
            outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            long downloadedBytes = 0;
            int bytesRead;
            long lastProgressUpdate = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;

                // Update progress at most every 100ms
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate > 100) {
                    int percent = totalBytes > 0 ? (int) (100 * downloadedBytes / totalBytes) : 0;
                    if (progressCallback != null) {
                        progressCallback.onProgress(percent, downloadedBytes, totalBytes);
                    }
                    lastProgressUpdate = now;
                }
            }

            outputStream.close();
            outputStream = null;

            // Rename temp file to final name
            if (targetFile.exists()) {
                targetFile.delete();
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new Exception("Failed to rename downloaded file");
            }

            Log.i(TAG, "Download complete: " + targetFile.getAbsolutePath());
            return true;

        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {}
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
            // Clean up temp file on failure
            if (tempFile.exists() && !targetFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Callback interface for download progress.
     */
    public interface DownloadProgressCallback {
        void onProgress(int percent, long bytesDownloaded, long totalBytes);
    }

    /**
     * Model information holder.
     */
    public static class ModelInfo {
        public final String id;
        public final String name;  // Display name for UI
        public final String displayName;  // Alias for name
        public final String fileName;
        public final String downloadUrl;
        public final long fileSizeBytes;
        public final long size;  // Alias for fileSizeBytes (for UI compatibility)
        public final long minRamBytes;

        public ModelInfo(String id, String displayName, String fileName,
                        String downloadUrl, long fileSizeBytes, long minRamBytes) {
            this.id = id;
            this.name = displayName;
            this.displayName = displayName;
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
            this.fileSizeBytes = fileSizeBytes;
            this.size = fileSizeBytes;
            this.minRamBytes = minRamBytes;
        }
    }
}
