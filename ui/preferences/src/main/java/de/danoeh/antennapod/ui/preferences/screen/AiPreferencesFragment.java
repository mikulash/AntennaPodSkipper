package de.danoeh.antennapod.ui.preferences.screen;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.ui.preferences.R;

public class AiPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_OPENAI_API_KEY = "prefOpenAiApiKey";
    private static final String PREF_OPENAI_MODEL = "prefOpenAiModel";
    private static final String PREF_LOCAL_TRANSCRIPTION_ENABLED = "prefLocalTranscriptionEnabled";
    private static final String PREF_LOCAL_TRANSCRIPTION_MODEL = "prefLocalTranscriptionModel";
    private static final String PREF_LOCAL_TRANSCRIPTION_DOWNLOAD = "prefLocalTranscriptionDownload";
    private static final String PREF_LOCAL_TRANSCRIPTION_DELETE = "prefLocalTranscriptionDelete";

    private LocalTranscriptionManager transcriptionManager;
    private ExecutorService downloadExecutor;
    private volatile boolean isDownloading = false;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            requireActivity().finish();
            return;
        }
        addPreferencesFromResource(R.xml.preferences_ai);

        transcriptionManager = new LocalTranscriptionManager(requireContext());
        downloadExecutor = Executors.newSingleThreadExecutor();

        setupApiKeyPreference();
        setupModelPreference();
        setupLocalTranscriptionPreferences();
        setupStatistics();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatistics();
        updateLocalTranscriptionUI();
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_ai_label);
    }

    private void setupApiKeyPreference() {
        EditTextPreference apiKeyPref = findPreference(PREF_OPENAI_API_KEY);
        if (apiKeyPref == null) {
            return;
        }
        apiKeyPref.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editText.setText(OpenAiPreferences.getApiKey(requireContext()));
        });
        apiKeyPref.setOnPreferenceChangeListener((preference, newValue) -> {
            OpenAiPreferences.setApiKey(requireContext(), (String) newValue);
            updateApiKeySummary(apiKeyPref);
            apiKeyPref.setText("");
            return false; // Avoid storing in default shared preferences
        });
        updateApiKeySummary(apiKeyPref);
    }

    private void setupModelPreference() {
        ListPreference modelPref = findPreference(PREF_OPENAI_MODEL);
        if (modelPref == null) {
            return;
        }
        modelPref.setValue(OpenAiPreferences.getModel(requireContext()));
        modelPref.setOnPreferenceChangeListener((preference, newValue) -> {
            OpenAiPreferences.setModel(requireContext(), (String) newValue);
            modelPref.setValue((String) newValue);
            return false;
        });
    }

    private void setupLocalTranscriptionPreferences() {
        // Enable/disable toggle
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
        if (enabledPref != null) {
            enabledPref.setChecked(OpenAiPreferences.isLocalTranscriptionEnabled(requireContext()));
            enabledPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                String model = OpenAiPreferences.getLocalTranscriptionModel(requireContext());

                // Check if model is downloaded before enabling
                if (enabled && !transcriptionManager.isModelDownloaded(model)) {
                    Toast.makeText(requireContext(),
                            R.string.pref_local_transcription_download_summary,
                            Toast.LENGTH_LONG).show();
                    return false;
                }

                OpenAiPreferences.setLocalTranscriptionEnabled(requireContext(), enabled);
                return true;
            });
        }

        // Model selection
        ListPreference modelPref = findPreference(PREF_LOCAL_TRANSCRIPTION_MODEL);
        if (modelPref != null) {
            modelPref.setValue(OpenAiPreferences.getLocalTranscriptionModel(requireContext()));
            modelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String newModel = (String) newValue;
                OpenAiPreferences.setLocalTranscriptionModel(requireContext(), newModel);

                // If local transcription is enabled but new model isn't downloaded, disable it
                if (OpenAiPreferences.isLocalTranscriptionEnabled(requireContext())
                        && !transcriptionManager.isModelDownloaded(newModel)) {
                    OpenAiPreferences.setLocalTranscriptionEnabled(requireContext(), false);
                    SwitchPreferenceCompat switchPref = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
                    if (switchPref != null) {
                        switchPref.setChecked(false);
                    }
                }

                updateLocalTranscriptionUI();
                return true;
            });
        }

        // Download button
        Preference downloadPref = findPreference(PREF_LOCAL_TRANSCRIPTION_DOWNLOAD);
        if (downloadPref != null) {
            downloadPref.setOnPreferenceClickListener(preference -> {
                if (!isDownloading) {
                    startModelDownload();
                }
                return true;
            });
        }

        // Delete button
        Preference deletePref = findPreference(PREF_LOCAL_TRANSCRIPTION_DELETE);
        if (deletePref != null) {
            deletePref.setOnPreferenceClickListener(preference -> {
                showDeleteConfirmation();
                return true;
            });
        }

        updateLocalTranscriptionUI();
    }

    private void updateLocalTranscriptionUI() {
        String selectedModel = OpenAiPreferences.getLocalTranscriptionModel(requireContext());
        boolean isDownloaded = transcriptionManager.isModelDownloaded(selectedModel);

        // Update download button
        Preference downloadPref = findPreference(PREF_LOCAL_TRANSCRIPTION_DOWNLOAD);
        if (downloadPref != null) {
            if (isDownloading) {
                downloadPref.setEnabled(false);
            } else if (isDownloaded) {
                downloadPref.setSummary(R.string.pref_local_transcription_download_summary_downloaded);
                downloadPref.setEnabled(false);
            } else {
                String sizeStr = transcriptionManager.getModelSizeString(selectedModel);
                downloadPref.setSummary(getString(R.string.pref_local_transcription_download_summary)
                        + " (" + sizeStr + ")");
                downloadPref.setEnabled(true);
            }
        }

        // Update delete button
        Preference deletePref = findPreference(PREF_LOCAL_TRANSCRIPTION_DELETE);
        if (deletePref != null) {
            deletePref.setEnabled(isDownloaded && !isDownloading);
            deletePref.setVisible(isDownloaded);
        }

        // Update enable switch
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
        if (enabledPref != null) {
            enabledPref.setEnabled(isDownloaded && !isDownloading);
        }
    }

    private void startModelDownload() {
        String modelName = OpenAiPreferences.getLocalTranscriptionModel(requireContext());
        isDownloading = true;
        updateLocalTranscriptionUI();

        Preference downloadPref = findPreference(PREF_LOCAL_TRANSCRIPTION_DOWNLOAD);

        downloadExecutor.execute(() -> {
            try {
                boolean success = transcriptionManager.downloadModel(modelName,
                        (percent, bytesDownloaded, totalBytes) -> {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (downloadPref != null) {
                                        if (percent < 0) {
                                            downloadPref.setSummary(R.string.pref_local_transcription_extracting);
                                        } else {
                                            downloadPref.setSummary(getString(
                                                    R.string.pref_local_transcription_downloading, percent));
                                        }
                                    }
                                });
                            }
                        });

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isDownloading = false;
                        if (success) {
                            Toast.makeText(requireContext(),
                                    R.string.pref_local_transcription_download_complete,
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateLocalTranscriptionUI();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isDownloading = false;
                        Toast.makeText(requireContext(),
                                getString(R.string.pref_local_transcription_download_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                        updateLocalTranscriptionUI();
                    });
                }
            }
        });
    }

    private void showDeleteConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_local_transcription_delete_title)
                .setMessage(R.string.pref_local_transcription_delete_confirm)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    deleteModel();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void deleteModel() {
        String modelName = OpenAiPreferences.getLocalTranscriptionModel(requireContext());

        // Disable local transcription if it was enabled
        if (OpenAiPreferences.isLocalTranscriptionEnabled(requireContext())) {
            OpenAiPreferences.setLocalTranscriptionEnabled(requireContext(), false);
            SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
            if (enabledPref != null) {
                enabledPref.setChecked(false);
            }
        }

        transcriptionManager.deleteModel(modelName);

        Toast.makeText(requireContext(),
                R.string.pref_local_transcription_model_deleted,
                Toast.LENGTH_SHORT).show();

        updateLocalTranscriptionUI();
    }

    private void updateApiKeySummary(EditTextPreference apiKeyPref) {
        String key = OpenAiPreferences.getApiKey(requireContext());
        if (TextUtils.isEmpty(key)) {
            apiKeyPref.setSummary(R.string.pref_openai_api_key_summary);
        } else {
            apiKeyPref.setSummary(R.string.pref_openai_api_key_set);
        }
    }

    private void setupStatistics() {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(R.string.pref_ai_stats_category);
        getPreferenceScreen().addPreference(category);

        Preference audioPref = new Preference(requireContext());
        audioPref.setKey("pref_ai_audio_transcribed");
        audioPref.setTitle(R.string.pref_ai_audio_transcribed);
        audioPref.setSummary("00:00:00");
        category.addPreference(audioPref);

        Preference tokensPref = new Preference(requireContext());
        tokensPref.setKey("pref_ai_analysis_tokens");
        tokensPref.setTitle(R.string.pref_ai_analysis_tokens);
        tokensPref.setSummary("0");
        category.addPreference(tokensPref);

        Preference costPref = new Preference(requireContext());
        costPref.setKey("pref_ai_total_cost");
        costPref.setTitle(R.string.pref_ai_total_cost);
        costPref.setSummary("$0.00");
        category.addPreference(costPref);
    }

    private void updateStatistics() {
        Preference audioPref = findPreference("pref_ai_audio_transcribed");
        if (audioPref != null) {
            long durationMs = OpenAiPreferences.getTotalAudioDuration(requireContext());
            long seconds = durationMs / 1000;
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            audioPref.setSummary(String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s));
        }

        Preference tokensPref = findPreference("pref_ai_analysis_tokens");
        if (tokensPref != null) {
            long tokens = OpenAiPreferences.getTotalAnalysisTokens(requireContext());
            tokensPref.setSummary(String.format(Locale.getDefault(), "%d", tokens));
        }

        Preference costPref = findPreference("pref_ai_total_cost");
        if (costPref != null) {
            long costMicros = OpenAiPreferences.getTotalCostMicros(requireContext());
            double cost = costMicros / 1_000_000.0;
            costPref.setSummary(String.format(Locale.getDefault(), "$%.4f", cost));
        }
    }
}
