package de.danoeh.antennapod.ui.preferences.screen;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;
import android.provider.OpenableColumns;

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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.InputStream;
import android.net.Uri;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
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
    private static final String PREF_LOCAL_LLM_CATEGORY = "prefLocalAdAnalysisCategory";
    private static final String PREF_LOCAL_LLM_ENABLED = "prefLocalAdAnalysisEnabled";
    private static final String PREF_LOCAL_LLM_MODEL = "prefLocalAdAnalysisModel";
    private static final String PREF_LOCAL_LLM_DOWNLOAD = "prefLocalAdAnalysisDownload";
    private static final String PREF_LOCAL_LLM_IMPORT = "prefLocalAdAnalysisImport";
    private static final String PREF_LOCAL_LLM_DELETE = "prefLocalAdAnalysisDelete";

    // Delete all models
    private static final String PREF_DELETE_ALL_MODELS = "prefDeleteAllModels";

    private LocalTranscriptionManager transcriptionManager;
    private de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager llmManager;
    private ExecutorService downloadExecutor;
    private volatile boolean isDownloading = false;
    private volatile boolean isLlmDownloading = false;
    private ActivityResultLauncher<String> importLauncher;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences_ai);

        transcriptionManager = new LocalTranscriptionManager(requireContext());
        llmManager = new de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager(requireContext());

        downloadExecutor = Executors.newSingleThreadExecutor();

        importLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                importManualModel(uri);
            }
        });

        setupApiKeyPreference();
        setupModelPreference();
        setupAnalysisTypePreference();
        setupLocalTranscriptionPreferences();
        setupLocalLlmPreferences();
        setupDeleteAllModels();
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
        updateDeleteAllModelsSummary();
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
        // Removed as ad analysis is now always cloud-based
    }

    private void updateVisibility(String analysisType) {
        // Always show API key and Model preferences as they are required for cloud
        // analysis
        Preference apiKeyPref = findPreference(PREF_OPENAI_API_KEY);
        if (apiKeyPref != null) {
            apiKeyPref.setVisible(true);
        }
        Preference modelPref = findPreference(PREF_OPENAI_MODEL);
        if (modelPref != null) {
            modelPref.setVisible(true);
        }
    }

    private void setupLocalTranscriptionPreferences() {
        // Enable/disable toggle
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
        if (enabledPref != null) {
            enabledPref.setChecked(LocalAiPreferences.isLocalTranscriptionEnabled(requireContext()));
            enabledPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                String model = LocalAiPreferences.getLocalTranscriptionModel(requireContext());

                // Check if model is downloaded before enabling
                if (enabled && !transcriptionManager.isModelDownloaded(model)) {
                    Toast.makeText(requireContext(),
                            R.string.pref_local_transcription_download_summary,
                            Toast.LENGTH_LONG).show();
                    return false;
                }

                LocalAiPreferences.setLocalTranscriptionEnabled(requireContext(), enabled);
                return true;
            });
        }

        // Model selection
        ListPreference modelPref = findPreference(PREF_LOCAL_TRANSCRIPTION_MODEL);
        if (modelPref != null) {
            modelPref.setValue(LocalAiPreferences.getLocalTranscriptionModel(requireContext()));
            modelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String newModel = (String) newValue;
                LocalAiPreferences.setLocalTranscriptionModel(requireContext(), newModel);

                // If local transcription is enabled but new model isn't downloaded, disable it
                if (LocalAiPreferences.isLocalTranscriptionEnabled(requireContext())
                        && !transcriptionManager.isModelDownloaded(newModel)) {
                    LocalAiPreferences.setLocalTranscriptionEnabled(requireContext(), false);
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
        String selectedModel = LocalAiPreferences.getLocalTranscriptionModel(requireContext());
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
        String modelName = LocalAiPreferences.getLocalTranscriptionModel(requireContext());

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
                        updateDeleteAllModelsSummary();
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
        String modelName = LocalAiPreferences.getLocalTranscriptionModel(requireContext());

        // Disable local transcription if it was enabled
        if (LocalAiPreferences.isLocalTranscriptionEnabled(requireContext())) {
            LocalAiPreferences.setLocalTranscriptionEnabled(requireContext(), false);
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
        updateDeleteAllModelsSummary();
    }

    private void setupLocalLlmPreferences() {
        // Enable/disable toggle
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_LLM_ENABLED);
        if (enabledPref != null) {
            enabledPref.setChecked(LocalAiPreferences.isLocalAdAnalysisEnabled(requireContext()));
            enabledPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                String model = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());

                // Check if model is downloaded before enabling
                if (enabled && !llmManager.isModelDownloaded(model)) {
                    Toast.makeText(requireContext(),
                            R.string.pref_local_ad_analysis_download_summary,
                            Toast.LENGTH_LONG).show();
                    return false;
                }

                LocalAiPreferences.setLocalAdAnalysisEnabled(requireContext(), enabled);
                return true;
            });
        }

        // Model selection
        ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
        if (modelPref != null) {
            modelPref.setValue(LocalAiPreferences.getLocalAdAnalysisModel(requireContext()));
            updateManualModelEntry(modelPref);
            modelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String newModel = (String) newValue;
                LocalAiPreferences.setLocalAdAnalysisModel(requireContext(), newModel);

                // If enabled but new model isn't downloaded, disable it
                if (LocalAiPreferences.isLocalAdAnalysisEnabled(requireContext())
                        && !llmManager.isModelDownloaded(newModel)) {
                    LocalAiPreferences.setLocalAdAnalysisEnabled(requireContext(), false);
                    SwitchPreferenceCompat switchPref = findPreference(PREF_LOCAL_LLM_ENABLED);
                    if (switchPref != null) {
                        switchPref.setChecked(false);
                    }
                }

                updateLocalLlmUI();
                return true;
            });
        }

        // Download button
        Preference downloadPref = findPreference(PREF_LOCAL_LLM_DOWNLOAD);
        if (downloadPref != null) {
            downloadPref.setOnPreferenceClickListener(preference -> {
                if (!isLlmDownloading) {
                    startLlmDownload();
                }
                return true;
            });
        }

        // Import button
        Preference importPref = findPreference(PREF_LOCAL_LLM_IMPORT);
        if (importPref != null) {
            importPref.setOnPreferenceClickListener(preference -> {
                importLauncher.launch("*/*");
                return true;
            });
        }

        // Delete button
        Preference deletePref = findPreference(PREF_LOCAL_LLM_DELETE);
        if (deletePref != null) {
            deletePref.setOnPreferenceClickListener(preference -> {
                showLlmDeleteConfirmation();
                return true;
            });
        }

        updateLocalLlmUI();
    }

    private void updateLocalLlmUI() {
        String selectedModel = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());
        boolean isDownloaded = llmManager.isModelDownloaded(selectedModel);
        ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
        if (modelPref != null) {
            updateManualModelEntry(modelPref);
        }

        // Update download button
        Preference downloadPref = findPreference(PREF_LOCAL_LLM_DOWNLOAD);
        if (downloadPref != null) {
            if (isLlmDownloading) {
                downloadPref.setEnabled(false);
                downloadPref.setSummary(R.string.pref_local_transcription_downloading); // Reuse string or generic
                                                                                        // "Downloading..."
            } else if (isDownloaded) {
                downloadPref.setSummary(R.string.pref_local_transcription_download_summary_downloaded); // Reuse
                                                                                                        // "Downloaded"
                                                                                                        // string
                downloadPref.setEnabled(false);
            } else {
                downloadPref.setSummary(R.string.pref_local_ad_analysis_download_summary);
                downloadPref.setEnabled(true);
            }
        }

        // Update delete button
        Preference deletePref = findPreference(PREF_LOCAL_LLM_DELETE);
        if (deletePref != null) {
            deletePref.setEnabled(isDownloaded && !isLlmDownloading);
            deletePref.setVisible(isDownloaded);
        }

        // Update enable switch
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_LLM_ENABLED);
        if (enabledPref != null) {
            enabledPref.setEnabled(isDownloaded && !isLlmDownloading);
        }
    }

    private void startLlmDownload() {
        String modelName = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());
        performLlmDownload(modelName);
    }

    private void performLlmDownload(String modelName) {
        isLlmDownloading = true;
        updateLocalLlmUI();

        Preference downloadPref = findPreference(PREF_LOCAL_LLM_DOWNLOAD);

        downloadExecutor.execute(() -> {
            try {
                boolean success = llmManager.downloadModel(modelName,
                        (percent, bytesDownloaded, totalBytes) -> {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (downloadPref != null && isLlmDownloading) {
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
                        isLlmDownloading = false;
                        if (success) {
                            Toast.makeText(requireContext(),
                                    R.string.pref_local_transcription_download_complete,
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateLocalLlmUI();
                        updateDeleteAllModelsSummary();
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

    private void showLlmDeleteConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_local_transcription_delete_title) // Reuse title "Delete Model"
                .setMessage(R.string.pref_local_transcription_delete_confirm)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    deleteLlmModel();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void deleteLlmModel() {
        String modelName = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());

        // Disable local analysis if it was enabled
        if (LocalAiPreferences.isLocalAdAnalysisEnabled(requireContext())) {
            LocalAiPreferences.setLocalAdAnalysisEnabled(requireContext(), false);
            SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_LLM_ENABLED);
            if (enabledPref != null) {
                enabledPref.setChecked(false);
            }
        }

        llmManager.deleteModel(modelName);
        if (LocalAiPreferences.MANUAL_MODEL_ID.equals(modelName)) {
            LocalAiPreferences.setManualModelPath(requireContext(), null);
        }

        Toast.makeText(requireContext(),
                R.string.pref_local_transcription_model_deleted,
                Toast.LENGTH_SHORT).show();

        updateLocalLlmUI();
        updateDeleteAllModelsSummary();
    }

    private void setupDeleteAllModels() {
        Preference deleteAllPref = findPreference(PREF_DELETE_ALL_MODELS);
        if (deleteAllPref != null) {
            deleteAllPref.setOnPreferenceClickListener(preference -> {
                showDeleteAllModelsConfirmation();
                return true;
            });
        }
        updateDeleteAllModelsSummary();
    }

    private void updateManualModelEntry(ListPreference modelPref) {
        String manualPath = LocalAiPreferences.getManualModelPath(requireContext());
        String manualLabel = null;
        if (!TextUtils.isEmpty(manualPath)) {
            String fileName = manualPath.substring(manualPath.lastIndexOf('/') + 1);
            manualLabel = getString(R.string.pref_local_ad_analysis_manual_label, fileName);
        }

        CharSequence[] entries = modelPref.getEntries();
        CharSequence[] entryValues = modelPref.getEntryValues();

        boolean hasManual = false;
        if (entryValues != null) {
            for (CharSequence val : entryValues) {
                if (LocalAiPreferences.MANUAL_MODEL_ID.contentEquals(val)) {
                    hasManual = true;
                    break;
                }
            }
        }

        if (manualLabel != null && entries != null && entryValues != null) {
            // Replace or append manual entry with filename
            int len = entryValues.length;
            CharSequence[] newEntries = new CharSequence[len];
            CharSequence[] newValues = new CharSequence[len];
            boolean replaced = false;
            for (int i = 0; i < len; i++) {
                newValues[i] = entryValues[i];
                if (LocalAiPreferences.MANUAL_MODEL_ID.contentEquals(entryValues[i])) {
                    newEntries[i] = manualLabel;
                    replaced = true;
                } else {
                    newEntries[i] = entries[i];
                }
            }
            if (!replaced) {
                // append
                newEntries = java.util.Arrays.copyOf(newEntries, len + 1);
                newValues = java.util.Arrays.copyOf(newValues, len + 1);
                newEntries[len] = manualLabel;
                newValues[len] = LocalAiPreferences.MANUAL_MODEL_ID;
            }
            modelPref.setEntries(newEntries);
            modelPref.setEntryValues(newValues);
        } else if (manualLabel == null && hasManual && entries != null && entryValues != null) {
            // Remove manual entry if path missing
            java.util.List<CharSequence> entryList = new java.util.ArrayList<>();
            java.util.List<CharSequence> valueList = new java.util.ArrayList<>();
            for (int i = 0; i < entryValues.length; i++) {
                if (!LocalAiPreferences.MANUAL_MODEL_ID.contentEquals(entryValues[i])) {
                    entryList.add(entries[i]);
                    valueList.add(entryValues[i]);
                }
            }
            modelPref.setEntries(entryList.toArray(new CharSequence[0]));
            modelPref.setEntryValues(valueList.toArray(new CharSequence[0]));
        }

        // Update summary to show filename next to selection
        if (LocalAiPreferences.MANUAL_MODEL_ID.equals(modelPref.getValue()) && manualLabel != null) {
            modelPref.setSummary(manualLabel);
        } else {
            modelPref.setSummary("%s");
        }
    }

    private void updateDeleteAllModelsSummary() {
        Preference deleteAllPref = findPreference(PREF_DELETE_ALL_MODELS);
        if (deleteAllPref == null) {
            return;
        }

        int transcriptionCount = transcriptionManager.getDownloadedModelsCount();
        int llmCount = llmManager.getDownloadedModelsCount();
        int totalCount = transcriptionCount + llmCount;

        long transcriptionSize = transcriptionManager.getDownloadedModelsSize();
        long llmSize = llmManager.getDownloadedModelsSize();
        long totalSize = transcriptionSize + llmSize;

        if (totalCount == 0) {
            deleteAllPref.setSummary(R.string.pref_delete_all_models_summary);
        } else {
            String sizeStr = formatSize(totalSize);
            String summary = totalCount + " model" + (totalCount > 1 ? "s" : "") + " (" + sizeStr + ")";
            deleteAllPref.setSummary(summary);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private void showDeleteAllModelsConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_delete_all_models_confirm_title)
                .setMessage(R.string.pref_delete_all_models_confirm_message)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    deleteAllModels();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void deleteAllModels() {
        // Disable local features if enabled
        if (LocalAiPreferences.isLocalTranscriptionEnabled(requireContext())) {
            LocalAiPreferences.setLocalTranscriptionEnabled(requireContext(), false);
            SwitchPreferenceCompat transcriptionSwitch = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
            if (transcriptionSwitch != null) {
                transcriptionSwitch.setChecked(false);
            }
        }
        if (LocalAiPreferences.isLocalAdAnalysisEnabled(requireContext())) {
            LocalAiPreferences.setLocalAdAnalysisEnabled(requireContext(), false);
            SwitchPreferenceCompat llmSwitch = findPreference(PREF_LOCAL_LLM_ENABLED);
            if (llmSwitch != null) {
                llmSwitch.setChecked(false);
            }
        }
        LocalAiPreferences.setManualModelPath(requireContext(), null);

        // Delete all models
        int transcriptionCount = transcriptionManager.deleteAllModels();
        int llmCount = llmManager.deleteAllModels();
        int totalCount = transcriptionCount + llmCount;

        // Update UI
        updateLocalTranscriptionUI();
        updateLocalLlmUI();
        updateDeleteAllModelsSummary();

        // Show result
        if (totalCount > 0) {
            Toast.makeText(requireContext(),
                    getString(R.string.pref_delete_all_models_success, totalCount),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(),
                    R.string.pref_delete_all_models_none,
                    Toast.LENGTH_SHORT).show();
        }
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

    private void importManualModel(Uri uri) {
        downloadExecutor.execute(() -> {
            try {
                // Step 1: Copy the file
                String sourceFileName = getDisplayNameFromUri(uri);
                try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
                    if (inputStream == null)
                        throw new IllegalArgumentException("Cannot open file stream");

                    llmManager.importModel(inputStream, LocalAiPreferences.MANUAL_MODEL_ID, sourceFileName);
                }

                // Step 2: Validate by initializing and getting a test response
                showImportProgress("Validating model...");
                String testResponse = llmManager.validateModel(LocalAiPreferences.MANUAL_MODEL_ID);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        // Select the manual model
                        LocalAiPreferences.setLocalAdAnalysisModel(requireContext(), LocalAiPreferences.MANUAL_MODEL_ID);

                        // Update ListPreference
                        ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
                        if (modelPref != null) {
                            modelPref.setValue(LocalAiPreferences.MANUAL_MODEL_ID);
                            updateManualModelEntry(modelPref);
                        }

                        updateLocalLlmUI();

                        // Show success dialog with model response
                        new AlertDialog.Builder(requireContext())
                                .setTitle(R.string.model_import_success_title)
                                .setMessage(getString(R.string.model_import_success_message, testResponse))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    });
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean isRawCandidate = msg.contains("Unable to open zip archive")
                        || msg.contains("Invalid Model Format");

                if (isRawCandidate) {
                    // Keep the imported file and fall back to raw interpreter
                    LocalAiPreferences.setLocalAdAnalysisModel(requireContext(), LocalAiPreferences.MANUAL_MODEL_ID);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
                            if (modelPref != null) {
                                modelPref.setValue(LocalAiPreferences.MANUAL_MODEL_ID);
                            }
                            updateLocalLlmUI();
                            new AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.model_import_success_title)
                                    .setMessage(getString(R.string.model_import_failed_message,
                                            "Model will use raw interpreter fallback: " + msg))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        });
                    }
                } else {
                    // Clean up failed import for other errors
                    try {
                        llmManager.deleteModel(LocalAiPreferences.MANUAL_MODEL_ID);
                    } catch (Exception ignored) {
                    }

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            new AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.model_import_failed_title)
                                    .setMessage(getString(R.string.model_import_failed_message, msg))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        });
                    }
                }
            }
        });
    }

    private void showImportProgress(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private String getDisplayNameFromUri(Uri uri) {
        String fallback = uri.getLastPathSegment();
        try (android.database.Cursor cursor = requireContext().getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback == null ? "manual_import.task" : fallback;
    }
}
