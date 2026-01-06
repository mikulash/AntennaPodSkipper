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
        int doneCount = 0;
        final int totalChunks = chunkPaths.size();
        final double totalProgressParts = totalChunks * 2; // request + success per chunk
        StringBuilder combined = new StringBuilder();
        try {
            for (int i = 0; i < chunkPaths.size(); i++) {
                final Path chunkPath = chunkPaths.get(i);
                try {
                    if (chunkPath == null || !Files.exists(chunkPath)) {
                        Log.e(TAG, "Chunk " + (i + 1) + " missing on disk; skipping section");
                        doneCount++;
                        setProgressStage("transcribing", calculatePercent(doneCount, totalProgressParts));
                        continue;
                    }
                    long sizeBytes = Files.size(chunkPath);
                    Log.i(TAG, "Transcribing chunk " + (i + 1) + "/" + chunkPaths.size()
                            + ": " + chunkPath.getFileName() + " (" + formatBytes(sizeBytes) + ")");
                    doneCount++;
                    setProgressStage("transcribing", calculatePercent(doneCount, totalProgressParts));
                    String transcription = provider.transcribeChunk(chunkPath, i, chunkPaths.size(), 2);
                    Log.d(TAG, "Chunk transcription " + (i + 1) + " done, length=" + transcription.length());
                    double offsetSeconds = i * TRANSCRIPTION_CHUNK_SECONDS;
                    String adjusted = applyOffset(transcription, offsetSeconds);
                    Log.i(TAG, "Chunk " + (i + 1) + " done, adjusted length=" + adjusted.length());
                    doneCount++;
                    setProgressStage("transcribing", calculatePercent(doneCount, totalProgressParts));
                    combined.append(adjusted);
                } catch (Exception e) {
                    if (isUnauthorized(e)) {
                        throw e;
                    }
                    Log.e(TAG, "Chunk " + (i + 1) + " failed after retries; skipping section", e);
                    doneCount++;
                    setProgressStage("transcribing", calculatePercent(doneCount, totalProgressParts));
                }
            }
            return combined.toString();
        } finally {
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
