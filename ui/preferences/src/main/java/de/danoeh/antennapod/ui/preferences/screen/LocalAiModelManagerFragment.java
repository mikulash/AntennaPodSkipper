package de.danoeh.antennapod.ui.preferences.screen;

import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.net.ai.service.ad.litert.LiteRtLLMManager;
import de.danoeh.antennapod.net.ai.service.ad.litert.LlmModel;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
import de.danoeh.antennapod.ui.preferences.R;

@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalAiModelManagerFragment extends Fragment {
    private static final String TAG = "LocalAiModelMgr";

    private LiteRtLLMManager llmManager;
    private RecyclerView recyclerView;
    private ModelAdapter adapter;
    private ExecutorService executorService;
    private TextView emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_transcription_model_manager, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        emptyView = view.findViewById(R.id.emptyView);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        return view;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        llmManager = new LiteRtLLMManager(requireContext());
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadModels();
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_manage_llm_models_title);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private void loadModels() {
        List<ModelItem> items = new ArrayList<>();

        // Add all available models (no grouping)
        for (LlmModel model : llmManager.getAvailableModels()) {
            items.add(new ModelItem(model));
        }

        // Add imported models section if there are any
        Set<String> importedModels = llmManager.getImportedModels();
        if (!importedModels.isEmpty()) {
            items.add(new ModelItem("Imported Models", true));
            for (String filename : importedModels) {
                items.add(new ModelItem(filename, LocalAiPreferences.IMPORTED_MODEL_PREFIX + filename));
            }
        }

        if (items.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            adapter = new ModelAdapter(items);
            recyclerView.setAdapter(adapter);
        }
    }

    /**
     * Represents an item in the model list - either a header or a model.
     */
    private static class ModelItem {
        final String headerTitle;
        final LlmModel model;
        final String importedFilename;
        final String importedModelId;
        final boolean isHeader;

        // Header constructor
        ModelItem(String headerTitle, boolean isHeader) {
            this.headerTitle = headerTitle;
            this.model = null;
            this.importedFilename = null;
            this.importedModelId = null;
            this.isHeader = isHeader;
        }

        // Built-in model constructor
        ModelItem(LlmModel model) {
            this.headerTitle = null;
            this.model = model;
            this.importedFilename = null;
            this.importedModelId = null;
            this.isHeader = false;
        }

        // Imported model constructor
        ModelItem(String filename, String modelId) {
            this.headerTitle = null;
            this.model = null;
            this.importedFilename = filename;
            this.importedModelId = modelId;
            this.isHeader = false;
        }

        String getDisplayName() {
            if (isHeader) return headerTitle;
            if (model != null) return model.getDisplayName();
            if (importedFilename != null) {
                String name = importedFilename;
                if (name.endsWith(".litertlm")) {
                    name = name.substring(0, name.length() - 9);
                }
                return name + " (Imported)";
            }
            return "Unknown";
        }

        String getModelId() {
            if (model != null) return model.getId();
            return importedModelId;
        }
    }

    private class ModelAdapter extends RecyclerView.Adapter<ModelViewHolder> {
        private final List<ModelItem> items;
        private final Map<String, DownloadStatus> downloadStatusMap = new HashMap<>();

        public ModelAdapter(List<ModelItem> items) {
            this.items = items;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isHeader ? 0 : 1;
        }

        @NonNull
        @Override
        public ModelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == 0) {
                View view = inflater.inflate(R.layout.list_item_model_header, parent, false);
                return new HeaderViewHolder(view);
            } else {
                View view = inflater.inflate(R.layout.list_item_model, parent, false);
                return new ModelItemViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull ModelViewHolder holder, int position) {
            ModelItem item = items.get(position);
            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).bind(item.headerTitle);
            } else {
                ((ModelItemViewHolder) holder).bind(item);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private class ModelItemViewHolder extends ModelViewHolder {
            TextView name;
            TextView size;
            Button actionButton;
            ProgressBar progressBar;
            TextView statusText;

            public ModelItemViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.name);
                size = itemView.findViewById(R.id.size);
                actionButton = itemView.findViewById(R.id.actionButton);
                progressBar = itemView.findViewById(R.id.progressBar);
                statusText = itemView.findViewById(R.id.statusText);
            }

            public void bind(ModelItem item) {
                name.setText(item.getDisplayName());

                String modelId = item.getModelId();
                boolean isDownloaded = llmManager.isModelDownloaded(modelId);

                // Build size/description text
                StringBuilder sizeText = new StringBuilder();
                if (item.model != null) {
                    // Show size and memory requirement
                    sizeText.append(item.model.getFormattedSize());
                    sizeText.append(" | Requires: ");
                    sizeText.append(item.model.getMinDeviceMemoryGb());
                    sizeText.append(" GB RAM");
                } else if (isDownloaded) {
                    // Imported model - show actual file size
                    File modelFile = llmManager.getModelPath(modelId);
                    sizeText.append(Formatter.formatFileSize(requireContext(), modelFile.length()));
                }
                size.setText(sizeText.toString());
                size.setVisibility(View.VISIBLE);

                DownloadStatus status = downloadStatusMap.get(modelId);

                if (status != null && status.isDownloading) {
                    // Downloading state
                    actionButton.setVisibility(View.GONE);
                    progressBar.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    progressBar.setProgress(status.progress);
                    statusText.setText(status.statusMessage);
                } else if (isDownloaded) {
                    // Downloaded state
                    actionButton.setVisibility(View.VISIBLE);
                    actionButton.setText(R.string.action_delete_model_file);
                    actionButton.setOnClickListener(v -> confirmDelete(item));
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                } else if (item.model != null && item.model.getUrl() != null) {
                    // Not downloaded state (built-in models only)
                    actionButton.setVisibility(View.VISIBLE);
                    actionButton.setText(R.string.action_download_model_file);
                    actionButton.setOnClickListener(v -> startDownload(item));
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                } else {
                    // Manual import or no URL - hide action button
                    actionButton.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                }
            }

            private void startDownload(ModelItem item) {
                if (item.model == null) return;

                // Check if model requires authentication
                if (item.model.needsAuth()) {
                    showAuthRequiredDialog(item.model);
                    return;
                }

                String modelId = item.model.getId();
                // Capture position now - it may change later
                final int position = getBindingAdapterPosition();

                DownloadStatus status = new DownloadStatus();
                status.isDownloading = true;
                status.statusMessage = getString(R.string.download_starting);
                downloadStatusMap.put(modelId, status);
                notifyItemChanged(position);

                executorService.execute(() -> {
                    try {
                        llmManager.downloadModel(item.model, (progress, currentBytes, totalBytes) -> {
                            if (getActivity() == null) return;
                            requireActivity().runOnUiThread(() -> {
                                if (progress < 0) {
                                    status.progress = 0;
                                    status.statusMessage = getString(R.string.download_type_extraction);
                                } else {
                                    status.progress = progress;
                                    status.statusMessage = Math.round(currentBytes / 1024f / 1024f) + "MB / "
                                            + Math.round(totalBytes / 1024f / 1024f) + "MB";
                                }
                                // Find current position by model ID
                                int currentPos = findPositionByModelId(modelId);
                                if (currentPos >= 0) {
                                    notifyItemChanged(currentPos);
                                }
                            });
                        }, null);

                        if (getActivity() == null) return;
                        requireActivity().runOnUiThread(() -> {
                            downloadStatusMap.remove(modelId);
                            int currentPos = findPositionByModelId(modelId);
                            if (currentPos >= 0) {
                                notifyItemChanged(currentPos);
                            }
                            Toast.makeText(requireContext(), R.string.download_complete, Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Download failed", e);
                        if (getActivity() == null) return;
                        requireActivity().runOnUiThread(() -> {
                            downloadStatusMap.remove(modelId);
                            int currentPos = findPositionByModelId(modelId);
                            if (currentPos >= 0) {
                                notifyItemChanged(currentPos);
                            }
                            Toast.makeText(requireContext(),
                                    getString(R.string.pref_local_transcription_download_failed, e.getMessage()),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            }

            private int findPositionByModelId(String modelId) {
                for (int i = 0; i < items.size(); i++) {
                    ModelItem item = items.get(i);
                    if (!item.isHeader && modelId.equals(item.getModelId())) {
                        return i;
                    }
                }
                return -1;
            }

            private void showAuthRequiredDialog(LlmModel model) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_local_llm_auth_required_title)
                        .setMessage(getString(R.string.pref_local_llm_auth_required_message, model.getDisplayName()))
                        .setPositiveButton(R.string.open_browser_label, (dialog, which) -> {
                            android.content.Intent browserIntent = new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(model.getUrl()));
                            startActivity(browserIntent);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }

            private void confirmDelete(ModelItem item) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.action_delete_model_file)
                        .setMessage(getString(R.string.pref_local_transcription_delete_confirm, item.getDisplayName()))
                        .setPositiveButton(R.string.model_action_confirm, (dialog, which) -> {
                            // Disable local analysis if this model was selected
                            String selectedModel = LocalAiPreferences.getLocalAdAnalysisModel(requireContext());
                            String modelId = item.getModelId();

                            if (modelId != null && modelId.equals(selectedModel)) {
                                LocalAiPreferences.setLocalAdAnalysisEnabled(requireContext(), false);
                            }

                            // Delete the model
                            if (item.importedFilename != null) {
                                llmManager.deleteImportedModel(item.importedFilename);
                            } else if (item.model != null) {
                                llmManager.deleteModel(item.model);
                            }

                            // Reload the list to reflect changes
                            loadModels();

                            Toast.makeText(requireContext(), R.string.pref_local_transcription_model_deleted,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.model_action_cancel, null)
                        .show();
            }
        }

        private class HeaderViewHolder extends ModelViewHolder {
            TextView title;

            public HeaderViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.title);
            }

            public void bind(String header) {
                title.setText(header);
            }
        }
    }

    static abstract class ModelViewHolder extends RecyclerView.ViewHolder {
        public ModelViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static class DownloadStatus {
        boolean isDownloading;
        int progress;
        String statusMessage;
    }
}
