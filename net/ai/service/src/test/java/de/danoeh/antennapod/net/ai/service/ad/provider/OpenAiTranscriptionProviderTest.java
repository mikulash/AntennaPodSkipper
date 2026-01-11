package de.danoeh.antennapod.net.ai.service.ad.provider;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.openai.errors.BadRequestException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link OpenAiTranscriptionProvider}.
 * Note: Actual API calls are not tested here - these tests focus on configuration and error handling.
 */
@RunWith(RobolectricTestRunner.class)
public class OpenAiTranscriptionProviderTest {

    private Context context;
    private static final String TEST_API_KEY = "sk-test-key-1234567890";
    private static final String PREF_KEY_OPENAI_API_KEY = "pref_openai_api_key";

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Clear preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().apply();
    }

    // ==================== Constructor Tests ====================

    @Test(expected = IllegalStateException.class)
    public void testConstructor_missingApiKey_throwsException() {
        // No API key set
        new OpenAiTranscriptionProvider(context);
    }

    @Test(expected = IllegalStateException.class)
    public void testConstructor_emptyApiKey_throwsException() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_KEY_OPENAI_API_KEY, "").apply();

        new OpenAiTranscriptionProvider(context);
    }

    @Test
    public void testConstructor_withValidApiKey_succeeds() {
        setApiKey(TEST_API_KEY);

        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);
        assertNotNull(provider);
    }

    @Test
    public void testConstructor_withLanguageOverride() {
        setApiKey(TEST_API_KEY);

        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context, "en");
        assertNotNull(provider);
    }

    @Test
    public void testConstructor_withNullLanguageOverride() {
        setApiKey(TEST_API_KEY);

        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context, null);
        assertNotNull(provider);
    }

    // ==================== getMaxAudioBytes() Tests ====================

    @Test
    public void testGetMaxAudioBytes_returns25MB() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        // OpenAI limit is 25 MiB
        long expected = 25L * 1024L * 1024L;
        assertEquals(expected, provider.getMaxAudioBytes());
    }

    // ==================== shouldNotRetry() Tests ====================

    @Test
    public void testShouldNotRetry_unsupportedMimeType_returnsTrue() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("Unsupported audio mime type");
        assertTrue(provider.shouldNotRetry(e));
    }

    @Test
    public void testShouldNotRetry_exceeds25MB_returnsTrue() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("File exceeds 25 MB limit");
        assertTrue(provider.shouldNotRetry(e));
    }

    @Test
    public void testShouldNotRetry_couldNotBeDecoded_returnsTrue() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        // BadRequestException is final, so we simulate with a nested cause
        Exception cause = new Exception("File could not be decoded");
        assertTrue(provider.shouldNotRetry(cause));
    }

    @Test
    public void testShouldNotRetry_formatNotSupported_returnsTrue() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("Audio format is not supported");
        assertTrue(provider.shouldNotRetry(e));
    }

    @Test
    public void testShouldNotRetry_genericError_returnsFalse() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("Connection timeout");
        assertFalse(provider.shouldNotRetry(e));
    }

    @Test
    public void testShouldNotRetry_rateLimitError_returnsFalse() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("Rate limit exceeded");
        assertFalse(provider.shouldNotRetry(e));
    }

    @Test
    public void testShouldNotRetry_nullMessage_returnsFalse() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception((String) null);
        assertFalse(provider.shouldNotRetry(e));
    }

    @Test
    public void testShouldNotRetry_nestedCause() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception innerCause = new Exception("Unsupported audio mime type");
        Exception outer = new Exception("Wrapped exception", innerCause);
        assertTrue(provider.shouldNotRetry(outer));
    }

    @Test
    public void testShouldNotRetry_caseInsensitive() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("UNSUPPORTED AUDIO MIME TYPE");
        assertTrue(provider.shouldNotRetry(e));
    }

    // ==================== buildErrorMessage() Tests ====================

    @Test
    public void testBuildErrorMessage_permanentError_addsHint() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("Unsupported audio mime type");
        String message = provider.buildErrorMessage(e);

        assertTrue(message.contains("Unsupported audio mime type"));
        assertTrue(message.contains("OpenAI supports limited audio formats up to 25 MB"));
    }

    @Test
    public void testBuildErrorMessage_retryableError_noHint() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception("Connection timeout");
        String message = provider.buildErrorMessage(e);

        assertEquals("Connection timeout", message);
        assertFalse(message.contains("OpenAI supports"));
    }

    @Test
    public void testBuildErrorMessage_nullMessage() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Exception e = new Exception((String) null);
        String message = provider.buildErrorMessage(e);

        assertNotNull(message);
    }

    // ==================== transcribeChunk() Tests ====================

    @Test
    public void testTranscribeChunk_nullPath_throwsIOException() throws Exception {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        try {
            provider.transcribeChunk(null, 0, 1, 3);
            fail("Should throw IOException for null path");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Chunk file missing"));
        }
    }

    @Test
    public void testTranscribeChunk_nonExistentFile_throwsIOException() throws Exception {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        Path nonExistentPath = new File("/nonexistent/path/audio.mp3").toPath();

        try {
            provider.transcribeChunk(nonExistentPath, 0, 1, 3);
            fail("Should throw IOException for non-existent file");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Chunk file missing"));
        }
    }

    // ==================== close() Tests ====================

    @Test
    public void testClose_doesNotThrow() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        // Should not throw
        provider.close();
    }

    @Test
    public void testClose_canBeCalledMultipleTimes() {
        setApiKey(TEST_API_KEY);
        OpenAiTranscriptionProvider provider = new OpenAiTranscriptionProvider(context);

        provider.close();
        provider.close();
        provider.close();
        // Should not throw
    }

    // ==================== Helper Methods ====================

    private void setApiKey(String apiKey) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_KEY_OPENAI_API_KEY, apiKey).apply();
    }
}
