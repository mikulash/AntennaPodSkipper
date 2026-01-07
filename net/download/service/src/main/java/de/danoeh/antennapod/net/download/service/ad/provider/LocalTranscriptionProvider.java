package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import de.danoeh.antennapod.net.download.service.ad.transcription.TranscriptionManager;
import de.danoeh.antennapod.net.download.service.ad.vosk.VoskTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalTranscriptionProvider implements TranscriptionProvider {
    private static final String TAG = "LocalTranscriptionProv";

    // No audio size limit for local transcription
    private static final long MAX_AUDIO_BYTES = Long.MAX_VALUE;

    private final TranscriptionManager transcriptionManager;
    private final String localModelName;

    public LocalTranscriptionProvider(Context context) throws IOException {
        this(context, null);
    }

    public LocalTranscriptionProvider(Context context, String overrideModelId) throws IOException {
        this.transcriptionManager = new VoskTranscriptionManager(context);

        String selectedLocalModel = overrideModelId;
        if (TextUtils.isEmpty(selectedLocalModel)) {
            selectedLocalModel = LocalAiPreferences.getLocalTranscriptionModel(context);
        }
        if (TextUtils.isEmpty(selectedLocalModel)) {
            selectedLocalModel = VoskTranscriptionManager.DEFAULT_MODEL_ID;
        }
        this.localModelName = selectedLocalModel;

        if (!transcriptionManager.isModelDownloaded(localModelName)) {
            throw new IOException("Local transcription model not downloaded: " + localModelName);
        }

        if (!transcriptionManager.hasEnoughMemory(localModelName)) {
            long requiredMb = transcriptionManager.getMinMemoryRequired(localModelName) / 1_000_000;
            throw new IOException("Not enough memory to load " + localModelName + " model. "
                    + "Required: " + requiredMb + " MB.");
        }

        Log.i(TAG, "Loading local transcription model: " + localModelName);
        transcriptionManager.loadModel(localModelName);
    }

    @Override
    public long getMaxAudioBytes() {
        return MAX_AUDIO_BYTES;
    }

    @Override
    public String transcribeChunk(Path chunkPath, int chunkIndex, int totalChunks, int maxRetries) throws Exception {
        String chunkLabel = (chunkIndex + 1) + "/" + totalChunks;

        if (chunkPath == null || !chunkPath.toFile().exists()) {
            throw new IOException("Chunk file missing: " + chunkPath);
        }

        Log.d(TAG, "Local transcription for chunk " + chunkLabel);

        int attempt = 0;
        while (true) {
            try {
                attempt++;
                File audioFile = chunkPath.toFile();
                // Assuming 150 second chunks
                double offsetSeconds = chunkIndex * 150.0;

                String vttResult = transcriptionManager.transcribeChunk(audioFile, offsetSeconds);
                Log.d(TAG, "Local transcription result: " + vttResult);
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
    public boolean shouldNotRetry(Throwable throwable) {
        String message = throwable.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.US);

        if (normalized.contains("model not loaded"))
            return true;
        if (normalized.contains("no audio track"))
            return true;
        if (normalized.contains("out of memory"))
            return true;
        if (normalized.contains("not enough memory"))
            return true;
        if (throwable instanceof OutOfMemoryError)
            return true;

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

    @Override
    public void close() {
        if (transcriptionManager != null) {
            transcriptionManager.unloadModel();
        }
    }
}
