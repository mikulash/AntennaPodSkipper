package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * Simple factory to construct the configured ad analysis provider.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisProviderFactory {
    private AdAnalysisProviderFactory() {
    }

    public static AdAnalysisProvider create(Context context) {
        // Only OpenAI is supported today, but this factory centralizes creation
        // so additional providers can be plugged in without changing worker logic.
        return new OpenAiAdAnalysisProvider(context);
    }
}
