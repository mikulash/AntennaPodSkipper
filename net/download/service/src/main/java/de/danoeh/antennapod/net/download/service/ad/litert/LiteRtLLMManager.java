package de.danoeh.antennapod.net.download.service.ad.litert;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager.DownloadProgressListener;

/**
 * Manages download and storage of LiteRT (MediaPipe GenAI) LLM models.
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
        LlmModel model = LlmModel.fromId(modelId);
        if (model != null) {
            return new File(getModelDirectory(), model.getFilename());
        }
        // Fallback for unknown models (e.g., manual imports)
        return new File(getModelDirectory(), modelId + ".task");
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

    public boolean downloadModel(String modelId, DownloadProgressListener listener) throws IOException {
        LlmModel model = LlmModel.fromId(modelId);
        if (model == null) {
            throw new IOException("Unknown model: " + modelId);
        }
        return downloadModel(model, listener, null);
    }

    public boolean downloadModel(LlmModel model, DownloadProgressListener listener, String authToken) throws IOException {
        if (model.getUrl() == null) {
            throw new IOException("Model " + model.getId() + " does not support download (manual import only)");
        }

        File outputFile = getModelPath(model);
        Log.i(TAG, "Downloading LiteRT model: " + model.getId() + " from " + model.getUrl());

        return downloadFile(model.getUrl(), outputFile, listener, model.needsAuth() ? authToken : null);
    }

    // specific download logic (simplified version of what's in LocalTranscriptionManager)
    private boolean downloadFile(String urlString, File outputFile, DownloadProgressListener listener, String authToken) throws IOException {
        File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");

            // Add HuggingFace authentication if required
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                 String newUrl = connection.getHeaderField("Location");
                 connection.disconnect();
                 connection = (HttpURLConnection) new URL(newUrl).openConnection();
                 connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");
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
                        int progress = contentLength > 0 ? (int)((totalRead * 100) / contentLength) : -1;
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
            if (connection != null) connection.disconnect();
            if (tempFile.exists()) tempFile.delete();
        }
    }
    /**
     * Imports a model from an input stream (e.g. from a content URI).
     * @param input The input stream of the source file.
     * @param modelName The name to save the model as (e.g. manual_import).
     * @return true if successful.
     */
    public boolean importModel(InputStream input, String modelName) throws IOException {
        File outputFile = getModelPath(modelName);
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

            // Validate it's a zip file (task files are zip archives)
            if (totalBytes < 1000) {
                tempFile.delete();
                throw new IOException("File too small (" + totalBytes + " bytes). Expected a ~529MB .task file. Did you download an HTML page instead?");
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
        return true;
    }

    /**
     * Validates a model by initializing it and generating a test response.
     * @param modelId The model ID to validate
     * @return The model's response to a test prompt
     * @throws Exception if the model fails to initialize or generate a response
     */
    public String validateModel(String modelId) throws Exception {
        LlmModel model = LlmModel.fromId(modelId);
        return validateModel(modelId, model);
    }

    /**
     * Converts LlmModel.BackendType to MediaPipe Backend.
     */
    private LlmInference.Backend toMediaPipeBackend(LlmModel.BackendType backendType) {
        if (backendType == null) {
            return LlmInference.Backend.CPU;
        }
        switch (backendType) {
            case GPU:
                return LlmInference.Backend.GPU;
            case CPU:
            default:
                return LlmInference.Backend.CPU;
        }
    }

    /**
     * Validates a model by initializing it and generating a test response.
     * @param modelId The model ID to validate
     * @param model The LlmModel config (can be null for manual imports)
     * @return The model's response to a test prompt
     * @throws Exception if the model fails to initialize or generate a response
     */
    public String validateModel(String modelId, LlmModel model) throws Exception {
        File modelFile = getModelPath(modelId);
        if (!modelFile.exists()) {
            throw new IllegalStateException("Model file not found: " + modelFile.getAbsolutePath());
        }

        Log.i(TAG, "Validating model: " + modelId);

        // Use model-specific settings if available, otherwise use defaults
        LlmInference.Backend backend = model != null
                ? toMediaPipeBackend(model.getPreferredBackend())
                : LlmInference.Backend.CPU;
        int maxTokens = model != null ? model.getMaxTokens() : 512;

        LlmInferenceOptions options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.getAbsolutePath())
                .setPreferredBackend(backend)
                .setMaxTokens(maxTokens)
                .build();

        LlmInference inference = null;
        try {
            inference = LlmInference.createFromOptions(context, options);

            // Format the test prompt using model-specific format
            String userMessage = "Say hi to the user";
            String testPrompt = model != null ? model.formatPrompt(userMessage)
                    : "<start_of_turn>user\n" + userMessage + "<end_of_turn>\n<start_of_turn>model\n";

            String response = inference.generateResponse(testPrompt);

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
        } finally {
            if (inference != null) {
                try {
                    inference.close();
                } catch (Exception ignored) {
                }
            }
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
