package de.danoeh.antennapod.storage.database;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AdSegmentStore}.
 */
@RunWith(RobolectricTestRunner.class)
public class AdSegmentStoreTest {

    private Context context;
    private static final long FEED_ITEM_ID_1 = 1001L;
    private static final long FEED_ITEM_ID_2 = 1002L;
    private static final long TIMESTAMP = 1609459200000L;
    private static final String MODEL = "gpt-4";

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Clean up any existing test data
        AdSegmentStore.clear(context, FEED_ITEM_ID_1);
        AdSegmentStore.clear(context, FEED_ITEM_ID_2);
    }

    @After
    public void tearDown() {
        // Clean up test data
        AdSegmentStore.clear(context, FEED_ITEM_ID_1);
        AdSegmentStore.clear(context, FEED_ITEM_ID_2);
    }

    // ==================== save() and load() Round-trip Tests ====================

    @Test
    public void testSaveAndLoad_basicRoundTrip() {
        List<AdSegment> segments = Arrays.asList(
                new AdSegment(10.0, 40.0, "sponsor", 0.9),
                new AdSegment(300.0, 360.0, "mid-roll", 0.85)
        );
        AdAnalysisResult original = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(original.getAnalyzedAtMillis(), loaded.getAnalyzedAtMillis());
        assertEquals(original.getModel(), loaded.getModel());
        assertEquals(original.getSegments().size(), loaded.getSegments().size());
    }

    @Test
    public void testSaveAndLoad_preservesSegmentData() {
        AdSegment originalSegment = new AdSegment(15.5, 45.75, "pre-roll ad", 0.92);
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.singletonList(originalSegment), TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(1, loaded.getSegments().size());
        AdSegment loadedSegment = loaded.getSegments().get(0);
        assertEquals(originalSegment.getStartSeconds(), loadedSegment.getStartSeconds(), 0.001);
        assertEquals(originalSegment.getEndSeconds(), loadedSegment.getEndSeconds(), 0.001);
        assertEquals(originalSegment.getReason(), loadedSegment.getReason());
        assertEquals(originalSegment.getConfidence(), loadedSegment.getConfidence(), 0.001);
    }

    @Test
    public void testSaveAndLoad_withTranscript() {
        String transcript = "WEBVTT\n\n00:00:00.000 --> 00:00:05.000\nHello world";
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.emptyList(), TIMESTAMP, MODEL, null, transcript);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(transcript, loaded.getTranscript());
    }

    @Test
    public void testSaveAndLoad_withError() {
        String error = "Analysis failed: API timeout";
        AdAnalysisResult original = new AdAnalysisResult(null, TIMESTAMP, MODEL, error);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(error, loaded.getError());
        assertFalse(loaded.isSuccess());
    }

    @Test
    public void testSaveAndLoad_withEmptySegments() {
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.emptyList(), TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertFalse(loaded.hasSegments());
        assertTrue(loaded.getSegments().isEmpty());
    }

    @Test
    public void testSaveAndLoad_withNullSegments() {
        AdAnalysisResult original = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertFalse(loaded.hasSegments());
    }

    @Test
    public void testSaveAndLoad_withMultipleSegments() {
        List<AdSegment> segments = Arrays.asList(
                new AdSegment(0.0, 30.0, "pre-roll", 0.95),
                new AdSegment(300.0, 330.0, "mid-roll 1", 0.88),
                new AdSegment(600.0, 630.0, "mid-roll 2", 0.87),
                new AdSegment(900.0, 930.0, "mid-roll 3", 0.90),
                new AdSegment(1200.0, 1230.0, "post-roll", 0.93)
        );
        AdAnalysisResult original = new AdAnalysisResult(segments, TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(5, loaded.getSegments().size());
    }

    @Test
    public void testSaveAndLoad_preservesAllFields() {
        List<AdSegment> segments = Collections.singletonList(
                new AdSegment(100.0, 200.0, "sponsor read", 0.87)
        );
        String transcript = "Full transcript content here";
        String error = null;
        AdAnalysisResult original = new AdAnalysisResult(
                segments, TIMESTAMP, MODEL, error, transcript);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(TIMESTAMP, loaded.getAnalyzedAtMillis());
        assertEquals(MODEL, loaded.getModel());
        assertEquals(transcript, loaded.getTranscript());
        assertTrue(loaded.isSuccess());
        assertEquals(1, loaded.getSegments().size());
    }

    @Test
    public void testSaveAndLoad_withSpecialCharactersInReason() {
        String reason = "Ad with \"quotes\" and <special> & chars";
        AdSegment segment = new AdSegment(0.0, 10.0, reason, 0.9);
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.singletonList(segment), TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(reason, loaded.getSegments().get(0).getReason());
    }

    @Test
    public void testSaveAndLoad_withUnicodeContent() {
        String unicodeReason = "广告 - реклама - 広告";
        String unicodeTranscript = "日本語テスト - Тест на русском";
        AdSegment segment = new AdSegment(0.0, 10.0, unicodeReason, 0.9);
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.singletonList(segment), TIMESTAMP, MODEL, null, unicodeTranscript);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(unicodeReason, loaded.getSegments().get(0).getReason());
        assertEquals(unicodeTranscript, loaded.getTranscript());
    }

    @Test
    public void testSaveAndLoad_withLargeTranscript() {
        StringBuilder largeTranscript = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeTranscript.append("Word ").append(i).append(" ");
        }
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.emptyList(), TIMESTAMP, MODEL, null, largeTranscript.toString());

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(largeTranscript.toString(), loaded.getTranscript());
    }

    @Test
    public void testSaveAndLoad_precisionOfDoubleValues() {
        AdSegment segment = new AdSegment(0.123456789, 1.987654321, "precision test", 0.999999999);
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.singletonList(segment), TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        AdSegment loadedSegment = loaded.getSegments().get(0);
        assertEquals(segment.getStartSeconds(), loadedSegment.getStartSeconds(), 0.0000001);
        assertEquals(segment.getEndSeconds(), loadedSegment.getEndSeconds(), 0.0000001);
        assertEquals(segment.getConfidence(), loadedSegment.getConfidence(), 0.0000001);
    }

    // ==================== hasAnalysis() Tests ====================

    @Test
    public void testHasAnalysis_afterSave() {
        assertFalse(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));

        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result);

        assertTrue(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));
    }

    @Test
    public void testHasAnalysis_forNonExistentItem() {
        assertFalse(AdSegmentStore.hasAnalysis(context, 999999L));
    }

    @Test
    public void testHasAnalysis_afterClear() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result);
        assertTrue(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));

        AdSegmentStore.clear(context, FEED_ITEM_ID_1);
        assertFalse(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));
    }

    @Test
    public void testHasAnalysis_differentFeedItems() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result);

        assertTrue(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));
        assertFalse(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_2));
    }

    // ==================== load() Tests ====================

    @Test
    public void testLoad_nonExistentFile() {
        AdAnalysisResult loaded = AdSegmentStore.load(context, 999999L);
        assertNull(loaded);
    }

    @Test
    public void testLoad_afterClear() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result);
        AdSegmentStore.clear(context, FEED_ITEM_ID_1);

        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);
        assertNull(loaded);
    }

    // ==================== clear() Tests ====================

    @Test
    public void testClear_existingFile() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result);
        assertTrue(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));

        AdSegmentStore.clear(context, FEED_ITEM_ID_1);
        assertFalse(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));
    }

    @Test
    public void testClear_nonExistentFile() {
        // Should not throw exception
        AdSegmentStore.clear(context, 999999L);
        assertFalse(AdSegmentStore.hasAnalysis(context, 999999L));
    }

    @Test
    public void testClear_multipleTimesOnSameId() {
        AdAnalysisResult result = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result);

        AdSegmentStore.clear(context, FEED_ITEM_ID_1);
        AdSegmentStore.clear(context, FEED_ITEM_ID_1);
        AdSegmentStore.clear(context, FEED_ITEM_ID_1);

        assertFalse(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));
    }

    @Test
    public void testClear_onlyAffectsSpecifiedId() {
        AdAnalysisResult result1 = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);
        AdAnalysisResult result2 = new AdAnalysisResult(null, TIMESTAMP + 1000, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result1);
        AdSegmentStore.save(context, FEED_ITEM_ID_2, result2);

        AdSegmentStore.clear(context, FEED_ITEM_ID_1);

        assertFalse(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_1));
        assertTrue(AdSegmentStore.hasAnalysis(context, FEED_ITEM_ID_2));
    }

    // ==================== save() Overwrite Tests ====================

    @Test
    public void testSave_overwritesExistingFile() {
        List<AdSegment> segments1 = Collections.singletonList(
                new AdSegment(0.0, 10.0, "first", 0.8)
        );
        AdAnalysisResult result1 = new AdAnalysisResult(segments1, TIMESTAMP, "model1", null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result1);

        List<AdSegment> segments2 = Collections.singletonList(
                new AdSegment(20.0, 30.0, "second", 0.9)
        );
        AdAnalysisResult result2 = new AdAnalysisResult(segments2, TIMESTAMP + 1000, "model2", null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, result2);

        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);
        assertNotNull(loaded);
        assertEquals("model2", loaded.getModel());
        assertEquals(TIMESTAMP + 1000, loaded.getAnalyzedAtMillis());
        assertEquals(1, loaded.getSegments().size());
        assertEquals("second", loaded.getSegments().get(0).getReason());
    }

    // ==================== Edge Cases ====================

    @Test
    public void testSaveAndLoad_withZeroTimestamp() {
        AdAnalysisResult original = new AdAnalysisResult(null, 0L, MODEL, null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(0L, loaded.getAnalyzedAtMillis());
    }

    @Test
    public void testSaveAndLoad_withEmptyModel() {
        AdAnalysisResult original = new AdAnalysisResult(null, TIMESTAMP, "", null);
        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals("", loaded.getModel());
    }

    @Test
    public void testSaveAndLoad_withEmptyError() {
        AdAnalysisResult original = new AdAnalysisResult(null, TIMESTAMP, MODEL, "");
        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals("", loaded.getError());
    }

    @Test
    public void testSaveAndLoad_withSegmentHavingZeroConfidence() {
        AdSegment segment = new AdSegment(0.0, 10.0, "low confidence", 0.0);
        AdAnalysisResult original = new AdAnalysisResult(
                Collections.singletonList(segment), TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, FEED_ITEM_ID_1);

        assertNotNull(loaded);
        assertEquals(0.0, loaded.getSegments().get(0).getConfidence(), 0.001);
    }

    @Test
    public void testSaveAndLoad_withVeryLongFeedItemId() {
        long longId = Long.MAX_VALUE;
        AdAnalysisResult original = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, longId, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, longId);

        assertNotNull(loaded);
        assertEquals(TIMESTAMP, loaded.getAnalyzedAtMillis());

        // Clean up
        AdSegmentStore.clear(context, longId);
    }

    @Test
    public void testSaveAndLoad_withNegativeFeedItemId() {
        long negativeId = -12345L;
        AdAnalysisResult original = new AdAnalysisResult(null, TIMESTAMP, MODEL, null);

        AdSegmentStore.save(context, negativeId, original);
        AdAnalysisResult loaded = AdSegmentStore.load(context, negativeId);

        assertNotNull(loaded);
        assertEquals(TIMESTAMP, loaded.getAnalyzedAtMillis());

        // Clean up
        AdSegmentStore.clear(context, negativeId);
    }

    // ==================== Multiple Feed Items Tests ====================

    @Test
    public void testMultipleFeedItems_independentStorage() {
        List<AdSegment> segments1 = Collections.singletonList(
                new AdSegment(0.0, 10.0, "item1", 0.8)
        );
        List<AdSegment> segments2 = Collections.singletonList(
                new AdSegment(20.0, 30.0, "item2", 0.9)
        );

        AdAnalysisResult result1 = new AdAnalysisResult(segments1, TIMESTAMP, "model1", null);
        AdAnalysisResult result2 = new AdAnalysisResult(segments2, TIMESTAMP + 1000, "model2", null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, result1);
        AdSegmentStore.save(context, FEED_ITEM_ID_2, result2);

        AdAnalysisResult loaded1 = AdSegmentStore.load(context, FEED_ITEM_ID_1);
        AdAnalysisResult loaded2 = AdSegmentStore.load(context, FEED_ITEM_ID_2);

        assertNotNull(loaded1);
        assertNotNull(loaded2);

        assertEquals("model1", loaded1.getModel());
        assertEquals("model2", loaded2.getModel());
        assertEquals("item1", loaded1.getSegments().get(0).getReason());
        assertEquals("item2", loaded2.getSegments().get(0).getReason());
    }

    @Test
    public void testMultipleFeedItems_clearOneDoesNotAffectOther() {
        AdAnalysisResult result1 = new AdAnalysisResult(null, TIMESTAMP, "model1", null);
        AdAnalysisResult result2 = new AdAnalysisResult(null, TIMESTAMP, "model2", null);

        AdSegmentStore.save(context, FEED_ITEM_ID_1, result1);
        AdSegmentStore.save(context, FEED_ITEM_ID_2, result2);

        AdSegmentStore.clear(context, FEED_ITEM_ID_1);

        assertNull(AdSegmentStore.load(context, FEED_ITEM_ID_1));
        assertNotNull(AdSegmentStore.load(context, FEED_ITEM_ID_2));
    }
}
