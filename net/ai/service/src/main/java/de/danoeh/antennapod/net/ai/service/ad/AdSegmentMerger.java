package de.danoeh.antennapod.net.ai.service.ad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.ad.AdSegment;

/**
 * Merges overlapping or near-adjacent ad segments.
 */
public final class AdSegmentMerger {
    private static final double DEFAULT_MERGE_GAP_SECONDS = 0.5;

    private AdSegmentMerger() {
    }

    public static List<AdSegment> merge(List<AdSegment> segments) {
        return merge(segments, DEFAULT_MERGE_GAP_SECONDS);
    }

    public static List<AdSegment> merge(List<AdSegment> segments, double mergeGapSeconds) {
        if (segments == null || segments.isEmpty()) {
            return new ArrayList<>();
        }
        List<AdSegment> sorted = new ArrayList<>(segments);
        Collections.sort(sorted,
                (first, second) -> Double.compare(first.getStartSeconds(), second.getStartSeconds()));

        List<AdSegment> merged = new ArrayList<>();
        AdSegment current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            AdSegment next = sorted.get(i);
            if (next.getStartSeconds() <= current.getEndSeconds() + mergeGapSeconds) {
                current = mergePair(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    public static List<AdSegment> expandAndMerge(List<AdSegment> segments, double paddingSeconds,
                                                  double episodeDurationSeconds) {
        if (segments == null || segments.isEmpty()) {
            return new ArrayList<>();
        }
        double safePadding = Math.max(0, paddingSeconds);
        boolean clampToDuration = episodeDurationSeconds > 0 && Double.isFinite(episodeDurationSeconds);
        List<AdSegment> expanded = new ArrayList<>(segments.size());
        for (AdSegment segment : segments) {
            if (segment == null) {
                continue;
            }
            double start = Math.max(0, segment.getStartSeconds() - safePadding);
            double end = segment.getEndSeconds() + safePadding;
            if (clampToDuration) {
                end = Math.min(end, episodeDurationSeconds);
            }
            if (end > start) {
                expanded.add(new AdSegment(start, end, segment.getReason(), segment.getConfidence()));
            }
        }
        return merge(expanded);
    }

    private static AdSegment mergePair(AdSegment first, AdSegment second) {
        double end = Math.max(first.getEndSeconds(), second.getEndSeconds());
        String reason = first.getReason() == null || first.getReason().isEmpty()
                ? second.getReason() : first.getReason();
        double confidence = Math.max(first.getConfidence(), second.getConfidence());
        return new AdSegment(first.getStartSeconds(), end, reason, confidence);
    }
}
