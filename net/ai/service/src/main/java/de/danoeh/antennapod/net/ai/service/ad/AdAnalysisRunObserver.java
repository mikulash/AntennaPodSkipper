package de.danoeh.antennapod.net.ai.service.ad;

import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * Structured logging helper for one ad analysis run.
 */
public class AdAnalysisRunObserver {
    private static final String TAG = "AdAnalysisRun";

    private final long feedItemId;
    private final long startedAtMs;

    public AdAnalysisRunObserver(long feedItemId) {
        this.feedItemId = feedItemId;
        this.startedAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Started feedItemId=" + feedItemId);
    }

    public void phaseStarted(String phase) {
        Log.i(TAG, "Phase started feedItemId=" + feedItemId + ", phase=" + phase
                + ", elapsed=" + formatElapsed());
    }

    public void phaseFinished(String phase) {
        Log.i(TAG, "Phase finished feedItemId=" + feedItemId + ", phase=" + phase
                + ", elapsed=" + formatElapsed());
    }

    public void finished(String outcome) {
        Log.i(TAG, "Finished feedItemId=" + feedItemId + ", outcome=" + outcome
                + ", elapsed=" + formatElapsed());
    }

    public void failed(String phase, Throwable throwable) {
        Log.e(TAG, "Failed feedItemId=" + feedItemId + ", phase=" + phase
                + ", elapsed=" + formatElapsed(), throwable);
    }

    private String formatElapsed() {
        double seconds = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0;
        return String.format(Locale.US, "%.3fs", seconds);
    }
}
