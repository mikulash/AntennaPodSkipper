package de.danoeh.antennapod.model.ad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link AdAnalysisResult}.
 */
public class AdAnalysisResultTest {

    private static final long TIMESTAMP = 1609459200000L; // 2021-01-01 00:00:00 UTC
    private static final String MODEL = "gpt-4";

    // ==================== Constructor Tests ====================

    @Test
    public void testConstructorWithoutTranscript() {
        List<AdSegment> segments = createSampleSegments();
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);

        assertEquals(2, result.getSegments().size());
        assertEquals(TIMESTAMP, result.getAnalyzedAtMillis());
        assertEquals(MODEL, result.getModel());
        assertNull(result.getError());
        assertNull(result.getTranscript());
    }

    @Test
    public void testConstructorWithTranscript() {
        List<AdSegment> segments = createSampleSegments();
        String transcript = "This is a sample transcript.";
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null, transcript);

        assertEquals(2, result.getSegments().size());
        assertEquals(TIMESTAMP, result.getAnalyzedAtMillis());
        assertEquals(MODEL, result.getModel());
        assertNull(result.getError());
        assertEquals(transcript, result.getTranscript());
    }

    @Test
    public void testConstructorWithNullSegments_createsEmptyList() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);

        assertNotNull(result.getSegments());
        assertTrue(result.getSegments().isEmpty());
    }

    @Test
    public void testConstructorWithEmptySegments() {
        AdAnalysisResult result = new AdAnalysisResult(Collections.emptyList(), TIMESTAMP, MODEL, null);

        assertNotNull(result.getSegments());
        assertTrue(result.getSegments().isEmpty());
    }

    @Test
    public void testConstructorWithError() {
        String error = "Analysis failed due to API error";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, error);

        assertEquals(error, result.getError());
        assertFalse(result.isSuccess());
    }

    @Test
    public void testConstructorWithAllNulls() {
        AdAnalysisResult result = new AdAnalysisResult(null, 0, null, null, null);

        assertNotNull(result.getSegments());
        assertTrue(result.getSegments().isEmpty());
        assertEquals(0, result.getAnalyzedAtMillis());
        assertNull(result.getModel());
        assertNull(result.getError());
        assertNull(result.getTranscript());
    }

    // ==================== getSegments() Tests ====================

    @Test
    public void testGetSegments_returnsUnmodifiableList() {
        List<AdSegment> segments = createSampleSegments();
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);

        List<AdSegment> returnedSegments = result.getSegments();

        // Attempt to modify should throw UnsupportedOperationException
        boolean threwException = false;
        try {
            returnedSegments.add(new AdSegment(50.0, 60.0, "new", 0.5));
        } catch (UnsupportedOperationException e) {
            threwException = true;
        }
        assertTrue("Should throw UnsupportedOperationException on modification", threwException);
    }

    @Test
    public void testGetSegments_defensiveCopy() {
        List<AdSegment> originalSegments = new ArrayList<>(createSampleSegments());
        AdAnalysisResult result = new AdAnalysisResult(originalSegments, TIMESTAMP, MODEL, null);

        // Modify original list
        originalSegments.add(new AdSegment(100.0, 110.0, "extra", 0.5));

        // Result segments should not be affected
        assertEquals(2, result.getSegments().size());
    }

    @Test
    public void testGetSegments_preservesOrder() {
        AdSegment first = new AdSegment(0.0, 10.0, "first", 0.9);
        AdSegment second = new AdSegment(20.0, 30.0, "second", 0.8);
        AdSegment third = new AdSegment(40.0, 50.0, "third", 0.7);
        List<AdSegment> segments = Arrays.asList(first, second, third);

        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);

        List<AdSegment> returnedSegments = result.getSegments();
        assertEquals(first, returnedSegments.get(0));
        assertEquals(second, returnedSegments.get(1));
        assertEquals(third, returnedSegments.get(2));
    }

    @Test
    public void testGetSegments_multipleCallsReturnSameList() {
        List<AdSegment> segments = createSampleSegments();
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);

        List<AdSegment> first = result.getSegments();
        List<AdSegment> second = result.getSegments();

        // Should return the same unmodifiable list instance
        assertEquals(first.size(), second.size());
    }

    // ==================== getAnalyzedAtMillis() Tests ====================

    @Test
    public void testGetAnalyzedAtMillis() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        assertEquals(TIMESTAMP, result.getAnalyzedAtMillis());
    }

    @Test
    public void testGetAnalyzedAtMillis_withZero() {
        AdAnalysisResult result = new AdAnalysisResult(null, 0, MODEL, null);
        assertEquals(0, result.getAnalyzedAtMillis());
    }

    @Test
    public void testGetAnalyzedAtMillis_withNegative() {
        // Edge case: negative timestamp
        AdAnalysisResult result = new AdAnalysisResult(null, -1000, MODEL, null);
        assertEquals(-1000, result.getAnalyzedAtMillis());
    }

    @Test
    public void testGetAnalyzedAtMillis_withMaxLong() {
        AdAnalysisResult result = new AdAnalysisResult(null, Long.MAX_VALUE, MODEL, null);
        assertEquals(Long.MAX_VALUE, result.getAnalyzedAtMillis());
    }

    // ==================== getModel() Tests ====================

    @Test
    public void testGetModel() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, "whisper-1", null);
        assertEquals("whisper-1", result.getModel());
    }

    @Test
    public void testGetModel_withNull() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, null, null);
        assertNull(result.getModel());
    }

    @Test
    public void testGetModel_withEmptyString() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, "", null);
        assertEquals("", result.getModel());
    }

    @Test
    public void testGetModel_typicalValues() {
        String[] models = {"gpt-4", "gpt-3.5-turbo", "whisper-1", "vosk-model-en"};
        for (String model : models) {
            AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, model, null);
            assertEquals(model, result.getModel());
        }
    }

    // ==================== getError() Tests ====================

    @Test
    public void testGetError() {
        String error = "API rate limit exceeded";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, error);
        assertEquals(error, result.getError());
    }

    @Test
    public void testGetError_withNull() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        assertNull(result.getError());
    }

    @Test
    public void testGetError_withEmptyString() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, "");
        assertEquals("", result.getError());
    }

    @Test
    public void testGetError_withLongMessage() {
        String longError = String.join("", Collections.nCopies(1000, "error "));
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, longError);
        assertEquals(longError, result.getError());
    }

    // ==================== getTranscript() Tests ====================

    @Test
    public void testGetTranscript() {
        String transcript = "Hello, this is a test transcript.";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null, transcript);
        assertEquals(transcript, result.getTranscript());
    }

    @Test
    public void testGetTranscript_withNull() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null, null);
        assertNull(result.getTranscript());
    }

    @Test
    public void testGetTranscript_withConstructorWithoutTranscript() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        assertNull(result.getTranscript());
    }

    @Test
    public void testGetTranscript_withEmptyString() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null, "");
        assertEquals("", result.getTranscript());
    }

    @Test
    public void testGetTranscript_withVttFormat() {
        String vttTranscript = "WEBVTT\n\n00:00:00.000 --> 00:00:05.000\nHello world\n";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null, vttTranscript);
        assertEquals(vttTranscript, result.getTranscript());
    }

    @Test
    public void testGetTranscript_withUnicode() {
        String unicodeTranscript = "日本語テスト - Тест на русском - 한국어 테스트";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null, unicodeTranscript);
        assertEquals(unicodeTranscript, result.getTranscript());
    }

    // ==================== hasSegments() Tests ====================

    @Test
    public void testHasSegments_withSegments() {
        List<AdSegment> segments = createSampleSegments();
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);
        assertTrue(result.hasSegments());
    }

    @Test
    public void testHasSegments_withoutSegments() {
        AdAnalysisResult result = new AdAnalysisResult(Collections.emptyList(), TIMESTAMP, MODEL, null);
        assertFalse(result.hasSegments());
    }

    @Test
    public void testHasSegments_withNullSegments() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        assertFalse(result.hasSegments());
    }

    @Test
    public void testHasSegments_withSingleSegment() {
        List<AdSegment> segments = Collections.singletonList(new AdSegment(0.0, 10.0, "ad", 0.9));
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);
        assertTrue(result.hasSegments());
    }

    // ==================== isSuccess() Tests ====================

    @Test
    public void testIsSuccess_withNullError() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        assertTrue(result.isSuccess());
    }

    @Test
    public void testIsSuccess_withEmptyError() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, "");
        assertTrue(result.isSuccess());
    }

    @Test
    public void testIsSuccess_withError() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, "Some error");
        assertFalse(result.isSuccess());
    }

    @Test
    public void testIsSuccess_withWhitespaceOnlyError() {
        // Whitespace-only error should be considered a failure
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, "   ");
        assertFalse(result.isSuccess());
    }

    @Test
    public void testIsSuccess_withSegmentsAndNoError() {
        List<AdSegment> segments = createSampleSegments();
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);
        assertTrue(result.isSuccess());
    }

    @Test
    public void testIsSuccess_withSegmentsAndError() {
        List<AdSegment> segments = createSampleSegments();
        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, "error");
        assertFalse(result.isSuccess());
    }

    // ==================== Integration Tests ====================

    @Test
    public void testCompleteSuccessfulAnalysis() {
        List<AdSegment> segments = Arrays.asList(
                new AdSegment(0.0, 30.0, "pre-roll", 0.95),
                new AdSegment(600.0, 660.0, "mid-roll", 0.88),
                new AdSegment(1800.0, 1830.0, "post-roll", 0.92)
        );
        String transcript = "Full episode transcript here...";

        AdAnalysisResult result = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null, transcript);

        assertTrue(result.isSuccess());
        assertTrue(result.hasSegments());
        assertEquals(3, result.getSegments().size());
        assertEquals(TIMESTAMP, result.getAnalyzedAtMillis());
        assertEquals(MODEL, result.getModel());
        assertEquals(transcript, result.getTranscript());
        assertNull(result.getError());
    }

    @Test
    public void testCompleteFailedAnalysis() {
        String error = "OpenAI API returned 429: Rate limit exceeded";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, error, null);

        assertFalse(result.isSuccess());
        assertFalse(result.hasSegments());
        assertEquals(error, result.getError());
    }

    @Test
    public void testAnalysisWithNoAdsFound() {
        String transcript = "Clean episode with no ads";
        AdAnalysisResult result = new AdAnalysisResult(
                Collections.emptyList(), TIMESTAMP, MODEL, null, transcript);

        assertTrue(result.isSuccess());
        assertFalse(result.hasSegments());
        assertEquals(transcript, result.getTranscript());
    }

    @Test
    public void testPartialFailureWithTranscript() {
        // Analysis failed but transcript was captured
        String transcript = "Transcript captured before error";
        String error = "Analysis timeout";
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, error, transcript);

        assertFalse(result.isSuccess());
        assertFalse(result.hasSegments());
        assertEquals(transcript, result.getTranscript());
        assertEquals(error, result.getError());
    }

    // ==================== Helper Methods ====================

    private List<AdSegment> createSampleSegments() {
        return Arrays.asList(
                new AdSegment(10.0, 40.0, "sponsor", 0.9),
                new AdSegment(300.0, 360.0, "mid-roll", 0.85)
        );
    }
}
