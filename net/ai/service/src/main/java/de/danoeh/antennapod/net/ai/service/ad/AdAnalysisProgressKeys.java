package de.danoeh.antennapod.net.ai.service.ad;

/**
 * WorkManager progress keys shared by the ad analysis worker and UI.
 */
public final class AdAnalysisProgressKeys {
    public static final String PERCENT = "ad_analysis_progress_percent";
    public static final String STAGE = "ad_analysis_progress_stage";
    public static final String CHUNKS_DONE = "ad_analysis_chunks_done";
    public static final String CHUNKS_TOTAL = "ad_analysis_chunks_total";

    private AdAnalysisProgressKeys() {
    }
}
