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
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Unable to open encrypted preferences", e);
            return null;
        }
    }
}
