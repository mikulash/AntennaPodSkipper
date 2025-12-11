package de.danoeh.antennapod.net.download.service.ad.provider;

import android.os.Build;
import androidx.annotation.RequiresApi;

/**
 * Interface for a component that can analyze text transcripts to detect ads.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public interface AdAnalysisProvider extends AutoCloseable {

    /**
     * Returns the model name or provider identifier used for storing results.
     */
    String getModelName();

    /**
     * Runs the ad analysis using the provided prompt and returns the raw provider
     * response.
     */
    String analyzeTranscript(String prompt) throws Exception;
}
