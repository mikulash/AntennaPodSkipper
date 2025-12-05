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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.ui.i18n.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.net.download.service.ad.provider.AdAnalysisProvider;
import de.danoeh.antennapod.net.download.service.ad.provider.AdAnalysisProviderFactory;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;

import org.greenrobot.eventbus.EventBus;

@RequiresApi(api = Build.VERSION_CODES.O)
public class AdAnalysisWorker extends Worker {
    public static final String DATA_FEED_ITEM_ID = "feedItemId";
    private static final String PROGRESS_KEY_PERCENT = "analysis_progress_percent";
    private static final String PROGRESS_KEY_STAGE = "analysis_progress_stage";
    private static final String TAG = "AdAnalysisWorker";
    private static final long TRANSCRIPTION_CHUNK_SECONDS = 150; // 2.5 minutes

    public AdAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        long feedItemId = getInputData().getLong(DATA_FEED_ITEM_ID, -1);
        Log.d(TAG, "Ad analysis started on item: " + feedItemId);

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
        AdAnalysisProvider provider;
        try {
            provider = AdAnalysisProviderFactory.create(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Ad analysis provider could not be created", e);
            saveError(feedItemId, e.getMessage(), null);
            return Result.success();
        }

        try {
            Log.i(TAG, "Ad analysis started for feedItemId=" + feedItemId
                    + ", title=" + item.getTitle());
            setProgressStage("transcribing", 0);
            String transcript = transcribeInChunks(provider, media);
            Log.i(TAG, "Transcription complete, length=" + transcript.length());

            Log.i(TAG, "Requesting ad classification using model " + provider.getModelName());
            setProgressStage("analyzing", 90);
            String content = provider.analyzeTranscript(buildPrompt(transcript, media.getDuration()));
            Log.i(TAG, "Model response content: " + content);
            Log.i(TAG, "Model response received, raw length=" + content.length());
            List<AdSegment> segments = mergeSegments(parseSegments(content));
            Log.i(TAG, "Ad analysis finished: " + segments.size() + " segment(s) detected");
            try {
                TranscriptUtils.storeTranscript(media, transcript);
            } catch (Exception e) {
                Log.w(TAG, "Failed to store transcript", e);
            }
            AdSegmentStore.save(getApplicationContext(), feedItemId,
                    new AdAnalysisResult(segments, System.currentTimeMillis(), provider.getModelName(), "",
                            transcript));
            setProgressStage("done", 100);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Ad analysis failed", e);
            if (isUnauthorized(e)) {
                AdSegmentStore.clear(getApplicationContext(), feedItemId);
                notifyInvalidApiKey();
            }
            if (!isUnauthorized(e)) {
                saveError(feedItemId, provider.buildErrorMessage(e), provider.getModelName());
            }
            return Result.failure();
        }
    }

    private String transcribeInChunks(AdAnalysisProvider provider, FeedMedia media) throws Exception {
        List<Path> chunkPaths = AudioChunkUtils.createAudioChunks(getApplicationContext(),
                media.getLocalFileUrl(), TRANSCRIPTION_CHUNK_SECONDS);
        Log.i(TAG, "Transcribing " + chunkPaths.size() + " chunk(s) " + "target=" + TRANSCRIPTION_CHUNK_SECONDS
                + "s each");
        validateChunkSizes(provider, chunkPaths);
        int doneCount = 0;
        final int totalChunks = chunkPaths.size();
        final double totalProgressParts = (totalChunks * 2) + 4; // request + success per chunk + analysis weight
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
                    Log.d(TAG, "Chunk transcription " + (i + 1) + " done, the text=" + transcription);
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
            // Analysis weight
            setProgressStage("analyzing", calculatePercent(doneCount, totalProgressParts));
            int finalDone = doneCount + 4;
            setProgressStage("analyzing", calculatePercent(finalDone, totalProgressParts));
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

    private void saveError(long feedItemId, String error) {
        saveError(feedItemId, error, null);
    }

    private void saveError(long feedItemId, String error, String modelName) {
        if (modelName == null) {
            modelName = "unknown";
        }
        AdSegmentStore.save(getApplicationContext(), feedItemId,
                new AdAnalysisResult(Collections.emptyList(), System.currentTimeMillis(),
                        modelName, error, null));
    }

    private String buildPrompt(String transcript, int durationMs) {
        return "You are a classifier that only finds advertisement or sponsor segments in podcasts. "
                + "An advertisement is a sponsor read, mid-roll, pre-roll, post-roll,"
                + " or explicit promotion (coupon codes, giveaways, discounts). "
                + "Do not tag normal banter, housekeeping, or episode content as ads. "
                + "Use seconds from start of episode for times. "
                + "Respond ONLY with valid JSON matching {\"ads\":[{\"startSeconds\":number,\"endSeconds\":number,\""
                + "reason\":string,\"confidence\":number}]} and nothing else.\n\n"
                + "Episode duration seconds: " + durationMs / 1000f + "\n"
                + "Transcript (WebVTT):\n\n"
                + transcript + "\n\nAgain, output only the JSON structure.";
    }

    private List<AdSegment> parseSegments(String rawJson) throws JSONException {
        List<AdSegment> segments = new ArrayList<>();
        String sanitized = sanitizeJson(rawJson);
        if (TextUtils.isEmpty(sanitized)) {
            return segments;
        }
        JSONObject root = new JSONObject(sanitized);
        JSONArray ads = root.optJSONArray("ads");
        if (ads == null) {
            return segments;
        }
        for (int i = 0; i < ads.length(); i++) {
            JSONObject ad = ads.getJSONObject(i);
            double start = ad.optDouble("startSeconds", 0);
            double end = ad.optDouble("endSeconds", 0);
            String reason = ad.optString("reason", "");
            double confidence = ad.optDouble("confidence", 0);
            if (end > start) {
                segments.add(new AdSegment(start, end, reason, confidence));
            }
        }
        return segments;
    }

    private List<AdSegment> mergeSegments(List<AdSegment> input) {
        if (input.isEmpty()) {
            return input;
        }
        input.sort(Comparator.comparingDouble(AdSegment::getStartSeconds));
        List<AdSegment> merged = new ArrayList<>();
        AdSegment current = input.get(0);
        for (int i = 1; i < input.size(); i++) {
            AdSegment next = input.get(i);
            if (next.getStartSeconds() <= current.getEndSeconds() + 0.5) {
                double end = Math.max(current.getEndSeconds(), next.getEndSeconds());
                String reason = TextUtils.isEmpty(current.getReason()) ? next.getReason() : current.getReason();
                double confidence = Math.max(current.getConfidence(), next.getConfidence());
                current = new AdSegment(current.getStartSeconds(), end, reason, confidence);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private String sanitizeJson(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return raw;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline >= 0 && firstNewline + 1 < cleaned.length()) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            cleaned = cleaned.trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return cleaned.substring(start, end + 1).trim();
        }
        return cleaned;
    }

    private void validateChunkSizes(AdAnalysisProvider provider, List<Path> chunkPaths) throws IOException {
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
}
