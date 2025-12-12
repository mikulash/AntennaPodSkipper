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

import de.danoeh.antennapod.model.feed.FeedMedia;
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
        if (OpenAiPreferences.isApiKeyRequired(context)
                && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))
                && !OpenAiPreferences.isLocalTranscriptionEnabled(context)) {
            return;
        }
        if (TextUtils.isEmpty(media.getLocalFileUrl())) {
            return;
        }
        Data input = new Data.Builder()
                .putLong(TranscriptionWorker.DATA_FEED_ITEM_ID, media.getItem().getId())
                .build();

        // Only require network if not running in fully local mode
        NetworkType networkType = OpenAiPreferences.isLocalTranscriptionEnabled(context)
                ? NetworkType.NOT_REQUIRED
                : NetworkType.CONNECTED;

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
