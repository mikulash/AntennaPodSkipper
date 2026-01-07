package de.danoeh.antennapod.net.download.service.ad;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.openai.errors.UnauthorizedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.ui.i18n.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.net.download.service.ad.provider.TranscriptionProvider;
import de.danoeh.antennapod.net.download.service.ad.provider.AdAnalysisProviderFactory;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;

import org.greenrobot.eventbus.EventBus;

@RequiresApi(api = Build.VERSION_CODES.O)
public class TranscriptionWorker extends Worker {
    public static final String DATA_FEED_ITEM_ID = "feedItemId";
    private static final String PROGRESS_KEY_PERCENT = "transcription_progress_percent";
    private static final String PROGRESS_KEY_STAGE = "transcription_progress_stage";
    private static final String TAG = "TranscriptionWorker";
    private static final long TRANSCRIPTION_CHUNK_SECONDS = 150; // 2.5 minutes

    public TranscriptionWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        long feedItemId = getInputData().getLong(DATA_FEED_ITEM_ID, -1);
        Log.d(TAG, "Transcription started on item: " + feedItemId);

        if (feedItemId <= 0) {
            return Result.failure();
        }

        FeedItem item = DBReader.getFeedItem(feedItemId);
        if (item == null || item.getMedia() == null) {
            return Result.failure();
        }
        FeedMedia media = item.getMedia();
        if (TextUtils.isEmpty(media.getLocalFileUrl())) {
            return Result.success();
        }

        TranscriptionProvider transcriptionProvider = null;
        String modelOverride = null;
        String languageOverride = null;
        if (item.getFeed() != null && item.getFeed().getPreferences() != null) {
            modelOverride = item.getFeed().getPreferences().getTranscriptionModel();
            languageOverride = item.getFeed().getPreferences().getTranscriptionLanguage();
        }

        try {
            transcriptionProvider = AdAnalysisProviderFactory.createTranscriptionProvider(getApplicationContext(),
                    modelOverride, languageOverride);
        } catch (Exception e) {
            Log.e(TAG, "Transcription provider could not be created", e);
            if (isMemoryError(e)) {
                notifyInsufficientMemory(e);
            }
            closeProvider(transcriptionProvider);
            return Result.failure();
        }

