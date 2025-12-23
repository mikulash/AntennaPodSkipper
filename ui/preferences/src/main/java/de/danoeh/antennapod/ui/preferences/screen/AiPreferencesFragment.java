package de.danoeh.antennapod.ui.preferences.screen;

import android.app.AlertDialog;
import android.content.Intent;
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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.InputStream;
import android.net.Uri;

import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.net.download.service.ad.litert.LlmModel;
import de.danoeh.antennapod.net.download.service.ad.whisper.VoskModel;
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
    private static final String PREF_MANAGE_TRANSCRIPTION_MODELS = "prefManageTranscriptionModels";
    private static final String PREF_LOCAL_TRANSCRIPTION_DELETE = "prefLocalTranscriptionDelete";

    // Local LLM Analysis
    private static final String PREF_LOCAL_LLM_CATEGORY = "prefLocalAdAnalysisCategory";
    private static final String PREF_LOCAL_LLM_ENABLED = "prefLocalAdAnalysisEnabled";
    private static final String PREF_LOCAL_LLM_MODEL = "prefLocalAdAnalysisModel";
    private static final String PREF_MANUAL_MODEL_SETTINGS = "prefManualModelSettings";
    private static final String PREF_MANAGE_LLM_MODELS = "prefManageLlmModels";
    private static final String PREF_LOCAL_LLM_IMPORT = "prefLocalAdAnalysisImport";

    // Delete all models
    // private static final String PREF_DELETE_ALL_MODELS = "prefDeleteAllModels";
    // // Removed
    private static final String PREF_DELETE_ALL_TRANSCRIPTION_MODELS = "prefDeleteAllTranscriptionModels";
    private static final String PREF_DELETE_ALL_LLM_MODELS = "prefDeleteAllLlmModels";

    private LocalTranscriptionManager transcriptionManager;
    private de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager llmManager;
    private ExecutorService downloadExecutor;
    private volatile boolean isDownloading = false;
    private ActivityResultLauncher<String> importLauncher;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences_ai);

        transcriptionManager = new LocalTranscriptionManager(requireContext());
        llmManager = new de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager(requireContext());

        downloadExecutor = Executors.newSingleThreadExecutor();

        importLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                promptManualBackendAndImport(uri);
            }
        });

        setupApiKeyPreference();
        setupModelPreference();
        setupAnalysisTypePreference();
        setupLocalTranscriptionPreferences();
        setupLocalLlmPreferences();
        setupDeleteAllTranscriptionModels();
        setupDeleteAllLlmModels();
        setupStatistics();
    }

    private void promptManualBackendAndImport(Uri uri) {
        // Create custom layout for dialog
        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        // Backend selection label
        android.widget.TextView backendLabel = new android.widget.TextView(requireContext());
        backendLabel.setText(R.string.pref_local_ad_analysis_manual_backend_title);
        backendLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        layout.addView(backendLabel);

        // Backend radio group
        android.widget.RadioGroup backendRadioGroup = new android.widget.RadioGroup(requireContext());
        backendRadioGroup.setOrientation(android.widget.RadioGroup.HORIZONTAL);

        android.widget.RadioButton gpuRadio = new android.widget.RadioButton(requireContext());
        gpuRadio.setText("GPU");
        gpuRadio.setId(android.view.View.generateViewId());
        backendRadioGroup.addView(gpuRadio);

        android.widget.RadioButton cpuRadio = new android.widget.RadioButton(requireContext());
        cpuRadio.setText("CPU");
        cpuRadio.setId(android.view.View.generateViewId());
        backendRadioGroup.addView(cpuRadio);

        // Set default selection
        String currentBackend = LocalAiPreferences.getManualModelBackend(requireContext());
        if ("CPU".equalsIgnoreCase(currentBackend)) {
            cpuRadio.setChecked(true);
        } else {
            gpuRadio.setChecked(true);
        }
        layout.addView(backendRadioGroup);

        // Add some spacing
        android.widget.Space space = new android.widget.Space(requireContext());
        space.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, padding));
        layout.addView(space);

        // Max tokens label
        android.widget.TextView maxTokensLabel = new android.widget.TextView(requireContext());
        maxTokensLabel.setText(R.string.pref_manual_model_max_tokens_title);
        maxTokensLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        layout.addView(maxTokensLabel);

        // Max tokens input
        android.widget.EditText maxTokensInput = new android.widget.EditText(requireContext());
        maxTokensInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxTokensInput.setHint(R.string.pref_manual_model_max_tokens_hint);
        int currentMaxTokens = LocalAiPreferences.getManualModelMaxTokens(requireContext());
        maxTokensInput.setText(String.valueOf(currentMaxTokens));
        layout.addView(maxTokensInput);

        // Hint text
        android.widget.TextView hintText = new android.widget.TextView(requireContext());
        hintText.setText(R.string.pref_manual_model_max_tokens_summary);
        hintText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        hintText.setAlpha(0.7f);
        layout.addView(hintText);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_local_ad_analysis_import_settings_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    // Save backend selection
                    String backend = gpuRadio.isChecked() ? "GPU" : "CPU";
                    LocalAiPreferences.setManualModelBackend(requireContext(), backend);

                    // Save max tokens
                    String maxTokensStr = maxTokensInput.getText().toString().trim();
                    int maxTokens = 512; // default
                    try {
                        maxTokens = Integer.parseInt(maxTokensStr);
                        if (maxTokens <= 0)
                            maxTokens = 512;
                    } catch (NumberFormatException ignored) {
                    }
                    LocalAiPreferences.setManualModelMaxTokens(requireContext(), maxTokens);

                    importManualModel(uri);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        updateDeleteAllTranscriptionModelsSummary();
        updateDeleteAllLlmModelsSummary();
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
        // Model Selection
        setupTranscriptionModelList();

        // Manage Models
        setupManageTranscriptionModels();

        // Enable Switch
        SwitchPreferenceCompat transcriptionSwitch = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
        if (transcriptionSwitch != null) {
            transcriptionSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                LocalAiPreferences.setLocalTranscriptionEnabled(requireContext(), enabled);
                updateLocalTranscriptionUI();
                return true;
            });
        }
    }

    private void setupManageTranscriptionModels() {
        Preference managePref = findPreference(PREF_MANAGE_TRANSCRIPTION_MODELS);
        if (managePref != null) {
            managePref.setOnPreferenceClickListener(preference -> {
                ((de.danoeh.antennapod.ui.preferences.PreferenceController) requireActivity())
                        .openScreen(new TranscriptionModelManagerFragment());
                return true;
            });
        }
    }

    private void setupTranscriptionModelList() {
        ListPreference modelPref = findPreference(PREF_LOCAL_TRANSCRIPTION_MODEL);
        if (modelPref != null) {
            modelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String modelId = (String) newValue;

                // Check if model is downloaded
                if (!transcriptionManager.isModelDownloaded(modelId)) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Model not downloaded")
                            .setMessage(
                                    "The selected model is not downloaded. Please download it in 'Manage Models' first.")
                            .setPositiveButton("Go to Manage Models", (d, w) -> {
                                ((de.danoeh.antennapod.ui.preferences.PreferenceController) requireActivity())
                                        .openScreen(new TranscriptionModelManagerFragment());
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    // Don't update value yet? Or update and let them discover it fails?
                    // Better to not update if not valid?
                    // Actually, let's allow setting it, but warn.
                }

                LocalAiPreferences.setLocalTranscriptionModel(requireContext(), modelId);
                updateLocalTranscriptionUI();
                return true;
            });
        }
    }

    private void updateLocalTranscriptionUI() {
        String selectedModel = LocalAiPreferences.getLocalTranscriptionModel(requireContext());
        boolean isDownloaded = transcriptionManager.isModelDownloaded(selectedModel);

        ListPreference modelPref = findPreference(PREF_LOCAL_TRANSCRIPTION_MODEL);
        if (modelPref != null) {
            // Update summary
            de.danoeh.antennapod.net.download.service.ad.whisper.VoskModel model = transcriptionManager
                    .getModelById(selectedModel);
            String label = (model != null) ? model.getName() : selectedModel;
            modelPref.setSummary(label);
            modelPref.setValue(selectedModel); // Ensure UI matches pref

            List<VoskModel> models = transcriptionManager
                    .getAvailableModels();
            // Filter only downloaded models
            List<VoskModel> downloadedModels = new java.util.ArrayList<>();
            for (VoskModel m : models) {
                if (transcriptionManager.isModelDownloaded(m.getId())) {
                    downloadedModels.add(m);
                }
            }

            // If the currently selected model is not downloaded (e.g. deleted), we should
            // still probably show it?
            // Or maybe just show what IS downloaded.
            // If selected is not in list, ListPreference might act weird.
            // Let's add the selected one if missing, OR just let the summary handle it.
            // The request is "show only currently downloaded".

            CharSequence[] entries = new CharSequence[downloadedModels.size()];
            CharSequence[] entryValues = new CharSequence[downloadedModels.size()];
            for (int i = 0; i < downloadedModels.size(); i++) {
                de.danoeh.antennapod.net.download.service.ad.whisper.VoskModel m = downloadedModels.get(i);
                entries[i] = m.getName();
                entryValues[i] = m.getId();
            }
            modelPref.setEntries(entries);
            modelPref.setEntryValues(entryValues);
        }

        // Update enable switch
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
        if (enabledPref != null) {
            enabledPref.setEnabled(isDownloaded);
            if (!isDownloaded && enabledPref.isChecked()) {
                enabledPref.setSummary("Model not downloaded");
            } else if (enabledPref.isChecked()) {
                enabledPref.setSummary(R.string.pref_local_transcription_summary);
            }
        }
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
            updateDownloadedModelsDropdown(modelPref);
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

        // Manage LLM Models
        Preference managePref = findPreference(PREF_MANAGE_LLM_MODELS);
        if (managePref != null) {
            managePref.setOnPreferenceClickListener(preference -> {
                ((de.danoeh.antennapod.ui.preferences.PreferenceController) requireActivity())
                        .openScreen(new LocalAiModelManagerFragment());
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

        updateLocalLlmUI();
    }

    private void updateLocalLlmUI() {
        String selectedModel = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());
        boolean isDownloaded = llmManager.isModelDownloaded(selectedModel);
        boolean isManualModel = LocalAiPreferences.MANUAL_MODEL_ID.equals(selectedModel);
        boolean isImportedModel = selectedModel != null
                && selectedModel.startsWith(LocalAiPreferences.IMPORTED_MODEL_PREFIX);

        ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
        if (modelPref != null) {
            updateDownloadedModelsDropdown(modelPref);
        }

        // Show/hide manual model settings
        Preference manualSettingsPref = findPreference(PREF_MANUAL_MODEL_SETTINGS);
        if (manualSettingsPref != null) {
            manualSettingsPref.setVisible((isManualModel || isImportedModel) && isDownloaded);
        }

        // Update enable switch
        SwitchPreferenceCompat enabledPref = findPreference(PREF_LOCAL_LLM_ENABLED);
        if (enabledPref != null) {
            enabledPref.setEnabled(isDownloaded);
        }
    }

    private void setupDeleteAllTranscriptionModels() {
        Preference deleteAllPref = findPreference(PREF_DELETE_ALL_TRANSCRIPTION_MODELS);
        if (deleteAllPref != null) {
            deleteAllPref.setOnPreferenceClickListener(preference -> {
                showDeleteAllTranscriptionModelsConfirmation();
                return true;
            });
        }
        updateDeleteAllTranscriptionModelsSummary();
    }

    private void setupDeleteAllLlmModels() {
        Preference deleteAllPref = findPreference(PREF_DELETE_ALL_LLM_MODELS);
        if (deleteAllPref != null) {
            deleteAllPref.setOnPreferenceClickListener(preference -> {
                showDeleteAllLlmModelsConfirmation();
                return true;
            });
        }
        updateDeleteAllLlmModelsSummary();
    }

    private void updateDownloadedModelsDropdown(ListPreference modelPref) {
        java.util.List<CharSequence> entryList = new java.util.ArrayList<>();
        java.util.List<CharSequence> valueList = new java.util.ArrayList<>();

        // Add only downloaded built-in models (show just the name, no size info)
        for (LlmModel model : llmManager.getAvailableModels()) {
            if (llmManager.isModelDownloaded(model.getId())) {
                entryList.add(model.getDisplayName());
                valueList.add(model.getId());
            }
        }

        // Add imported models (which are always downloaded)
        java.util.Set<String> importedModels = llmManager.getImportedModels();
        for (String filename : importedModels) {
            String displayName = stripModelExtension(filename);
            String value = LocalAiPreferences.IMPORTED_MODEL_PREFIX + filename;
            entryList.add(displayName);
            valueList.add(value);
        }

        // Also add manual model if it exists and isn't already in imported list
        String manualPath = LocalAiPreferences.getManualModelPath(requireContext());
        if (!TextUtils.isEmpty(manualPath)) {
            java.io.File manualFile = new java.io.File(manualPath);
            String manualFilename = manualFile.getName();
            // Only add if not already in imported list
            if (!importedModels.contains(manualFilename)) {
                entryList.add(stripModelExtension(manualFilename));
                valueList.add(LocalAiPreferences.MANUAL_MODEL_ID);
            }
        }

        modelPref.setEntries(entryList.toArray(new CharSequence[0]));
        modelPref.setEntryValues(valueList.toArray(new CharSequence[0]));

        // Handle case when no models are downloaded
        if (entryList.isEmpty()) {
            modelPref.setEnabled(false);
            modelPref.setSummary(R.string.pref_llm_no_models_downloaded);
        } else {
            modelPref.setEnabled(true);
            // Update summary based on selection
            String selectedValue = modelPref.getValue();
            if (selectedValue != null && selectedValue.startsWith(LocalAiPreferences.IMPORTED_MODEL_PREFIX)) {
                String filename = selectedValue.substring(LocalAiPreferences.IMPORTED_MODEL_PREFIX.length());
                modelPref.setSummary(stripModelExtension(filename));
            } else if (LocalAiPreferences.MANUAL_MODEL_ID.equals(selectedValue) && !TextUtils.isEmpty(manualPath)) {
                String fileName = new java.io.File(manualPath).getName();
                modelPref.setSummary(stripModelExtension(fileName));
            } else {
                modelPref.setSummary("%s");
            }
        }
    }

    private String stripModelExtension(String filename) {
        if (filename == null) {
            return "";
        }
        if (filename.endsWith(".litertlm")) {
            return filename.substring(0, filename.length() - 9);
        }
        return filename;
    }

    private void updateDeleteAllTranscriptionModelsSummary() {
        Preference deleteAllPref = findPreference(PREF_DELETE_ALL_TRANSCRIPTION_MODELS);
        if (deleteAllPref == null) {
            return;
        }

        int count = transcriptionManager.getDownloadedModelsCount();
        long size = transcriptionManager.getDownloadedModelsSize();

        if (count == 0) {
            deleteAllPref.setSummary(R.string.pref_delete_all_transcription_models_summary);
        } else {
            String sizeStr = formatSize(size);
            String summary = count + " model" + (count > 1 ? "s" : "") + " (" + sizeStr + ")";
            deleteAllPref.setSummary(summary);
        }
    }

    private void updateDeleteAllLlmModelsSummary() {
        Preference deleteAllPref = findPreference(PREF_DELETE_ALL_LLM_MODELS);
        if (deleteAllPref == null) {
            return;
        }

        int count = llmManager.getDownloadedModelsCount();
        long size = llmManager.getDownloadedModelsSize();

        if (count == 0) {
            deleteAllPref.setSummary(R.string.pref_delete_all_llm_models_summary);
        } else {
            String sizeStr = formatSize(size);
            String summary = count + " model" + (count > 1 ? "s" : "") + " (" + sizeStr + ")";
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

    private void showDeleteAllTranscriptionModelsConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_delete_all_transcription_models_confirm_title)
                .setMessage(R.string.pref_delete_all_transcription_models_confirm_message)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    deleteAllTranscriptionModels();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void showDeleteAllLlmModelsConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_delete_all_llm_models_confirm_title)
                .setMessage(R.string.pref_delete_all_llm_models_confirm_message)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    deleteAllLlmModels();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void deleteAllTranscriptionModels() {
        // Disable local features if enabled
        if (LocalAiPreferences.isLocalTranscriptionEnabled(requireContext())) {
            LocalAiPreferences.setLocalTranscriptionEnabled(requireContext(), false);
            SwitchPreferenceCompat transcriptionSwitch = findPreference(PREF_LOCAL_TRANSCRIPTION_ENABLED);
            if (transcriptionSwitch != null) {
                transcriptionSwitch.setChecked(false);
            }
        }

        String keepTranscription = LocalAiPreferences.getLocalTranscriptionModel(requireContext());
        int totalCount = transcriptionManager.deleteAllModelsExcept(keepTranscription); // Keep selected model
                                                                                        // consistent with previous
                                                                                        // behavior

        // Update UI
        updateLocalTranscriptionUI();
        updateDeleteAllTranscriptionModelsSummary();

        // Show result
        if (totalCount > 0) {
            Toast.makeText(requireContext(),
                    getString(R.string.pref_delete_all_transcription_models_success, totalCount),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(),
                    R.string.pref_delete_all_transcription_models_none,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteAllLlmModels() {
        if (LocalAiPreferences.isLocalAdAnalysisEnabled(requireContext())) {
            LocalAiPreferences.setLocalAdAnalysisEnabled(requireContext(), false);
            SwitchPreferenceCompat llmSwitch = findPreference(PREF_LOCAL_LLM_ENABLED);
            if (llmSwitch != null) {
                llmSwitch.setChecked(false);
            }
        }
        LocalAiPreferences.setManualModelPath(requireContext(), null);

        String keepLlm = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());
        int totalCount = llmManager.deleteAllModelsExcept(keepLlm);

        // Update UI
        updateLocalLlmUI();
        updateDeleteAllLlmModelsSummary();

        // Show result
        if (totalCount > 0) {
            Toast.makeText(requireContext(),
                    getString(R.string.pref_delete_all_llm_models_success, totalCount),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(),
                    R.string.pref_delete_all_llm_models_none,
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
                        LocalAiPreferences.setLocalAdAnalysisModel(requireContext(),
                                LocalAiPreferences.MANUAL_MODEL_ID);

                        // Update ListPreference
                        ListPreference modelPref = findPreference(PREF_LOCAL_LLM_MODEL);
                        if (modelPref != null) {
                            modelPref.setValue(LocalAiPreferences.MANUAL_MODEL_ID);
                            updateDownloadedModelsDropdown(modelPref);
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
                .query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
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
        return fallback == null ? "manual_import.litertlm" : fallback;
    }
}
