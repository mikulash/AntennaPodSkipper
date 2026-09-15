package de.danoeh.antennapod.net.ai.service.ad;

/**
 * Central configuration for the ad analysis workflow.
 */
public final class AdAnalysisConfig {
    public static final long TRANSCRIPTION_CHUNK_SECONDS = 150; // 2.5 minutes
    public static final int MAX_TRANSCRIPT_CHARS_PER_CHUNK = 100_000; // ~25k tokens
    public static final double AD_SEGMENT_PADDING_SECONDS = 1.0;
    public static final int MAX_PARALLEL_ANALYSIS_REQUESTS = 3;
    public static final int TRANSCRIPTION_PROGRESS_WEIGHT_PERCENT = 50;

    private AdAnalysisConfig() {
    }
}
