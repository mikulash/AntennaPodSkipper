package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.danoeh.antennapod.net.download.service.ad.litert.InferenceModel;
import de.danoeh.antennapod.net.download.service.ad.litert.LlmModel;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

/**
 * Transcript analysis provider that uses on-device LLM inference via LiteRT-LM 0.8.0.
 * Uses the singleton InferenceModel with .litertlm format models.
 * The model is loaded once and reused across multiple analysis requests.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalTranscriptAnalysisProvider implements TranscriptAnalysisProvider {
    private static final String TAG = "LocalTranscriptAnalysisProv";
    private static final String MODEL_NAME_PREFIX = "local-litert+";

    // Characters per token - use conservative 1:1 ratio since LLM tokenization
    // often results in more tokens than characters (especially for punctuation,
    // special chars, and non-English text in transcripts)
    private static final float CHARS_PER_TOKEN = 1.0f;

    // The instruction prompt template adds ~400 characters for system message
    private static final int SYSTEM_MESSAGE_TOKENS = 400;

    // Reserve tokens for the expected JSON response
    private static final int RESPONSE_TOKENS = 150;

    // Total reserved = system message + response + safety margin
    private static final int RESERVED_TOKENS = SYSTEM_MESSAGE_TOKENS + RESPONSE_TOKENS + 50;

    private static final int MAX_UNUSED_TOKEN_COUNT = 20; // Max consecutive <unused> tokens

    // Base system message for ad classification
    private static final String SYSTEM_MESSAGE_BASE =
            "You are a classifier that only finds advertisement or sponsor segments in podcasts. "
            + "An advertisement is a sponsor read, mid-roll, pre-roll, post-roll, "
            + "or explicit promotion (coupon codes, giveaways, discounts). "
            + "Do not tag normal banter, housekeeping, or episode content as ads. "
            + "Use seconds from start of episode for times. "
            + "Respond ONLY with valid JSON matching {\"ads\":[{\"startSeconds\":number,\"endSeconds\":number,"
            + "\"reason\":string,\"confidence\":number}]} and nothing else.";

    private final Context context;
    private final String modelId;
    private InferenceModel inferenceModel;

    public LocalTranscriptAnalysisProvider(Context context) throws IOException {
        this.context = context.getApplicationContext();
        this.modelId = LocalAiPreferences.getLocalAdAnalysisModel(context);

        try {
            // Get the singleton instance - model is loaded only once
            this.inferenceModel = InferenceModel.getInstance(context);
            Log.i(TAG, "Using singleton InferenceModel for: " + modelId);
        } catch (InferenceModel.ModelLoadFailException e) {
            throw new IOException("Failed to initialize LLM model: " + e.getMessage(), e);
        }
    }

    /**
     * Calculate max transcript chunk size in characters.
     * Token budget breakdown:
     * - maxTokens: Total tokens the model can handle (input + output)
     * - SYSTEM_MESSAGE_TOKENS: ~400 tokens for the system instruction
     * - RESPONSE_TOKENS: ~150 tokens for the JSON response
     * - Safety margin: ~50 tokens
     * Remaining tokens are available for the transcript chunk.
     * Uses 1:1 char-to-token ratio for safety.
     */
    private int getMaxTranscriptChunkChars() {
        int maxTokens = getModelMaxTokens();
        int availableForTranscript = Math.max(100, maxTokens - RESERVED_TOKENS);
        int result = (int) (availableForTranscript * CHARS_PER_TOKEN);
        Log.d(TAG, "Token budget: maxTokens=" + maxTokens + ", reserved=" + RESERVED_TOKENS
                + ", availableForTranscript=" + availableForTranscript + " -> " + result + " chars");
        return result;
    }

    private int getModelMaxTokens() {
        LlmModel modelConfig = inferenceModel != null ? inferenceModel.getModelConfig() : null;
        if (modelConfig != null) {
            return modelConfig.getMaxTokens();
        }
        // For manual imports, use user-configured value
        return LocalAiPreferences.getManualModelMaxTokens(context);
    }

    @Override
    public String getModelName() {
        return MODEL_NAME_PREFIX + modelId;
    }

    @Override
    public String analyzeTranscript(String prompt) throws Exception {
        return analyzeTranscript(prompt, null);
    }

    @Override
    public String analyzeTranscript(String prompt, ProgressListener listener) throws Exception {
        if (inferenceModel == null) {
            throw new IllegalStateException("InferenceModel not initialized");
        }
        Log.i(TAG, "Running LiteRT-LM analysis...");

        // Extract transcript and duration from the prompt
        int durationMs = extractDuration(prompt);

        int maxTranscriptChars = getMaxTranscriptChunkChars();

        // Check if the transcript is too long and needs chunking
        if (prompt.length() > maxTranscriptChars) {
            return analyzeInChunks(prompt, durationMs, listener);
        }

        if (listener != null) {
            listener.onProgress(10);
        }

        // Build system message and user message separately
        String systemMessage = buildSystemMessage(durationMs, 0);
        String userMessage = buildUserMessage(prompt);

        // Reset session with system message
        inferenceModel.resetSession(systemMessage);

        String result = generateResponse(userMessage);
        Log.d(TAG, "LiteRT-LM result: " + result);

        if (listener != null) {
            listener.onProgress(100);
        }
        return result;
    }

    private String analyzeInChunks(String transcript, int durationMs, ProgressListener listener) throws Exception {
        Log.i(TAG, "Transcript too long, analyzing in chunks...");

        List<TranscriptChunk> chunks = splitTranscript(transcript);
        Log.i(TAG, "Split into " + chunks.size() + " chunks");

        JSONArray allAds = new JSONArray();
        for (int i = 0; i < chunks.size(); i++) {
            TranscriptChunk chunk = chunks.get(i);
            Log.i(TAG, "Analyzing chunk " + (i + 1) + "/" + chunks.size() +
                    " (time offset: " + chunk.startTimeSeconds + "s)");

            if (listener != null) {
                int percent = (int) ((i / (float) chunks.size()) * 100);
                listener.onProgress(percent);
            }

            // Build system message and user message for this chunk
            String systemMessage = buildSystemMessage(durationMs, chunk.startTimeSeconds);
            String userMessage = buildUserMessage(chunk.text);

            try {
                // Reset session with system message for each chunk
                inferenceModel.resetSession(systemMessage);

                String result = generateResponse(userMessage);
                Log.d(TAG, "Chunk " + (i + 1) + " result: " + result);

                mergeChunkResults(allAds, result);
            } catch (Exception e) {
                Log.w(TAG, "Chunk " + (i + 1) + " analysis failed: " + e.getMessage());
            }
        }

        if (listener != null) {
            listener.onProgress(100);
        }

        JSONObject finalResult = new JSONObject();
        finalResult.put("ads", allAds);
        return finalResult.toString();
    }

    /**
     * Build the system message with context about the episode.
     */
    private String buildSystemMessage(int durationMs, double timeOffset) {
        StringBuilder sb = new StringBuilder(SYSTEM_MESSAGE_BASE);
        sb.append("\n\nEpisode duration: ").append(durationMs / 1000f).append(" seconds.");
        if (timeOffset > 0) {
            sb.append("\nThis transcript portion starts at ").append((int) timeOffset).append(" seconds.");
        }
        return sb.toString();
    }

    /**
     * Build the user message containing the transcript.
     */
    private String buildUserMessage(String transcript) {
        return "Analyze this transcript for ads:\n\n" + transcript + "\n\nOutput only JSON.";
    }

    private String generateResponse(String userMessage) throws Exception {
        String result = inferenceModel.generateResponse(userMessage);

        // Validate result
        if (isGarbageOutput(result)) {
            throw new Exception("Model generated garbage output (repeated unused tokens). "
                    + "This may indicate an incompatible model format or wrong prompt template.");
        }

        return result;
    }

    private int extractDuration(String prompt) {
        Pattern pattern = Pattern.compile("Episode duration seconds: ([\\d.]+)");
        Matcher matcher = pattern.matcher(prompt);
        if (matcher.find()) {
            try {
                return (int) (Float.parseFloat(matcher.group(1)) * 1000);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private List<TranscriptChunk> splitTranscript(String transcript) {
        List<TranscriptChunk> chunks = new ArrayList<>();
        String[] lines = transcript.split("\n");

        StringBuilder currentChunk = new StringBuilder();
        double chunkStartTime = 0;
        double lastEndTime = 0;

        int maxChunkSize = getMaxTranscriptChunkChars();

        for (String line : lines) {
            if (line.contains("-->")) {
                String[] parts = line.split("-->");
                if (parts.length == 2) {
                    double startTime = parseTimestamp(parts[0].trim());
                    lastEndTime = parseTimestamp(parts[1].trim());

                    if (currentChunk.length() == 0) {
                        chunkStartTime = startTime;
                    }
                }
            }

            currentChunk.append(line).append("\n");

            if (currentChunk.length() > maxChunkSize) {
                chunks.add(new TranscriptChunk(currentChunk.toString(), chunkStartTime));
                currentChunk = new StringBuilder();
                chunkStartTime = lastEndTime;
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(new TranscriptChunk(currentChunk.toString(), chunkStartTime));
        }

        return chunks;
    }

    private double parseTimestamp(String timestamp) {
        String[] parts = timestamp.split(":");
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

    private void mergeChunkResults(JSONArray allAds, String chunkResult) {
        try {
            String cleaned = chunkResult.trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }

            JSONObject json = new JSONObject(cleaned);
            JSONArray ads = json.optJSONArray("ads");
            if (ads != null) {
                for (int i = 0; i < ads.length(); i++) {
                    allAds.put(ads.getJSONObject(i));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse chunk result: " + e.getMessage());
        }
    }

    private boolean isGarbageOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return true;
        }

        int unusedCount = 0;
        String[] tokens = output.split("<");
        for (String token : tokens) {
            if (token.startsWith("unused")) {
                unusedCount++;
            }
        }

        return unusedCount > tokens.length / 2 || unusedCount > MAX_UNUSED_TOKEN_COUNT;
    }

    @Override
    public void close() {
        // Don't close the singleton - it's shared across providers
        // The singleton is closed when the app exits or model changes
        Log.d(TAG, "LocalTranscriptAnalysisProvider closed (singleton kept alive)");
    }

    private static class TranscriptChunk {
        final String text;
        final double startTimeSeconds;

        TranscriptChunk(String text, double startTimeSeconds) {
            this.text = text;
            this.startTimeSeconds = startTimeSeconds;
        }
    }
}
