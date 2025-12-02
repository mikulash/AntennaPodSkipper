package de.danoeh.antennapod.net.download.service.ad;

import android.content.Context;
import android.text.TextUtils;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

public final class AdAnalysisWorkScheduler {
    private static final String UNIQUE_PREFIX = "ad-analysis-";

    private AdAnalysisWorkScheduler() {
    }

    public static void enqueueIfNeeded(Context context, FeedMedia media) {
        if (media == null || media.getItem() == null) {
            return;
        }
        if (!UserPreferences.isAutoAdAnalysisEnabled()) {
            return;
        }
        if (TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            return;
        }
        Data input = new Data.Builder()
                .putLong(AdAnalysisWorker.DATA_FEED_ITEM_ID, media.getItem().getId())
                .build();

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AdAnalysisWorker.class)
                .addTag(UNIQUE_PREFIX + media.getItem().getId())
                .setConstraints(constraints)
                .setInputData(input)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + media.getItem().getId(),
                ExistingWorkPolicy.KEEP,
                request);
    }
}
