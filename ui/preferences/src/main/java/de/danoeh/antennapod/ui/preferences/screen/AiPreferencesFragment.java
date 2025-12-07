package de.danoeh.antennapod.ui.preferences.screen;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.net.download.service.ad.local.LlmModelManager;
import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.ui.preferences.R;

@RequiresApi(api = Build.VERSION_CODES.O)
public class AiPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_OPENAI_API_KEY = "prefOpenAiApiKey";
    private static final String PREF_OPENAI_MODEL = "prefOpenAiModel";
    private static final String PREF_AD_ANALYSIS_TYPE = "prefAdAnalysisType";

    // Local Transcription
    private static final String PREF_LOCAL_TRANSCRIPTION_ENABLED = "prefLocalTranscriptionEnabled";
    private static final String PREF_LOCAL_TRANSCRIPTION_MODEL = "prefLocalTranscriptionModel";
    private static final String PREF_LOCAL_TRANSCRIPTION_DOWNLOAD = "prefLocalTranscriptionDownload";
    private static final String PREF_LOCAL_TRANSCRIPTION_DELETE = "prefLocalTranscriptionDelete";

    // Local LLM Analysis
    private static final String PREF_LOCAL_LLM_CATEGORY = "prefLocalLlmCategory";
    private static final String PREF_LOCAL_LLM_MODEL = "prefLocalLlmModel";
    private static final String PREF_LOCAL_LLM_DOWNLOAD = "prefLocalLlmDownload";
    private static final String PREF_LOCAL_LLM_DELETE = "prefLocalLlmDelete";

    private LocalTranscriptionManager transcriptionManager;
    private LlmModelManager llmModelManager;
    private ExecutorService downloadExecutor;
    private volatile boolean isDownloading = false;
    private volatile boolean isLlmDownloading = false;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences_ai);

        transcriptionManager = new LocalTranscriptionManager(requireContext());
        llmModelManager = new LlmModelManager(requireContext());
        downloadExecutor = Executors.newSingleThreadExecutor();

        setupApiKeyPreference();
        setupModelPreference();
        setupAnalysisTypePreference();
        setupLocalTranscriptionPreferences();
        setupLocalLlmPreferences();
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
        updateLocalLlmUI();
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

    private void setupAnalysisTypePreference() {
        ListPreference typePref = findPreference(PREF_AD_ANALYSIS_TYPE);
        if (typePref == null) {
            return;
        }
        typePref.setValue(OpenAiPreferences.getAdAnalysisType(requireContext()));
        typePref.setOnPreferenceChangeListener((preference, newValue) -> {
            OpenAiPreferences.setAdAnalysisType(requireContext(), (String) newValue);
            updateVisibility((String) newValue);
            return true;
        });
        updateVisibility(OpenAiPreferences.getAdAnalysisType(requireContext()));
    }

    private void updateVisibility(String analysisType) {
        boolean isLocal = OpenAiPreferences.ANALYSIS_TYPE_LOCAL.equals(analysisType);

        PreferenceCategory localCategory = findPreference(PREF_LOCAL_LLM_CATEGORY);
        if (localCategory != null) {
            localCategory.setVisible(isLocal);
        }

        Preference apiKeyPref = findPreference(PREF_OPENAI_API_KEY);
        if (apiKeyPref != null) {
            apiKeyPref.setVisible(!isLocal);
        }
        Preference modelPref = findPreference(PREF_OPENAI_MODEL);
        if (modelPref != null) {
            modelPref.setVisible(!isLocal);
        }
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

        // Check if device has enough memory for this model
        if (!transcriptionManager.hasEnoughMemory(modelName)) {
            long requiredMb = transcriptionManager.getMinMemoryRequired(modelName) / 1_000_000;
            long limitMb = transcriptionManager.getPerAppMemoryLimit() / 1_000_000;
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_local_transcription_memory_warning_title)
                    .setMessage(getString(R.string.pref_local_transcription_memory_warning,
                            requiredMb, limitMb))
                    .setPositiveButton(R.string.download_anyway_label, (dialog, which) -> {
                        performModelDownload(modelName);
                    })
                    .setNegativeButton(R.string.cancel_label, null)
                    .show();
            return;
        }

        performModelDownload(modelName);
    }

    private void performModelDownload(String modelName) {
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

    private void setupLocalLlmPreferences() {
        ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
        if (modelPref != null) {
            java.util.List<LlmModelManager.ModelInfo> models = LlmModelManager.getAvailableModels();
            CharSequence[] entries = new CharSequence[models.size()];
            CharSequence[] values = new CharSequence[models.size()];
            for (int i = 0; i < models.size(); i++) {
                entries[i] = models.get(i).name;
                values[i] = models.get(i).id;
            }
            modelPref.setEntries(entries);
            modelPref.setEntryValues(values);

            String current = OpenAiPreferences.getLocalLlmModelId(requireContext());
            modelPref.setValue(current);
            modelPref.setSummary(modelPref.getEntry());

            modelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String newVal = (String) newValue;
                OpenAiPreferences.setLocalLlmModelId(requireContext(), newVal);
                int index = modelPref.findIndexOfValue(newVal);
                if (index >= 0) {
                    modelPref.setSummary(modelPref.getEntries()[index]);
                }
                updateLocalLlmUI();
                return true;
            });
        }

        Preference downloadPref = findPreference(PREF_LOCAL_LLM_DOWNLOAD);
        if (downloadPref != null) {
            downloadPref.setOnPreferenceClickListener(preference -> {
                if (!isLlmDownloading) {
                    startLlmDownload();
                }
                return true;
            });
        }

        Preference deletePref = findPreference(PREF_LOCAL_LLM_DELETE);
        if (deletePref != null) {
            deletePref.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_local_llm_delete_title)
                        .setMessage(R.string.pref_local_llm_delete_confirm)
                        .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                            String modelId = OpenAiPreferences.getLocalLlmModelId(requireContext());
                            llmModelManager.deleteModel(modelId);
                            updateLocalLlmUI();
                            Toast.makeText(requireContext(), R.string.pref_local_transcription_model_deleted,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.cancel_label, null)
                        .show();
                return true;
            });
        }

        updateLocalLlmUI();
    }

    private void updateLocalLlmUI() {
        String modelId = OpenAiPreferences.getLocalLlmModelId(requireContext());
        boolean isDownloaded = llmModelManager.isModelDownloaded(modelId);

        Preference downloadPref = findPreference(PREF_LOCAL_LLM_DOWNLOAD);
        if (downloadPref != null) {
            if (isLlmDownloading) {
                downloadPref.setEnabled(false);
            } else if (isDownloaded) {
                downloadPref.setEnabled(false);
                downloadPref.setSummary(R.string.pref_local_llm_download_summary_downloaded);
            } else {
                downloadPref.setEnabled(true);
                // Maybe show size?
                LlmModelManager.ModelInfo info = LlmModelManager.getModelInfo(modelId);
                String sizeStr = info != null ? String.format(Locale.US, "%.1f GB", info.size / 1_000_000_000.0) : "";
                downloadPref.setSummary(getString(R.string.pref_local_llm_download_summary) + " (" + sizeStr + ")");
            }
        }

        Preference deletePref = findPreference(PREF_LOCAL_LLM_DELETE);
        if (deletePref != null) {
            deletePref.setVisible(isDownloaded && !isLlmDownloading);
        }
    }

    private void startLlmDownload() {
        String modelId = OpenAiPreferences.getLocalLlmModelId(requireContext());
        isLlmDownloading = true;
        updateLocalLlmUI();

        Preference downloadPref = findPreference(PREF_LOCAL_LLM_DOWNLOAD);

        downloadExecutor.execute(() -> {
            try {
                boolean success = llmModelManager.downloadModel(modelId, (percent, bytes, total) -> {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (downloadPref != null) {
                                downloadPref.setSummary(getString(R.string.pref_local_llm_downloading, percent));
                            }
                        });
                    }
                });

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isLlmDownloading = false;
                        if (success) {
                            Toast.makeText(requireContext(), R.string.pref_local_llm_download_complete,
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateLocalLlmUI();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isLlmDownloading = false;
                        Toast.makeText(requireContext(),
                                getString(R.string.pref_local_transcription_download_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                        updateLocalLlmUI();
                    });
                }
            }
        });
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
