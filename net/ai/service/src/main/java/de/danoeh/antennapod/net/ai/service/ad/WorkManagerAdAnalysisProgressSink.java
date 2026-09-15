package de.danoeh.antennapod.net.ai.service.ad;

import android.util.Log;

import androidx.work.Data;
import androidx.work.ListenableWorker;

/**
 * Reports ad analysis progress to WorkManager and logs each stage transition.
 */
public class WorkManagerAdAnalysisProgressSink implements AdAnalysisProgressSink {
    private static final String TAG = "AdAnalysisProgress";

    public interface ForegroundUpdater {
        void update(String stage, int percent, int chunksDone, int chunksTotal);
    }

    private final ListenableWorker worker;
    private final ForegroundUpdater foregroundUpdater;

    public WorkManagerAdAnalysisProgressSink(ListenableWorker worker) {
        this(worker, null);
    }

    public WorkManagerAdAnalysisProgressSink(ListenableWorker worker, ForegroundUpdater foregroundUpdater) {
        this.worker = worker;
        this.foregroundUpdater = foregroundUpdater;
    }

    @Override
    public void report(String stage, int percent) {
        Log.i(TAG, "Progress stage=" + stage + ", percent=" + percent);
        Data progress = new Data.Builder()
                .putString(AdAnalysisProgressKeys.STAGE, stage)
                .putInt(AdAnalysisProgressKeys.PERCENT, percent)
                .build();
        worker.setProgressAsync(progress);
        updateForeground(stage, percent, 0, 0);
    }

    @Override
    public void report(String stage, int percent, int chunksDone, int chunksTotal) {
        Log.i(TAG, "Progress stage=" + stage + ", percent=" + percent
                + ", chunks=" + chunksDone + "/" + chunksTotal);
        Data progress = new Data.Builder()
                .putString(AdAnalysisProgressKeys.STAGE, stage)
                .putInt(AdAnalysisProgressKeys.PERCENT, percent)
                .putInt(AdAnalysisProgressKeys.CHUNKS_DONE, chunksDone)
                .putInt(AdAnalysisProgressKeys.CHUNKS_TOTAL, chunksTotal)
                .build();
        worker.setProgressAsync(progress);
        updateForeground(stage, percent, chunksDone, chunksTotal);
    }

    private void updateForeground(String stage, int percent, int chunksDone, int chunksTotal) {
        if (foregroundUpdater != null) {
            foregroundUpdater.update(stage, percent, chunksDone, chunksTotal);
        }
    }
}
