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
 * Local AI (on-device) preference storage.
 * Keeps LiteRT/OpenWhisper settings separate from cloud OpenAI configuration.
 */
public final class LocalAiPreferences {
    private static final String TAG = "LocalAiPreferences";
    private static final String PREF_NAME = "openai_secure";

    // Local transcription preferences
    private static final String PREF_USE_LOCAL_TRANSCRIPTION = "pref_use_local_transcription";
    private static final String PREF_LOCAL_TRANSCRIPTION_MODEL = "pref_local_transcription_model";
    private static final String DEFAULT_LOCAL_MODEL = "small";

    // Local Ad Analysis (LiteRT) settings
    private static final String PREF_USE_LOCAL_AD_ANALYSIS = "prefLocalAdAnalysisEnabled";
    private static final String PREF_LOCAL_AD_ANALYSIS_MODEL = "prefLocalAdAnalysisModel";
    private static final String DEFAULT_LOCAL_LLM_MODEL = "gemma3-1b-cpu";

    private static final String PREF_MANUAL_MODEL_PATH = "prefManualModelPath";
    public static final String MANUAL_MODEL_ID = "manual_import";

    private LocalAiPreferences() {
    }

    public static void setManualModelPath(Context context, String path) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) return;
        if (path == null) {
            prefs.edit().remove(PREF_MANUAL_MODEL_PATH).apply();
        } else {
            prefs.edit().putString(PREF_MANUAL_MODEL_PATH, path).apply();
        }
    }

    @Nullable
    public static String getManualModelPath(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) return null;
        return prefs.getString(PREF_MANUAL_MODEL_PATH, null);
    }

    public static boolean isLocalAdAnalysisEnabled(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        return prefs != null && prefs.getBoolean(PREF_USE_LOCAL_AD_ANALYSIS, false);
    }

    public static String getLocalAdAnalysisModel(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return DEFAULT_LOCAL_LLM_MODEL;
        }
        return prefs.getString(PREF_LOCAL_AD_ANALYSIS_MODEL, DEFAULT_LOCAL_LLM_MODEL);
    }

    public static void setLocalAdAnalysisEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        prefs.edit().putBoolean(PREF_USE_LOCAL_AD_ANALYSIS, enabled).apply();
    }

    public static void setLocalAdAnalysisModel(Context context, @Nullable String model) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        if (model == null || model.trim().isEmpty()) {
            prefs.edit().putString(PREF_LOCAL_AD_ANALYSIS_MODEL, DEFAULT_LOCAL_LLM_MODEL).apply();
        } else {
            prefs.edit().putString(PREF_LOCAL_AD_ANALYSIS_MODEL, model.trim()).apply();
        }
    }

    public static boolean isLocalTranscriptionEnabled(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        return prefs != null && prefs.getBoolean(PREF_USE_LOCAL_TRANSCRIPTION, true);
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
        return prefs.getString(PREF_LOCAL_TRANSCRIPTION_MODEL, DEFAULT_LOCAL_MODEL);
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
