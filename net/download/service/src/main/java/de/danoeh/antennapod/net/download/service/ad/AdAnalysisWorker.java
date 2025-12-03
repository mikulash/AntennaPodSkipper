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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.net.download.service.ad.provider.AdAnalysisProvider;
import de.danoeh.antennapod.net.download.service.ad.provider.AdAnalysisProviderFactory;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;

@RequiresApi(api = Build.VERSION_CODES.O)
public class AdAnalysisWorker extends Worker {
    public static final String DATA_FEED_ITEM_ID = "feedItemId";
    private static final String PROGRESS_KEY_PERCENT = "analysis_progress_percent";
    private static final String PROGRESS_KEY_STAGE = "analysis_progress_stage";
    private static final String TAG = "AdAnalysisWorker";
    private static final long TRANSCRIPTION_CHUNK_SECONDS = 300; // 5 minutes

    public AdAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        long feedItemId = getInputData().getLong(DATA_FEED_ITEM_ID, -1);
        Log.d(TAG, "Ad analysis started on item: " +feedItemId);

        if (feedItemId <= 0) {
            return Result.failure();
        }
        if (!UserPreferences.isAutoAdAnalysisEnabled()) {
            return Result.success();
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
                    new AdAnalysisResult(segments, System.currentTimeMillis(), provider.getModelName(), "", transcript));
            setProgressStage("done", 100);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Ad analysis failed", e);
            saveError(feedItemId, provider.buildErrorMessage(e), provider.getModelName());
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("401") || message.toLowerCase().contains("unauthorized")) {
                return Result.failure();
            }
            if (provider.shouldNotRetry(e)) {
                return Result.failure();
            }
            return Result.retry();
        }
    }

    private String transcribeInChunks(AdAnalysisProvider provider, FeedMedia media) throws Exception {
        List<Path> chunkPaths = AudioChunkUtils.createAudioChunks(getApplicationContext(),
                media.getLocalFileUrl(), TRANSCRIPTION_CHUNK_SECONDS);
        Log.i(TAG, "Transcribing " + chunkPaths.size() + " chunk(s) "
                + "target=" + TRANSCRIPTION_CHUNK_SECONDS + "s each");
        validateChunkSizes(provider, chunkPaths);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, chunkPaths.size()));
        Map<Integer, Future<String>> futures = new HashMap<>();
        AtomicInteger doneCount = new AtomicInteger();
        final int totalChunks = chunkPaths.size();
        final double totalParts = (totalChunks * 2) + Math.max(1, (totalChunks * 2) / 4.0); // request + success per chunk + analysis weight
        try {
            for (int i = 0; i < chunkPaths.size(); i++) {
                final int index = i;
                final Path chunkPath = chunkPaths.get(i);
                futures.put(index, executor.submit(() -> {
                    long sizeBytes = Files.size(chunkPath);
                    Log.i(TAG, "Transcribing chunk " + (index + 1) + "/" + chunkPaths.size()
                            + ": " + chunkPath.getFileName() + " (" + formatBytes(sizeBytes) + ")");
                    int requested = doneCount.incrementAndGet();
                    setProgressStage("transcribing", calculatePercent(requested, totalParts));
                    String transcription;
                    try {
                        transcription = provider.transcribeChunk(chunkPath, index, chunkPaths.size(), 2);
                    } catch (Exception e) {
                        Log.e(TAG, "Chunk " + (index + 1) + " failed after retries; skipping section", e);
                        int finished = doneCount.incrementAndGet();
                        setProgressStage("transcribing", calculatePercent(finished, totalParts));
                        return "";
                    }
                    double offsetSeconds = index * TRANSCRIPTION_CHUNK_SECONDS;
                    String adjusted = applyOffset(transcription, offsetSeconds);
                    Log.i(TAG, "Chunk " + (index + 1) + " done, adjusted length=" + adjusted.length());
                    int finished = doneCount.incrementAndGet();
                    setProgressStage("transcribing", calculatePercent(finished, totalParts));
                    return adjusted;
                }));
            }
            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < chunkPaths.size(); i++) {
                Future<String> f = futures.get(i);
                if (f != null) {
                    combined.append(f.get());
                }
            }
            // Analysis weight
            setProgressStage("analyzing", calculatePercent(doneCount.get(), totalParts));
            int analysisParts = Math.max(1, (int) Math.round((totalChunks * 2) / 4.0));
            int finalDone = doneCount.addAndGet(analysisParts);
            setProgressStage("analyzing", calculatePercent(finalDone, totalParts));
            return combined.toString();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } finally {
            executor.shutdownNow();
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
        return "You are a classifier that only finds advertisement or sponsor segments in podcasts. " +
                "An advertisement is a sponsor read, mid-roll, pre-roll, post-roll, or explicit promotion (coupon codes, giveaways, discounts). " +
                "Do not tag normal banter, housekeeping, or episode content as ads. " +
                "Use seconds from start of episode for times. Respond ONLY with valid JSON matching {\"ads\":[{\"startSeconds\":number,\"endSeconds\":number,\"reason\":string,\"confidence\":number}]} and nothing else.\n\n" +
                "Episode duration seconds: " + durationMs / 1000f + "\n" +
                "Transcript (WebVTT):\n\n" +
                transcript +
                "\n\nAgain, output only the JSON structure.";
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
}
