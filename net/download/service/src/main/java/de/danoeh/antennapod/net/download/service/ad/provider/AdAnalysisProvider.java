package de.danoeh.antennapod.net.download.service.ad.provider;

import android.os.Build;

import androidx.annotation.RequiresApi;

import java.nio.file.Path;

/**
 * Strategy interface for AI providers that can transcribe audio and analyze ad segments.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public interface AdAnalysisProvider {

    /**
     * Returns the model name or provider identifier used for storing results.
     */
    String getModelName();

    /**
     * Maximum audio size in bytes that can be sent to the provider per request.
     */
    long getMaxAudioBytes();

    /**
     * Transcribes a single chunk of audio, retrying up to the supplied maximum attempts.
     */
    String transcribeChunk(Path chunkPath, int chunkIndex, int totalChunks, int maxRetries) throws Exception;

    /**
     * Runs the ad analysis using the provided prompt and returns the raw provider response.
     */
    String analyzeTranscript(String prompt) throws Exception;

    /**
     * Returns true if the given exception indicates a permanent error and work should not be retried.
     */
    boolean shouldNotRetry(Throwable throwable);

    /**
     * Human-readable error message to persist when analysis fails.
     */
    String buildErrorMessage(Throwable throwable);
}
