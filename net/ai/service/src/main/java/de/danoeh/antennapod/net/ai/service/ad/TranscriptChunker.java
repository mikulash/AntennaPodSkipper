package de.danoeh.antennapod.net.ai.service.ad;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits long transcripts into model-sized chunks while preserving content order.
 */
public final class TranscriptChunker {
    private TranscriptChunker() {
    }

    public static List<String> split(String transcript, int maxCharsPerChunk) {
        if (TextUtils.isEmpty(transcript)) {
            return Collections.emptyList();
        }
        if (maxCharsPerChunk <= 0 || transcript.length() <= maxCharsPerChunk) {
            return Collections.singletonList(transcript);
        }

        List<String> chunks = new ArrayList<>();
        int numChunks = (int) Math.ceil((double) transcript.length() / maxCharsPerChunk);
        int chunkSize = Math.max(1, transcript.length() / numChunks);

        int start = 0;
        while (start < transcript.length()) {
            int end = Math.min(start + chunkSize, transcript.length());
            if (end < transcript.length()) {
                int newlineIndex = transcript.lastIndexOf('\n', end);
                if (newlineIndex > start) {
                    end = newlineIndex;
                }
            }
            if (end <= start) {
                end = Math.min(start + chunkSize, transcript.length());
            }
            chunks.add(transcript.substring(start, end));
            start = end;
        }
        return chunks;
    }
}
