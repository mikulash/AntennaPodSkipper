package de.danoeh.antennapod.ui.preferences.screen;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
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

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.danoeh.antennapod.event.ModelDownloadEvent;
import de.danoeh.antennapod.net.ai.service.ad.vosk.VoskTranscriptionManager;
import de.danoeh.antennapod.net.ai.service.ad.vosk.VoskModel;
import de.danoeh.antennapod.ui.preferences.R;

@RequiresApi(api = Build.VERSION_CODES.O)
public class TranscriptionModelManagerFragment extends Fragment {
    private static final String TAG = "TranscriptionModelMgr";

    private VoskTranscriptionManager transcriptionManager;
    private RecyclerView recyclerView;
    private ModelAdapter adapter;
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
        transcriptionManager = new VoskTranscriptionManager(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadModels();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onModelDownloadEvent(ModelDownloadEvent event) {
        if (adapter == null) {
            return;
        }

        String modelId = event.getModelId();
        DownloadStatus status = adapter.downloadStatusMap.get(modelId);

        switch (event.getStatus()) {
            case STARTED:
                if (status == null) {
                    status = new DownloadStatus();
                    adapter.downloadStatusMap.put(modelId, status);
                }
                status.isDownloading = true;
                status.progress = 0;
                status.statusMessage = getString(R.string.download_starting);
                break;

            case PROGRESS:
                if (status == null) {
                    status = new DownloadStatus();
                    adapter.downloadStatusMap.put(modelId, status);
                }
                status.isDownloading = true;
                status.progress = event.getProgress();
                status.statusMessage = Math.round(event.getBytesDownloaded() / 1024f / 1024f) + "MB / "
                        + Math.round(event.getTotalBytes() / 1024f / 1024f) + "MB";
                break;

            case EXTRACTING:
                if (status != null) {
                    status.progress = 0;
                    status.statusMessage = getString(R.string.download_type_extraction);
                }
                break;

            case COMPLETED:
                adapter.downloadStatusMap.remove(modelId);
                Toast.makeText(requireContext(), R.string.pref_local_transcription_download_complete,
                        Toast.LENGTH_SHORT).show();
                break;

            case FAILED:
                adapter.downloadStatusMap.remove(modelId);
                Toast.makeText(requireContext(),
                        getString(R.string.pref_local_transcription_download_failed, event.getErrorMessage()),
                        Toast.LENGTH_SHORT).show();
                break;

            case CANCELLED:
                adapter.downloadStatusMap.remove(modelId);
                Toast.makeText(requireContext(), R.string.download_canceled_msg,
                        Toast.LENGTH_SHORT).show();
                break;

            default:

        }

        int position = adapter.findPositionByModelId(modelId);
        if (position >= 0) {
            adapter.notifyItemChanged(position);
        }
    }

    private void loadModels() {
        List<VoskModel> models = transcriptionManager.getAvailableModels();
        Collections.sort(models, Comparator.comparing(VoskModel::getLanguage).thenComparing(VoskModel::getName));

        if (models.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            adapter = new ModelAdapter(models);
            recyclerView.setAdapter(adapter);
        }
    }

    private class ModelAdapter extends RecyclerView.Adapter<ModelViewHolder> {
        private final List<Object> items;
        final Map<String, DownloadStatus> downloadStatusMap = new HashMap<>();

        public ModelAdapter(List<VoskModel> models) {
            this.items = new ArrayList<>();
            String currentLanguage = "";
            for (VoskModel model : models) {
                if (!model.getLanguage().equals(currentLanguage)) {
                    currentLanguage = model.getLanguage();
                    items.add(currentLanguage);
                }
                items.add(model);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? 0 : 1;
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
            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).bind((String) items.get(position));
            } else {
                ((ModelItemViewHolder) holder).bind((VoskModel) items.get(position));
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
            ImageButton cancelButton;

            public ModelItemViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.name);
                size = itemView.findViewById(R.id.size);
                actionButton = itemView.findViewById(R.id.actionButton);
                progressBar = itemView.findViewById(R.id.progressBar);
                statusText = itemView.findViewById(R.id.statusText);
                cancelButton = itemView.findViewById(R.id.cancelButton);
            }

            public void bind(VoskModel model) {
                name.setText(model.getName());
                size.setText(Formatter.formatFileSize(requireContext(), model.getSize()));

                DownloadStatus status = downloadStatusMap.get(model.getId());
                boolean isDownloaded = transcriptionManager.isModelDownloaded(model.getId());

                if (status != null && status.isDownloading) {
                    // Downloading state
                    actionButton.setVisibility(View.GONE);
                    progressBar.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    cancelButton.setVisibility(View.VISIBLE);
                    progressBar.setProgress(status.progress);
                    statusText.setText(status.statusMessage);
                    cancelButton.setOnClickListener(v -> cancelDownload(model));
                } else if (isDownloaded) {
                    // Downloaded state
                    actionButton.setVisibility(View.VISIBLE);
                    actionButton.setText(R.string.action_delete_model_file);
                    actionButton.setOnClickListener(v -> confirmDelete(model));
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                } else {
                    // Not downloaded state
                    actionButton.setVisibility(View.VISIBLE);
                    actionButton.setText(R.string.action_download_model_file);
                    actionButton.setOnClickListener(v -> startDownload(model));
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                }
            }

            private void startDownload(VoskModel model) {
                String modelId = model.getId();

                // Check memory before starting download
                if (!transcriptionManager.hasEnoughMemory(modelId)) {
                    showInsufficientMemoryDialog(model);
                    return;
                }

                // Start the download worker
                ModelDownloadWorker.enqueue(requireContext(), modelId);
            }

            private void cancelDownload(VoskModel model) {
                ModelDownloadWorker.cancel(requireContext(), model.getId());
                downloadStatusMap.remove(model.getId());
                notifyItemChanged(getBindingAdapterPosition());
                Toast.makeText(requireContext(), R.string.download_canceled_msg, Toast.LENGTH_SHORT).show();
            }

            private void confirmDelete(VoskModel model) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.action_delete_model_file)
                        .setMessage(getString(R.string.pref_local_transcription_delete_confirm, model.getName()))
                        .setPositiveButton(R.string.model_action_confirm, (dialog, which) -> {
                            transcriptionManager.deleteModel(model.getId());
                            notifyItemChanged(getBindingAdapterPosition());
                        })
                        .setNegativeButton(R.string.model_action_cancel, null)
                        .show();
            }

            private void showInsufficientMemoryDialog(VoskModel model) {
                long requiredMb = transcriptionManager.getMinMemoryRequired(model.getId()) / 1_000_000;
                android.app.ActivityManager activityManager = (android.app.ActivityManager) requireContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memInfo);
                long availableMb = (memInfo.availMem - memInfo.threshold - 100_000_000L) / 1_000_000;

                // Find smaller models
                List<VoskModel> smallerModels = new ArrayList<>();
                for (VoskModel m : transcriptionManager.getAvailableModels()) {
                    if (transcriptionManager.getMinMemoryRequired(m.getId())
                            < transcriptionManager.getMinMemoryRequired(model.getId())) {
                        smallerModels.add(m);
                    }
                }

                StringBuilder message = new StringBuilder();
                message.append(getString(R.string.pref_local_transcription_insufficient_memory,
                        model.getName(), availableMb, requiredMb));

                if (!smallerModels.isEmpty()) {
                    message.append("\n\n").append(getString(R.string.pref_local_transcription_try_smaller_model));
                    for (VoskModel smaller : smallerModels) {
                        long smallerMb = transcriptionManager.getMinMemoryRequired(smaller.getId()) / 1_000_000;
                        message.append("\n• ").append(smaller.getName())
                                .append(" (").append(smallerMb).append(" MB)");
                    }
                }

                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_local_transcription_memory_error_title)
                        .setMessage(message.toString())
                        .setPositiveButton(android.R.string.ok, null)
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

        int findPositionByModelId(String modelId) {
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (item instanceof VoskModel && modelId.equals(((VoskModel) item).getId())) {
                    return i;
                }
            }
            return -1;
        }
    }

    abstract static class ModelViewHolder extends RecyclerView.ViewHolder {
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
