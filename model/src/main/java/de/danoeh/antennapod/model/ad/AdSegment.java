package de.danoeh.antennapod.model.ad;

import androidx.annotation.NonNull;

/**
 * Represents a detected advertisement segment within a media file.
 */
public class AdSegment {
    private final double startSeconds;
    private final double endSeconds;
    private final String reason;
    private final double confidence;

    public AdSegment(double startSeconds, double endSeconds, String reason, double confidence) {
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.reason = reason == null ? "" : reason;
        this.confidence = confidence;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public double getEndSeconds() {
        return endSeconds;
    }

    public String getReason() {
        return reason;
    }

    public double getConfidence() {
        return confidence;
    }

    @NonNull
    @Override
    public String toString() {
        return "AdSegment{"
                + "startSeconds="
                + startSeconds
                + ", endSeconds="
                + endSeconds
                + ", confidence="
                + confidence
                + '}';
    }
}
