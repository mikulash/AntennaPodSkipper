package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager;
import de.danoeh.antennapod.net.download.service.ad.litert.LlmModel;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalAdAnalysisProvider implements AdAnalysisProvider {
    private static final String TAG = "LocalAdAnalysisProv";
    private static final String MODEL_NAME_PREFIX = "local-litert+";
    private static final int DEFAULT_MAX_TOKENS = 2048; // Default max cache size
    private static final int MAX_PROMPT_CHARS = 1500; // Conservative limit for chunking (~500 tokens)
    private static final com.google.mediapipe.tasks.genai.llminference.ProgressListener<String> NO_OP_LISTENER =
            (result, done) -> { };

    private final Context context;
    private final LiteRtLLMManager llmManager;
    private final String litertModelId;
    private final LlmModel llmModelConfig;
    private LlmInference llmInference;
    private LlmInferenceSession llmSession;

    public LocalAdAnalysisProvider(Context context) throws IOException {
        this.context = context;
        this.llmManager = new LiteRtLLMManager(context);
        this.litertModelId = LocalAiPreferences.getLocalAdAnalysisModel(context);
        this.llmModelConfig = LlmModel.fromId(litertModelId);

        if (!llmManager.isModelDownloaded(litertModelId)) {
            throw new IOException("Local analysis model not downloaded: " + litertModelId);
        }

        initializeLlmInference();
    }

    private LlmInference.Backend toMediaPipeBackend(LlmModel.BackendType backendType) {
        if (backendType == null) {
            return LlmInference.Backend.GPU;
        }
        switch (backendType) {
            case GPU:
                return LlmInference.Backend.GPU;
            case CPU:
            default:
                return LlmInference.Backend.CPU;
        }
    }

    private void initializeLlmInference() throws IOException {
        Log.i(TAG, "Initializing LiteRT LLM Inference with model: " + litertModelId);
        File modelFile = llmManager.getModelPath(litertModelId);

        // Clear any stale XNNPack cache to avoid native crashes when loading the model.
        llmManager.clearXnnpackCache(litertModelId);

        // Use model-specific configuration if available
        LlmInference.Backend backend = llmModelConfig != null
                ? toMediaPipeBackend(llmModelConfig.getPreferredBackend())
                : LlmInference.Backend.GPU;
        int maxTokens = llmModelConfig != null
                ? llmModelConfig.getMaxTokens()
                : DEFAULT_MAX_TOKENS;

        LlmInferenceOptions.Builder optionsBuilder = LlmInferenceOptions.builder()
                .setModelPath(modelFile.getAbsolutePath())
                .setPreferredBackend(backend)
                .setMaxTokens(maxTokens);

        LlmInferenceOptions options = optionsBuilder.build();

        try {
            this.llmInference = LlmInference.createFromOptions(context, options);
            this.llmSession = createSession();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Failed to get metadata") || msg.contains("odml.infra.proto.LlmParameters"))) {
                throw new IOException("Invalid Model Format: The selected file is a raw TFLite model. " +
                        "MediaPipe requires a Task Bundle (.bin/.task) with metadata. " +
                        "Please convert your model or download a compatible bundle.", e);
            }
            throw new IOException("Failed to initialize MediaPipe engine: " + e.getMessage(), e);
        }
    }

    private LlmInferenceSession createSession() throws IOException {
        if (llmInference == null) {
            throw new IOException("LLM engine not initialized");
        }
        LlmInferenceSessionOptions.Builder optionsBuilder = LlmInferenceSessionOptions.builder();
        float temperature = llmModelConfig != null ? llmModelConfig.getTemperature() : 1.0f;
        int topK = llmModelConfig != null ? llmModelConfig.getTopK() : 40;
        float topP = llmModelConfig != null ? llmModelConfig.getTopP() : 0.95f;
        optionsBuilder
                .setTemperature(temperature)
                .setTopK(topK)
                .setTopP(topP);
        try {
            return LlmInferenceSession.createFromOptions(llmInference, optionsBuilder.build());
        } catch (Exception e) {
            throw new IOException("Failed to create LLM session: " + e.getMessage(), e);
        }
    }

    @Override
    public String getModelName() {
        return MODEL_NAME_PREFIX + litertModelId;
    }

    @Override
    public String analyzeTranscript(String prompt) throws Exception {
        return analyzeTranscript(prompt, null);
    }

    @Override
    public String analyzeTranscript(String prompt, ProgressListener listener) throws Exception {
        if (llmInference == null || llmSession == null) {
            throw new IllegalStateException("LLM engine not initialized");
        }
        Log.i(TAG, "Running LiteRT analysis...");

        // Check if the prompt is too long and needs chunking
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return analyzeInChunks(prompt, listener);
        }

        if (listener != null) {
            listener.onProgress(10);
        }
        String formattedPrompt = formatPromptForModel(prompt);
        String result = generateResponse(formattedPrompt);
        Log.d(TAG, "LiteRT result: " + result);
        if (listener != null) {
            listener.onProgress(100);
        }
        return result;
    }

    private String analyzeInChunks(String fullPrompt, ProgressListener listener) throws Exception {
        Log.i(TAG, "Transcript too long, analyzing in chunks...");

        // Extract transcript from the prompt
        String transcript = extractTranscript(fullPrompt);
        int durationMs = extractDuration(fullPrompt);

        // Split transcript into chunks
        List<TranscriptChunk> chunks = splitTranscript(transcript);
        Log.i(TAG, "Split into " + chunks.size() + " chunks");

        // Analyze each chunk
        JSONArray allAds = new JSONArray();
        for (int i = 0; i < chunks.size(); i++) {
            TranscriptChunk chunk = chunks.get(i);
            Log.i(TAG, "Analyzing chunk " + (i + 1) + "/" + chunks.size() +
                    " (time offset: " + chunk.startTimeSeconds + "s)");

            // Report progress based on chunk completion
            if (listener != null) {
                int percent = (int) ((i / (float) chunks.size()) * 100);
                listener.onProgress(percent);
            }

            String chunkPrompt = buildChunkPrompt(chunk.text, durationMs, chunk.startTimeSeconds);
            String formattedPrompt = formatPromptForModel(chunkPrompt);

            try {
                // Recreate session for each chunk to keep context isolated
                recreateSessionOnly();

                String result = generateResponse(formattedPrompt);
                Log.d(TAG, "Chunk " + (i + 1) + " result: " + result);

                // Parse and merge results
                mergeChunkResults(allAds, result);
            } catch (Exception e) {
                Log.w(TAG, "Chunk " + (i + 1) + " analysis failed: " + e.getMessage());
            }
        }

        if (listener != null) {
            listener.onProgress(100);
        }

        // Build final result
        JSONObject finalResult = new JSONObject();
        finalResult.put("ads", allAds);
        return finalResult.toString();
    }

    private void reinitializeLlmInference() throws IOException {
        // Close existing inference
        if (llmInference != null) {
            try {
                llmInference.close();
            } catch (Exception ignored) {
            }
        }
        if (llmSession != null) {
            try {
                llmSession.close();
            } catch (Exception ignored) {
            }
            llmSession = null;
        }
        // Create new instance
        initializeLlmInference();
    }

    private void recreateSessionOnly() throws IOException {
        if (llmSession != null) {
            try {
                llmSession.close();
            } catch (Exception ignored) {
            }
        }
        llmSession = createSession();
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

        for (String line : lines) {
            // Parse timestamp lines to track time
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

            // Check if chunk is large enough to split
            if (currentChunk.length() > MAX_PROMPT_CHARS - 300) {
                chunks.add(new TranscriptChunk(currentChunk.toString(), chunkStartTime));
                currentChunk = new StringBuilder();
                chunkStartTime = lastEndTime;
            }
        }

        // Add remaining content
        if (currentChunk.length() > 0) {
            chunks.add(new TranscriptChunk(currentChunk.toString(), chunkStartTime));
        }

        return chunks;
    }

    private double parseTimestamp(String timestamp) {
        // Format: HH:MM:SS.mmm
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
        return "You are a classifier that only finds advertisement or sponsor segments in podcasts. "
                + "An advertisement is a sponsor read, mid-roll, pre-roll, post-roll, "
                + "or explicit promotion (coupon codes, giveaways, discounts). "
                + "Do not tag normal banter, housekeeping, or episode content as ads. "
                + "Use seconds from start of episode for times. "
                + "This is a portion of the transcript starting at " + (int) timeOffset + " seconds. "
                + "Respond ONLY with valid JSON matching {\"ads\":[{\"startSeconds\":number,\"endSeconds\":number,"
                + "\"reason\":string,\"confidence\":number}]} and nothing else.\n\n"
                + "Episode duration seconds: " + durationMs / 1000f + "\n"
                + "Transcript portion (WebVTT):\n\n"
                + chunkText + "\n\nAgain, output only the JSON structure.";
    }

    private void mergeChunkResults(JSONArray allAds, String chunkResult) {
        try {
            // Clean up the result
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

    private String formatPromptForModel(String rawPrompt) {
        // Use model-specific prompt formatting if available
        if (llmModelConfig != null) {
            return llmModelConfig.formatPrompt(rawPrompt);
        }

        // Fallback: For manually imported models or unknown models, assume Gemma format
        return "<start_of_turn>user\n" + rawPrompt + "<end_of_turn>\n<start_of_turn>model\n";
    }

    private String generateResponse(String formattedPrompt) throws Exception {
        if (llmSession == null) {
            throw new IllegalStateException("LLM session not initialized");
        }
        llmSession.addQueryChunk(formattedPrompt);
        ListenableFuture<String> future = llmSession.generateResponseAsync(NO_OP_LISTENER);
        return future.get();
    }

    @Override
    public void close() {
        if (llmSession != null) {
            try {
                llmSession.close();
            } catch (Exception ignored) {
            }
        }
        if (llmInference != null) {
            try {
                llmInference.close();
            } catch (Exception ignored) {
            }
        }
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
