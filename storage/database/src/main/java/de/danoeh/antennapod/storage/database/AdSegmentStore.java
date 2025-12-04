package de.danoeh.antennapod.storage.database;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;

/**
 * Lightweight file-based persistence for advertisement analysis results.
 */
public final class AdSegmentStore {
    private static final String TAG = "AdSegmentStore";
    private static final String DIRECTORY = "ad_segments";

    private AdSegmentStore() {
    }

    public static void save(@NonNull Context context, long feedItemId, @NonNull AdAnalysisResult result) {
        File dir = context.getDir(DIRECTORY, Context.MODE_PRIVATE);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create ad segment directory " + dir.getAbsolutePath());
            return;
        }
        File target = new File(dir, fileName(feedItemId));
        JSONObject json = new JSONObject();
        try {
            json.put("analyzedAtMillis", result.getAnalyzedAtMillis());
            json.put("model", result.getModel());
            json.put("error", result.getError());
            json.put("transcript", result.getTranscript());
            JSONArray ads = new JSONArray();
            for (AdSegment segment : result.getSegments()) {
                JSONObject obj = new JSONObject();
                obj.put("startSeconds", segment.getStartSeconds());
                obj.put("endSeconds", segment.getEndSeconds());
                obj.put("reason", segment.getReason());
                obj.put("confidence", segment.getConfidence());
                ads.put(obj);
            }
            json.put("ads", ads);
        } catch (JSONException e) {
            Log.e(TAG, "Could not serialize ad analysis ", e);
            return;
        }

        try (FileWriter writer = new FileWriter(target, false)) {
            writer.write(json.toString());
        } catch (IOException e) {
            Log.e(TAG, "Failed writing ad analysis ", e);
        }
    }

    @Nullable
    public static AdAnalysisResult load(@NonNull Context context, long feedItemId) {
        File target = new File(context.getDir(DIRECTORY, Context.MODE_PRIVATE), fileName(feedItemId));
        if (!target.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(target))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            JSONObject json = new JSONObject(builder.toString());
            JSONArray ads = json.optJSONArray("ads");
            List<AdSegment> segments = new ArrayList<>();
            if (ads != null) {
                for (int i = 0; i < ads.length(); i++) {
                    JSONObject obj = ads.getJSONObject(i);
                    segments.add(new AdSegment(
                            obj.optDouble("startSeconds"),
                            obj.optDouble("endSeconds"),
                            obj.optString("reason"),
                            obj.optDouble("confidence", 0)
                    ));
                }
            }
            long analyzedAt = json.optLong("analyzedAtMillis", 0);
            String model = json.optString("model", "");
            String error = json.optString("error", "");
            String transcript = json.optString("transcript", null);
            return new AdAnalysisResult(segments, analyzedAt, model, error, transcript);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed reading ad analysis", e);
            return null;
        }
    }

    public static boolean hasAnalysis(@NonNull Context context, long feedItemId) {
        File target = new File(context.getDir(DIRECTORY, Context.MODE_PRIVATE), fileName(feedItemId));
        return target.exists();
    }

    public static void clear(@NonNull Context context, long feedItemId) {
        File target = new File(context.getDir(DIRECTORY, Context.MODE_PRIVATE), fileName(feedItemId));
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Failed to delete ad analysis file " + target.getAbsolutePath());
        }
    }

    private static String fileName(long feedItemId) {
        return "ad_" + feedItemId + ".json";
    }
}
