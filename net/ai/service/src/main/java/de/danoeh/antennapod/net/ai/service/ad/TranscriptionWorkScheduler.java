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

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public final class TranscriptionWorkScheduler {
    private static final String UNIQUE_PREFIX = "transcription-";

    private TranscriptionWorkScheduler() {
    }

    public static void enqueueManual(Context context, FeedMedia media) {
        enqueue(context, media, ExistingWorkPolicy.REPLACE);
    }

    private static void enqueue(Context context, FeedMedia media, ExistingWorkPolicy policy) {
        if (media == null || media.getItem() == null) {
            return;
        }

        // Check if feed has a cloud model override
        String feedModelOverride = null;
        if (media.getItem().getFeed() != null && media.getItem().getFeed().getPreferences() != null) {
            feedModelOverride = media.getItem().getFeed().getPreferences().getTranscriptionModel();
        }
        boolean feedUsesCloudModel = feedModelOverride != null && feedModelOverride.startsWith("cloud:");

        // Determine if we need an API key
        boolean needsApiKey = feedUsesCloudModel
                || (!LocalAiPreferences.isLocalTranscriptionEnabled(context)
                    && OpenAiPreferences.isApiKeyRequired(context));

        if (needsApiKey && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            return;
        }
        if (TextUtils.isEmpty(media.getLocalFileUrl())) {
            return;
        }
        Data input = new Data.Builder()
                .putLong(TranscriptionWorker.DATA_FEED_ITEM_ID, media.getItem().getId())
                .build();

        // Require network if using cloud model or if not running local transcription globally
        boolean needsNetwork = feedUsesCloudModel || !LocalAiPreferences.isLocalTranscriptionEnabled(context);
        NetworkType networkType = needsNetwork ? NetworkType.CONNECTED : NetworkType.NOT_REQUIRED;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TranscriptionWorker.class)
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
