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

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
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

import org.greenrobot.eventbus.EventBus;

/**
 * Worker that analyzes an existing transcript to detect ad segments.
 * Requires that transcription has already been completed via TranscriptionWorker.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class AdAnalysisWorker extends Worker {
    public static final String DATA_FEED_ITEM_ID = "feedItemId";
    private static final String PROGRESS_KEY_PERCENT = "analysis_progress_percent";
    private static final String PROGRESS_KEY_STAGE = "analysis_progress_stage";
    private static final String TAG = "AdAnalysisWorker";

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

        // Load existing transcript
        String transcript = loadTranscript(media);
        if (TextUtils.isEmpty(transcript)) {
            Log.e(TAG, "No transcript available for analysis");
            saveError(feedItemId, "No transcript available. Please transcribe the episode first.", null, null);
            return Result.failure();
        }

        AdAnalysisProvider analysisProvider = null;

        try {
            analysisProvider = AdAnalysisProviderFactory.createAnalysisProvider(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Analysis provider could not be created", e);
            saveError(feedItemId, e.getMessage(), null, transcript);
            closeProvider(analysisProvider);
            return Result.failure();
        }

        try {
            Log.i(TAG, "Ad analysis started for feedItemId=" + feedItemId
                    + ", title=" + item.getTitle());
            setProgressStage("analyzing", 10);

            // Analyze the transcript
            Log.i(TAG, "Requesting ad classification using model " + analysisProvider.getModelName());
            setProgressStage("analyzing", 50);
            String content = analysisProvider.analyzeTranscript(buildPrompt(transcript, media.getDuration()));
            Log.i(TAG, "Model response content: " + content);
            Log.i(TAG, "Model response received, raw length=" + content.length());
            List<AdSegment> segments = mergeSegments(parseSegments(content));
            Log.i(TAG, "Ad analysis finished: " + segments.size() + " segment(s) detected");
            AdSegmentStore.save(getApplicationContext(), feedItemId,
                    new AdAnalysisResult(segments, System.currentTimeMillis(), analysisProvider.getModelName(), "",
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
                saveError(feedItemId, e.getMessage(),
                        analysisProvider != null ? analysisProvider.getModelName() : "unknown",
                        transcript);
            }
            return Result.failure();
        } finally {
            closeProvider(analysisProvider);
        }
    }

    private String loadTranscript(FeedMedia media) {
        String transcriptFileUrl = media.getTranscriptFileUrl();
        if (TextUtils.isEmpty(transcriptFileUrl)) {
            return null;
        }
        try {
            File transcriptFile = new File(transcriptFileUrl);
            if (transcriptFile.exists()) {
                return FileUtils.readFileToString(transcriptFile, (String) null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load transcript", e);
        }
        return null;
    }

    private void closeProvider(AdAnalysisProvider ap) {
        if (ap != null) {
            try {
                ap.close();
            } catch (Exception ignored) { }
        }
    }

    private void saveError(long feedItemId, String error, String modelName, String transcript) {
        if (modelName == null) {
            modelName = "unknown";
        }
        AdSegmentStore.save(getApplicationContext(), feedItemId,
                new AdAnalysisResult(Collections.emptyList(), System.currentTimeMillis(),
                        modelName, error, transcript));
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
