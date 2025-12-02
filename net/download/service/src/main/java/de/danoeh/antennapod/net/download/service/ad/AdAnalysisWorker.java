package de.danoeh.antennapod.net.download.service.ad;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

public class AdAnalysisWorker extends Worker {
    public static final String DATA_FEED_ITEM_ID = "feedItemId";
    private static final String TAG = "AdAnalysisWorker";
    private static final String MODEL_NAME = "gpt-4.1-mini";
    private static final long TRANSCRIPTION_CHUNK_SECONDS = 600; // 10 minutes

    public AdAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {

        long feedItemId = getInputData().getLong(DATA_FEED_ITEM_ID, -1);
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
        String apiKey = OpenAiPreferences.getApiKey(getApplicationContext());
        if (TextUtils.isEmpty(apiKey)) {
            saveError(feedItemId, "Missing OpenAI API key");
            return Result.success();
        }
        try {
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();

            String transcript = transcribeInChunks(client, media);

            ChatCompletionCreateParams chatParams = ChatCompletionCreateParams.builder()
                    .addUserMessage(buildPrompt(transcript, media.getDuration()))
                    .model(ChatModel.GPT_4_1_MINI)
                    .build();

            ChatCompletion completion = client.chat().completions().create(chatParams);
            String content = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                content = completion.choices().isEmpty()
                        ? ""
                        : completion.choices().get(0).message().content().orElse("");
            }
            List<AdSegment> segments = mergeSegments(parseSegments(content));
            AdSegmentStore.save(getApplicationContext(), feedItemId,
                    new AdAnalysisResult(segments, System.currentTimeMillis(), MODEL_NAME, ""));
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Ad analysis failed", e);
            saveError(feedItemId, e.getMessage());
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("401") || message.toLowerCase().contains("unauthorized")) {
                return Result.failure();
            }
            return Result.retry();
        }
    }

    private String transcribeInChunks(OpenAIClient client, FeedMedia media) throws Exception {
        List<Path> chunkPaths = AudioChunkUtils.createAudioChunks(getApplicationContext(),
                media.getLocalFileUrl(), TRANSCRIPTION_CHUNK_SECONDS);
        StringBuilder combinedVtt = new StringBuilder();
        double offsetSeconds = 0;
        try {
            for (Path chunkPath : chunkPaths) {
                TranscriptionCreateParams transcriptionParams = TranscriptionCreateParams.builder()
                        .model(AudioModel.WHISPER_1)
                        .file(chunkPath)
                        .responseFormat(AudioResponseFormat.VTT)
                        .build();
                TranscriptionCreateResponse transcription = client.audio().transcriptions()
                        .create(transcriptionParams);
                combinedVtt.append(applyOffset(transcription.asTranscription().text(), offsetSeconds));
                offsetSeconds += TRANSCRIPTION_CHUNK_SECONDS;
            }
        } finally {
            for (Path chunkPath : chunkPaths) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Files.deleteIfExists(chunkPath);
                    }
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
        }
        return combinedVtt.toString();
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
        return String.format(Locale.US,"%02d:%02d:%06.3f", hours, minutes, seconds);
    }

    private void saveError(long feedItemId, String error) {
        AdSegmentStore.save(getApplicationContext(), feedItemId,
                new AdAnalysisResult(Collections.emptyList(), System.currentTimeMillis(), MODEL_NAME, error));
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
        if (TextUtils.isEmpty(rawJson)) {
            return segments;
        }
        JSONObject root = new JSONObject(rawJson);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            input.sort(Comparator.comparingDouble(AdSegment::getStartSeconds));
        }
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
}
