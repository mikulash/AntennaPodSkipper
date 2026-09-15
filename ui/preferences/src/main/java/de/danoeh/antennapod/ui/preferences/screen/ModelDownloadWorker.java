package de.danoeh.antennapod.ui.preferences.screen;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.greenrobot.eventbus.EventBus;

import de.danoeh.antennapod.event.ModelDownloadEvent;
import de.danoeh.antennapod.net.ai.service.ad.vosk.VoskModel;
import de.danoeh.antennapod.net.ai.service.ad.vosk.VoskTranscriptionManager;

/**
 * WorkManager worker for downloading transcription models in the background.
 * Runs without a notification and posts progress via EventBus.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class ModelDownloadWorker extends Worker {
    private static final String TAG = "ModelDownloadWorker";
    private static final String WORK_NAME_PREFIX = "model_download_";
    public static final String KEY_MODEL_ID = "model_id";

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    public ModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String modelId = getInputData().getString(KEY_MODEL_ID);
        if (modelId == null) {
            Log.e(TAG, "No model ID provided");
            return Result.failure();
        }

        VoskTranscriptionManager transcriptionManager = new VoskTranscriptionManager(getApplicationContext());
        VoskModel model = transcriptionManager.getModelById(modelId);
        if (model == null) {
            Log.e(TAG, "Unknown model ID: " + modelId);
            EventBus.getDefault().post(ModelDownloadEvent.failed(modelId, "Unknown model"));
            return Result.failure();
        }

        // Acquire wake locks
        acquireWakeLocks();

        try {
            EventBus.getDefault().post(ModelDownloadEvent.started(modelId));

            boolean success = transcriptionManager.downloadModel(modelId,
                    (progress, currentBytes, totalBytes) -> {
                        if (isStopped()) {
                            return;
                        }

                        if (progress < 0) {
                            EventBus.getDefault().post(ModelDownloadEvent.extracting(modelId));
                        } else {
                            EventBus.getDefault().post(ModelDownloadEvent.progress(
                                    modelId, progress, currentBytes, totalBytes));
                        }
                    });

            if (isStopped()) {
                EventBus.getDefault().post(ModelDownloadEvent.cancelled(modelId));
                return Result.failure();
            }

            if (success) {
                EventBus.getDefault().post(ModelDownloadEvent.completed(modelId));
                return Result.success();
            } else {
                EventBus.getDefault().post(ModelDownloadEvent.failed(modelId, "Download failed"));
                return Result.failure();
            }
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            if (!isStopped()) {
                EventBus.getDefault().post(ModelDownloadEvent.failed(modelId, e.getMessage()));
            }
            return Result.failure();
        } finally {
            releaseWakeLocks();
        }
    }

    private void acquireWakeLocks() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG);
                wifiLock.acquire();
            }

            PowerManager powerManager = (PowerManager) getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntennaPod:" + TAG);
                wakeLock.acquire(60 * 60 * 1000L); // 1 hour max
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire wake locks", e);
        }
    }

    private void releaseWakeLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to release wake locks", e);
        }
    }

    /**
     * Starts a model download in the background.
     * Uses unique work to prevent duplicate downloads of the same model.
     */
    public static void enqueue(Context context, String modelId) {
        Data inputData = new Data.Builder()
                .putString(KEY_MODEL_ID, modelId)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelDownloadWorker.class)
                .setInputData(inputData)
                .build();

        // Use unique work to prevent duplicate downloads
        WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_PREFIX + modelId, ExistingWorkPolicy.KEEP, request);
    }

    /**
     * Cancels any ongoing download for the specified model.
     */
    public static void cancel(Context context, String modelId) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PREFIX + modelId);
    }
}
