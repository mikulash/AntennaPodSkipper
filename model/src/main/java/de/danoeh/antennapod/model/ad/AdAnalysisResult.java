package de.danoeh.antennapod.model.ad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures the persisted result of advertisement analysis for a single episode.
 */
public class AdAnalysisResult {
    private final List<AdSegment> segments;
    private final long analyzedAtMillis;
    private final String model;
    private final String error;

    public AdAnalysisResult(List<AdSegment> segments, long analyzedAtMillis, String model, String error) {
        this.segments = segments == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(segments));
        this.analyzedAtMillis = analyzedAtMillis;
        this.model = model;
        this.error = error;
    }

    public List<AdSegment> getSegments() {
        return segments;
    }

    public long getAnalyzedAtMillis() {
        return analyzedAtMillis;
    }

    public String getModel() {
        return model;
    }

    public String getError() {
        return error;
    }

    public boolean hasSegments() {
        return !segments.isEmpty();
    }

    public boolean isSuccess() {
        return error == null || error.isEmpty();
    }
}
