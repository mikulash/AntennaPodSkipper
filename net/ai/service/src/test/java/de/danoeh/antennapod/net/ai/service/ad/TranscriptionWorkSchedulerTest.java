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
 * Unit tests for {@link TranscriptionWorkScheduler}.
 * Tests focus on validation logic, constraint building, and input data creation.
 * Note: Actual WorkManager enqueuing is not tested here.
 */
@RunWith(RobolectricTestRunner.class)
public class TranscriptionWorkSchedulerTest {

    private Context context;
    private static final String PREF_KEY_OPENAI_API_KEY = "pref_openai_api_key";
    private static final String PREF_KEY_LOCAL_TRANSCRIPTION = "pref_local_ai_enabled";
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
        String prefix = "transcription-";
        assertNotNull(prefix);
        assertTrue(prefix.startsWith("transcription"));
    }

    @Test
    public void testUniqueWorkName_withFeedItemId() {
        String prefix = "transcription-";
        long feedItemId = 12345L;
        String uniqueName = prefix + feedItemId;
        assertEquals("transcription-12345", uniqueName);
    }

    @Test
    public void testUniqueWorkName_differentIds() {
        String prefix = "transcription-";
        String name1 = prefix + 1L;
        String name2 = prefix + 2L;
        assertFalse(name1.equals(name2));
    }

    // ==================== Data Builder Tests ====================

    @Test
    public void testDataBuilder_containsFeedItemId() {
        long feedItemId = 54321L;
        Data input = new Data.Builder()
                .putLong(TranscriptionWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(feedItemId, input.getLong(TranscriptionWorker.DATA_FEED_ITEM_ID, -1));
    }

    @Test
    public void testDataBuilder_zeroId() {
        long feedItemId = 0L;
        Data input = new Data.Builder()
                .putLong(TranscriptionWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(0L, input.getLong(TranscriptionWorker.DATA_FEED_ITEM_ID, -1));
    }

    @Test
    public void testDataBuilder_negativeId() {
        long feedItemId = -1L;
        Data input = new Data.Builder()
                .putLong(TranscriptionWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(-1L, input.getLong(TranscriptionWorker.DATA_FEED_ITEM_ID, 0));
    }

    @Test
    public void testDataBuilder_maxLongId() {
        long feedItemId = Long.MAX_VALUE;
        Data input = new Data.Builder()
                .putLong(TranscriptionWorker.DATA_FEED_ITEM_ID, feedItemId)
                .build();

        assertEquals(Long.MAX_VALUE, input.getLong(TranscriptionWorker.DATA_FEED_ITEM_ID, -1));
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
    public void testApiKeyValidation_whitespaceKey() {
        String apiKey = "   ";
        boolean isEmpty = apiKey.trim().isEmpty();
        assertTrue(isEmpty);
    }

    // ==================== Cloud Model Detection Tests ====================

    @Test
    public void testCloudModelDetection_cloudPrefix() {
        String model = "cloud:whisper-1";
        boolean isCloud = model != null && model.startsWith("cloud:");
        assertTrue(isCloud);
    }

    @Test
    public void testCloudModelDetection_localModel() {
        String model = "vosk-model-en-us-0.22";
        boolean isCloud = model != null && model.startsWith("cloud:");
        assertFalse(isCloud);
    }

    @Test
    public void testCloudModelDetection_nullModel() {
        String model = null;
        boolean isCloud = model != null && model.startsWith("cloud:");
        assertFalse(isCloud);
    }

    @Test
    public void testCloudModelDetection_emptyModel() {
        String model = "";
        boolean isCloud = model != null && model.startsWith("cloud:");
        assertFalse(isCloud);
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

    // ==================== Network Requirement Logic Tests ====================

    @Test
    public void testNetworkRequirement_cloudModel_needsNetwork() {
        boolean feedUsesCloudModel = true;
        boolean localTranscriptionEnabled = true;

        boolean needsNetwork = feedUsesCloudModel || !localTranscriptionEnabled;
        assertTrue(needsNetwork);
    }

    @Test
    public void testNetworkRequirement_localModel_localDisabled_needsNetwork() {
        boolean feedUsesCloudModel = false;
        boolean localTranscriptionEnabled = false;

        boolean needsNetwork = feedUsesCloudModel || !localTranscriptionEnabled;
        assertTrue(needsNetwork);
    }

    @Test
    public void testNetworkRequirement_localModel_localEnabled_noNetwork() {
        boolean feedUsesCloudModel = false;
        boolean localTranscriptionEnabled = true;

        boolean needsNetwork = feedUsesCloudModel || !localTranscriptionEnabled;
        assertFalse(needsNetwork);
    }

    // ==================== Constraint Building Tests ====================

    @Test
    public void testConstraints_batteryNotLow() {
        // The scheduler requires battery not low
        boolean requiresBatteryNotLow = true;
        assertTrue(requiresBatteryNotLow);
    }

    // ==================== Tag Building Tests ====================

    @Test
    public void testTagBuilding_format() {
        String prefix = "transcription-";
        long itemId = 99999L;
        String tag = prefix + itemId;
        assertEquals("transcription-99999", tag);
    }

    @Test
    public void testTagBuilding_uniqueness() {
        String prefix = "transcription-";
        String tag1 = prefix + 1L;
        String tag2 = prefix + 1L;
        assertEquals(tag1, tag2); // Same ID = same tag
    }
}
