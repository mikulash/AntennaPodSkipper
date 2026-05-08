package de.danoeh.antennapod.net.ai.service.ad;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.model.ad.AdSegment;

/**
 * Converts model JSON responses into validated ad segments.
 */
public final class AdSegmentJsonParser {
    private static final String TAG = "AdSegmentJsonParser";

    private AdSegmentJsonParser() {
    }

    public static List<AdSegment> parse(String rawJson) {
        List<AdSegment> segments = new ArrayList<>();
        String sanitized = sanitize(rawJson);
        if (TextUtils.isEmpty(sanitized)) {
            return segments;
        }
        try {
            JSONObject root = new JSONObject(sanitized);
            JSONArray ads = root.optJSONArray("ads");
            if (ads == null) {
                return segments;
            }
            for (int i = 0; i < ads.length(); i++) {
                JSONObject ad = ads.optJSONObject(i);
                if (ad == null) {
                    continue;
                }
                double start = ad.optDouble("startSeconds", 0);
                double end = ad.optDouble("endSeconds", 0);
                String reason = ad.optString("reason", "");
                double confidence = ad.optDouble("confidence", 0);
                if (end > start) {
                    segments.add(new AdSegment(start, end, reason, confidence));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring invalid ad analysis JSON", e);
        }
        return segments;
    }

    public static String sanitize(String raw) {
        if (TextUtils.isEmpty(raw)) {
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
}
