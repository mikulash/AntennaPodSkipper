package de.danoeh.antennapod.net.ai.service.ad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TranscriptChunkerTest {

    @Test
    public void splitWebVtt_keepsTimestampWithCueText() {
        String firstCue = "00:00:00.000 --> 00:00:05.000\nFirst cue text.\n\n";
        String secondCue = "00:00:05.000 --> 00:00:10.000\nSecond cue text.\n\n";
        String transcript = firstCue + secondCue;
        int splitInsideSecondTimestamp = firstCue.length() + "00:00:05.000 -->".length();

        List<String> chunks = TranscriptChunker.split(transcript, splitInsideSecondTimestamp);

        assertEquals(2, chunks.size());
        assertEquals(firstCue, chunks.get(0));
        assertEquals(secondCue, chunks.get(1));
    }

    @Test
    public void splitWebVtt_usesTimestampBoundaryWhenBlankLinesAreMissing() {
        String firstCue = "00:00:00.000 --> 00:00:05.000\nFirst cue text.\n";
        String secondCue = "00:00:05.000 --> 00:00:10.000\nSecond cue text.\n";
        String transcript = firstCue + secondCue;

        List<String> chunks = TranscriptChunker.split(transcript, firstCue.length() + 10);

        assertEquals(2, chunks.size());
        assertEquals(firstCue, chunks.get(0));
        assertEquals(secondCue, chunks.get(1));
    }

    @Test
    public void splitWebVtt_doesNotBreakSingleOversizedCue() {
        String transcript = "00:00:00.000 --> 00:10:00.000\n" + "a".repeat(500) + "\n";

        List<String> chunks = TranscriptChunker.split(transcript, 100);

        assertEquals(1, chunks.size());
        assertEquals(transcript, chunks.get(0));
    }

    @Test
    public void splitWebVtt_eachChunkContainsTimestampAndText() {
        String transcript = "WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:05.000\nFirst cue text.\n\n"
                + "00:00:05.000 --> 00:00:10.000\nSecond cue text.\n\n"
                + "00:00:10.000 --> 00:00:15.000\nThird cue text.\n";

        List<String> chunks = TranscriptChunker.split(transcript, 65);

        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            if (chunk.trim().equals("WEBVTT")) {
                continue;
            }
            assertTrue(chunk.contains("-->"));
            String afterTimestamp = chunk.substring(chunk.indexOf("-->") + 3);
            assertFalse(afterTimestamp.trim().isEmpty());
        }
        assertEquals(transcript, String.join("", chunks));
    }
}
