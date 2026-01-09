package de.danoeh.antennapod.net.ai.service.ad.provider;

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
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class OpenAiTranscriptionProvider implements TranscriptionProvider {
    private static final String TAG = "OpenAiTranscriptionProv";
    private static final long MAX_OPENAI_AUDIO_BYTES = 25L * 1024L * 1024L; // 25 MiB hard limit
    private static final double PRICE_WHISPER_PER_MIN = 0.006;

    private final Context context;
    private final OpenAIClient client;
    private final String languageOverride;

    public OpenAiTranscriptionProvider(Context context) {
        this(context, null);
    }

    public OpenAiTranscriptionProvider(Context context, String languageOverride) {
        this.context = context;
        this.languageOverride = languageOverride;
        String apiKey = OpenAiPreferences.getApiKey(context);
        if (TextUtils.isEmpty(apiKey)) {
            throw new IllegalStateException("Missing OpenAI API key");
        }
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    @Override
    public long getMaxAudioBytes() {
        return MAX_OPENAI_AUDIO_BYTES;
    }

    @Override
    public String transcribeChunk(Path chunkPath, int chunkIndex, int totalChunks, int maxRetries) throws Exception {
        int attempt = 0;
        String chunkLabel = (chunkIndex + 1) + "/" + totalChunks;
        while (true) {
            if (chunkPath == null || !Files.exists(chunkPath)) {
                throw new IOException("Chunk file missing: " + chunkPath);
            }
            TranscriptionCreateParams.Builder paramsBuilder = TranscriptionCreateParams.builder()
                    .model(AudioModel.WHISPER_1)
                    .file(chunkPath)
                    .responseFormat(AudioResponseFormat.VTT);

            // Add language hint if specified
            if (!TextUtils.isEmpty(languageOverride)) {
                paramsBuilder.language(languageOverride);
                Log.d(TAG, "Using language override: " + languageOverride);
            }

            TranscriptionCreateParams transcriptionParams = paramsBuilder.build();
            Log.d(TAG, "Transcription attempt " + attempt + " for chunk " + chunkLabel);
            try {
                attempt++;
                TranscriptionCreateResponse response = client.audio().transcriptions()
                        .create(transcriptionParams);
                Log.d(TAG, "Transcription " + chunkLabel + " response received OK");

                return response.asTranscription().text();
            } catch (OpenAIIoException e) {
                boolean last = attempt > maxRetries;
                Log.w(TAG, "Transcription attempt " + attempt + " failed (" + e.getMessage() + ")", e);
                if (last) {
                    throw e;
                }
                Thread.sleep(500L * attempt);
            }
        }
    }

    @Override
    public boolean shouldNotRetry(Throwable throwable) {
        String message = throwable.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.US);
        if (normalized.contains("unsupported audio mime type") || normalized.contains("exceeds 25 mb")) {
            return true;
        }
        if (throwable instanceof BadRequestException) {
            return normalized.contains("could not be decoded") || normalized.contains("format is not supported");
        }
        Throwable cause = throwable.getCause();
        return cause != null && shouldNotRetry(cause);
    }

    @Override
    public String buildErrorMessage(Throwable throwable) {
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        if (shouldNotRetry(throwable)) {
            return message + " (OpenAI supports limited audio formats up to 25 MB)";
        }
        return message;
    }

    @Override
    public void close() {
    }
}
