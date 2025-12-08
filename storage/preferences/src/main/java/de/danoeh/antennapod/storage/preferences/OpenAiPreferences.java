package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Secure storage for the user-supplied OpenAI API key.
 */
public final class OpenAiPreferences {
    private static final String TAG = "OpenAiPreferences";
    private static final String PREF_NAME = "openai_secure";
    private static final String PREF_API_KEY = "pref_openai_api_key";
    private static final String PREF_MODEL = "pref_openai_model";
    private static final String DEFAULT_MODEL = "gpt-5-nano";

    private static final String PREF_TOTAL_AUDIO_DURATION = "pref_openai_total_audio_duration";
    private static final String PREF_TOTAL_ANALYSIS_TOKENS = "pref_openai_total_analysis_tokens";
    private static final String PREF_TOTAL_COST = "pref_openai_total_cost_micros";

    // Local transcription preferences
    private static final String PREF_USE_LOCAL_TRANSCRIPTION = "pref_use_local_transcription";
    private static final String PREF_LOCAL_TRANSCRIPTION_MODEL = "pref_local_transcription_model";
    private static final String DEFAULT_LOCAL_MODEL = "en-small";

    // Local Analysis (LLM) preferences
    private static final String PREF_AD_ANALYSIS_TYPE = "pref_ad_analysis_type"; // "cloud" or "local"
    public static final String ANALYSIS_TYPE_CLOUD = "cloud";
    public static final String ANALYSIS_TYPE_LOCAL = "local";
    private static final String PREF_LOCAL_LLM_MODEL_ID = "pref_local_llm_model_id";
    private static final String DEFAULT_LOCAL_LLM_MODEL_ID = "qwen3-0.6b-instruct";

    private OpenAiPreferences() {
    }

    @Nullable
    public static String getApiKey(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return null;
        }
        return prefs.getString(PREF_API_KEY, null);
    }

    public static void setApiKey(Context context, @Nullable String apiKey) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            prefs.edit().remove(PREF_API_KEY).apply();
        } else {
            prefs.edit().putString(PREF_API_KEY, apiKey.trim()).apply();
        }
    }

    public static String getModel(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return DEFAULT_MODEL;
        }
        return prefs.getString(PREF_MODEL, DEFAULT_MODEL);
    }

    public static void setModel(Context context, @Nullable String model) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        if (model == null || model.trim().isEmpty()) {
            prefs.edit().putString(PREF_MODEL, DEFAULT_MODEL).apply();
        } else {
            prefs.edit().putString(PREF_MODEL, model.trim()).apply();
        }
    }

    public static long getTotalAudioDuration(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        return prefs == null ? 0 : prefs.getLong(PREF_TOTAL_AUDIO_DURATION, 0);
    }

    public static void addAudioDuration(Context context, long durationMs) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        long current = prefs.getLong(PREF_TOTAL_AUDIO_DURATION, 0);
        prefs.edit().putLong(PREF_TOTAL_AUDIO_DURATION, current + durationMs).apply();
    }

    public static long getTotalAnalysisTokens(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        return prefs == null ? 0 : prefs.getLong(PREF_TOTAL_ANALYSIS_TOKENS, 0);
    }

    public static void addAnalysisTokens(Context context, long tokens) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        long current = prefs.getLong(PREF_TOTAL_ANALYSIS_TOKENS, 0);
        prefs.edit().putLong(PREF_TOTAL_ANALYSIS_TOKENS, current + tokens).apply();
    }

    public static long getTotalCostMicros(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        return prefs == null ? 0 : prefs.getLong(PREF_TOTAL_COST, 0);
    }

    public static void addCost(Context context, double costDollars) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        long currentMicros = prefs.getLong(PREF_TOTAL_COST, 0);
        long addMicros = (long) (costDollars * 1_000_000.0);
        prefs.edit().putLong(PREF_TOTAL_COST, currentMicros + addMicros).apply();
    }

    // Local transcription settings

    public static boolean isLocalTranscriptionEnabled(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        return prefs != null && prefs.getBoolean(PREF_USE_LOCAL_TRANSCRIPTION, false);
    }

    public static void setLocalTranscriptionEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        prefs.edit().putBoolean(PREF_USE_LOCAL_TRANSCRIPTION, enabled).apply();
    }

    public static String getLocalTranscriptionModel(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return DEFAULT_LOCAL_MODEL;
        }
        String stored = prefs.getString(PREF_LOCAL_TRANSCRIPTION_MODEL, DEFAULT_LOCAL_MODEL);
        // Migrate legacy values without language prefix
        if ("small".equals(stored)) {
            stored = "en-small";
        } else if ("medium".equals(stored)) {
            stored = "en-medium";
        } else if ("large".equals(stored)) {
            stored = "en-large";
        }
        if (!DEFAULT_LOCAL_MODEL.equals(stored)) {
            // Persist migrated value
            prefs.edit().putString(PREF_LOCAL_TRANSCRIPTION_MODEL, stored).apply();
        }
        return stored;
    }

    public static void setLocalTranscriptionModel(Context context, @Nullable String model) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        if (model == null || model.trim().isEmpty()) {
            prefs.edit().putString(PREF_LOCAL_TRANSCRIPTION_MODEL, DEFAULT_LOCAL_MODEL).apply();
        } else {
            prefs.edit().putString(PREF_LOCAL_TRANSCRIPTION_MODEL, model.trim()).apply();
        }
    }

    // Local Analysis (LLM) settings

    public static String getAdAnalysisType(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return ANALYSIS_TYPE_CLOUD;
        }
        return prefs.getString(PREF_AD_ANALYSIS_TYPE, ANALYSIS_TYPE_CLOUD);
    }

    public static void setAdAnalysisType(Context context, String type) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        prefs.edit().putString(PREF_AD_ANALYSIS_TYPE, type).apply();
    }

    public static String getLocalLlmModelId(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            // Default to TinyLlama if not set
            return DEFAULT_LOCAL_LLM_MODEL_ID;
        }
        return prefs.getString(PREF_LOCAL_LLM_MODEL_ID, DEFAULT_LOCAL_LLM_MODEL_ID);
    }

    public static void setLocalLlmModelId(Context context, String modelId) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        prefs.edit().putString(PREF_LOCAL_LLM_MODEL_ID, modelId).apply();
    }

    /**
     * Returns true if an OpenAI API key is required for ad analysis.
     * API key is NOT required when both local transcription AND local analysis are enabled.
     */
    public static boolean isApiKeyRequired(Context context) {
        boolean localTranscription = isLocalTranscriptionEnabled(context);
        boolean localAnalysis = ANALYSIS_TYPE_LOCAL.equals(getAdAnalysisType(context));
        return !localTranscription || !localAnalysis;
    }

    /**
     * Returns true if running in fully local mode (both transcription and analysis are local).
     */
    public static boolean isFullyLocalMode(Context context) {
        return isLocalTranscriptionEnabled(context)
                && ANALYSIS_TYPE_LOCAL.equals(getAdAnalysisType(context));
    }

    @Nullable
    private static SharedPreferences getEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Unable to open encrypted preferences", e);
            return null;
        }
    }
}
