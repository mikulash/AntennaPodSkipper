package de.danoeh.antennapod.playback.service;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DelayedEpisodeDeletionWorker extends Worker {
    private static final String TAG = "DelayedEpisodeDeletion";
    private static final String MEDIA_ID = "mediaId";
    private static final String COMPLETED_AT = "completedAt";
    private static final String DOWNLOAD_DATE = "downloadDate";

    public DelayedEpisodeDeletionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void deleteAfterPlayback(Context context, FeedMedia media) {
        long delay = UserPreferences.getAutoDeleteDelayMillis();
        if (delay == 0) {
            DBWriter.deleteFeedMediaOfItem(context, media);
            return;
        }
        Data data = new Data.Builder()
                .putLong(MEDIA_ID, media.getId())
                .putLong(COMPLETED_AT, System.currentTimeMillis())
                .putLong(DOWNLOAD_DATE, media.getDownloadDate())
                .build();
        enqueue(context, data, delay, ExistingWorkPolicy.REPLACE);
    }

    private static void enqueue(Context context, Data data, long delay, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DelayedEpisodeDeletionWorker.class)
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                TAG + "-" + data.getLong(MEDIA_ID, 0), policy, request);
    }

    static long getRemainingDelay(FeedMedia media, long completedAt, long now, long delay) {
        return Math.max(completedAt, media.getLastPlayedTimeStatistics()) + delay - now;
    }

    static boolean isDeletionAllowed(FeedItem item) {
        FeedPreferences.AutoDeleteAction action = item.getFeed().getPreferences().getCurrentAutoDelete();
        boolean globalEnabled = UserPreferences.isAutoDelete()
                && (!item.getFeed().isLocalFeed() || UserPreferences.isAutoDeleteLocal());
        return (action == FeedPreferences.AutoDeleteAction.ALWAYS
                || (action == FeedPreferences.AutoDeleteAction.GLOBAL && globalEnabled))
                && (!item.isTagged(FeedItem.TAG_FAVORITE) || !UserPreferences.shouldFavoriteKeepEpisode());
    }

    @NonNull
    @Override
    public Result doWork() {
        long mediaId = getInputData().getLong(MEDIA_ID, 0);
        long completedAt = getInputData().getLong(COMPLETED_AT, 0);
        if (mediaId <= 0 || completedAt <= 0) {
            Log.e(TAG, "Missing media ID or playback completion time");
            return Result.failure();
        }
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        long delay = UserPreferences.getAutoDeleteDelayMillis();
        if (media == null || media.getLocalFileUrl() == null || delay == 0
                || media.getDownloadDate() != getInputData().getLong(DOWNLOAD_DATE, 0)
                || !isDeletionAllowed(media.getItem())) {
            return Result.success();
        }
        long remaining = getRemainingDelay(media, completedAt, System.currentTimeMillis(), delay);
        if (PlaybackPreferences.getCurrentlyPlayingFeedMediaId() == mediaId
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
            remaining = delay;
        }
        if (remaining > 0) {
            enqueue(getApplicationContext(), getInputData(), remaining, ExistingWorkPolicy.APPEND_OR_REPLACE);
            return Result.success();
        }
        try {
            DBWriter.deleteFeedMediaOfItem(getApplicationContext(), media).get();
            return Result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Episode deletion interrupted", e);
            return Result.retry();
        } catch (ExecutionException e) {
            Log.e(TAG, "Episode deletion failed", e);
            return Result.retry();
        }
    }
}
