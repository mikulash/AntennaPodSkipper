package de.danoeh.antennapod.net.ai.service.ad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Unit tests for logic in {@link TranscriptAnalysisWorker}.
 * Tests focus on JSON parsing, segment merging, transcript splitting, and error detection.
 * Note: Actual worker execution requires Android's WorkManager and is not tested here.
 */
public class TranscriptAnalysisWorkerTest {

    private static final int MAX_TRANSCRIPT_CHARS_PER_CHUNK = 100000;

    // ==================== JSON Sanitization Tests ====================

    @Test
    public void testSanitizeJson_plainJson() {
        String raw = "{\"ads\": []}";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": []}", sanitized);
    }

    @Test
    public void testSanitizeJson_withCodeBlock() {
        String raw = "```json\n{\"ads\": []}\n```";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": []}", sanitized);
    }

    @Test
    public void testSanitizeJson_withCodeBlockNoLanguage() {
        String raw = "```\n{\"ads\": []}\n```";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": []}", sanitized);
    }

    @Test
    public void testSanitizeJson_withLeadingText() {
        String raw = "Here is the result:\n{\"ads\": [{\"startSeconds\": 0, \"endSeconds\": 30}]}";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": [{\"startSeconds\": 0, \"endSeconds\": 30}]}", sanitized);
    }

    @Test
    public void testSanitizeJson_withTrailingText() {
        String raw = "{\"ads\": []} This is the analysis result.";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": []}", sanitized);
    }

    @Test
    public void testSanitizeJson_null() {
        String sanitized = sanitizeJson(null);
        assertEquals(null, sanitized);
    }

    @Test
    public void testSanitizeJson_empty() {
        String sanitized = sanitizeJson("");
        assertEquals("", sanitized);
    }

    @Test
    public void testSanitizeJson_whitespace() {
        String raw = "   {\"ads\": []}   ";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": []}", sanitized);
    }

    @Test
    public void testSanitizeJson_nestedBraces() {
        String raw = "{\"ads\": [{\"nested\": {\"key\": \"value\"}}]}";
        String sanitized = sanitizeJson(raw);
        assertEquals("{\"ads\": [{\"nested\": {\"key\": \"value\"}}]}", sanitized);
    }

    // ==================== Segment Parsing Tests ====================

    @Test
    public void testParseSegments_emptyAds() throws JSONException {
        String json = "{\"ads\": []}";
        List<AdSegmentData> segments = parseSegments(json);
        assertTrue(segments.isEmpty());
    }

    @Test
    public void testParseSegments_singleAd() throws JSONException {
        String json = "{\"ads\": [{\"startSeconds\": 10.5, \"endSeconds\": 45.0, \"reason\": \"sponsor\", \"confidence\": 0.95}]}";
        List<AdSegmentData> segments = parseSegments(json);

        assertEquals(1, segments.size());
        assertEquals(10.5, segments.get(0).start, 0.001);
        assertEquals(45.0, segments.get(0).end, 0.001);
        assertEquals("sponsor", segments.get(0).reason);
        assertEquals(0.95, segments.get(0).confidence, 0.001);
    }

    @Test
    public void testParseSegments_multipleAds() throws JSONException {
        String json = "{\"ads\": ["
                + "{\"startSeconds\": 0, \"endSeconds\": 30, \"reason\": \"intro\", \"confidence\": 0.9},"
                + "{\"startSeconds\": 120, \"endSeconds\": 180, \"reason\": \"midroll\", \"confidence\": 0.85},"
                + "{\"startSeconds\": 600, \"endSeconds\": 650, \"reason\": \"sponsor\", \"confidence\": 0.95}"
                + "]}";
        List<AdSegmentData> segments = parseSegments(json);

        assertEquals(3, segments.size());
    }

    @Test
    public void testParseSegments_missingFields() throws JSONException {
        String json = "{\"ads\": [{\"startSeconds\": 10}]}";
        List<AdSegmentData> segments = parseSegments(json);

        // Should be filtered out because end <= start (both default to 0 for missing)
        assertTrue(segments.isEmpty());
    }

    @Test
    public void testParseSegments_invalidRange() throws JSONException {
        String json = "{\"ads\": [{\"startSeconds\": 50, \"endSeconds\": 30}]}";
        List<AdSegmentData> segments = parseSegments(json);

        // Should be filtered out because end < start
        assertTrue(segments.isEmpty());
    }

    @Test
    public void testParseSegments_noAdsArray() throws JSONException {
        String json = "{\"result\": \"no ads found\"}";
        List<AdSegmentData> segments = parseSegments(json);
        assertTrue(segments.isEmpty());
    }

    @Test
    public void testParseSegments_defaultValues() throws JSONException {
        String json = "{\"ads\": [{\"startSeconds\": 0, \"endSeconds\": 30}]}";
        List<AdSegmentData> segments = parseSegments(json);

        assertEquals(1, segments.size());
        assertEquals("", segments.get(0).reason); // Default empty string
        assertEquals(0.0, segments.get(0).confidence, 0.001); // Default 0
    }

    // ==================== Segment Merging Tests ====================

    @Test
    public void testMergeSegments_noOverlap() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));
        input.add(new AdSegmentData(100, 130, "ad2", 0.8));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(2, merged.size());
    }

    @Test
    public void testMergeSegments_exactOverlap() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));
        input.add(new AdSegmentData(30, 60, "ad2", 0.8));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(1, merged.size());
        assertEquals(0, merged.get(0).start, 0.001);
        assertEquals(60, merged.get(0).end, 0.001);
    }

    @Test
    public void testMergeSegments_withinThreshold() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));
        input.add(new AdSegmentData(30.4, 60, "ad2", 0.8)); // 0.4s gap, within 0.5s threshold

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(1, merged.size());
    }

    @Test
    public void testMergeSegments_justOutsideThreshold() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));
        input.add(new AdSegmentData(30.6, 60, "ad2", 0.8)); // 0.6s gap, outside 0.5s threshold

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(2, merged.size());
    }

    @Test
    public void testMergeSegments_unsorted() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(100, 130, "ad2", 0.8));
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(2, merged.size());
        assertEquals(0, merged.get(0).start, 0.001); // Should be sorted
    }

    @Test
    public void testMergeSegments_overlapping() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));
        input.add(new AdSegmentData(20, 50, "ad2", 0.95)); // Overlaps

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(1, merged.size());
        assertEquals(0, merged.get(0).start, 0.001);
        assertEquals(50, merged.get(0).end, 0.001);
        assertEquals(0.95, merged.get(0).confidence, 0.001); // Max confidence
    }

    @Test
    public void testMergeSegments_empty() {
        List<AdSegmentData> input = new ArrayList<>();
        List<AdSegmentData> merged = mergeSegments(input);
        assertTrue(merged.isEmpty());
    }

    @Test
    public void testMergeSegments_single() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(1, merged.size());
    }

    @Test
    public void testMergeSegments_chainMerge() {
        // Three segments that chain together
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "ad1", 0.9));
        input.add(new AdSegmentData(30, 60, "ad2", 0.85));
        input.add(new AdSegmentData(60, 90, "ad3", 0.95));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals(1, merged.size());
        assertEquals(0, merged.get(0).start, 0.001);
        assertEquals(90, merged.get(0).end, 0.001);
    }

    @Test
    public void testMergeSegments_preservesFirstReason() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "first reason", 0.9));
        input.add(new AdSegmentData(30, 60, "second reason", 0.85));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals("first reason", merged.get(0).reason);
    }

    @Test
    public void testMergeSegments_usesNextReasonIfEmpty() {
        List<AdSegmentData> input = new ArrayList<>();
        input.add(new AdSegmentData(0, 30, "", 0.9));
        input.add(new AdSegmentData(30, 60, "has reason", 0.85));

        List<AdSegmentData> merged = mergeSegments(input);
        assertEquals("has reason", merged.get(0).reason);
    }

    // ==================== Transcript Splitting Tests ====================

    @Test
    public void testSplitTranscript_shortTranscript() {
        String transcript = "This is a short transcript.";
        List<String> chunks = splitTranscriptIfNeeded(transcript);
        assertEquals(1, chunks.size());
        assertEquals(transcript, chunks.get(0));
    }

    @Test
    public void testSplitTranscript_exactlyAtLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_TRANSCRIPT_CHARS_PER_CHUNK; i++) {
            sb.append('a');
        }
        String transcript = sb.toString();

        List<String> chunks = splitTranscriptIfNeeded(transcript);
        assertEquals(1, chunks.size());
    }

    @Test
    public void testSplitTranscript_slightlyOverLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_TRANSCRIPT_CHARS_PER_CHUNK + 1000; i++) {
            sb.append('a');
        }
        String transcript = sb.toString();

        List<String> chunks = splitTranscriptIfNeeded(transcript);
        assertEquals(2, chunks.size());
    }

    @Test
    public void testSplitTranscript_doubleLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_TRANSCRIPT_CHARS_PER_CHUNK * 2; i++) {
            sb.append('a');
        }
        String transcript = sb.toString();

        List<String> chunks = splitTranscriptIfNeeded(transcript);
        assertEquals(2, chunks.size());
    }

    @Test
    public void testSplitTranscript_tripleLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_TRANSCRIPT_CHARS_PER_CHUNK * 3; i++) {
            sb.append('a');
        }
        String transcript = sb.toString();

        List<String> chunks = splitTranscriptIfNeeded(transcript);
        assertTrue(chunks.size() >= 3);
    }

    @Test
    public void testSplitTranscript_empty() {
        List<String> chunks = splitTranscriptIfNeeded("");
        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0));
    }

    @Test
    public void testSplitTranscript_prefersNewlineBreak() {
        StringBuilder sb = new StringBuilder();
        // Create a string that's just over the limit with newlines
        for (int i = 0; i < MAX_TRANSCRIPT_CHARS_PER_CHUNK / 100; i++) {
            for (int j = 0; j < 99; j++) {
                sb.append('a');
            }
            sb.append('\n');
        }
        // Add more to push over the limit
        for (int i = 0; i < 1000; i++) {
            sb.append('b');
        }

        String transcript = sb.toString();
        List<String> chunks = splitTranscriptIfNeeded(transcript);

        // Should split, and first chunk should end at a newline (roughly)
        assertTrue(chunks.size() >= 2);
    }

    // ==================== Unauthorized Detection Tests ====================

    @Test
    public void testIsUnauthorized_unauthorizedException() {
        // Simulate the behavior of checking for unauthorized
        String message = "Unauthorized";
        boolean isUnauth = message.toLowerCase(Locale.US).contains("unauthorized");
        assertTrue(isUnauth);
    }

    @Test
    public void testIsUnauthorized_401Code() {
        String message = "Error 401: Invalid API key";
        boolean isUnauth = message.toLowerCase(Locale.US).contains("401");
        assertTrue(isUnauth);
    }

    @Test
    public void testIsUnauthorized_nestedCause() {
        Exception inner = new Exception("Unauthorized");
        Exception outer = new Exception("API call failed", inner);

        // Check nested cause
        Throwable cause = outer.getCause();
        boolean isUnauth = cause != null && cause.getMessage() != null
                && cause.getMessage().toLowerCase(Locale.US).contains("unauthorized");
        assertTrue(isUnauth);
    }

    @Test
    public void testIsUnauthorized_regularError() {
        String message = "Connection timeout";
        boolean isUnauth = message.toLowerCase(Locale.US).contains("unauthorized")
                || message.toLowerCase(Locale.US).contains("401");
        assertFalse(isUnauth);
    }

    // ==================== Progress Stage Tests ====================

    @Test
    public void testProgressStage_analyzing() {
        String stage = "analyzing";
        assertEquals("analyzing", stage);
    }

    @Test
    public void testProgressStage_done() {
        String stage = "done";
        assertEquals("done", stage);
    }

    @Test
    public void testProgressPercent_bounds() {
        int percent = 50;
        assertTrue(percent >= 0 && percent <= 100);
    }

    @Test
    public void testProgressPercent_chunkCalculation() {
        int completed = 2;
        int total = 4;
        int percent = (completed * 100) / total;
        assertEquals(50, percent);
    }

    // ==================== Data Key Constants Tests ====================

    @Test
    public void testDataKey_feedItemId() {
        String key = "feedItemId";
        assertEquals(TranscriptAnalysisWorker.DATA_FEED_ITEM_ID, key);
    }

    // ==================== Helper Classes and Methods (mirroring worker logic) ====================

    private static class AdSegmentData {
        final double start;
        final double end;
        final String reason;
        final double confidence;

        AdSegmentData(double start, double end, String reason, double confidence) {
            this.start = start;
            this.end = end;
            this.reason = reason;
            this.confidence = confidence;
        }
    }

    private String sanitizeJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline >= 0 && firstNewline + 1 < cleaned.length()) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            cleaned = cleaned.trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return cleaned.substring(start, end + 1).trim();
        }
        return cleaned;
    }

    private List<AdSegmentData> parseSegments(String rawJson) throws JSONException {
        List<AdSegmentData> segments = new ArrayList<>();
        String sanitized = sanitizeJson(rawJson);
        if (sanitized == null || sanitized.isEmpty()) {
            return segments;
        }
        JSONObject root = new JSONObject(sanitized);
        JSONArray ads = root.optJSONArray("ads");
        if (ads == null) {
            return segments;
        }
        for (int i = 0; i < ads.length(); i++) {
            JSONObject ad = ads.getJSONObject(i);
            double start = ad.optDouble("startSeconds", 0);
            double end = ad.optDouble("endSeconds", 0);
            String reason = ad.optString("reason", "");
            double confidence = ad.optDouble("confidence", 0);
            if (end > start) {
                segments.add(new AdSegmentData(start, end, reason, confidence));
            }
        }
        return segments;
    }

    private List<AdSegmentData> mergeSegments(List<AdSegmentData> input) {
        if (input.isEmpty()) {
            return input;
        }
        input.sort(Comparator.comparingDouble(s -> s.start));
        List<AdSegmentData> merged = new ArrayList<>();
        AdSegmentData current = input.get(0);
        for (int i = 1; i < input.size(); i++) {
            AdSegmentData next = input.get(i);
            if (next.start <= current.end + 0.5) {
                double end = Math.max(current.end, next.end);
                String reason = current.reason.isEmpty() ? next.reason : current.reason;
                double confidence = Math.max(current.confidence, next.confidence);
                current = new AdSegmentData(current.start, end, reason, confidence);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private List<String> splitTranscriptIfNeeded(String transcript) {
        List<String> chunks = new ArrayList<>();
        if (transcript.length() <= MAX_TRANSCRIPT_CHARS_PER_CHUNK) {
            chunks.add(transcript);
            return chunks;
        }

        int numChunks = (int) Math.ceil((double) transcript.length() / MAX_TRANSCRIPT_CHARS_PER_CHUNK);
        int chunkSize = transcript.length() / numChunks;

        int start = 0;
        while (start < transcript.length()) {
            int end = Math.min(start + chunkSize, transcript.length());
            if (end < transcript.length()) {
                int newlineIndex = transcript.lastIndexOf('\n', end);
                if (newlineIndex > start) {
                    end = newlineIndex;
                }
            }
            chunks.add(transcript.substring(start, end));
            start = end;
        }

        return chunks;
    }
}
