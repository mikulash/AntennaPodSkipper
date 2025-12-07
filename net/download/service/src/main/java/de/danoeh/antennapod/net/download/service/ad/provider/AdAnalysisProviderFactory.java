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
    public static AdAnalysisProvider create(Context context) {
        if (OpenAiPreferences.isLocalTranscriptionEnabled(context)) {
            String localModel = OpenAiPreferences.getLocalTranscriptionModel(context);
            LocalTranscriptionManager manager = new LocalTranscriptionManager(context);

            if (!manager.isModelDownloaded(localModel)) {
                throw new IllegalStateException("Local transcription model not downloaded: " + localModel
                        + ". Please download the model in Settings > AI & Ad Skipping.");
            }

            try {
                Log.i(TAG, "Using local transcription provider with model: " + localModel);
                return new LocalTranscriptionProvider(context);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create local transcription provider", e);
                throw new IllegalStateException("Failed to initialize local transcription: " + e.getMessage(), e);
            }
        }

        // Use OpenAI provider when local transcription is not enabled
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
