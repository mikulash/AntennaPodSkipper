package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.CompleteAnalysisActionButton;
import de.danoeh.antennapod.databinding.ItemAdAnalysisFragmentBinding;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.ai.service.ad.AdAnalysisProgressKeys;
import de.danoeh.antennapod.net.ai.service.ad.AdAnalysisStages;
import de.danoeh.antennapod.net.ai.service.ad.AdAnalysisWorkScheduler;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

@RequiresApi(api = Build.VERSION_CODES.O)
public class ItemAdAnalysisFragment extends Fragment {
    private static final String TAG = "ItemAdAnalysisFragment";

    private ItemAdAnalysisFragmentBinding viewBinding;
    private Disposable disposable;
    private FeedItem item;
    private LiveData<List<WorkInfo>> analysisWorkLiveData;
    private boolean isAnalysisRunning = false;
    private boolean canReanalyzeTranscript = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = ItemAdAnalysisFragmentBinding.inflate(inflater, container, false);
        viewBinding.adSegmentsContent.setMovementMethod(LinkMovementMethod.getInstance());
        TabLayout tabs = viewBinding.adTabLayout;
        tabs.addTab(tabs.newTab().setText(R.string.ad_segments_tab_ads));
        tabs.addTab(tabs.newTab().setText(R.string.ad_segments_tab_transcript));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        viewBinding.butActionComplete.setOnClickListener(v -> onAnalysisButtonClicked());
        viewBinding.btnReanalyzeTranscript.setOnClickListener(v -> reanalyzeExistingTranscript());
        viewBinding.butActionCompleteIcon.setImageResource(R.drawable.ic_ad_analysis);
        return viewBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        load();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewBinding = null;
        item = null;
        analysisWorkLiveData = null;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        load();
    }

    private void load() {
        if (disposable != null) {
            disposable.dispose();
        }
        Context context = requireContext().getApplicationContext();
        disposable = Maybe.<LoadedData>create(emitter -> {
            FeedMedia media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
            if (media == null) {
                emitter.onComplete();
                return;
            }
            FeedItem feedItem = DBReader.getFeedItem(media.getItemId());
            if (feedItem == null) {
                emitter.onComplete();
                return;
            }
            AdAnalysisResult result = AdSegmentStore.load(context, feedItem.getId());
            String transcript = loadTranscriptText(feedItem.getMedia(), result);
            emitter.onSuccess(new LoadedData(feedItem, result, transcript));
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::display, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void display(LoadedData data) {
        if (viewBinding == null) {
            return;
        }
        boolean itemChanged = item == null || item.getId() != data.item.getId();
        item = data.item;
        if (itemChanged) {
            observeAnalysisWork(item.getId());
        }
        FeedMedia media = item.getMedia();
        boolean downloaded = media != null && media.isDownloaded();
        viewBinding.aiButtonsRow.setVisibility(downloaded ? View.VISIBLE : View.GONE);
        updateAnalysisButton();

        canReanalyzeTranscript = false;
        AdAnalysisResult result = data.result;
        if (!downloaded && result == null) {
            viewBinding.adSegmentsContent.setText(R.string.transcription_requires_download);
        } else if (result == null) {
            viewBinding.adSegmentsContent.setText(R.string.ad_segments_not_analyzed);
        } else if (!result.isSuccess()) {
            viewBinding.adSegmentsContent.setText(getString(R.string.ad_analysis_failed, result.getError()));
        } else if (result.getSegments().isEmpty()) {
            viewBinding.adSegmentsContent.setText(R.string.ad_segments_empty);
            canReanalyzeTranscript = downloaded && !TextUtils.isEmpty(data.transcript);
        } else {
            viewBinding.adSegmentsContent.setText(buildSegmentsText(result), TextView.BufferType.SPANNABLE);
        }
        if (TextUtils.isEmpty(data.transcript)) {
            viewBinding.adTranscriptContent.setText(R.string.ad_segments_not_analyzed);
        } else {
            viewBinding.adTranscriptContent.setText(data.transcript);
        }
        showTab(viewBinding.adTabLayout.getSelectedTabPosition());
    }

    private CharSequence buildSegmentsText(AdAnalysisResult result) {
        long totalAdDurationMs = 0;
        for (AdSegment segment : result.getSegments()) {
            totalAdDurationMs += (long) Math.max(0, (segment.getEndSeconds() - segment.getStartSeconds()) * 1000);
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int totalStart = sb.length();
        sb.append(getString(R.string.ad_segments_total_length,
                Converter.getDurationStringLong((int) totalAdDurationMs)));
        sb.setSpan(new StyleSpan(Typeface.BOLD), totalStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (AdSegment segment : result.getSegments()) {
            sb.append("\n");
            final int startMs = (int) (segment.getStartSeconds() * 1000);
            int spanStart = sb.length();
            sb.append(Converter.getDurationStringLong(startMs));
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    seekTo(startMs);
                }
            }, spanStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" - ");
            sb.append(Converter.getDurationStringLong((int) (segment.getEndSeconds() * 1000)));
            sb.setSpan(new StyleSpan(Typeface.BOLD), spanStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.isEmpty(segment.getReason())) {
                sb.append("\n");
                int reasonStart = sb.length();
                sb.append(segment.getReason());
                sb.setSpan(new ForegroundColorSpan(ThemeUtils.getColorFromAttr(requireContext(),
                        android.R.attr.textColorSecondary)), reasonStart, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private void seekTo(int positionMs) {
        if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            PlaybackController.bindToMedia3Service(getActivity(), controller -> controller.seekTo(positionMs));
        } else {
            PlaybackController.bindToService(getActivity(), playbackService -> playbackService.seekTo(positionMs));
        }
    }

    private void showTab(int index) {
        if (viewBinding == null) {
            return;
        }
        viewBinding.adSegmentsContent.setVisibility(index == 1 ? View.GONE : View.VISIBLE);
        viewBinding.adTranscriptContent.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        boolean showReanalyze = canReanalyzeTranscript && !isAnalysisRunning && index != 1;
        viewBinding.btnReanalyzeTranscript.setVisibility(showReanalyze ? View.VISIBLE : View.GONE);
    }

    private void onAnalysisButtonClicked() {
        if (item == null) {
            return;
        }
        if (isAnalysisRunning) {
            WorkManager.getInstance(requireContext())
                    .cancelAllWorkByTag(AdAnalysisWorkScheduler.TAG_PREFIX + item.getId());
            return;
        }
        new CompleteAnalysisActionButton(item).onClick(requireContext());
    }

    private void reanalyzeExistingTranscript() {
        if (item == null || item.getMedia() == null) {
            return;
        }
        if (AdAnalysisWorkScheduler.enqueueAnalysisOnly(requireContext(), item.getMedia())) {
            viewBinding.btnReanalyzeTranscript.setVisibility(View.GONE);
            Toast.makeText(requireContext(), R.string.ad_analysis_queued, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), R.string.transcription_api_key_missing_message,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void observeAnalysisWork(long feedItemId) {
        if (analysisWorkLiveData != null) {
            analysisWorkLiveData.removeObservers(getViewLifecycleOwner());
        }
        analysisWorkLiveData = WorkManager.getInstance(requireContext())
                .getWorkInfosByTagLiveData(AdAnalysisWorkScheduler.TAG_PREFIX + feedItemId);
        analysisWorkLiveData.observe(getViewLifecycleOwner(), this::onAnalysisWorkChanged);
    }

    private void onAnalysisWorkChanged(List<WorkInfo> workInfos) {
        if (viewBinding == null) {
            return;
        }
        WorkInfo active = null;
        if (workInfos != null) {
            for (WorkInfo info : workInfos) {
                if (info.getState() == WorkInfo.State.RUNNING) {
                    active = info;
                    break;
                }
                if (info.getState() == WorkInfo.State.ENQUEUED || info.getState() == WorkInfo.State.BLOCKED) {
                    active = info;
                }
            }
        }
        boolean wasRunning = isAnalysisRunning;
        isAnalysisRunning = active != null;
        if (active != null && active.getState() == WorkInfo.State.RUNNING) {
            String stage = active.getProgress().getString(AdAnalysisProgressKeys.STAGE);
            int percent = active.getProgress().getInt(AdAnalysisProgressKeys.PERCENT, -1);
            int chunksDone = active.getProgress().getInt(AdAnalysisProgressKeys.CHUNKS_DONE, 0);
            int chunksTotal = active.getProgress().getInt(AdAnalysisProgressKeys.CHUNKS_TOTAL, 0);
            String label = AdAnalysisStages.ANALYZING.equalsIgnoreCase(stage)
                    || AdAnalysisStages.TRANSCRIPTION_DONE.equalsIgnoreCase(stage)
                    ? getString(R.string.ad_analysis_analyzing) : getString(R.string.ad_analysis_transcribing);
            if (chunksTotal > 0) {
                label = label + " (" + chunksDone + "/" + chunksTotal + ")";
            }
            showProgress(label, percent);
        } else if (active != null) {
            showProgress(getString(R.string.ad_analysis_in_queue), -1);
        } else {
            updateAnalysisButton();
            if (wasRunning) {
                load();
            }
        }
        showTab(viewBinding.adTabLayout.getSelectedTabPosition());
    }

    private void showProgress(String label, int percent) {
        viewBinding.circularProgressComplete.setVisibility(View.VISIBLE);
        viewBinding.circularProgressComplete.setIndeterminate(percent < 0);
        if (percent >= 0) {
            viewBinding.circularProgressComplete.setPercentage(Math.max(0.01f, percent / 100f), item);
        }
        viewBinding.butActionCompleteIcon.setVisibility(View.INVISIBLE);
        viewBinding.butActionCompleteText.setText(label);
    }

    private void updateAnalysisButton() {
        if (isAnalysisRunning || item == null) {
            return;
        }
        viewBinding.circularProgressComplete.setVisibility(View.GONE);
        viewBinding.butActionCompleteIcon.setVisibility(View.VISIBLE);
        viewBinding.butActionCompleteText.setText(AdSegmentStore.hasAnalysis(requireContext(), item.getId())
                ? R.string.ad_analysis_again : R.string.action_complete_analysis);
    }

    @Nullable
    private static String loadTranscriptText(@Nullable FeedMedia media, @Nullable AdAnalysisResult result) {
        if (result != null && !TextUtils.isEmpty(result.getTranscript())) {
            return result.getTranscript();
        }
        if (media != null && !TextUtils.isEmpty(media.getTranscriptFileUrl())) {
            try {
                File transcriptFile = new File(media.getTranscriptFileUrl());
                if (transcriptFile.exists() && transcriptFile.length() > 0) {
                    return new String(Files.readAllBytes(transcriptFile.toPath()), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load transcript text", e);
            }
        }
        return null;
    }

    private static class LoadedData {
        final FeedItem item;
        final AdAnalysisResult result;
        final String transcript;

        LoadedData(FeedItem item, AdAnalysisResult result, String transcript) {
            this.item = item;
            this.result = result;
            this.transcript = transcript;
        }
    }
}
