package de.danoeh.antennapod.net.download.service.ad.litert;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

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

    // Model Identifiers
    public static final String MODEL_GEMMA_2B = "gemma-2b";
    public static final String MODEL_QWEN_0_6B = "qwen-0.6b";
    public static final String MODEL_QWEN_1_7B = "qwen-1.7b";

    // URLs - using placeholders for Qwen as per plan, real URL for Gemma if possible or standard placeholder
    // NOTE: In a real production app, these should be stable, versioned URLs.
    // URLs
    // Gemma 2B is Gated -> Cannot download directly without auth.
    // We recommend using Qwen models which are open.
    private static final String URL_GEMMA_2B = ""; 

    // Qwen 2.5 (0.5B) - Verified Public URL (Float32 or Int4 if available, using the one found)
    private static final String URL_QWEN_0_6B = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_seq128_f32_ekv1280.tflite?download=true"; 

    // Qwen 2.5 (1.5B) - Assumed matching pattern
    private static final String URL_QWEN_1_7B = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_f32_ekv1280.tflite?download=true";

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

    public File getModelPath(String modelName) {
         // The filename MUST be just the name for simplicity, or we map it.
         // MediaPipe usually expects a specific extension (.bin)
         String filename = modelName + ".bin";
         return new File(getModelDirectory(), filename);
    }

    public boolean isModelDownloaded(String modelName) {
        File file = getModelPath(modelName);
        return file.exists() && file.length() > 0;
    }

    public void deleteModel(String modelName) {
        File file = getModelPath(modelName);
        if (file.exists()) {
            file.delete();
            Log.i(TAG, "Deleted model: " + modelName);
        }
    }

    public boolean downloadModel(String modelName, DownloadProgressListener listener) throws IOException {
        String urlString;
        switch (modelName) {
            case MODEL_GEMMA_2B:
                urlString = URL_GEMMA_2B;
                break;
            case MODEL_QWEN_0_6B:
                urlString = URL_QWEN_0_6B;
                break;
            case MODEL_QWEN_1_7B:
                urlString = URL_QWEN_1_7B;
                break;
            default:
                throw new IOException("Unknown model: " + modelName);
        }

        File outputFile = getModelPath(modelName);
        Log.i(TAG, "Downloading LiteRT model: " + modelName + " from " + urlString);

        return downloadFile(urlString, outputFile, listener);
    }

    // specific download logic (simplified version of what's in LocalTranscriptionManager)
    private boolean downloadFile(String urlString, File outputFile, DownloadProgressListener listener) throws IOException {
        File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                 String newUrl = connection.getHeaderField("Location");
                 connection.disconnect();
                 connection = (HttpURLConnection) new URL(newUrl).openConnection();
                 connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");
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
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.flush();
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
}
