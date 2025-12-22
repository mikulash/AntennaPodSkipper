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
    private static final String DEFAULT_LOCAL_LLM_MODEL = "gemma3-1b";

    private static final String PREF_MANUAL_MODEL_PATH = "prefManualModelPath";
    private static final String PREF_MANUAL_MODEL_BACKEND = "prefManualModelBackend";
    private static final String PREF_MANUAL_MODEL_MAX_TOKENS = "prefManualModelMaxTokens";
    private static final String PREF_IMPORTED_MODELS = "prefImportedModels";
    private static final String DEFAULT_MANUAL_MODEL_BACKEND = "GPU";
    private static final int DEFAULT_MANUAL_MODEL_MAX_TOKENS = 512;
    public static final String MANUAL_MODEL_ID = "manual_import";
    public static final String IMPORTED_MODEL_PREFIX = "imported:";
    private static final String IMPORTED_MODELS_SEPARATOR = "|||";

    private LocalAiPreferences() {
    }

    /**
     * Gets the set of imported model filenames.
     * @return Set of imported model filenames (without the prefix)
     */
    public static java.util.Set<String> getImportedModels(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (prefs == null) {
            return result;
        }
        String stored = prefs.getString(PREF_IMPORTED_MODELS, "");
        if (!stored.isEmpty()) {
            String[] models = stored.split(java.util.regex.Pattern.quote(IMPORTED_MODELS_SEPARATOR));
            for (String model : models) {
                if (!model.trim().isEmpty()) {
                    result.add(model.trim());
                }
            }
        }
        return result;
    }

    /**
     * Adds an imported model to the list.
     * @param filename The model filename (without path)
     */
    public static void addImportedModel(Context context, String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return;
        }
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        java.util.Set<String> models = getImportedModels(context);
        models.add(filename.trim());
        saveImportedModels(prefs, models);
    }

    /**
     * Removes an imported model from the list.
     * @param filename The model filename to remove
     */
    public static void removeImportedModel(Context context, String filename) {
        if (filename == null) {
            return;
        }
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        java.util.Set<String> models = getImportedModels(context);
        models.remove(filename.trim());
        saveImportedModels(prefs, models);
    }

    /**
     * Clears all imported models from the list.
     */
    public static void clearImportedModels(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null) {
            return;
        }
        prefs.edit().remove(PREF_IMPORTED_MODELS).apply();
    }

    private static void saveImportedModels(SharedPreferences prefs, java.util.Set<String> models) {
        if (models.isEmpty()) {
            prefs.edit().remove(PREF_IMPORTED_MODELS).apply();
        } else {
            StringBuilder sb = new StringBuilder();
            for (String model : models) {
                if (sb.length() > 0) {
                    sb.append(IMPORTED_MODELS_SEPARATOR);
                }
                sb.append(model);
            }
            prefs.edit().putString(PREF_IMPORTED_MODELS, sb.toString()).apply();
        }
    }

    public static void setManualModelPath(Context context, String path) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null)
            return;
        if (path == null) {
            prefs.edit().remove(PREF_MANUAL_MODEL_PATH).apply();
        } else {
            prefs.edit().putString(PREF_MANUAL_MODEL_PATH, path).apply();
        }
    }

    @Nullable
    public static String getManualModelPath(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null)
            return null;
        return prefs.getString(PREF_MANUAL_MODEL_PATH, null);
    }

    public static void setManualModelBackend(Context context, @Nullable String backend) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null)
            return;
        if (backend == null || backend.trim().isEmpty()) {
            prefs.edit().putString(PREF_MANUAL_MODEL_BACKEND, DEFAULT_MANUAL_MODEL_BACKEND).apply();
        } else {
            prefs.edit().putString(PREF_MANUAL_MODEL_BACKEND, backend.trim().toUpperCase()).apply();
        }
    }

    public static String getManualModelBackend(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null)
            return DEFAULT_MANUAL_MODEL_BACKEND;
        return prefs.getString(PREF_MANUAL_MODEL_BACKEND, DEFAULT_MANUAL_MODEL_BACKEND);
    }

    public static void setManualModelMaxTokens(Context context, int maxTokens) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null)
            return;
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MANUAL_MODEL_MAX_TOKENS;
        }
        prefs.edit().putInt(PREF_MANUAL_MODEL_MAX_TOKENS, maxTokens).apply();
    }

    public static int getManualModelMaxTokens(Context context) {
        SharedPreferences prefs = getEncryptedPrefs(context);
        if (prefs == null)
            return DEFAULT_MANUAL_MODEL_MAX_TOKENS;
        return prefs.getInt(PREF_MANUAL_MODEL_MAX_TOKENS, DEFAULT_MANUAL_MODEL_MAX_TOKENS);
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
