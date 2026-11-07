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
    private final Seeker seeker;
    private String cachedPlayableId;
    private AdAnalysisResult cachedResult;
    private double lastSkipTarget = -1;

    interface Seeker {
        void seekTo(int positionMs);
    }

    AdSkipController(Context context, Seeker seeker) {
        this.context = context;
        this.seeker = seeker;
    }

    void onProgress(@Nullable Playable playable, int positionMs) {
        if (!UserPreferences.isAdSkipEnabled()) {
            return;
        }
        if (!(playable instanceof FeedMedia media)) {
            clear();
            return;
        }
        loadIfNecessary(media);
        if (cachedResult == null || cachedResult.getSegments().isEmpty()) {
            return;
        }
        double positionSec = positionMs / 1000.0;
        double offsetSec = Math.max(0, UserPreferences.getAdSkipOffset());
        double durationSec = media.getDuration() > 0
                ? media.getDuration() / 1000.0 : Double.POSITIVE_INFINITY;
        for (AdSegment segment : getEffectiveSegments(cachedResult.getSegments(), offsetSec, durationSec)) {
            if (positionSec >= segment.getStartSeconds() && positionSec < segment.getEndSeconds()) {
                int target = (int) Math.round(segment.getEndSeconds() * 1000);
                if (lastSkipTarget != target) {
                    seeker.seekTo(target);
                    lastSkipTarget = target;
                }
                break;
            }
        }
    }

    static List<AdSegment> getEffectiveSegments(List<AdSegment> segments,
            double offsetSeconds, double durationSeconds) {
        List<AdSegment> expanded = new ArrayList<>(segments.size());
        double safeOffset = Math.max(0, offsetSeconds);
        boolean clampEnd = durationSeconds > 0 && Double.isFinite(durationSeconds);
        for (AdSegment segment : segments) {
            double start = Math.max(0, segment.getStartSeconds() - safeOffset);
            double end = segment.getEndSeconds() + safeOffset;
            if (clampEnd) {
                end = Math.min(end, durationSeconds);
            }
            if (end > start) {
                if (!expanded.isEmpty() && start <= expanded.get(expanded.size() - 1).getEndSeconds()) {
                    AdSegment prev = expanded.get(expanded.size() - 1);
                    expanded.set(expanded.size() - 1, new AdSegment(
                            prev.getStartSeconds(), Math.max(prev.getEndSeconds(), end),
                            prev.getReason(), Math.max(prev.getConfidence(), segment.getConfidence())));
                } else {
                    expanded.add(new AdSegment(start, end, segment.getReason(), segment.getConfidence()));
                }
            }
        }
        return expanded;
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
