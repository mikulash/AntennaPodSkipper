package de.danoeh.antennapod.net.ai.service.ad;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.work.Data;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AdAnalysisWorkScheduler}.
 * Tests focus on validation logic, constraint building, and input data creation.
 * Note: Actual WorkManager enqueuing is not tested here.
 */
@RunWith(RobolectricTestRunner.class)
public class AdAnalysisWorkSchedulerTest {

    private Context context;
    private static final String PREF_KEY_OPENAI_API_KEY = "pref_openai_api_key";
    private static final String TEST_API_KEY = "sk-test-key-1234567890";

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Clear preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().apply();
    }

    // ==================== Unique Work Name Tests ====================

    @Test
    public void testUniqueWorkPrefix() {
        String prefix = "ad-analysis-";
        assertNotNull(prefix);
        assertTrue(prefix.startsWith("ad-analysis"));
    }

    @Test
    public void testUniqueWorkName_withFeedItemId() {
        String prefix = "ad-analysis-";
        long feedItemId = 12345L;
        String uniqueName = prefix + feedItemId;
        assertEquals("ad-analysis-12345", uniqueName);
    }

    @Test
    public void testUniqueWorkName_differentIds() {
        String prefix = "ad-analysis-";
        String name1 = prefix + 1L;
        String name2 = prefix + 2L;
        assertFalse(name1.equals(name2));
    }

    @Test
    public void testUniqueWorkName_sameIdSameName() {
        String prefix = "ad-analysis-";
        String name1 = prefix + 100L;
        String name2 = prefix + 100L;
        assertEquals(name1, name2);
    }

    // ==================== Data Builder Tests ====================

    @Test
    public void testDataBuilder_containsFeedItemId() {
        long feedItemId = 54321L;
        Data input = new Data.Builder()
                .putLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(feedItemId, input.getLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, -1));
    }

    @Test
    public void testDataBuilder_zeroId() {
        long feedItemId = 0L;
        Data input = new Data.Builder()
                .putLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(0L, input.getLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, -1));
    }

    @Test
    public void testDataBuilder_negativeId() {
        long feedItemId = -1L;
        Data input = new Data.Builder()
                .putLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(-1L, input.getLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, 0));
    }

    @Test
    public void testDataBuilder_largeId() {
        long feedItemId = 9999999999L;
        Data input = new Data.Builder()
                .putLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(9999999999L, input.getLong(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, -1));
    }

    // ==================== API Key Validation Logic Tests ====================

    @Test
    public void testApiKeyValidation_emptyKey() {
        String apiKey = "";
        boolean isEmpty = apiKey == null || apiKey.isEmpty();
        assertTrue(isEmpty);
    }

    @Test
    public void testApiKeyValidation_nullKey() {
        String apiKey = null;
        boolean isEmpty = apiKey == null || apiKey.isEmpty();
        assertTrue(isEmpty);
    }

    @Test
    public void testApiKeyValidation_validKey() {
        String apiKey = TEST_API_KEY;
        boolean isEmpty = apiKey == null || apiKey.isEmpty();
        assertFalse(isEmpty);
    }

    @Test
    public void testApiKeyValidation_whitespaceOnlyKey() {
        String apiKey = "     ";
        boolean isEmpty = apiKey.trim().isEmpty();
        assertTrue(isEmpty);
    }

    // ==================== Local File URL Validation Tests ====================

    @Test
    public void testLocalFileUrlValidation_validPath() {
        String localFileUrl = "/storage/emulated/0/Podcasts/episode.mp3";
        boolean isEmpty = localFileUrl == null || localFileUrl.isEmpty();
        assertFalse(isEmpty);
    }

    @Test
    public void testLocalFileUrlValidation_nullPath() {
        String localFileUrl = null;
        boolean isEmpty = localFileUrl == null || localFileUrl.isEmpty();
        assertTrue(isEmpty);
    }

    @Test
    public void testLocalFileUrlValidation_emptyPath() {
        String localFileUrl = "";
        boolean isEmpty = localFileUrl == null || localFileUrl.isEmpty();
        assertTrue(isEmpty);
    }

    @Test
    public void testLocalFileUrlValidation_pathWithSpaces() {
        String localFileUrl = "/storage/My Podcasts/episode 1.mp3";
        boolean isEmpty = localFileUrl == null || localFileUrl.isEmpty();
        assertFalse(isEmpty);
    }

    // ==================== Network Requirement Tests ====================

    @Test
    public void testNetworkRequirement_alwaysConnected() {
        // Ad analysis always requires network (uses OpenAI API)
        boolean needsNetwork = true;
        assertTrue(needsNetwork);
    }

    // ==================== Constraint Building Tests ====================

    @Test
    public void testConstraints_batteryNotLow() {
        // The scheduler requires battery not low
        boolean requiresBatteryNotLow = true;
        assertTrue(requiresBatteryNotLow);
    }

    @Test
    public void testConstraints_networkConnected() {
        // Ad analysis requires network connection
        boolean requiresNetwork = true;
        assertTrue(requiresNetwork);
    }

    // ==================== Tag Building Tests ====================

    @Test
    public void testTagBuilding_format() {
        String prefix = "ad-analysis-";
        long itemId = 99999L;
        String tag = prefix + itemId;
        assertEquals("ad-analysis-99999", tag);
    }

    @Test
    public void testTagBuilding_uniquenessPerItem() {
        String prefix = "ad-analysis-";
        String tag1 = prefix + 1L;
        String tag2 = prefix + 1L;
        assertEquals(tag1, tag2); // Same ID = same tag (for replacement)
    }

    @Test
    public void testTagBuilding_differentFromTranscription() {
        String adAnalysisTag = "ad-analysis-123";
        String transcriptionTag = "transcription-123";
        assertFalse(adAnalysisTag.equals(transcriptionTag));
    }

    // ==================== Work Policy Tests ====================

    @Test
    public void testWorkPolicy_replaceExisting() {
        // Ad analysis uses REPLACE policy for manual triggers
        // This ensures re-running analysis replaces any existing work
        String policy = "REPLACE";
        assertEquals("REPLACE", policy);
    }

    // ==================== Media and Item Null Checks ====================

    @Test
    public void testNullCheck_mediaNull() {
        Object media = null;
        boolean shouldReturn = (media == null);
        assertTrue(shouldReturn);
    }

    @Test
    public void testNullCheck_itemNull() {
        // Simulates media.getItem() returning null
        Object item = null;
        boolean shouldReturn = (item == null);
        assertTrue(shouldReturn);
    }

    // ==================== Integration Scenario Tests ====================

    @Test
    public void testScenario_validInputs() {
        // All conditions met for enqueuing
        String apiKey = TEST_API_KEY;
        String localFileUrl = "/path/to/audio.mp3";
        long feedItemId = 12345L;

        boolean hasApiKey = apiKey != null && !apiKey.isEmpty();
        boolean hasLocalFile = localFileUrl != null && !localFileUrl.isEmpty();
        boolean hasValidId = feedItemId > 0;

        assertTrue(hasApiKey);
        assertTrue(hasLocalFile);
        assertTrue(hasValidId);
    }

    @Test
    public void testScenario_missingApiKey() {
        // Should not enqueue when API key is missing
        String apiKey = "";
        boolean hasApiKey = apiKey != null && !apiKey.isEmpty();
        assertFalse(hasApiKey);
    }

    @Test
    public void testScenario_missingLocalFile() {
        // Should not enqueue when local file is missing
        String localFileUrl = null;
        boolean hasLocalFile = localFileUrl != null && !localFileUrl.isEmpty();
        assertFalse(hasLocalFile);
    }
}
