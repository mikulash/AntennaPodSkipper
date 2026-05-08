package de.danoeh.antennapod.net.ai.service.ad;

import android.util.Log;

import androidx.work.Data;
import androidx.work.ListenableWorker;

/**
 * Reports ad analysis progress to WorkManager and logs each stage transition.
 */
public class WorkManagerAdAnalysisProgressSink implements AdAnalysisProgressSink {
    private static final String TAG = "AdAnalysisProgress";

    private final ListenableWorker worker;

    public WorkManagerAdAnalysisProgressSink(ListenableWorker worker) {
        this.worker = worker;
    }

    @Override
    public void report(String stage, int percent) {
        Log.i(TAG, "Progress stage=" + stage + ", percent=" + percent);
        Data progress = new Data.Builder()
                .putString(AdAnalysisProgressKeys.STAGE, stage)
                .putInt(AdAnalysisProgressKeys.PERCENT, percent)
                .build();
        worker.setProgressAsync(progress);
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
    }
}
