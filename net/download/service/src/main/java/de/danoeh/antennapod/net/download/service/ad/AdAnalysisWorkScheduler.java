package de.danoeh.antennapod.net.download.service.ad;

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

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public final class AdAnalysisWorkScheduler {
    private static final String UNIQUE_PREFIX = "ad-analysis-";

    private AdAnalysisWorkScheduler() {
    }

    public static void enqueueIfNeeded(Context context, FeedMedia media) {
        enqueue(context, media, true, ExistingWorkPolicy.KEEP, false);
    }

    public static void enqueueManual(Context context, FeedMedia media, boolean reuseExistingTranscript) {
        enqueue(context, media, false, ExistingWorkPolicy.REPLACE, reuseExistingTranscript);
    }

    private static void enqueue(Context context, FeedMedia media, boolean respectPreference,
                                ExistingWorkPolicy policy, boolean reuseExistingTranscript) {
        if (media == null || media.getItem() == null) {
            return;
        }
        FeedItem item = media.getItem();
        Feed feed = item.getFeed();
        FeedPreferences feedPreferences = feed != null ? feed.getPreferences() : null;
        if (respectPreference && (feedPreferences == null || !feedPreferences.isAutoAdAnalysisEnabled())) {
            return;
        }
        if (OpenAiPreferences.isApiKeyRequired(context)
                && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            return;
        }
        if (TextUtils.isEmpty(media.getLocalFileUrl())) {
            return;
        }
        Data input = new Data.Builder()
                .putLong(AdAnalysisWorker.DATA_FEED_ITEM_ID, item.getId())
                .putBoolean(AdAnalysisWorker.DATA_REUSE_EXISTING_TRANSCRIPT, reuseExistingTranscript)
                .build();

        // Only require network if not running in fully local mode
        NetworkType networkType = OpenAiPreferences.isFullyLocalMode(context)
                ? NetworkType.NOT_REQUIRED
                : NetworkType.CONNECTED;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AdAnalysisWorker.class)
                .addTag(UNIQUE_PREFIX + media.getItem().getId())
                .setConstraints(constraints)
                .setInputData(input)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + media.getItem().getId(),
                policy,
                request);
    }
}