        try {
            Log.i(TAG, "Transcription started for feedItemId=" + feedItemId
                    + ", title=" + item.getTitle());
            setProgressStage("transcribing", 0);

            String transcript = transcribeInChunks(transcriptionProvider, media);
            Log.i(TAG, "Transcription complete, length=" + transcript.length());

            // Store transcript
            try {
                TranscriptUtils.storeTranscript(media, transcript);
                Log.i(TAG, "Transcript stored successfully");
            } catch (Exception e) {
                Log.w(TAG, "Failed to store transcript", e);
                return Result.failure();
            }

            setProgressStage("done", 100);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Transcription failed", e);
            if (isUnauthorized(e)) {
                notifyInvalidApiKey();
            }
            return Result.failure();
        } finally {
            closeProvider(transcriptionProvider);
        }
    }

    private void closeProvider(TranscriptionProvider tp) {
        if (tp != null) {
            try {
                tp.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String transcribeInChunks(TranscriptionProvider provider, FeedMedia media) throws Exception {
        List<Path> chunkPaths = AudioChunkUtils.createAudioChunks(getApplicationContext(),
                media.getLocalFileUrl(), TRANSCRIPTION_CHUNK_SECONDS);
        Log.i(TAG, "Transcribing " + chunkPaths.size() + " chunk(s) " + "target=" + TRANSCRIPTION_CHUNK_SECONDS
                + "s each");
        validateChunkSizes(provider, chunkPaths);

        // Parallel execution setup
        int availableProcessors = Runtime.getRuntime().availableProcessors();

        // Calculate dynamic thread count based on memory
        long maxMemory = Runtime.getRuntime().maxMemory();
        long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long availableMemory = maxMemory - usedMemory;

        // Conservative estimate: 64MB per thread (30-40MB audio buffer + native
        // overhead)
        final long MEMORY_PER_THREAD = 64 * 1024 * 1024;
        // Keep 200MB for the rest of the app/UI to prevent OOM
        final long SAFE_BUFFER = 200 * 1024 * 1024;

        int maxThreadsByMemory = (int) ((availableMemory - SAFE_BUFFER) / MEMORY_PER_THREAD);
        // Ensure at least 1 thread, but don't exceed processors or memory limit
        int threadCount = Math.max(1, Math.min(availableProcessors, maxThreadsByMemory));

        Log.i(TAG, "Memory stats: Max=" + (maxMemory / 1024 / 1024) + "MB, Used=" + (usedMemory / 1024 / 1024)
                + "MB, Avail=" + (availableMemory / 1024 / 1024) + "MB. Threads: ByCPU=" + availableProcessors
                + ", ByMem=" + maxThreadsByMemory + " -> Using " + threadCount + " threads");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();

        final int totalChunks = chunkPaths.size();
        final double totalProgressParts = totalChunks * 2; // request + success per chunk
        java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger lastReportedPercent = new java.util.concurrent.atomic.AtomicInteger(0);

        try {
            // Submit all chunks
            for (int i = 0; i < chunkPaths.size(); i++) {
                // Check if work was cancelled before submitting next chunk
                if (isStopped()) {
                    Log.i(TAG, "Transcription cancelled, stopping chunk submission");
                    executor.shutdownNow();
                    throw new InterruptedException("Work cancelled");
                }

                final int chunkIndex = i;
                final Path chunkPath = chunkPaths.get(i);

                futures.add(executor.submit(() -> {
                    try {
                        // Check cancellation at start of chunk processing
                        if (Thread.currentThread().isInterrupted() || isStopped()) {
                            Log.i(TAG, "Chunk " + (chunkIndex + 1) + " cancelled");
                            throw new InterruptedException("Chunk processing cancelled");
                        }

                        if (chunkPath == null || !Files.exists(chunkPath)) {
                            Log.e(TAG, "Chunk " + (chunkIndex + 1) + " missing on disk; skipping section");
                            int currentDone = doneCount.incrementAndGet();
                            updateProgressIfIncreased(lastReportedPercent, currentDone, totalProgressParts);
                            return "";
                        }

                        long sizeBytes = Files.size(chunkPath);
                        Log.i(TAG, "Transcribing chunk " + (chunkIndex + 1) + "/" + totalChunks
                                + ": " + chunkPath.getFileName() + " (" + formatBytes(sizeBytes) + ")");

                        // Update progress (started part)
                        int currentDone = doneCount.incrementAndGet();
                        updateProgressIfIncreased(lastReportedPercent, currentDone, totalProgressParts);

                        // Transcribe
                        String transcription = provider.transcribeChunk(chunkPath, chunkIndex, totalChunks, 2);
                        Log.d(TAG,
                                "Chunk transcription " + (chunkIndex + 1) + " done, length=" + transcription.length());

                        double offsetSeconds = chunkIndex * TRANSCRIPTION_CHUNK_SECONDS;
                        String adjusted = applyOffset(transcription, offsetSeconds);

                        // Update progress (completed part)
                        currentDone = doneCount.incrementAndGet();
                        updateProgressIfIncreased(lastReportedPercent, currentDone, totalProgressParts);

                        return adjusted;
                    } catch (Exception e) {
                        Log.e(TAG, "Chunk " + (chunkIndex + 1) + " failed", e);
                        // Forward exception to be caught in main thread
                        throw e;
                    }
                }));
            }

            // Collect results in order
            StringBuilder combined = new StringBuilder();
            Exception firstException = null;

            for (int i = 0; i < futures.size(); i++) {
                // Check if work was cancelled before processing next result
                if (isStopped()) {
                    Log.i(TAG, "Transcription cancelled while collecting results");
                    executor.shutdownNow();
                    throw new InterruptedException("Work cancelled");
                }

                try {
                    combined.append(futures.get(i).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    // Unwrap the exception
                    Throwable cause = e.getCause();
                    if (isUnauthorized(cause)) {
                        // Immediately stop and rethrow if unauthorized
                        executor.shutdownNow();
                        throw (Exception) cause;
                    }
                    if (cause instanceof InterruptedException) {
                        // Chunk was cancelled
                        executor.shutdownNow();
                        throw new InterruptedException("Chunk cancelled");
                    }
                    if (firstException == null && cause instanceof Exception) {
                        firstException = (Exception) cause;
                    }
                    Log.e(TAG, "Failed to get result for chunk " + (i + 1), e);
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new IOException("Transcription interrupted", e);
                }
            }

            if (firstException != null) {
                // Determine if we should fail the whole process or just log
                // For now, if a chunk fails we'll have a gap, but maybe better to fail?
                // The original code swallowed errors mostly but threw if unauthorized.
                // We'll throw if we encountered a significant error.
                throw firstException;
            }

            return combined.toString();

        } finally {
            executor.shutdownNow(); // Ensure we stop all threads if we exit early
            for (Path chunkPath : chunkPaths) {
                try {
                    Files.deleteIfExists(chunkPath);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
        }
    }

    private String applyOffset(String vtt, double offsetSeconds) {
        String[] lines = vtt.split("\n");
        StringBuilder adjusted = new StringBuilder();
        for (String line : lines) {
            if (line.trim().equalsIgnoreCase("WEBVTT")) {
                continue; // Avoid duplicating headers when concatenating chunks
            }
            if (line.contains("-->")) {
                String[] parts = line.split("-->");
                if (parts.length == 2) {
                    String start = parts[0].trim();
                    String end = parts[1].trim();
                    String newStart = formatTime(parseSeconds(start) + offsetSeconds);
                    String newEnd = formatTime(parseSeconds(end) + offsetSeconds);
                    adjusted.append(newStart).append(" --> ").append(newEnd).append('\n');
                    continue;
                }
            }
            adjusted.append(line).append('\n');
        }
        return adjusted.toString();
    }

    private double parseSeconds(String timeString) {
        // Format: HH:MM:SS.mmm
        String[] parts = timeString.split(":");
        if (parts.length != 3) {
            return 0;
        }
        try {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2].replace(',', '.'));
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String formatTime(double seconds) {
        int hours = (int) (seconds / 3600);
        seconds -= hours * 3600;
        int minutes = (int) (seconds / 60);
        seconds -= minutes * 60;
        return String.format(Locale.US, "%02d:%02d:%06.3f", hours, minutes, seconds);
    }

    private void validateChunkSizes(TranscriptionProvider provider, List<Path> chunkPaths) throws IOException {
        long maxBytes = provider.getMaxAudioBytes();
        if (maxBytes <= 0) {
            return;
        }
        for (Path chunkPath : chunkPaths) {
            long size = Files.size(chunkPath);
            if (size > maxBytes) {
                Log.e(TAG, "Chunk too large for provider (" + formatBytes(size) + "): " + chunkPath);
                throw new IOException("Audio chunk exceeds provider limit: " + chunkPath.getFileName());
            }
        }
    }

    private String formatBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.2f MB", mb);
    }

    private int calculatePercent(int completedParts, double totalParts) {
        if (totalParts <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.max(0, Math.round((completedParts / totalParts) * 100)));
    }

    private void setProgressStage(String stage, int percent) {
        Data progress = new Data.Builder()
                .putString(PROGRESS_KEY_STAGE, stage)
                .putInt(PROGRESS_KEY_PERCENT, percent)
                .build();
        setProgressAsync(progress);
    }

    /**
     * Updates progress only if the new percentage is higher than the last reported percentage.
     * This prevents the progress bar from going backward when chunks complete out of order.
     */
    private void updateProgressIfIncreased(java.util.concurrent.atomic.AtomicInteger lastReportedPercent,
            int currentDone, double totalProgressParts) {
        int newPercent = calculatePercent(currentDone, totalProgressParts);
        int oldPercent = lastReportedPercent.get();

        // Only update if progress increased
        if (newPercent > oldPercent) {
            // Use compareAndSet to avoid race conditions
            if (lastReportedPercent.compareAndSet(oldPercent, newPercent)) {
                setProgressStage("transcribing", newPercent);
            }
        }
    }

    private boolean isUnauthorized(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof UnauthorizedException) {
            return true;
        }
        String message = throwable.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.US);
            if (normalized.contains("unauthorized") || normalized.contains("401")) {
                return true;
            }
        }
        return isUnauthorized(throwable.getCause());
    }

    private void notifyInvalidApiKey() {
        try {
            EventBus.getDefault().post(new MessageEvent(
                    getApplicationContext().getString(R.string.ad_analysis_invalid_key)));
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify user about invalid OpenAI API key", e);
        }
    }

    private boolean isMemoryError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String message = throwable.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.US);
            if (normalized.contains("not enough memory") || normalized.contains("insufficient memory")
                    || normalized.contains("not enough system ram")) {
                return true;
            }
        }
        return isMemoryError(throwable.getCause());
    }

    private void notifyInsufficientMemory(Throwable throwable) {
        try {
            String message = getApplicationContext().getString(R.string.transcription_insufficient_memory_error);

            // Try to extract the model name and required memory from the error message
            String errorMsg = throwable.getMessage();
            if (errorMsg != null && errorMsg.contains("Required:")) {
                message = errorMsg; // Use the detailed error message
            }

            EventBus.getDefault().post(new MessageEvent(message));
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify user about insufficient memory", e);
        }
    }
}
