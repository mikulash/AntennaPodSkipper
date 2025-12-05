package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

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
     * If local transcription is enabled and the model is downloaded, uses
     * LocalTranscriptionProvider. Otherwise, falls back to OpenAI.
     */
    public static AdAnalysisProvider create(Context context) {
        if (OpenAiPreferences.isLocalTranscriptionEnabled(context)) {
            String localModel = OpenAiPreferences.getLocalTranscriptionModel(context);
            LocalTranscriptionManager manager = new LocalTranscriptionManager(context);

            if (manager.isModelDownloaded(localModel)) {
                try {
                    Log.i(TAG, "Using local transcription provider with model: " + localModel);
                    return new LocalTranscriptionProvider(context);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create local provider, falling back to OpenAI", e);
                }
            } else {
                Log.w(TAG, "Local transcription enabled but model not downloaded: " + localModel);
            }
        }

        // Default to OpenAI provider
        Log.i(TAG, "Using OpenAI transcription provider");
        return new OpenAiAdAnalysisProvider(context);
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
