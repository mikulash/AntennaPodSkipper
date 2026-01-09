package de.danoeh.antennapod.net.ai.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;

import de.danoeh.antennapod.net.ai.service.ad.vosk.VoskTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

/**
 * Factory to construct providers for the ad analysis workflow.
 * Creates both transcription providers (audio → text) and transcript analysis providers (text → ads).
 * Supports cloud-based (OpenAI) and local (Vosk/LiteRT-LM) providers.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisProviderFactory {
    private static final String TAG = "AdAnalysisProviderFactory";

    private AdAnalysisProviderFactory() {
    }

    /**
     * Creates the appropriate ad analysis provider based on user preferences.
     * If local transcription is enabled, attempts to create
     * LocalTranscriptionProvider.
     * If local transcription fails, throws an exception instead of falling back to
     * OpenAI.
     *
     * @throws IllegalStateException if local transcription is enabled but cannot be
     *                               initialized
     */
    /**
     * Creates the appropriate transcription provider based on user preferences.
     */
    public static TranscriptionProvider createTranscriptionProvider(Context context) {
        return createTranscriptionProvider(context, null, null);
    }

    /**
     * Creates the appropriate transcription provider based on user preferences.
     *
     * @param modelOverride Optional model ID to override global preference
     * @param languageOverride Optional language code for cloud transcription
     */
    public static TranscriptionProvider createTranscriptionProvider(Context context, String modelOverride,
            String languageOverride) {
        // Check if explicitly using a cloud model
        if (modelOverride != null && modelOverride.startsWith("cloud:")) {
            Log.i(TAG, "Creating OpenAiTranscriptionProvider with language: " + languageOverride);
            return new OpenAiTranscriptionProvider(context, languageOverride);
        }

        if (LocalAiPreferences.isLocalTranscriptionEnabled(context)) {
            Log.i(TAG, "Creating LocalTranscriptionProvider with override: " + modelOverride);
            try {
                return new LocalTranscriptionProvider(context, modelOverride);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create local transcription provider", e);
                throw new IllegalStateException("Failed to initialize local transcription: " + e.getMessage(), e);
            }
        } else {
            Log.i(TAG, "Creating OpenAiTranscriptionProvider with language: " + languageOverride);
            return new OpenAiTranscriptionProvider(context, languageOverride);
        }
    }

    /**
     * Creates the appropriate transcript analysis provider based on user preferences.
     * Uses LiteRT-LM 0.8.1+ with .litertlm format models.
     */
    public static TranscriptAnalysisProvider createAnalysisProvider(Context context) throws IOException {
        if (LocalAiPreferences.isLocalAdAnalysisEnabled(context)) {
            Log.i(TAG, "Creating LocalTranscriptAnalysisProvider with LiteRT-LM");
            return new LocalTranscriptAnalysisProvider(context);
        } else {
            Log.i(TAG, "Creating OpenAiTranscriptAnalysisProvider");
            return new OpenAiTranscriptAnalysisProvider(context);
        }
    }

    /**
     * Checks if local transcription is available (model downloaded and preference
     * enabled).
     */
    public static boolean isLocalTranscriptionAvailable(Context context) {
        if (!LocalAiPreferences.isLocalTranscriptionEnabled(context)) {
            return false;
        }
        String localModel = LocalAiPreferences.getLocalTranscriptionModel(context);
        VoskTranscriptionManager manager = new VoskTranscriptionManager(context);
        return manager.isModelDownloaded(localModel);
    }
}
