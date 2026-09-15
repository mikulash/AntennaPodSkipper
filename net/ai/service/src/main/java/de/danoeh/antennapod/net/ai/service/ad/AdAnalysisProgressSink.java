package de.danoeh.antennapod.net.ai.service.ad;

/**
 * Destination for observable ad analysis progress updates.
 */
public interface AdAnalysisProgressSink {
    void report(String stage, int percent);

    void report(String stage, int percent, int chunksDone, int chunksTotal);
}
