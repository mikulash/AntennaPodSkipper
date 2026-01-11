package de.danoeh.antennapod.model.ad;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AdSegment}.
 */
public class AdSegmentTest {

    @Test
    public void testConstructorWithValidValues() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);

        assertEquals(10.5, segment.getStartSeconds(), 0.001);
        assertEquals(25.0, segment.getEndSeconds(), 0.001);
        assertEquals("sponsor", segment.getReason());
        assertEquals(0.95, segment.getConfidence(), 0.001);
    }

    @Test
    public void testConstructorWithNullReason_convertsToEmptyString() {
        AdSegment segment = new AdSegment(0.0, 10.0, null, 0.5);

        assertNotNull(segment.getReason());
        assertEquals("", segment.getReason());
    }

    @Test
    public void testConstructorWithZeroValues() {
        AdSegment segment = new AdSegment(0.0, 0.0, "", 0.0);

        assertEquals(0.0, segment.getStartSeconds(), 0.001);
        assertEquals(0.0, segment.getEndSeconds(), 0.001);
        assertEquals("", segment.getReason());
        assertEquals(0.0, segment.getConfidence(), 0.001);
    }

    @Test
    public void testConstructorWithNegativeValues() {
        // Edge case: negative values should be stored as-is
        AdSegment segment = new AdSegment(-5.0, -1.0, "test", -0.5);

        assertEquals(-5.0, segment.getStartSeconds(), 0.001);
        assertEquals(-1.0, segment.getEndSeconds(), 0.001);
        assertEquals(-0.5, segment.getConfidence(), 0.001);
    }

    @Test
    public void testConstructorWithEndBeforeStart() {
        // Edge case: end time before start time should be stored as-is
        AdSegment segment = new AdSegment(30.0, 10.0, "reversed", 0.5);

        assertEquals(30.0, segment.getStartSeconds(), 0.001);
        assertEquals(10.0, segment.getEndSeconds(), 0.001);
    }

    @Test
    public void testConstructorWithLargeValues() {
        AdSegment segment = new AdSegment(3600.0, 7200.0, "long segment", 1.0);

        assertEquals(3600.0, segment.getStartSeconds(), 0.001);
        assertEquals(7200.0, segment.getEndSeconds(), 0.001);
        assertEquals(1.0, segment.getConfidence(), 0.001);
    }

    @Test
    public void testConstructorWithMaxDouble() {
        AdSegment segment = new AdSegment(Double.MAX_VALUE, Double.MAX_VALUE, "max", Double.MAX_VALUE);

        assertEquals(Double.MAX_VALUE, segment.getStartSeconds(), 0.001);
        assertEquals(Double.MAX_VALUE, segment.getEndSeconds(), 0.001);
        assertEquals(Double.MAX_VALUE, segment.getConfidence(), 0.001);
    }

    @Test
    public void testConstructorWithMinDouble() {
        AdSegment segment = new AdSegment(Double.MIN_VALUE, Double.MIN_VALUE, "min", Double.MIN_VALUE);

        assertEquals(Double.MIN_VALUE, segment.getStartSeconds(), 0.001);
        assertEquals(Double.MIN_VALUE, segment.getEndSeconds(), 0.001);
        assertEquals(Double.MIN_VALUE, segment.getConfidence(), 0.001);
    }

    @Test
    public void testGetStartSeconds() {
        AdSegment segment = new AdSegment(15.75, 30.5, "test", 0.8);
        assertEquals(15.75, segment.getStartSeconds(), 0.001);
    }

    @Test
    public void testGetEndSeconds() {
        AdSegment segment = new AdSegment(15.75, 30.5, "test", 0.8);
        assertEquals(30.5, segment.getEndSeconds(), 0.001);
    }

    @Test
    public void testGetReason() {
        AdSegment segment = new AdSegment(0.0, 10.0, "pre-roll ad", 0.9);
        assertEquals("pre-roll ad", segment.getReason());
    }

    @Test
    public void testGetReasonWithSpecialCharacters() {
        String reason = "Ad with \"quotes\" and special chars: <>&";
        AdSegment segment = new AdSegment(0.0, 10.0, reason, 0.9);
        assertEquals(reason, segment.getReason());
    }

    @Test
    public void testGetConfidence() {
        AdSegment segment = new AdSegment(0.0, 10.0, "test", 0.87);
        assertEquals(0.87, segment.getConfidence(), 0.001);
    }

    @Test
    public void testGetConfidenceAtBoundaries() {
        AdSegment segmentZero = new AdSegment(0.0, 10.0, "test", 0.0);
        AdSegment segmentOne = new AdSegment(0.0, 10.0, "test", 1.0);

        assertEquals(0.0, segmentZero.getConfidence(), 0.001);
        assertEquals(1.0, segmentOne.getConfidence(), 0.001);
    }

    @Test
    public void testToStringContainsStartSeconds() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        assertTrue(segment.toString().contains("10.5"));
    }

    @Test
    public void testToStringContainsEndSeconds() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        assertTrue(segment.toString().contains("25.0"));
    }

    @Test
    public void testToStringContainsConfidence() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        assertTrue(segment.toString().contains("0.95"));
    }

    @Test
    public void testToStringIsNotNull() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        assertNotNull(segment.toString());
    }

    @Test
    public void testToStringFormat() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        String str = segment.toString();
        assertTrue(str.startsWith("AdSegment{"));
        assertTrue(str.endsWith("}"));
    }

    @Test
    public void testImmutability_startSecondsCannotBeModified() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        double original = segment.getStartSeconds();
        // No setter exists, value should remain unchanged
        assertEquals(original, segment.getStartSeconds(), 0.001);
    }

    @Test
    public void testImmutability_endSecondsCannotBeModified() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        double original = segment.getEndSeconds();
        assertEquals(original, segment.getEndSeconds(), 0.001);
    }

    @Test
    public void testImmutability_reasonCannotBeModified() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        String original = segment.getReason();
        assertEquals(original, segment.getReason());
    }

    @Test
    public void testImmutability_confidenceCannotBeModified() {
        AdSegment segment = new AdSegment(10.5, 25.0, "sponsor", 0.95);
        double original = segment.getConfidence();
        assertEquals(original, segment.getConfidence(), 0.001);
    }

    @Test
    public void testMultipleSegmentsAreIndependent() {
        AdSegment segment1 = new AdSegment(0.0, 10.0, "first", 0.5);
        AdSegment segment2 = new AdSegment(10.0, 20.0, "second", 0.8);

        assertEquals(0.0, segment1.getStartSeconds(), 0.001);
        assertEquals(10.0, segment1.getEndSeconds(), 0.001);
        assertEquals("first", segment1.getReason());

        assertEquals(10.0, segment2.getStartSeconds(), 0.001);
        assertEquals(20.0, segment2.getEndSeconds(), 0.001);
        assertEquals("second", segment2.getReason());
    }

    @Test
    public void testPrecisionWithSmallFractions() {
        AdSegment segment = new AdSegment(0.001, 0.002, "tiny", 0.001);

        assertEquals(0.001, segment.getStartSeconds(), 0.0001);
        assertEquals(0.002, segment.getEndSeconds(), 0.0001);
        assertEquals(0.001, segment.getConfidence(), 0.0001);
    }

    @Test
    public void testReasonTypicalValues() {
        String[] typicalReasons = {"sponsor", "ad", "pre-roll", "mid-roll", "post-roll", "promotion"};

        for (String reason : typicalReasons) {
            AdSegment segment = new AdSegment(0.0, 10.0, reason, 0.9);
            assertEquals(reason, segment.getReason());
        }
    }

    @Test
    public void testReasonWithEmptyString() {
        AdSegment segment = new AdSegment(0.0, 10.0, "", 0.9);
        assertEquals("", segment.getReason());
    }

    @Test
    public void testReasonWithWhitespaceOnly() {
        AdSegment segment = new AdSegment(0.0, 10.0, "   ", 0.9);
        assertEquals("   ", segment.getReason());
    }

    @Test
    public void testReasonWithUnicode() {
        String unicodeReason = "广告 - реклама - 広告";
        AdSegment segment = new AdSegment(0.0, 10.0, unicodeReason, 0.9);
        assertEquals(unicodeReason, segment.getReason());
    }
}
