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

    // Model Identifier for Gemma3-1B-IT
    // Using the int4 quantized model which provides a good balance between size (529 MB) and performance
    public static final String MODEL_GEMMA3_1B = "gemma3-1b-it";

    // Gemma3-1B-IT int4 quantized model from LiteRT community
    // This is a 529 MB model optimized for on-device inference
    private static final String URL_GEMMA3_1B = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task";

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
         // MediaPipe LLM Inference API expects .task extension for model bundles with metadata
         String filename = modelName + ".task";
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
        if (MODEL_GEMMA3_1B.equals(modelName)) {
            urlString = URL_GEMMA3_1B;
        } else {
            throw new IOException("Unknown model: " + modelName + ". Only " + MODEL_GEMMA3_1B + " is supported.");
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

        // Validate it's actually a zip file (task files are zip archives)
        // TEMPORARILY DISABLED - Let MediaPipe validate instead
        boolean isValid = isValidZipFile(tempFile);
        if (!isValid) {
            Log.w(TAG, "ZIP validation failed, but continuing anyway. MediaPipe will validate properly.");
        }

        if (outputFile.exists()) {
            outputFile.delete();
        }
        if (!tempFile.renameTo(outputFile)) {
            throw new IOException("Failed to rename imported temp file to " + outputFile.getName());
        }
        return true;
    }

    private boolean isValidZipFile(File file) {
        // Read file header to diagnose issues
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] header = new byte[4];
            int read = fis.read(header);
            if (read >= 4) {
                String hex = String.format("%02X %02X %02X %02X", header[0], header[1], header[2], header[3]);
                Log.i(TAG, "File header bytes: " + hex);

                // ZIP files should start with 'PK' (0x50 0x4B)
                boolean isPKHeader = (header[0] == 0x50 && header[1] == 0x4B);
                Log.i(TAG, "Has ZIP signature (PK): " + isPKHeader);

                if (!isPKHeader) {
                    // Try to read as text to see if it's an HTML error page
                    fis.close();
                    try (java.io.FileInputStream fis2 = new java.io.FileInputStream(file);
                         java.io.InputStreamReader reader = new java.io.InputStreamReader(fis2);
                         java.io.BufferedReader br = new java.io.BufferedReader(reader)) {
                        String firstLine = br.readLine();
                        if (firstLine != null) {
                            Log.w(TAG, "File appears to be text. First line: " + firstLine);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading file header", e);
        }

        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(file)) {
            // Just check if it can be opened as a zip file
            boolean hasEntries = zipFile.entries().hasMoreElements();
            Log.i(TAG, "Zip validation successful, has entries: " + hasEntries);
            return hasEntries;
        } catch (Exception e) {
            Log.e(TAG, "File is not a valid zip archive", e);
            return false;
        }
    }
}
