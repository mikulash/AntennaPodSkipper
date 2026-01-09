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

@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisWorkScheduler {
    private static final String UNIQUE_PREFIX = "ad-analysis-";

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
                .putLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, item.getId())
                .build();

        // Only require network if not running in fully local mode
        NetworkType networkType = NetworkType.CONNECTED;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TranscriptAnalysisWorker.class)
                .addTag(UNIQUE_PREFIX + item.getId())
                .setConstraints(constraints)
                .setInputData(input)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + item.getId(),
                ExistingWorkPolicy.REPLACE,
                request);
    }
}
