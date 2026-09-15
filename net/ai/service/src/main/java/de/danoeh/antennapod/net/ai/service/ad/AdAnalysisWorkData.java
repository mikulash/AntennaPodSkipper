package de.danoeh.antennapod.net.ai.service.ad;

/**
 * Input data keys shared by the ad analysis scheduler and worker.
 */
public final class AdAnalysisWorkData {
    public static final String FEED_ITEM_ID = "feedItemId";
    public static final String ANALYSIS_ONLY = "analysisOnly";

    private AdAnalysisWorkData() {
    }
}
