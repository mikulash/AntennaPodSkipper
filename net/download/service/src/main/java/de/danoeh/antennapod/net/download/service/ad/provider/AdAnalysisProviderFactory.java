package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

/**
 * Factory to construct the configured ad analysis provider.
 * Supports both cloud-based (OpenAI Whisper) and local (Vosk) transcription.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisProviderFactory {
    private static final String TAG = "AdAnalysisProviderFactory";

    private AdAnalysisProviderFactory() {
    }

    /**
     * Creates the appropriate ad analysis provider based on user preferences.
     *
     * If local transcription is enabled, attempts to create LocalTranscriptionProvider.
     * If local transcription fails, throws an exception instead of falling back to OpenAI.
     *
     * @throws IllegalStateException if local transcription is enabled but cannot be initialized
     */
    /**
     * Creates the appropriate transcription provider based on user preferences.
     */
    public static TranscriptionProvider createTranscriptionProvider(Context context) {
        if (OpenAiPreferences.isLocalTranscriptionEnabled(context)) {
            Log.i(TAG, "Creating LocalTranscriptionProvider");
            try {
                return new LocalTranscriptionProvider(context);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create local transcription provider", e);
                throw new IllegalStateException("Failed to initialize local transcription: " + e.getMessage(), e);
            }
        } else {
            Log.i(TAG, "Creating OpenAiTranscriptionProvider");
            return new OpenAiTranscriptionProvider(context);
        }
    }

    /**
     * Creates the appropriate ad analysis provider based on user preferences.
     */
    public static AdAnalysisProvider createAnalysisProvider(Context context) throws IOException {
        if (OpenAiPreferences.isLocalAdAnalysisEnabled(context)) {
            Log.i(TAG, "Creating LocalAdAnalysisProvider");
            try {
            return new LocalAdAnalysisProvider(context);
        } catch (IOException e) {
            // Check for MediaPipe metadata error
            if (e.getMessage() != null && e.getMessage().contains("Invalid Model Format")) {
                Log.w(TAG, "MediaPipe failed (invalid format), utilizing Raw LiteRT Interpreter...");
                try {
                    return new RawAdAnalysisProvider(context);
                } catch (Exception rawEx) {
                    Log.e(TAG, "Raw Interpreter fallback also failed", rawEx);
                    throw e; // Throw original error if both fail
                }
            }
            throw e;
        }
        } else {
            Log.i(TAG, "Creating OpenAiAdAnalysisProvider");
            return new OpenAiAdAnalysisProvider(context);
        }
    }

    /**
     * Checks if local transcription is available (model downloaded and preference enabled).
     */
    public static boolean isLocalTranscriptionAvailable(Context context) {
        if (!OpenAiPreferences.isLocalTranscriptionEnabled(context)) {
            return false;
        }
        String localModel = OpenAiPreferences.getLocalTranscriptionModel(context);
        LocalTranscriptionManager manager = new LocalTranscriptionManager(context);
        return manager.isModelDownloaded(localModel);
    }
}
