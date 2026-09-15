package de.danoeh.antennapod.net.ai.service.ad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits long transcripts into model-sized chunks while preserving content order.
 */
public final class TranscriptChunker {
    private static final Pattern CUE_SEPARATOR = Pattern.compile("\\r?\\n[\\t ]*\\r?\\n");
    private static final Pattern TIMESTAMP_LINE = Pattern.compile(
            "(?m)^(?=\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}[\\t ]+-->)");

    private TranscriptChunker() {
    }

    public static List<String> split(String transcript, int maxCharsPerChunk) {
        if (transcript == null || transcript.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxCharsPerChunk <= 0 || transcript.length() <= maxCharsPerChunk) {
            return Collections.singletonList(transcript);
        }

        if (transcript.contains("-->")) {
            return splitWebVtt(transcript, maxCharsPerChunk);
        }
        return splitPlainText(transcript, maxCharsPerChunk);
    }

    private static List<String> splitWebVtt(String transcript, int maxCharsPerChunk) {
        List<Integer> cueBoundaries = findCueBoundaries(transcript);
        if (cueBoundaries.isEmpty()) {
            return Collections.singletonList(transcript);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < transcript.length()) {
            int preferredEnd = Math.min(start + maxCharsPerChunk, transcript.length());
            if (preferredEnd == transcript.length()) {
                chunks.add(transcript.substring(start));
                break;
            }

            int end = findBoundaryAtOrBefore(cueBoundaries, start, preferredEnd);
            if (end <= start) {
                end = findBoundaryAfter(cueBoundaries, preferredEnd);
            }
            if (end <= start) {
                chunks.add(transcript.substring(start));
                break;
            }
            chunks.add(transcript.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private static List<Integer> findCueBoundaries(String transcript) {
        List<Integer> boundaries = new ArrayList<>();
        Matcher separatorMatcher = CUE_SEPARATOR.matcher(transcript);
        while (separatorMatcher.find()) {
            if (transcript.lastIndexOf("-->", separatorMatcher.start()) >= 0) {
                boundaries.add(separatorMatcher.end());
            }
        }
        Matcher timestampMatcher = TIMESTAMP_LINE.matcher(transcript);
        boolean firstTimestamp = true;
        while (timestampMatcher.find()) {
            if (!firstTimestamp && !boundaries.contains(timestampMatcher.start())) {
                boundaries.add(timestampMatcher.start());
            }
            firstTimestamp = false;
        }
        Collections.sort(boundaries);
        return boundaries;
    }

    private static int findBoundaryAtOrBefore(List<Integer> boundaries, int start, int preferredEnd) {
        int selected = -1;
        for (int boundary : boundaries) {
            if (boundary > preferredEnd) {
                break;
            }
            if (boundary > start) {
                selected = boundary;
            }
        }
        return selected;
    }

    private static int findBoundaryAfter(List<Integer> boundaries, int preferredEnd) {
        for (int boundary : boundaries) {
            if (boundary > preferredEnd) {
                return boundary;
            }
        }
        return -1;
    }

    private static List<String> splitPlainText(String transcript, int maxCharsPerChunk) {
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
