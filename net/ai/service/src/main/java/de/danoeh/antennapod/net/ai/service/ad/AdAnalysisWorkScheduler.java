package de.danoeh.antennapod.net.ai.service.ad;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

/**
 * Schedules combined transcription + ad analysis work.
 * Uses a single shared queue name with APPEND_OR_REPLACE policy so that only
 * one episode is processed at a time. Additional requests are queued behind the
 * currently running work, while retries recover from a failed or cancelled
 * queue chain.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisWorkScheduler {
    /** Shared queue name — all episodes share this so they execute serially. */
    public static final String QUEUE_NAME = "ad-analysis-queue";
    /** Per-episode tag prefix for observing / cancelling individual items. */
    public static final String TAG_PREFIX = "ad-analysis-";

    private AdAnalysisWorkScheduler() {
    }

    public static void enqueueManual(Context context, FeedMedia media) {
        if (media == null || media.getItem() == null) {
            return;
        }
        FeedItem item = media.getItem();
        if (OpenAiPreferences.isApiKeyRequired(context)
                && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            return;
        }
        if (TextUtils.isEmpty(media.getLocalFileUrl())) {
            return;
        }
        Data input = new Data.Builder()
                .putLong(AdAnalysisWorker.DATA_FEED_ITEM_ID, item.getId())
                .build();

        // Only require network if not running in fully local mode
        NetworkType networkType = NetworkType.CONNECTED;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AdAnalysisWorker.class)
                .addTag(TAG_PREFIX + item.getId())
                .setConstraints(constraints)
                .setInputData(input)
                .build();

        // APPEND_OR_REPLACE preserves serial queueing but does not attach new
        // retries to an old failed/cancelled chain where they would never run.
        WorkManager.getInstance(context).enqueueUniqueWork(
                QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request);
    }
}
