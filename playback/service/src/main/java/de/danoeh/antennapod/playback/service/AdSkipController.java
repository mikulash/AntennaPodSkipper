package de.danoeh.antennapod.playback.service;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
class AdSkipController {
    private final Context context;
    private final PlaybackService playbackService;
    private String cachedPlayableId;
    private AdAnalysisResult cachedResult;
    private double lastSkipTarget = -1;

    AdSkipController(Context context, PlaybackService playbackService) {
        this.context = context;
        this.playbackService = playbackService;
    }

    void onProgress(@Nullable Playable playable, int positionMs) {
        if (!UserPreferences.isAdSkipEnabled()) {
            return;
        }
        if (!(playable instanceof FeedMedia)) {
            clear();
            return;
        }
        FeedMedia media = (FeedMedia) playable;
        loadIfNecessary(media);
        if (cachedResult == null || cachedResult.getSegments().isEmpty()) {
            return;
        }
        double positionSec = positionMs / 1000.0;
        for (AdSegment segment : cachedResult.getSegments()) {
            if (positionSec >= segment.getStartSeconds() && positionSec < segment.getEndSeconds()) {
                int target = (int) Math.round(segment.getEndSeconds() * 1000);
                if (lastSkipTarget != target) {
                    playbackService.seekTo(target);
                    lastSkipTarget = target;
                }
                break;
            }
        }
    }

    void onPlayableChanged(@Nullable Playable playable) {
        if (playable instanceof FeedMedia) {
            loadIfNecessary((FeedMedia) playable);
        } else {
            clear();
        }
    }

    void clear() {
        cachedPlayableId = null;
        cachedResult = null;
        lastSkipTarget = -1;
    }

    private void loadIfNecessary(FeedMedia media) {
        if (media.getItem() == null) {
            clear();
            return;
        }
        String identifier = media.getIdentifier().toString();
        if (!TextUtils.equals(identifier, cachedPlayableId)) {
            cachedPlayableId = identifier;
            cachedResult = AdSegmentStore.load(context, media.getItem().getId());
            lastSkipTarget = -1;
            if (cachedResult != null) {
                List<AdSegment> segments = new ArrayList<>(cachedResult.getSegments());
                segments.sort(Comparator.comparingDouble(AdSegment::getStartSeconds));
                cachedResult = new AdAnalysisResult(segments, cachedResult.getAnalyzedAtMillis(),
                        cachedResult.getModel(), cachedResult.getError(), cachedResult.getTranscript());
            }
        }
    }
}
