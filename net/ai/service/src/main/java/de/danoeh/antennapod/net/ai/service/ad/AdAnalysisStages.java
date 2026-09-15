package de.danoeh.antennapod.net.ai.service.ad;

/**
 * Progress stages reported by {@link AdAnalysisWorker} and consumed by the UI.
 */
public final class AdAnalysisStages {
    public static final String TRANSCRIBING = "transcribing";
    public static final String TRANSCRIPTION_DONE = "transcription_done";
    public static final String ANALYZING = "analyzing";
    public static final String DONE = "done";

    private AdAnalysisStages() {
    }
}
