package de.danoeh.antennapod.net.ai.service.ad.vosk;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.danoeh.antennapod.net.ai.service.ad.transcription.TranscriptionManager;

/**
 * Manages Vosk speech recognition model download and on-device transcription.
 * Vosk is a lightweight, offline speech recognition toolkit that works well on
 * Android.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class VoskTranscriptionManager implements TranscriptionManager {
    private static final String TAG = "VoskTranscriptionMgr";

    // Models
    public static final String DEFAULT_MODEL_ID = "vosk-model-small-en-us-0.15";
    private static final List<VoskModel> AVAILABLE_MODELS = new ArrayList<>();

    static {
        // English
        AVAILABLE_MODELS.add(new VoskModel("vosk-model-small-en-us-0.15", "English (US) Small", "English",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40_000_000L));
        AVAILABLE_MODELS.add(new VoskModel("vosk-model-en-us-0.22-lgraph", "English (US) Medium", "English",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", 128_000_000L));
        AVAILABLE_MODELS.add(new VoskModel("vosk-model-en-us-0.22", "English (US) Large", "English",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip", 1_800_000_000L));
    }

    // Audio processing constants
    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private Model model;
    private volatile boolean isModelLoaded = false;
    private volatile boolean isModelLoading = false;
    private volatile String loadedModelName = null;

    public VoskTranscriptionManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns the directory where models are stored.
     */
    public File getModelDirectory() {
        File modelDir = new File(context.getFilesDir(), "vosk_models");
        if (!modelDir.exists()) {
            if (!modelDir.mkdirs()) {
                Log.w(TAG, "Failed to create model directory: " + modelDir.getAbsolutePath());
            }
        }
        return modelDir;
    }

    /**
     * Returns the model directory for a specific model ID.
     */
    public File getModelPath(String modelId) {
        String dirName = resolveModelId(modelId);
        return new File(getModelDirectory(), dirName);
    }

    private String resolveModelId(String modelId) {
        if ("small".equals(modelId)) {
            return "vosk-model-small-en-us-0.15";
        }
        if ("medium".equals(modelId)) {
            return "vosk-model-en-us-0.22-lgraph";
        }
        if ("large".equals(modelId)) {
            return "vosk-model-en-us-0.22";
        }
        return modelId;
    }

    public List<VoskModel> getAvailableModels() {
        return new ArrayList<>(AVAILABLE_MODELS);
    }

    public VoskModel getModelById(String id) {
        String resolvedId = resolveModelId(id);
        for (VoskModel model : AVAILABLE_MODELS) {
            if (model.getId().equals(resolvedId)) {
                return model;
            }
        }
        return null; // Or return a dummy unknown model
    }

    /**
     * Checks if a model is downloaded and ready to use.
     */
    @Override
    public boolean isModelDownloaded(String modelName) {
        File modelPath = getModelPath(modelName);
        // Check for key model files
        File amFile = new File(modelPath, "am/final.mdl");
        // Some models have different structure
        if (!amFile.exists()) {
            amFile = new File(modelPath, "model/am/final.mdl");
        }
        return modelPath.exists() && modelPath.isDirectory()
                && (amFile.exists() || new File(modelPath, "graph").exists());
    }

    /**
     * Gets the expected model size for download progress.
     */
    public long getModelSize(String modelId) {
        VoskModel model = getModelById(modelId);
        if (model != null) {
            return model.getSize();
        }
        return 0;
    }

    /**
     * Gets the minimum memory required to load a model.
     */
    @Override
    public long getMinMemoryRequired(String modelId) {
        VoskModel model = getModelById(modelId);
        long size = (model != null) ? model.getSize() : 0;

        // Rough estimate based on size.
        // Small (~40MB) -> 100MB
        // Medium (~130MB) -> 300MB
        // Large (~1.8GB) -> 2.5GB

        if (size > 1_000_000_000L) {
            return 2_500_000_000L;
        } else if (size > 100_000_000L) {
            return 300_000_000L;
        } else {
            return 100_000_000L;
        }
    }

    /**
     * Checks if there's enough available memory to load a model.
     * Note: Native libraries like Vosk allocate memory outside the Java heap,
     * so we check system-wide available memory instead of just Java heap.
     */
    @Override
    public boolean hasEnoughMemory(String modelName) {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);

        long required = getMinMemoryRequired(modelName);
        long totalRam = memInfo.totalMem;

        // Estimate per-app limit for logging/debugging, but don't strictly enforce it
        // as Android's management is dynamic and native heaps can grow larger.
        double memoryFraction = totalRam > 6_000_000_000L ? 0.60 : 0.40; // Increased leniency
        long maxPerAppEstimate = (long) (totalRam * memoryFraction);

        long nativeHeapUsed = android.os.Debug.getNativeHeapAllocatedSize();
        long javaHeapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long currentAppUsage = nativeHeapUsed + javaHeapUsed;

        long availableByPolicy = maxPerAppEstimate - currentAppUsage;

        Log.d(TAG, "Memory check for " + modelName + ": total RAM=" + (totalRam / 1_000_000)
                + "MB, est. soft limit ~" + (maxPerAppEstimate / 1_000_000)
                + "MB, current usage=" + (currentAppUsage / 1_000_000)
                + "MB, required=" + (required / 1_000_000) + "MB");

        if (required > availableByPolicy) {
            // Log a warning but don't block. If the system has free RAM, we should try.
            Log.w(TAG, "App usage " + (currentAppUsage / 1_000_000) + "MB exceeds soft limit "
                    + (maxPerAppEstimate / 1_000_000) + "MB, but proceeding if system RAM is available.");
        }

        // Check current system available memory - this is the real hard limit
        long availableMemory = memInfo.availMem;
        long threshold = memInfo.threshold;
        // Require enough for the model + a safety buffer (e.g. 100MB)
        long usableSystemMemory = availableMemory - threshold - (100 * 1_000_000L);

        boolean enoughSystemRam = usableSystemMemory >= required;
        if (!enoughSystemRam) {
            Log.e(TAG, "Not enough system RAM. Available: " + (usableSystemMemory / 1_000_000)
                    + "MB, Required: " + (required / 1_000_000) + "MB");
        }
        return enoughSystemRam;
    }

    /**
     * Gets the estimated per-app memory limit for this device.
     */
    public long getPerAppMemoryLimit() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);
        long totalRam = memInfo.totalMem;
        // High-end devices allow more memory per app
        double memoryFraction = totalRam > 6_000_000_000L ? 0.40 : 0.33;
        return (long) (totalRam * memoryFraction);
    }

    /**
     * Gets the human-readable model size.
     */
    public String getModelSizeString(String modelName) {
        long size = getModelSize(modelName);
        if (size >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", size / 1_000_000_000.0);
        } else {
            return String.format(Locale.US, "%.0f MB", size / 1_000_000.0);
        }
    }

    /**
     * Downloads and extracts the Vosk model.
     *
     * @param modelId  The modelId of model to download (small or large)
     * @param listener Progress callback
     * @return true if download was successful
     */
    public boolean downloadModel(String modelId, DownloadProgressListener listener)
            throws IOException {
        VoskModel model = getModelById(modelId);
        if (model == null) {
            throw new IOException("Unknown model ID: " + modelId);
        }
        String modelUrl = model.getUrl();
        String modelName = model.getId();

        File modelDir = getModelDirectory();
        File zipFile = new File(modelDir, modelName + ".zip");

        Log.i(TAG, "Downloading Vosk model: " + modelName);

        try {
            // Download the zip file
            if (!downloadFile(modelUrl, zipFile, listener)) {
                return false;
            }

            // Extract the zip file
            Log.i(TAG, "Extracting model...");
            if (listener != null) {
                listener.onProgress(-1, 0, 0); // Indeterminate progress for extraction
            }

            File tempExtractDir = new File(modelDir, "temp_" + modelName);
            if (tempExtractDir.exists()) {
                deleteRecursively(tempExtractDir);
            }
            if (!tempExtractDir.mkdirs()) {
                throw new IOException("Failed to create temporary extraction directory: "
                        + tempExtractDir.getAbsolutePath());
            }

            extractZip(zipFile, tempExtractDir);

            // Handle extraction result - find the model root
            File finalModelPath = new File(modelDir, modelName);
            if (finalModelPath.exists()) {
                deleteRecursively(finalModelPath);
            }

            File[] extractedFiles = tempExtractDir.listFiles();
            if (extractedFiles != null && extractedFiles.length == 1 && extractedFiles[0].isDirectory()) {
                // Zip contained a single folder (the standard Vosk case)
                // Rename that folder to the expected model ID
                if (!extractedFiles[0].renameTo(finalModelPath)) {
                    // Fallback if atomic rename fails (e.g. crossing volumes, unlikely here)
                    // But renaming directories can be flaky.
                    // If rename fails, we might leave it or try manual move.
                    // For now assume rename works on same fs.
                    Log.e(TAG, "Failed to rename extracted directory");
                    // Try moving content out?
                    throw new IOException("Failed to finalize model directory");
                }
            } else {
                // Zip was flat or contained multiple items at root
                // Rename the temp dir itself
                if (!tempExtractDir.renameTo(finalModelPath)) {
                    throw new IOException("Failed to finalize model directory (from flat zip)");
                }
            }

            // cleanup temp dir shell if we moved the inner folder
            if (tempExtractDir.exists() && (tempExtractDir.list() == null || tempExtractDir.list().length == 0)) {
                if (!tempExtractDir.delete()) {
                    Log.w(TAG, "Failed to delete temporary extraction directory: "
                            + tempExtractDir.getAbsolutePath());
                }
            }

            Log.i(TAG, "Model download and extraction complete: " + modelName);
            return true;

        } finally {
            // Clean up zip file
            if (zipFile.exists()) {
                if (!zipFile.delete()) {
                    Log.w(TAG, "Failed to delete zip file: " + zipFile.getAbsolutePath());
                }
            }
        }
    }

    private boolean downloadFile(String urlString, File outputFile, DownloadProgressListener listener)
            throws IOException {
        File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");

        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "AntennaPod");
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            // Handle redirects
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                String newUrl = connection.getHeaderField("Location");
                connection.disconnect();
                connection = (HttpURLConnection) new URL(newUrl).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                responseCode = connection.getResponseCode();
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed with response code: " + responseCode);
                return false;
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength <= 0) {
                // Fallback size? We don't have it easily here without passing it down.
                // Just use a default small size or 0.
                contentLength = 40_000_000L;
            }

            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    // Check if thread is interrupted (for cancellation)
                    if (Thread.currentThread().isInterrupted()) {
                        Log.i(TAG, "Download interrupted for model");
                        return false;
                    }

                    output.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    if (listener != null) {
                        int progress = (int) ((totalRead * 100) / contentLength);
                        listener.onProgress(progress, totalRead, contentLength);
                    }
                }
            }

            // Rename temp file to final file
            if (tempFile.exists()) {
                if (outputFile.exists()) {
                    if (!outputFile.delete()) {
                        throw new IOException("Failed to delete existing file: " + outputFile.getAbsolutePath());
                    }
                }
                if (!tempFile.renameTo(outputFile)) {
                    throw new IOException("Failed to rename temp file to: " + outputFile);
                }
            }

            return true;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tempFile.exists()) {
                if (!tempFile.delete()) {
                    Log.w(TAG, "Failed to delete temporary file: " + tempFile.getAbsolutePath());
                }
            }
        }
    }

    private void extractZip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());

                // Prevent zip slip vulnerability
                String destDirPath = destDir.getCanonicalPath();
                String newFilePath = newFile.getCanonicalPath();
                if (!newFilePath.startsWith(destDirPath + File.separator)) {
                    throw new IOException("Entry is outside of target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!newFile.mkdirs() && !newFile.exists()) {
                        throw new IOException("Failed to create directory: " + newFile.getAbsolutePath());
                    }
                } else {
                    // Create parent directories
                    File parentDir = newFile.getParentFile();
                    if (parentDir != null && !parentDir.mkdirs() && !parentDir.exists()) {
                        throw new IOException("Failed to create parent directory: " + parentDir.getAbsolutePath());
                    }

                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Deletes the downloaded model files.
     */
    public void deleteModel(String modelName) {
        if (isModelLoaded && modelName.equals(loadedModelName)) {
            unloadModel();
        }

        File modelPath = getModelPath(modelName);
        if (modelPath.exists()) {
            deleteRecursively(modelPath);
        }

        Log.i(TAG, "Model deleted: " + modelName);
    }

    /**
     * Deletes all downloaded transcription model files.
     * 
     * @return The number of deleted models.
     */
    public int deleteAllModels() {
        // Unload any loaded model first
        if (isModelLoaded) {
            unloadModel();
        }

        File modelDir = getModelDirectory();
        int deletedCount = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteRecursively(file);
                    deletedCount++;
                    Log.i(TAG, "Deleted model directory: " + file.getName());
                }
            }
        }
        Log.i(TAG, "Deleted " + deletedCount + " transcription model(s)");
        return deletedCount;
    }

    /**
     * Deletes all downloaded transcription models except the given model.
     *
     * @param keepModelId model id to keep (may be null)
     * @return number of deleted models
     */
    public int deleteAllModelsExcept(@Nullable String keepModelId) {
        if (isModelLoaded && keepModelId != null && !keepModelId.equals(loadedModelName)) {
            unloadModel();
        }
        File modelDir = getModelDirectory();
        int deletedCount = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isDirectory()) {
                    continue;
                }
                if (keepModelId != null && file.getName().contains(keepModelId)) {
                    continue;
                }
                deleteRecursively(file);
                deletedCount++;
                Log.i(TAG, "Deleted model directory: " + file.getName());
            }
        }
        return deletedCount;
    }

    /**
     * Gets the number of downloaded transcription models.
     * 
     * @return Count of downloaded models.
     */
    public int getDownloadedModelsCount() {
        File modelDir = getModelDirectory();
        int count = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Gets the total size of all downloaded transcription models in bytes.
     * 
     * @return Total size in bytes.
     */
    public long getDownloadedModelsSize() {
        File modelDir = getModelDirectory();
        long totalSize = 0;
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    totalSize += getDirectorySize(file);
                }
            }
        }
        return totalSize;
    }

    private long getDirectorySize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    size += getDirectorySize(file);
                } else {
                    size += file.length();
                }
            }
        }
        return size;
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete file: " + file.getAbsolutePath());
        }
    }

    /**
     * Loads the model into memory for inference.
     * This method blocks until the model is loaded or fails.
     * For large models, consider using loadModelAsync() instead.
     */
    @Override
    public synchronized void loadModel(String modelName) throws IOException {
        if (isModelLoaded && modelName.equals(loadedModelName)) {
            return; // Already loaded
        }

        if (isModelLoading) {
            throw new IOException("Another model is currently being loaded");
        }

        if (isModelLoaded) {
            unloadModel();
        }

        File modelPath = getModelPath(modelName);
        if (!modelPath.exists()) {
            throw new IOException("Model not found: " + modelPath.getAbsolutePath());
        }

        // Check memory before loading
        if (!hasEnoughMemory(modelName)) {
            long required = getMinMemoryRequired(modelName) / 1_000_000;
            throw new IOException("Not enough memory to load model. Required: " + required + " MB. "
                    + "Try closing other apps or use a smaller model.");
        }

        Log.i(TAG, "Loading Vosk model: " + modelName);
        isModelLoading = true;

        try {
            model = new Model(modelPath.getAbsolutePath());
            isModelLoaded = true;
            loadedModelName = modelName;
            Log.i(TAG, "Model loaded successfully: " + modelName);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory loading model", e);
            model = null;
            isModelLoaded = false;
            loadedModelName = null;
            throw new IOException("Out of memory loading model. Try a smaller model or close other apps.", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load model", e);
            model = null;
            isModelLoaded = false;
            loadedModelName = null;
            throw new IOException("Failed to load transcription model: " + e.getMessage(), e);
        } finally {
            isModelLoading = false;
        }
    }

    /**
     * Loads the model asynchronously to avoid blocking the UI thread.
     * Use this for medium and large models.
     *
     * @param modelName The model to load
     * @param callback  Callback for completion or error
     */
    public void loadModelAsync(String modelName, ModelLoadCallback callback) {
        if (isModelLoaded && modelName.equals(loadedModelName)) {
            if (callback != null) {
                callback.onModelLoaded();
            }
            return;
        }

        if (isModelLoading) {
            if (callback != null) {
                callback.onModelLoadFailed(new IOException("Another model is currently being loaded"));
            }
            return;
        }

        new Thread(() -> {
            try {
                loadModel(modelName);
                if (callback != null) {
                    callback.onModelLoaded();
                }
            } catch (IOException e) {
                if (callback != null) {
                    callback.onModelLoadFailed(e);
                }
            }
        }, "VoskModelLoader").start();
    }

    /**
     * Callback interface for async model loading.
     */
    public interface ModelLoadCallback {
        void onModelLoaded();

        void onModelLoadFailed(Exception e);
    }

    /**
     * Checks if a model is currently being loaded.
     */
    public boolean isModelLoading() {
        return isModelLoading;
    }

    /**
     * Unloads the model from memory.
     */
    @Override
    public synchronized void unloadModel() {
        if (model != null) {
            model.close();
            model = null;
        }
        isModelLoaded = false;
        loadedModelName = null;
        Log.i(TAG, "Model unloaded");
    }

    /**
     * Transcribes an audio file to WebVTT format.
     *
     * @param audioFile Path to the audio file
     * @return WebVTT formatted transcription
     */
    public String transcribe(File audioFile) throws IOException {
        if (!isModelLoaded || model == null) {
            throw new IllegalStateException("Model not loaded. Call loadModel() first.");
        }

        Log.i(TAG, "Transcribing with model " + loadedModelName + ": " + audioFile.getName());

        // Convert audio to 16kHz mono PCM
        short[] audioData = loadAndResampleAudio(audioFile);

        StringBuilder vttBuilder = new StringBuilder();
        vttBuilder.append("WEBVTT\n\n");

        try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
            recognizer.setWords(true);

            // Process audio in chunks
            int chunkSize = SAMPLE_RATE * 4; // 4 seconds of audio at a time

            int position = 0;
            while (position < audioData.length) {
                int samplesToProcess = Math.min(chunkSize, audioData.length - position);

                // Convert shorts to bytes
                ByteBuffer byteBuffer = ByteBuffer.allocate(samplesToProcess * 2);
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < samplesToProcess; i++) {
                    byteBuffer.putShort(audioData[position + i]);
                }

                byte[] chunk = byteBuffer.array();
                boolean isFinal = (position + samplesToProcess >= audioData.length);

                if (isFinal) {
                    recognizer.acceptWaveForm(chunk, chunk.length);
                    String result = recognizer.getFinalResult();
                    appendVttFromResult(vttBuilder, result, (double) position / SAMPLE_RATE);
                } else {
                    if (recognizer.acceptWaveForm(chunk, chunk.length)) {
                        String result = recognizer.getResult();
                        appendVttFromResult(vttBuilder, result, (double) position / SAMPLE_RATE);
                    }
                }

                position += samplesToProcess;
            }
        }

        return vttBuilder.toString();
    }

    /**
     * Transcribes a chunk of audio and returns VTT-formatted text with offset
     * applied.
     *
     * @param audioFile     Path to the audio chunk file
     * @param offsetSeconds Time offset to add to timestamps
     * @return WebVTT formatted transcription for this chunk
     */
    @Override
    public String transcribeChunk(File audioFile, double offsetSeconds) throws IOException {
        if (!isModelLoaded || model == null) {
            throw new IllegalStateException("Model not loaded. Call loadModel() first.");
        }

        Log.i(TAG, "Transcribing chunk with model " + loadedModelName + ": " + audioFile.getName()
                + " with offset " + offsetSeconds + "s");

        short[] audioData = loadAndResampleAudio(audioFile);

        StringBuilder vttBuilder = new StringBuilder();

        try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
            recognizer.setWords(true);

            int chunkSize = SAMPLE_RATE * 4;
            int position = 0;

            while (position < audioData.length) {
                int samplesToProcess = Math.min(chunkSize, audioData.length - position);

                ByteBuffer byteBuffer = ByteBuffer.allocate(samplesToProcess * 2);
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < samplesToProcess; i++) {
                    byteBuffer.putShort(audioData[position + i]);
                }

                byte[] chunk = byteBuffer.array();
                boolean isFinal = (position + samplesToProcess >= audioData.length);

                if (isFinal) {
                    recognizer.acceptWaveForm(chunk, chunk.length);
                    String result = recognizer.getFinalResult();
                    appendVttFromResult(vttBuilder, result,
                            offsetSeconds + (double) position / SAMPLE_RATE);
                } else {
                    if (recognizer.acceptWaveForm(chunk, chunk.length)) {
                        String result = recognizer.getResult();
                        appendVttFromResult(vttBuilder, result,
                                offsetSeconds + (double) position / SAMPLE_RATE);
                    }
                }

                position += samplesToProcess;
            }
        }

        return vttBuilder.toString();
    }

    private void appendVttFromResult(StringBuilder vttBuilder, String jsonResult, double baseTime) {
        try {
            JSONObject result = new JSONObject(jsonResult);

            // Check for word-level results
            if (result.has("result")) {
                JSONArray words = result.getJSONArray("result");
                if (words.length() > 0) {
                    // Group words into subtitle segments (~5 seconds each)
                    StringBuilder segmentText = new StringBuilder();
                    double segmentStart = -1;
                    double segmentEnd = 0;

                    for (int i = 0; i < words.length(); i++) {
                        JSONObject word = words.getJSONObject(i);
                        String wordText = word.getString("word");
                        double wordStart = word.getDouble("start");
                        double wordEnd = word.getDouble("end");

                        if (segmentStart < 0) {
                            segmentStart = wordStart;
                        }

                        segmentText.append(wordText).append(" ");
                        segmentEnd = wordEnd;

                        // Create new segment every ~5 seconds or at end
                        if (segmentEnd - segmentStart >= 5.0 || i == words.length() - 1) {
                            vttBuilder.append(formatVttTime(segmentStart))
                                    .append(" --> ")
                                    .append(formatVttTime(segmentEnd))
                                    .append("\n")
                                    .append(segmentText.toString().trim())
                                    .append("\n\n");

                            segmentText = new StringBuilder();
                            segmentStart = -1;
                        }
                    }
                }
            } else if (result.has("text")) {
                // Fallback to full text if no word-level timing
                String text = result.getString("text").trim();
                if (!text.isEmpty()) {
                    vttBuilder.append(formatVttTime(baseTime))
                            .append(" --> ")
                            .append(formatVttTime(baseTime + 5.0))
                            .append("\n")
                            .append(text)
                            .append("\n\n");
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse recognition result", e);
        }
    }

    private short[] loadAndResampleAudio(File audioFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(audioFile.getAbsolutePath());

            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }

            if (audioTrack < 0) {
                throw new IOException("No audio track found in file");
            }

            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);

            String mime = format.getString(MediaFormat.KEY_MIME);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = 0;
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION);
            }

            // Estimate required size: duration (sec) * sampleRate * channels * 2 (bytes per
            // short)
            int requiredBytes;
            if (durationUs > 0) {
                // Add 1 second buffer just in case
                double durationSec = (durationUs / 1000000.0) + 1.0;
                requiredBytes = (int) (durationSec * sampleRate * channels * 2);
                // Additional safety padding of 1MB
                requiredBytes += 1024 * 1024;
            } else {
                // Fallback if duration unknown: 30MB (enough for ~5 mins of 44.1kHz stereo)
                requiredBytes = 30 * 1024 * 1024;
            }

            Log.d(TAG, "Allocating audio buffer: " + (requiredBytes / 1_000_000.0) + " MB for duration "
                    + (durationUs / 1000000.0) + "s");
            ByteBuffer rawAudio = ByteBuffer.allocate(requiredBytes);
            rawAudio.order(ByteOrder.LITTLE_ENDIAN);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEos = false;
            boolean sawOutputEos = false;

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    int inputBufIndex = codec.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEos = true;
                        } else {
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputBufIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputBufIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufIndex);
                    byte[] chunk = new byte[info.size];
                    outputBuffer.get(chunk);
                    outputBuffer.clear();

                    if (rawAudio.remaining() >= chunk.length) {
                        rawAudio.put(chunk);
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true;
                    }
                }
            }

            codec.stop();
            codec.release();

            rawAudio.flip();

            return resampleTo16kMono(rawAudio, sampleRate, channels);

        } finally {
            extractor.release();
        }
    }

    private short[] resampleTo16kMono(ByteBuffer rawAudio, int srcRate, int channels) {
        int numSamples = rawAudio.remaining() / 2;
        short[] samples = new short[numSamples];
        rawAudio.asShortBuffer().get(samples);

        // Convert to mono if stereo
        short[] monoSamples;
        if (channels == 2) {
            monoSamples = new short[numSamples / 2];
            for (int i = 0; i < monoSamples.length; i++) {
                int left = samples[i * 2];
                int right = samples[i * 2 + 1];
                monoSamples[i] = (short) ((left + right) / 2);
            }
        } else {
            monoSamples = samples;
        }

        // Resample to 16kHz if needed
        if (srcRate != SAMPLE_RATE) {
            return linearResample(monoSamples, srcRate, SAMPLE_RATE);
        }

        return monoSamples;
    }

    private short[] linearResample(short[] input, int srcRate, int dstRate) {
        double ratio = (double) dstRate / srcRate;
        int outputLength = (int) (input.length * ratio);
        short[] output = new short[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i / ratio;
            int index = (int) srcIndex;
            double frac = srcIndex - index;

            if (index + 1 < input.length) {
                output[i] = (short) (input[index] * (1 - frac) + input[index + 1] * frac);
            } else if (index < input.length) {
                output[i] = input[index];
            }
        }

        return output;
    }

    private String formatVttTime(double seconds) {
        int hours = (int) (seconds / 3600);
        seconds -= hours * 3600;
        int minutes = (int) (seconds / 60);
        seconds -= minutes * 60;
        return String.format(Locale.US, "%02d:%02d:%06.3f", hours, minutes, seconds);
    }

    public boolean isModelLoaded() {
        return isModelLoaded;
    }

    public String getLoadedModelName() {
        return loadedModelName;
    }

    /**
     * Callback interface for download progress.
     */
    public interface DownloadProgressListener {
        /**
         * Called when download progress is updated.
         *
         * @param percent         Progress percentage (0-100), or -1 for indeterminate
         * @param bytesDownloaded Bytes downloaded so far
         * @param totalBytes      Total bytes to download
         */
        void onProgress(int percent, long bytesDownloaded, long totalBytes);
    }
}
