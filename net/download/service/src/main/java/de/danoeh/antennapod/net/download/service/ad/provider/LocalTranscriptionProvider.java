package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

/**
 * Ad analysis provider that uses local on-device transcription (Vosk)
 * combined with OpenAI for ad segment analysis.
 *
 * This provider transcribes audio locally on the device, saving API costs
 * for transcription while still using GPT for intelligent ad detection.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalTranscriptionProvider implements AdAnalysisProvider {
    private static final String TAG = "LocalTranscriptionProv";
    private static final String MODEL_NAME_PREFIX = "local-vosk+";
    private static final String DEFAULT_GPT_MODEL = "gpt-5-nano";

    // No audio size limit for local transcription
    private static final long MAX_AUDIO_BYTES = Long.MAX_VALUE;

    // Pricing for GPT analysis only (no Whisper costs)
    private static final double PRICE_INPUT_PER_1M = 0.15;
    private static final double PRICE_OUTPUT_PER_1M = 0.60;

    private final Context context;
    private final LocalTranscriptionManager transcriptionManager;
    private final OpenAIClient openAiClient;
    private final String gptModelName;
    private final String localModelName;

    public LocalTranscriptionProvider(Context context) throws IOException {
        this.context = context;
        this.transcriptionManager = new LocalTranscriptionManager(context);

        // Load local model
        String selectedLocalModel = OpenAiPreferences.getLocalTranscriptionModel(context);
        if (TextUtils.isEmpty(selectedLocalModel)) {
            selectedLocalModel = LocalTranscriptionManager.MODEL_SMALL;
        }
        this.localModelName = selectedLocalModel;

        if (!transcriptionManager.isModelDownloaded(localModelName)) {
            throw new IOException("Local transcription model not downloaded: " + localModelName);
        }

        // Check memory before attempting to load
        if (!transcriptionManager.hasEnoughMemory(localModelName)) {
            long requiredMb = transcriptionManager.getMinMemoryRequired(localModelName) / 1_000_000;
            throw new IOException("Not enough memory to load " + localModelName + " model. "
                    + "Required: " + requiredMb + " MB. Try a smaller model or close other apps.");
        }

        Log.i(TAG, "Loading local transcription model: " + localModelName);
        transcriptionManager.loadModel(localModelName);
        Log.i(TAG, "Local transcription model loaded successfully");

        // Initialize OpenAI client for analysis
        Log.i(TAG, "Initializing OpenAI Cloud Analysis...");
        String apiKey = OpenAiPreferences.getApiKey(context);
        if (TextUtils.isEmpty(apiKey)) {
            throw new IllegalStateException("Missing OpenAI API key for ad analysis");
        }
        this.openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        String storedModel = OpenAiPreferences.getModel(context);
        this.gptModelName = TextUtils.isEmpty(storedModel) ? DEFAULT_GPT_MODEL : storedModel;
    }

    @Override
    public String getModelName() {
        return MODEL_NAME_PREFIX + gptModelName;
    }

    @Override
    public long getMaxAudioBytes() {
        return MAX_AUDIO_BYTES;
    }

    @Override
    public String transcribeChunk(Path chunkPath, int chunkIndex, int totalChunks, int maxRetries)
            throws Exception {
        String chunkLabel = (chunkIndex + 1) + "/" + totalChunks;

        if (chunkPath == null || !java.nio.file.Files.exists(chunkPath)) {
            throw new IOException("Chunk file missing: " + chunkPath);
        }

        Log.d(TAG, "Local transcription for chunk " + chunkLabel);

        int attempt = 0;
        while (true) {
            try {
                attempt++;
                File audioFile = chunkPath.toFile();

                // Calculate offset based on chunk index
                // Assuming 150 second chunks as per AdAnalysisWorker
                double offsetSeconds = chunkIndex * 150.0;

                String vttResult = transcriptionManager.transcribeChunk(audioFile, offsetSeconds);
                Log.d(TAG, "Local transcription " + chunkLabel + " complete, length=" + vttResult.length());

                return vttResult;

            } catch (Exception e) {
                boolean last = attempt > maxRetries;
                Log.w(TAG, "Local transcription attempt " + attempt + " failed for chunk "
                        + chunkLabel + ": " + e.getMessage()
                        + (last ? " (giving up)" : " (retrying)"), e);
                if (last) {
                    throw e;
                }
                Thread.sleep(500L * attempt);
            }
        }
    }

    @Override
    public String analyzeTranscript(String prompt) throws Exception {
        if (openAiClient == null) {
            throw new IllegalStateException("OpenAI client not initialized");
        }

        ChatModel chatModel = resolveChatModel(gptModelName);
        ChatCompletionCreateParams chatParams = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(chatModel)
                .build();

        ChatCompletion completion = openAiClient.chat().completions().create(chatParams);
        if (completion.choices().isEmpty()) {
            throw new IllegalStateException("AI provider returned no choices");
        }

        Log.d(TAG, "analyzeTranscript: completion usage" + completion.usage());
        completion.usage().ifPresent(this::trackTokenUsage);

        return completion.choices().get(0).message().content().orElse("");
    }

    private void trackTokenUsage(CompletionUsage usage) {
        long input = usage.promptTokens();
        long output = usage.completionTokens();
        long total = usage.totalTokens();

        OpenAiPreferences.addAnalysisTokens(context, total);

        double cost = (input / 1_000_000.0 * PRICE_INPUT_PER_1M)
                + (output / 1_000_000.0 * PRICE_OUTPUT_PER_1M);
        OpenAiPreferences.addCost(context, cost);
    }

    @Override
    public boolean shouldNotRetry(Throwable throwable) {
        String message = throwable.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.US);

        // Local transcription specific errors
        if (normalized.contains("model not loaded")) {
            return true;
        }
        if (normalized.contains("no audio track")) {
            return true;
        }
        if (normalized.contains("out of memory")) {
            return true;
        }
        if (normalized.contains("not enough memory")) {
            return true;
        }
        if (throwable instanceof OutOfMemoryError) {
            return true;
        }

        Throwable cause = throwable.getCause();
        return cause != null && shouldNotRetry(cause);
    }

    @Override
    public String buildErrorMessage(Throwable throwable) {
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        if (shouldNotRetry(throwable)) {
            return message + " (Local transcription failed - check model and audio format)";
        }
        return message;
    }

    private ChatModel resolveChatModel(String selectedModel) {
        if (TextUtils.isEmpty(selectedModel)) {
            return ChatModel.GPT_5_NANO;
        }
        switch (selectedModel) {
            case "gpt-5.1":
                return ChatModel.GPT_5_1;
            case "gpt-5-mini":
                return ChatModel.GPT_5_MINI;
            case "gpt-5-nano":
                return ChatModel.GPT_5_NANO;
            default:
                try {
                    return ChatModel.of(selectedModel);
                } catch (Exception e) {
                    Log.w(TAG, "Unknown model " + selectedModel + ", falling back to default", e);
                    return ChatModel.GPT_5_NANO;
                }
        }
    }

    /**
     * Clean up resources when done.
     */
    @Override
    public void close() {
        if (transcriptionManager != null) {
            transcriptionManager.unloadModel();
        }
    }
}
