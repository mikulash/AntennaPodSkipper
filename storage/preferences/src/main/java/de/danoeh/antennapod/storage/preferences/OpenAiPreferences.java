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

    private OpenAiPreferences() {
    }

    /**
     * Returns true if an OpenAI API key is required for ad analysis.
     * Returns false if local ad analysis (LiteRT) is enabled.
     */
    public static boolean isApiKeyRequired(Context context) {
        return !LocalAiPreferences.isLocalAdAnalysisEnabled(context);
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
