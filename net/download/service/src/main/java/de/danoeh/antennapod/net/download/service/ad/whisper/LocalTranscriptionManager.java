package de.danoeh.antennapod.net.download.service.ad.whisper;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages local speech recognition model download and on-device transcription
 * using Vosk.
 * Vosk is a lightweight, offline speech recognition toolkit that works well on
 * Android.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalTranscriptionManager {
    private static final String TAG = "LocalTranscriptionMgr";

    // Vosk model URLs - English models for on-device transcription
    public static final String MODEL_SMALL = "small";
    public static final String MODEL_MEDIUM = "medium";
    public static final String MODEL_LARGE = "large";

    private static final String MODEL_URL_SMALL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String MODEL_URL_MEDIUM = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip";
    private static final String MODEL_URL_LARGE = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip";

    // Model sizes for progress tracking
    private static final long MODEL_SIZE_SMALL = 40_000_000L; // ~40 MB
    private static final long MODEL_SIZE_MEDIUM = 128_000_000L; // ~128 MB
    private static final long MODEL_SIZE_LARGE = 1_800_000_000L; // ~1.8 GB

    // Minimum available memory required for each model (with safety margin)
    private static final long MIN_MEMORY_SMALL = 100_000_000L; // 100 MB
    private static final long MIN_MEMORY_MEDIUM = 300_000_000L; // 300 MB
    private static final long MIN_MEMORY_LARGE = 2_500_000_000L; // 2.5 GB

    // Audio processing constants
    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private Model model;
    private volatile boolean isModelLoaded = false;
    private volatile boolean isModelLoading = false;
    private volatile String loadedModelName = null;
    private volatile Exception loadingException = null;

    public LocalTranscriptionManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns the directory where models are stored.
     */
    public File getModelDirectory() {
        File modelDir = new File(context.getFilesDir(), "vosk_models");
        if (!modelDir.exists()) {
            modelDir.mkdirs();
        }
        return modelDir;
    }

    /**
     * Returns the model directory for a specific model.
     */
    public File getModelPath(String modelName) {
        String dirName;
        switch (modelName) {
            case MODEL_SMALL:
                dirName = "vosk-model-small-en-us-0.15";
                break;
            case MODEL_MEDIUM:
                dirName = "vosk-model-en-us-0.22-lgraph";
                break;
            case MODEL_LARGE:
            default:
                dirName = "vosk-model-en-us-0.22";
                break;
        }
        return new File(getModelDirectory(), dirName);
    }

    /**
     * Checks if a model is downloaded and ready to use.
     */
    public boolean isModelDownloaded(String modelName) {
        File modelPath = getModelPath(modelName);
        // Check for key model files
        File amFile = new File(modelPath, "am/final.mdl");
        File confFile = new File(modelPath, "conf/model.conf");
        // Some models have different structure
        if (!amFile.exists()) {
            amFile = new File(modelPath, "model/am/final.mdl");
        }
        if (!confFile.exists()) {
            confFile = new File(modelPath, "model/conf/model.conf");
        }
        return modelPath.exists() && modelPath.isDirectory()
                && (amFile.exists() || new File(modelPath, "graph").exists());
    }

    /**
     * Gets the expected model size for download progress.
     */
    public long getModelSize(String modelName) {
        switch (modelName) {
            case MODEL_SMALL:
                return MODEL_SIZE_SMALL;
            case MODEL_MEDIUM:
                return MODEL_SIZE_MEDIUM;
            case MODEL_LARGE:
            default:
                return MODEL_SIZE_LARGE;
        }
    }

    /**
     * Gets the minimum memory required to load a model.
     */
    public long getMinMemoryRequired(String modelName) {
        switch (modelName) {
            case MODEL_SMALL:
                return MIN_MEMORY_SMALL;
            case MODEL_MEDIUM:
                return MIN_MEMORY_MEDIUM;
            case MODEL_LARGE:
            default:
                return MIN_MEMORY_LARGE;
        }
    }

    /**
     * Checks if there's enough available memory to load a model.
     * Note: Native libraries like Vosk allocate memory outside the Java heap,
     * so we check system-wide available memory instead of just Java heap.
     */
    public boolean hasEnoughMemory(String modelName) {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);

        long required = getMinMemoryRequired(modelName);
        long totalRam = memInfo.totalMem;

        // Android per-app memory limits vary by device and OEM
        // High-end devices (8GB+) typically allow more per app
        // Use a sliding scale: 25% for low-RAM, up to 40% for high-RAM devices
        double memoryFraction = totalRam > 6_000_000_000L ? 0.40 : 0.33;
        long maxPerAppEstimate = (long) (totalRam * memoryFraction);

        // Also account for current app memory usage
        long nativeHeapUsed = android.os.Debug.getNativeHeapAllocatedSize();
        long javaHeapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long currentAppUsage = nativeHeapUsed + javaHeapUsed;
        long availableForModel = maxPerAppEstimate - currentAppUsage - (100 * 1_000_000L); // 100MB safety

        Log.d(TAG, "Memory check for " + modelName + ": total RAM=" + (totalRam / 1_000_000)
                + "MB, per-app limit ~" + (maxPerAppEstimate / 1_000_000)
                + "MB, current usage=" + (currentAppUsage / 1_000_000)
                + "MB, available for model=" + (availableForModel / 1_000_000)
                + "MB, required=" + (required / 1_000_000) + "MB");

        if (required > availableForModel) {
            Log.w(TAG, "Model " + modelName + " requires " + (required / 1_000_000)
                    + "MB but only ~" + (availableForModel / 1_000_000) + "MB available");
            return false;
        }

        // Also check current system available memory
        long availableMemory = memInfo.availMem;
        long threshold = memInfo.threshold;
        long usableSystemMemory = availableMemory - threshold - (100 * 1_000_000L);

        return usableSystemMemory >= required;
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
     * @param modelName The model to download (small or large)
     * @param listener  Progress callback
     * @return true if download was successful
     */
    public boolean downloadModel(String modelName, DownloadProgressListener listener)
            throws IOException {
        String modelUrl;
        switch (modelName) {
            case MODEL_SMALL:
                modelUrl = MODEL_URL_SMALL;
                break;
            case MODEL_MEDIUM:
                modelUrl = MODEL_URL_MEDIUM;
                break;
            case MODEL_LARGE:
            default:
                modelUrl = MODEL_URL_LARGE;
                break;
        }
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

            extractZip(zipFile, modelDir);

            Log.i(TAG, "Model download and extraction complete: " + modelName);
            return true;

        } finally {
            // Clean up zip file
            if (zipFile.exists()) {
                zipFile.delete();
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
                contentLength = getModelSize(MODEL_SMALL); // Fallback
            }

            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
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
                    outputFile.delete();
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
                tempFile.delete();
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
                    newFile.mkdirs();
                } else {
                    // Create parent directories
                    newFile.getParentFile().mkdirs();

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
        file.delete();
    }

    /**
     * Loads the model into memory for inference.
     * This method blocks until the model is loaded or fails.
     * For large models, consider using loadModelAsync() instead.
     */
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
        loadingException = null;

        try {
            // Request garbage collection before loading large models
            if (!MODEL_SMALL.equals(modelName)) {
                System.gc();
                try {
                    Thread.sleep(100); // Give GC a moment
                } catch (InterruptedException ignored) {
                }
            }

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

        Log.i(TAG, "Transcribing: " + audioFile.getName());

        // Convert audio to 16kHz mono PCM
        short[] audioData = loadAndResampleAudio(audioFile);

        StringBuilder vttBuilder = new StringBuilder();
        vttBuilder.append("WEBVTT\n\n");

        try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
            recognizer.setWords(true);

            // Process audio in chunks
            int chunkSize = SAMPLE_RATE * 4; // 4 seconds of audio at a time
            byte[] buffer = new byte[chunkSize * 2]; // 2 bytes per sample

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
    public String transcribeChunk(File audioFile, double offsetSeconds) throws IOException {
        if (!isModelLoaded || model == null) {
            throw new IllegalStateException("Model not loaded. Call loadModel() first.");
        }

        Log.i(TAG, "Transcribing chunk: " + audioFile.getName() + " with offset " + offsetSeconds + "s");

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
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            String mime = format.getString(MediaFormat.KEY_MIME);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer rawAudio = ByteBuffer.allocate(1024 * 1024 * 100); // 100MB max
            rawAudio.order(ByteOrder.LITTLE_ENDIAN);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    int inputBufIndex = codec.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
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
                        sawOutputEOS = true;
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
                monoSamples[i] = (short) ((samples[i * 2] + samples[i * 2 + 1]) / 2);
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
