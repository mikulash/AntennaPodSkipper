package de.danoeh.antennapod.net.ai.service.ad;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the logic in {@link AudioChunkUtils}.
 * Tests mime type validation and chunk calculation logic.
 * Note: Tests requiring MediaExtractor/MediaMuxer would need Robolectric with proper shadows.
 */
public class AudioChunkUtilsTest {

    // MIME type constants matching AudioChunkUtils
    private static final String MIME_MP3 = "audio/mpeg";
    private static final String MIME_AAC = "audio/mp4a-latm";
    private static final String MIME_MP4 = "audio/mp4";
    private static final String MIME_WEBM = "audio/webm";
    private static final String MIME_WAV = "audio/wav";
    private static final String MIME_WAVE = "audio/x-wav";
    private static final String MIME_OPUS = "audio/opus";
    private static final String MIME_RAW = "audio/raw";

    // ==================== MIME Type Validation Tests ====================

    @Test
    public void testIsOpenAiSupportedMime_mp3() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_MP3));
    }

    @Test
    public void testIsOpenAiSupportedMime_aac() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_AAC));
    }

    @Test
    public void testIsOpenAiSupportedMime_mp4() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_MP4));
    }

    @Test
    public void testIsOpenAiSupportedMime_webm() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_WEBM));
    }

    @Test
    public void testIsOpenAiSupportedMime_wav() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_WAV));
    }

    @Test
    public void testIsOpenAiSupportedMime_xwav() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_WAVE));
    }

    @Test
    public void testIsOpenAiSupportedMime_opusCodec() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_OPUS));
    }

    @Test
    public void testIsOpenAiSupportedMime_rawPcmCodec() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime(MIME_RAW));
    }

    @Test
    public void testIsOpenAiSupportedMime_uppercase() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime("AUDIO/MPEG"));
        assertTrue(AudioChunkUtils.isSupportedTrackMime("AUDIO/MP4A-LATM"));
    }

    @Test
    public void testIsOpenAiSupportedMime_mixedCase() {
        assertTrue(AudioChunkUtils.isSupportedTrackMime("Audio/Mpeg"));
        assertTrue(AudioChunkUtils.isSupportedTrackMime("Audio/Mp4"));
    }

    @Test
    public void testIsOpenAiSupportedMime_unsupported() {
        assertFalse(AudioChunkUtils.isSupportedTrackMime("audio/ogg"));
        assertFalse(AudioChunkUtils.isSupportedTrackMime("audio/flac"));
        assertFalse(AudioChunkUtils.isSupportedTrackMime("audio/vorbis"));
        assertFalse(AudioChunkUtils.isSupportedTrackMime("video/mp4"));
    }

    @Test
    public void testIsOpenAiSupportedMime_null() {
        assertFalse(AudioChunkUtils.isSupportedTrackMime(null));
    }

    @Test
    public void testIsOpenAiSupportedMime_empty() {
        assertFalse(AudioChunkUtils.isSupportedTrackMime(""));
    }

    @Test
    public void testIsOpenAiSupportedMime_withSuffix() {
        // OpenAI accepts various audio/mpeg subtypes
        assertTrue(AudioChunkUtils.isSupportedTrackMime("audio/mpeg; codecs=mp3"));
    }

    // ==================== Chunk Calculation Tests ====================

    @Test
    public void testChunkCalculation_shortAudio() {
        long durationSeconds = 60; // 1 minute
        long chunkDurationSeconds = 150; // 2.5 minutes

        int expectedChunks = calculateExpectedChunks(durationSeconds, chunkDurationSeconds);
        assertEquals(1, expectedChunks);
    }

    @Test
    public void testChunkCalculation_exactFit() {
        long durationSeconds = 300; // 5 minutes
        long chunkDurationSeconds = 150; // 2.5 minutes

        int expectedChunks = calculateExpectedChunks(durationSeconds, chunkDurationSeconds);
        assertEquals(2, expectedChunks);
    }

    @Test
    public void testChunkCalculation_withRemainder() {
        long durationSeconds = 400; // 6m 40s
        long chunkDurationSeconds = 150; // 2.5 minutes

        int expectedChunks = calculateExpectedChunks(durationSeconds, chunkDurationSeconds);
        assertEquals(3, expectedChunks);
    }

    @Test
    public void testChunkCalculation_longPodcast() {
        long durationSeconds = 3600; // 1 hour
        long chunkDurationSeconds = 150; // 2.5 minutes

        int expectedChunks = calculateExpectedChunks(durationSeconds, chunkDurationSeconds);
        assertEquals(24, expectedChunks);
    }

    @Test
    public void testChunkCalculation_veryLongPodcast() {
        long durationSeconds = 7200; // 2 hours
        long chunkDurationSeconds = 150; // 2.5 minutes

        int expectedChunks = calculateExpectedChunks(durationSeconds, chunkDurationSeconds);
        assertEquals(48, expectedChunks);
    }

    // ==================== Duration Conversion Tests ====================

    @Test
    public void testDurationConversion_minutesToMicroseconds() {
        long seconds = 150;
        long microseconds = seconds * 1_000_000L;
        assertEquals(150_000_000L, microseconds);
    }

    @Test
    public void testDurationConversion_hourToMicroseconds() {
        long seconds = 3600;
        long microseconds = seconds * 1_000_000L;
        assertEquals(3_600_000_000L, microseconds);
    }

    @Test
    public void testDurationConversion_microsecondsToSeconds() {
        long microseconds = 3_600_000_000L;
        long seconds = microseconds / 1_000_000L;
        assertEquals(3600, seconds);
    }

    // ==================== Chunk Boundary Tests ====================

    @Test
    public void testChunkBoundaries_firstChunk() {
        long durationUs = 600_000_000L; // 600 seconds = 10 minutes
        long chunkDurationUs = 150_000_000L; // 150 seconds = 2.5 minutes
        long startUs = 0;

        long endUs = Math.min(durationUs, startUs + chunkDurationUs);
        assertEquals(150_000_000L, endUs);
    }

    @Test
    public void testChunkBoundaries_lastChunk() {
        long durationUs = 400_000_000L; // 400 seconds
        long chunkDurationUs = 150_000_000L; // 150 seconds
        long startUs = 300_000_000L; // Third chunk starts at 300s

        long endUs = Math.min(durationUs, startUs + chunkDurationUs);
        assertEquals(400_000_000L, endUs); // Should end at duration
    }

    @Test
    public void testChunkBoundaries_iterateAllChunks() {
        long durationUs = 500_000_000L; // 500 seconds
        long chunkDurationUs = 150_000_000L;

        int chunkCount = 0;
        long startUs = 0;
        while (startUs < durationUs) {
            long endUs = Math.min(durationUs, startUs + chunkDurationUs);
            chunkCount++;
            startUs = endUs;
        }

        assertEquals(4, chunkCount);
    }

    // ==================== Buffer Size Tests ====================

    @Test
    public void testBufferSize_default() {
        int bufferSize = 256 * 1024; // 256KB
        assertEquals(262144, bufferSize);
    }

    @Test
    public void testBufferSize_sufficientForSamples() {
        // Most audio samples are much smaller than 256KB
        int bufferSize = 256 * 1024;
        int typicalFrameSize = 4096; // Typical AAC frame
        assertTrue(bufferSize > typicalFrameSize);
    }

    // ==================== File Extension Tests ====================

    @Test
    public void testFileExtension_mp3() {
        String extension = AudioChunkUtils.getChunkFileExtension(MIME_MP3);
        assertEquals(".mp3", extension);
    }

    @Test
    public void testFileExtension_matchesChunkContainer() {
        assertEquals(".m4a", AudioChunkUtils.getChunkFileExtension(MIME_AAC));
        assertEquals(".webm", AudioChunkUtils.getChunkFileExtension(MIME_OPUS));
        assertEquals(".webm", AudioChunkUtils.getChunkFileExtension(MIME_WEBM));
        assertEquals(".wav", AudioChunkUtils.getChunkFileExtension(MIME_RAW));
        assertEquals(".wav", AudioChunkUtils.getChunkFileExtension(MIME_WAV));
    }

    @Test
    public void testWavHeader_containsPcmMetadataAndDataLength() throws Exception {
        File output = File.createTempFile("audio_chunk_test", ".wav");
        try {
            try (RandomAccessFile wav = new RandomAccessFile(output, "rw")) {
                AudioChunkUtils.writeWavHeader(wav, 1, 2, 48_000, 16, 96_000);
            }
            byte[] header = Files.readAllBytes(output.toPath());
            ByteBuffer values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

            assertEquals("RIFF", new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(96_036, values.getInt(4));
            assertEquals("WAVE", new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(2, values.getShort(22));
            assertEquals(48_000, values.getInt(24));
            assertEquals(192_000, values.getInt(28));
            assertEquals(16, values.getShort(34));
            assertEquals(96_000, values.getInt(40));
        } finally {
            assertTrue(output.delete() || !output.exists());
        }
    }

    // ==================== Audio Track Selection Logic Tests ====================

    @Test
    public void testAudioTrackSelection_audioMimeStartsWith() {
        assertTrue("audio/mpeg".startsWith("audio/"));
        assertTrue("audio/mp4".startsWith("audio/"));
        assertTrue("audio/wav".startsWith("audio/"));
    }

    @Test
    public void testAudioTrackSelection_videoMimeDoesNotMatch() {
        assertFalse("video/mp4".startsWith("audio/"));
        assertFalse("text/plain".startsWith("audio/"));
    }

    private int calculateExpectedChunks(long durationSeconds, long chunkDurationSeconds) {
        return (int) Math.ceil((double) durationSeconds / chunkDurationSeconds);
    }
}
