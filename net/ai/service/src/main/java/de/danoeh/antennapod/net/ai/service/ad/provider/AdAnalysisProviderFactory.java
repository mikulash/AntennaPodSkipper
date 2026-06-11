package de.danoeh.antennapod.net.ai.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;

import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;

/**
 * Factory to construct providers for the ad analysis workflow.
 * Creates both transcription providers (audio → text) and transcript analysis providers (text → ads).
 * Supports cloud-based (OpenAI or Azure OpenAI) and local (Vosk) providers.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisProviderFactory {
    private static final String TAG = "AdAnalysisProvFactory";

    private AdAnalysisProviderFactory() {
    }

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
     * Creates the OpenAI transcript analysis provider.
     * Local ad analysis has been removed - always use cloud-based analysis.
     */
    public static TranscriptAnalysisProvider createAnalysisProvider(Context context) throws IOException {
        Log.i(TAG, "Creating OpenAiTranscriptAnalysisProvider");
        return new OpenAiTranscriptAnalysisProvider(context);
    }
}
