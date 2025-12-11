package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import com.openai.models.ChatModel;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class OpenAiAdAnalysisProvider implements AdAnalysisProvider {
    private static final String TAG = "OpenAiAdProvider";
    private static final String DEFAULT_MODEL_NAME = "gpt-5-nano";
    private static final long MAX_OPENAI_AUDIO_BYTES = 25L * 1024L * 1024L; // 25 MiB hard limit

    // Pricing (Estimated)
    private static final double PRICE_WHISPER_PER_MIN = 0.006;
    private static final double PRICE_INPUT_PER_1M = 0.15; // $0.15 per 1M input tokens (approx gpt-4o-mini)
    private static final double PRICE_OUTPUT_PER_1M = 0.60; // $0.60 per 1M output tokens

    private final Context context;
    private final OpenAIClient client;
    private final String modelName;

    public OpenAiAdAnalysisProvider(Context context) {
        this.context = context;
        String apiKey = OpenAiPreferences.getApiKey(context);
        if (TextUtils.isEmpty(apiKey)) {
            throw new IllegalStateException("Missing OpenAI API key");
        }
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        String storedModel = OpenAiPreferences.getModel(context);
        this.modelName = TextUtils.isEmpty(storedModel) ? DEFAULT_MODEL_NAME : storedModel;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public long getMaxAudioBytes() {
        return MAX_OPENAI_AUDIO_BYTES;
    }

    @Override
    public String transcribeChunk(Path chunkPath, int chunkIndex, int totalChunks, int maxRetries)
            throws Exception {
        int attempt = 0;
        String chunkLabel = (chunkIndex + 1) + "/" + totalChunks;
        while (true) {
            if (chunkPath == null || !Files.exists(chunkPath)) {
                throw new IOException("Chunk file missing: " + chunkPath);
            }
            TranscriptionCreateParams transcriptionParams = TranscriptionCreateParams.builder()
                    .model(AudioModel.WHISPER_1)
                    .file(chunkPath)
                    .responseFormat(AudioResponseFormat.VTT)
                    .build();
            Log.d(TAG, "Transcription attempt " + attempt + " for chunk " + chunkLabel
                    + " with params: " + transcriptionParams);
            try {
                attempt++;
                TranscriptionCreateResponse response = client.audio().transcriptions()
                        .create(transcriptionParams);
                Log.d(TAG, "Transcription " + chunkLabel + " response received OK");

                // Track usage
                trackAudioUsage(chunkPath);

                return response.asTranscription().text();
            } catch (OpenAIIoException e) {
                boolean last = attempt > maxRetries;
                Log.w(TAG, "Transcription attempt " + attempt + " failed for chunk "
                        + chunkLabel + " (" + chunkPath.getFileName() + "): " + e.getMessage()
                        + (last ? " (giving up)" : " (retrying)"), e);
                Log.e(TAG, "ATTEMPT FAILED ERR " + e + "|" + e.getCause() + "|" + e.getMessage() + "|" + e.getCause());
                if (last) {
                    throw e;
                }
                Thread.sleep(500L * attempt);
            }
        }
    }

    @Override
    public String analyzeTranscript(String prompt) throws Exception {
        ChatModel chatModel = resolveChatModel(modelName);
        ChatCompletionCreateParams chatParams = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(chatModel)
                .build();
        ChatCompletion completion = client.chat().completions().create(chatParams);
        if (completion.choices().isEmpty()) {
            throw new IllegalStateException("AI provider returned no choices");
        }
        Log.d(TAG, "analyzeTranscript: completion usage" + completion.usage());
        // Track usage
        completion.usage().ifPresent(this::trackTokenUsage);

        return completion.choices().get(0).message().content().orElse("");
    }

    private void trackAudioUsage(Path chunkPath) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, android.net.Uri.fromFile(chunkPath.toFile()));
            String time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Log.d(TAG, "Audio duration for " + chunkPath + ": " + time);
            if (time != null) {
                long durationMs = Long.parseLong(time);
                OpenAiPreferences.addAudioDuration(context, durationMs);

                double minutes = durationMs / 1000.0 / 60.0;
                double cost = minutes * PRICE_WHISPER_PER_MIN;
                OpenAiPreferences.addCost(context, cost);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to track audio usage for " + chunkPath, e);
        }
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
        if (normalized.contains("unsupported audio mime type")) {
            return true;
        }
        if (normalized.contains("exceeds 25 mb")) {
            return true;
        }
        if (throwable instanceof BadRequestException) {
            return normalized.contains("could not be decoded")
                    || normalized.contains("format is not supported");
        }
        Throwable cause = throwable.getCause();
        return cause != null && shouldNotRetry(cause);
    }

    @Override
    public String buildErrorMessage(Throwable throwable) {
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        if (shouldNotRetry(throwable)) {
            return message + " (OpenAI supports mp3, mp4/m4a, mpeg/mpga, wav, and webm up to 25 MB per file)";
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

    @Override
    public void close() {
    }
}
