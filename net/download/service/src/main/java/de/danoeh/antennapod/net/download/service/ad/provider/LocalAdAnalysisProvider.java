package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.google.common.util.concurrent.ListenableFuture;

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
 * Ad analysis provider that uses on-device LLM inference via the singleton
 * InferenceModel.
 * The model is loaded once and reused across multiple analysis requests.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalAdAnalysisProvider implements AdAnalysisProvider {
    private static final String TAG = "LocalAdAnalysisProv";
    private static final String MODEL_NAME_PREFIX = "local-litert+";

    // Characters per token - use conservative 1:1 ratio since LLM tokenization
    // often results in more tokens than characters (especially for punctuation,
    // special chars, and non-English text in transcripts)
    private static final float CHARS_PER_TOKEN = 1.0f;

    // The instruction prompt template (buildChunkPrompt) adds ~500 characters
    // which translates to ~500 tokens with our conservative 1:1 ratio
    private static final int INSTRUCTION_PROMPT_TOKENS = 500;

    // Reserve tokens for the expected JSON response
    private static final int RESPONSE_TOKENS = 150;

    // Total reserved = instruction prompt + response + safety margin
    private static final int RESERVED_TOKENS = INSTRUCTION_PROMPT_TOKENS + RESPONSE_TOKENS + 50;

    private static final int MAX_UNUSED_TOKEN_COUNT = 20; // Max consecutive <unused> tokens
    private static final com.google.mediapipe.tasks.genai.llminference.ProgressListener<String> NO_OP_LISTENER = (
            result, done) -> {
    };

    private final Context context;
    private final String modelId;
    private InferenceModel inferenceModel;

    public LocalAdAnalysisProvider(Context context) throws IOException {
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
     * 
     * Token budget breakdown:
     * - maxTokens: Total tokens the model can handle (input + output)
     * - INSTRUCTION_PROMPT_TOKENS: ~500 tokens for the system instruction
     * - RESPONSE_TOKENS: ~150 tokens for the JSON response
     * - Safety margin: ~50 tokens
     * 
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
        Log.i(TAG, "Running LiteRT analysis...");

        int maxTranscriptChars = getMaxTranscriptChunkChars();

        // Check if the prompt is too long and needs chunking
        if (prompt.length() > maxTranscriptChars) {
            return analyzeInChunks(prompt, listener);
        }

        if (listener != null) {
            listener.onProgress(10);
        }

        String formattedPrompt = inferenceModel.formatPrompt(prompt);
        String result = generateResponse(formattedPrompt);
        Log.d(TAG, "LiteRT result: " + result);

        if (listener != null) {
            listener.onProgress(100);
        }
        return result;
    }

    private String analyzeInChunks(String fullPrompt, ProgressListener listener) throws Exception {
        Log.i(TAG, "Transcript too long, analyzing in chunks...");

        String transcript = extractTranscript(fullPrompt);
        int durationMs = extractDuration(fullPrompt);

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

            String chunkPrompt = buildChunkPrompt(chunk.text, durationMs, chunk.startTimeSeconds);
            String formattedPrompt = inferenceModel.formatPrompt(chunkPrompt);

            try {
                // Reset session for each chunk to keep context isolated
                inferenceModel.resetSession();

                String result = generateResponse(formattedPrompt);
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

    private String generateResponse(String formattedPrompt) throws Exception {
        ListenableFuture<String> future = inferenceModel.generateResponseAsync(formattedPrompt, NO_OP_LISTENER);
        String result = future.get();

        // Validate result
        if (isGarbageOutput(result)) {
            throw new Exception("Model generated garbage output (repeated unused tokens). "
                    + "This may indicate an incompatible model format or wrong prompt template.");
        }

        return result;
    }

    private String extractTranscript(String prompt) {
        int transcriptStart = prompt.indexOf("Transcript (WebVTT):");
        if (transcriptStart >= 0) {
            int start = transcriptStart + "Transcript (WebVTT):".length();
            int end = prompt.lastIndexOf("Again, output only");
            if (end > start) {
                return prompt.substring(start, end).trim();
            }
            return prompt.substring(start).trim();
        }
        return prompt;
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

        // getMaxTranscriptChunkChars already accounts for instruction prompt and
        // response overhead
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

    private String buildChunkPrompt(String chunkText, int durationMs, double timeOffset) {
        List<String> promptParts = buildChunkPrompt(durationMs, timeOffset);
        return promptParts.get(0) + chunkText + promptParts.get(1);
    }

    private List<String> buildChunkPrompt(int durationMs, double timeOffset) {
        List<String> retval = new ArrayList<>();
        retval.add("You are a classifier that only finds advertisement or sponsor segments in podcasts. "
                + "An advertisement is a sponsor read, mid-roll, pre-roll, post-roll, "
                + "or explicit promotion (coupon codes, giveaways, discounts). "
                + "Do not tag normal banter, housekeeping, or episode content as ads. "
                + "Use seconds from start of episode for times. "
                + "This is a portion of the transcript starting at " + (int) timeOffset + " seconds. "
                + "Respond ONLY with valid JSON matching {\"ads\":[{\"startSeconds\":number,\"endSeconds\":number,"
                + "\"reason\":string,\"confidence\":number}]} and nothing else.\n\n"
                + "Episode duration seconds: " + durationMs / 1000f + "\n"
                + "Transcript portion (WebVTT):\n\n");
        retval.add("\n\nAgain, output only the JSON structure.");
        return retval;
    }

    private int getJustPromptLength(int durationMs, double timeOffset) {
        List<String> promptParts = buildChunkPrompt(durationMs, timeOffset);
        int length = 0;
        for (String part : promptParts) {
            length += part.length();
        }
        int safetyMargin = 50;
        return length + safetyMargin; // safety margin
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
        Log.d(TAG, "LocalAdAnalysisProvider closed (singleton kept alive)");
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
