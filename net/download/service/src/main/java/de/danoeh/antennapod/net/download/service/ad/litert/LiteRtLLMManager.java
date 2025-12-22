package de.danoeh.antennapod.net.download.service.ad.litert;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager.DownloadProgressListener;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

/**
 * Manages download and storage of LiteRT-LM models in .litertlm format.
 * Uses LiteRT-LM 0.8.1+ with LiteRT 2.1.0+.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class LiteRtLLMManager {
    private static final String TAG = "LiteRtLLMManager";

    private final Context context;

    public LiteRtLLMManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getModelDirectory() {
        File dir = new File(context.getFilesDir(), "litert_models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getModelPath(String modelId) {
        if (LocalAiPreferences.MANUAL_MODEL_ID.equals(modelId)) {
            String storedPath = LocalAiPreferences.getManualModelPath(context);
            if (storedPath != null) {
                File storedFile = new File(storedPath);
                if (storedFile.exists() && storedFile.length() > 0) {
                    return storedFile;
                }
            }
        }
        LlmModel model = LlmModel.fromId(modelId);
        if (model != null) {
            return new File(getModelDirectory(), model.getFilename());
        }
        // Fallback for unknown models (e.g., manual imports)
        return new File(getModelDirectory(), modelId + ".litertlm");
    }

    public File getModelPath(LlmModel model) {
        return new File(getModelDirectory(), model.getFilename());
    }

    public boolean isModelDownloaded(String modelId) {
        File file = getModelPath(modelId);
        return file.exists() && file.length() > 0;
    }

    public boolean isModelDownloaded(LlmModel model) {
        File file = getModelPath(model);
        return file.exists() && file.length() > 0;
    }

    /**
     * Clears any cached XNNPack weight cache for the given model id.
     * Corrupted cache files can trigger native crashes when loading models.
     */
    public void clearXnnpackCache(String modelId) {
        File cacheDir = context.getCacheDir();
        File cacheFile = new File(cacheDir, modelId + ".xnnpack_cache");
        File lockFile = new File(cacheDir, modelId + ".xnnpack_cache.lock");
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
        if (lockFile.exists()) {
            lockFile.delete();
        }
    }

    public void deleteModel(String modelId) {
        File file = getModelPath(modelId);
        if (file.exists()) {
            file.delete();
            Log.i(TAG, "Deleted model: " + modelId);
        }
    }

    public void deleteModel(LlmModel model) {
        File file = getModelPath(model);
        if (file.exists()) {
            file.delete();
            Log.i(TAG, "Deleted model: " + model.getId());
        }
    }

    /**
     * Deletes all downloaded LLM model files.
     * 
     * @return The number of deleted models.
     */
    public int deleteAllModels() {
        File modelDir = getModelDirectory();
        int deletedCount = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.delete()) {
                    Log.i(TAG, "Deleted model file: " + file.getName());
                    deletedCount++;
                }
            }
        }
        Log.i(TAG, "Deleted " + deletedCount + " LLM model(s)");
        return deletedCount;
    }

    /**
     * Deletes all downloaded models except the one specified to keep.
     *
     * @param keepModelId model id to keep (may be null)
     * @return number of deleted models
     */
    public int deleteAllModelsExcept(@Nullable String keepModelId) {
        File modelDir = getModelDirectory();
        int deletedCount = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                if (keepModelId != null && file.getName().startsWith(keepModelId)) {
                    continue;
                }
                if (file.delete()) {
                    Log.i(TAG, "Deleted model file: " + file.getName());
                    deletedCount++;
                }
            }
        }
        return deletedCount;
    }

    /**
     * Gets the number of downloaded LLM models.
     * 
     * @return Count of downloaded models.
     */
    public int getDownloadedModelsCount() {
        File modelDir = getModelDirectory();
        int count = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Gets the total size of all downloaded LLM models in bytes.
     * 
     * @return Total size in bytes.
     */
    public long getDownloadedModelsSize() {
        File modelDir = getModelDirectory();
        long totalSize = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }

    public boolean downloadModel(String modelId, DownloadProgressListener listener) throws IOException {
        LlmModel model = LlmModel.fromId(modelId);
        if (model == null) {
            throw new IOException("Unknown model: " + modelId);
        }
        return downloadModel(model, listener, null);
    }

    public boolean downloadModel(LlmModel model, DownloadProgressListener listener, String authToken)
            throws IOException {
        if (model.getUrl() == null) {
            throw new IOException("Model " + model.getId() + " does not support download (manual import only)");
        }

        File outputFile = getModelPath(model);
        Log.i(TAG, "Downloading LiteRT model: " + model.getId() + " from " + model.getUrl());

        return downloadFile(model.getUrl(), outputFile, listener, model.needsAuth() ? authToken : null);
    }

    // specific download logic (simplified version of what's in
    // LocalTranscriptionManager)
    private boolean downloadFile(String urlString, File outputFile, DownloadProgressListener listener, String authToken)
            throws IOException {
        File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");

            // Add HuggingFace authentication if required
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                String newUrl = connection.getHeaderField("Location");
                connection.disconnect();
                connection = (HttpURLConnection) new URL(newUrl).openConnection();
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");
                if (authToken != null && !authToken.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + authToken);
                }
                responseCode = connection.getResponseCode();
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Failed download, response: " + responseCode + " for URL: " + urlString);
                return false;
            }

            long contentLength = connection.getContentLengthLong();

            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    if (listener != null) {
                        int progress = contentLength > 0 ? (int) ((totalRead * 100) / contentLength) : -1;
                        listener.onProgress(progress, totalRead, contentLength);
                    }
                }
            }

            if (outputFile.exists()) {
                outputFile.delete();
            }
            if (!tempFile.renameTo(outputFile)) {
                throw new IOException("Failed to rename temp file");
            }
            return true;
        } finally {
            if (connection != null)
                connection.disconnect();
            if (tempFile.exists())
                tempFile.delete();
        }
    }

    /**
     * Imports a model from an input stream (e.g. from a content URI).
     * 
     * @param input          The input stream of the source file.
     * @param modelName      The model ID (e.g. manual_import).
     * @param sourceFileName The original file name so we preserve the extension.
     * @return true if successful.
     */
    public boolean importModel(InputStream input, String modelName, String sourceFileName) throws IOException {
        String targetFileName = sourceFileName != null && !sourceFileName.trim().isEmpty()
                ? sourceFileName
                : modelName + ".litertlm";
        File outputFile = new File(getModelDirectory(), targetFileName);
        Log.i(TAG, "Importing manual model to: " + outputFile.getAbsolutePath());

        File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");

        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            output.flush();

            Log.i(TAG, "Imported file size: " + (totalBytes / 1_000_000) + " MB");

            // Validate file size (.litertlm files should be substantial)
            if (totalBytes < 1000) {
                tempFile.delete();
                throw new IOException("File too small (" + totalBytes
                        + " bytes). Expected a .litertlm file (typically 500MB+). Did you download an HTML page instead?");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write import stream", e);
            tempFile.delete();
            throw e;
        }

        if (outputFile.exists()) {
            outputFile.delete();
        }
        if (!tempFile.renameTo(outputFile)) {
            throw new IOException("Failed to rename imported temp file to " + outputFile.getName());
        }
        if (LocalAiPreferences.MANUAL_MODEL_ID.equals(modelName)) {
            LocalAiPreferences.setManualModelPath(context, outputFile.getAbsolutePath());
        }
        return true;
    }

    /**
     * Validates a model by initializing it and generating a test response.
     * 
     * @param modelId The model ID to validate
     * @return The model's response to a test prompt
     * @throws Exception if the model fails to initialize or generate a response
     */
    public String validateModel(String modelId) throws Exception {
        LlmModel model = LlmModel.fromId(modelId);
        return validateModel(modelId, model);
    }

    /**
     * Validates a model by initializing it and generating a test response.
     * Uses the InferenceModel singleton to validate with the same code path as
     * analysis.
     * 
     * @param modelId The model ID to validate
     * @param model   The LlmModel config (can be null for manual imports)
     * @return The model's response to a test prompt
     * @throws Exception if the model fails to initialize or generate a response
     */
    public String validateModel(String modelId, LlmModel model) throws Exception {
        File modelFile = getModelPath(modelId);
        if (!modelFile.exists()) {
            throw new IllegalStateException("Model file not found: " + modelFile.getAbsolutePath());
        }

        // Clear any stale cache before loading
        clearXnnpackCache(modelId);

        Log.i(TAG, "Validating model: " + modelId);

        // Reset the singleton to force reload with potentially new model
        InferenceModel.closeInstance();

        try {
            // Get singleton instance (will load the model)
            InferenceModel inferenceModel = InferenceModel.resetInstance(context);

            // Reset session with a simple system message
            inferenceModel.resetSession("You are a helpful assistant. Be brief.");

            // Generate response synchronously
            String response = inferenceModel.generateResponse("Say hello!");

            if (response == null || response.trim().isEmpty()) {
                throw new IllegalStateException("Model returned empty response");
            }

            Log.i(TAG, "Model validation successful. Response: " + response);

            // Truncate long responses for display
            response = response.trim();
            if (response.length() > 200) {
                response = response.substring(0, 200) + "...";
            }

            return response;
        } catch (InferenceModel.ModelLoadFailException e) {
            throw new Exception("Failed to load model for validation: " + e.getMessage(), e);
        }
    }

    /**
     * Gets all available models (excluding manual import).
     */
    public LlmModel[] getAvailableModels() {
        LlmModel[] all = LlmModel.values();
        LlmModel[] result = new LlmModel[all.length - 1];
        int idx = 0;
        for (LlmModel m : all) {
            if (m != LlmModel.MANUAL_IMPORT) {
                result[idx++] = m;
            }
        }
        return result;
    }

    /**
     * Gets models that don't require HuggingFace authentication.
     */
    public LlmModel[] getPublicModels() {
        return LlmModel.getPublicModels();
    }

    /**
     * Gets recommended models for ad analysis.
     */
    public LlmModel[] getRecommendedModels() {
        return LlmModel.getRecommendedModels();
    }
}
