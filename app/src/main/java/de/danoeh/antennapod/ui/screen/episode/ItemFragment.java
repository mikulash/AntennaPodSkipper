package de.danoeh.antennapod.ui.screen.episode;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.skydoves.balloon.ArrowOrientation;
import com.skydoves.balloon.ArrowOrientationRules;
import com.skydoves.balloon.Balloon;
import com.skydoves.balloon.BalloonAnimation;
import com.google.android.material.tabs.TabLayout;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.graphics.Typeface;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.CancelDownloadActionButton;
import de.danoeh.antennapod.actionbutton.DeleteActionButton;
import de.danoeh.antennapod.actionbutton.DownloadActionButton;
import de.danoeh.antennapod.actionbutton.ItemActionButton;
import de.danoeh.antennapod.actionbutton.MarkAsPlayedActionButton;
import de.danoeh.antennapod.actionbutton.PauseActionButton;
import de.danoeh.antennapod.actionbutton.PlayActionButton;
import de.danoeh.antennapod.actionbutton.PlayLocalActionButton;
import de.danoeh.antennapod.actionbutton.StreamActionButton;
import de.danoeh.antennapod.actionbutton.VisitWebsiteActionButton;
import de.danoeh.antennapod.actionbutton.AnalyzeAdsActionButton;
import de.danoeh.antennapod.actionbutton.TranscribeActionButton;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.databinding.FeeditemFragmentBinding;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.UnreadItemsUpdateEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackStatus;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UsageStatistics;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter;
import de.danoeh.antennapod.ui.cleaner.ShownotesCleaner;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.common.DateFormatter;
import de.danoeh.antennapod.ui.common.ImagePlaceholder;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.ui.episodes.ImageResourceUtils;
import de.danoeh.antennapod.ui.screen.feed.FeedItemlistFragment;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Displays information about a FeedItem and actions.
 */
public class ItemFragment extends Fragment {

    private static final String TAG = "ItemFragment";
    private static final String ARG_FEEDITEM = "feeditem";

    /**
     * Creates a new instance of an ItemFragment
     *
     * @param feeditem The ID of the FeedItem to show
     * @return The ItemFragment instance
     */
    public static ItemFragment newInstance(long feeditem) {
        ItemFragment fragment = new ItemFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_FEEDITEM, feeditem);
        fragment.setArguments(args);
        return fragment;
    }

    private boolean itemsLoaded = false;
    private long itemId;
    private FeedItem item;
    private String webviewData;

    private ItemActionButton actionButton1;
    private ItemActionButton actionButton2;
    private ItemActionButton actionButtonTranscribe;
    private ItemActionButton actionButtonAd;
    private Disposable disposable;
    private PlaybackController controller;
    private FeeditemFragmentBinding viewBinding;
    private LiveData<List<WorkInfo>> transcriptionWorkLiveData;
    private LiveData<List<WorkInfo>> adWorkLiveData;
    private boolean isTranscriptionRunning = false;
    private String transcriptionStageLabel = null;
    private int transcriptionPercent = -1;
    private boolean isAdAnalysisRunning = false;
    private String adAnalysisStageLabel = null;
    private int adAnalysisPercent = -1;

    private boolean isAdAnalysisSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        itemId = getArguments().getLong(ARG_FEEDITEM);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        viewBinding = FeeditemFragmentBinding.inflate(inflater, container, false);
        viewBinding.header.setVisibility(View.INVISIBLE);
        viewBinding.txtvPodcast.setOnClickListener(v -> openPodcast());
        if (Build.VERSION.SDK_INT >= 23) {
            viewBinding.txtvTitle.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL);
        }
        viewBinding.txtvTitle.setEllipsize(TextUtils.TruncateAt.END);
        viewBinding.webvDescription.setTimecodeSelectedListener(time -> {
            if (controller != null && item.getMedia() != null && controller.getMedia() != null
                    && Objects.equals(item.getMedia().getIdentifier(), controller.getMedia().getIdentifier())) {
                controller.seekTo(time);
            } else {
                EventBus.getDefault().post(new MessageEvent(getString(R.string.play_this_to_seek_position_message)));
            }
        });
        registerForContextMenu(viewBinding.webvDescription);
        viewBinding.imgvCover.setOnClickListener(v -> openPodcast());
        viewBinding.butAction1.setOnClickListener(v -> {
            if (actionButton1 instanceof StreamActionButton && !UserPreferences.isStreamOverDownload()
                    && UsageStatistics.hasSignificantBiasTo(UsageStatistics.ACTION_STREAM)) {
                showOnDemandConfigBalloon(true);
                return;
            } else if (actionButton1 == null) {
                return; // Not loaded yet
            }
            actionButton1.onClick(getContext());
        });
        viewBinding.butActionTranscribe.setOnClickListener(v -> {
            if (actionButtonTranscribe == null || item == null) {
                return;
            }
            if (isTranscriptionRunning) {
                cancelTranscriptionWork(item.getId());
                isTranscriptionRunning = false;
                transcriptionStageLabel = null;
                transcriptionPercent = -1;
                viewBinding.circularProgressTranscribe.setVisibility(View.GONE);
                updateButtons();
                return;
            }
            actionButtonTranscribe.onClick(getContext());
        });
        viewBinding.butActionAd.setOnClickListener(v -> {
            if (actionButtonAd == null || item == null) {
                return;
            }
            if (isAdAnalysisRunning) {
                cancelAdAnalysisWork(item.getId());
                isAdAnalysisRunning = false;
                adAnalysisStageLabel = null;
                adAnalysisPercent = -1;
                viewBinding.circularProgressAd.setVisibility(View.GONE);
                updateButtons();
                return;
            }
            actionButtonAd.onClick(getContext());
        });
        viewBinding.butAction2.setOnClickListener(v -> {
            if (actionButton2 instanceof DownloadActionButton && UserPreferences.isStreamOverDownload()
                    && UsageStatistics.hasSignificantBiasTo(UsageStatistics.ACTION_DOWNLOAD)) {
                showOnDemandConfigBalloon(false);
                return;
            } else if (actionButton2 == null) {
                return; // Not loaded yet
            }
            actionButton2.onClick(getContext());
        });
        viewBinding.txtvPodcast.setOnLongClickListener(v -> {
            copyToClipboard(requireContext(), viewBinding.txtvPodcast.getText().toString());
            return true;
        });
        viewBinding.txtvTitle.setOnLongClickListener(v -> {
            copyToClipboard(requireContext(), viewBinding.txtvTitle.getText().toString());
            return true;
        });
        if (isAdAnalysisSupported()) {
            setupAdTabs();
            if (item != null) {
                observeTranscriptionWork(item.getId());
                observeAdAnalysisWork(item.getId());
            }
        } else {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            viewBinding.aiButtonsRow.setVisibility(View.GONE);
        }
        return viewBinding.getRoot();
    }

    public void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(text, text);
            clipboard.setPrimaryClip(clip);
            if (Build.VERSION.SDK_INT <= 32) {
                EventBus.getDefault().post(new MessageEvent(getString(R.string.copied_to_clipboard)));
            }
        }
    }

    private void showOnDemandConfigBalloon(boolean offerStreaming) {
        final boolean isLocaleRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                == View.LAYOUT_DIRECTION_RTL;
        final Balloon balloon = new Balloon.Builder(getContext())
                .setArrowOrientation(ArrowOrientation.TOP)
                .setArrowOrientationRules(ArrowOrientationRules.ALIGN_FIXED)
                .setArrowPosition(0.25f + ((isLocaleRtl ^ offerStreaming) ? 0f : 0.5f))
                .setWidthRatio(1.0f)
                .setMarginLeft(8)
                .setMarginRight(8)
                .setBackgroundColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorSecondary))
                .setBalloonAnimation(BalloonAnimation.OVERSHOOT)
                .setLayout(R.layout.popup_bubble_view)
                .setDismissWhenTouchOutside(true)
                .setLifecycleOwner(this)
                .build();
        final Button positiveButton = balloon.getContentView().findViewById(R.id.balloon_button_positive);
        final Button negativeButton = balloon.getContentView().findViewById(R.id.balloon_button_negative);
        final TextView message = balloon.getContentView().findViewById(R.id.balloon_message);
        message.setText(offerStreaming
                ? R.string.on_demand_config_stream_text : R.string.on_demand_config_download_text);
        positiveButton.setOnClickListener(v1 -> {
            UserPreferences.setStreamOverDownload(offerStreaming);
            // Update all visible lists to reflect new streaming action button
            EventBus.getDefault().post(new UnreadItemsUpdateEvent());
            EventBus.getDefault().post(new MessageEvent(getString(R.string.on_demand_config_setting_changed)));
            balloon.dismiss();
        });
        negativeButton.setOnClickListener(v1 -> {
            UsageStatistics.doNotAskAgain(UsageStatistics.ACTION_STREAM); // Type does not matter. Both are silenced.
            balloon.dismiss();
        });
        balloon.showAlignBottom(viewBinding.butAction1, 0, (int) (-12 * getResources().getDisplayMetrics().density));
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        controller = new PlaybackController(getActivity()) {
            @Override
            public void loadMediaInfo() {
                // Do nothing
            }
        };
        controller.init();
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (itemsLoaded) {
            viewBinding.progbarLoading.setVisibility(View.GONE);
            updateAppearance();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        controller.release();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
        viewBinding.contentRoot.removeView(viewBinding.webvDescription);
        viewBinding.webvDescription.destroy();
        viewBinding = null;
    }

    private void onFragmentLoaded() {
        if (webviewData != null && !itemsLoaded) {
            viewBinding.webvDescription.loadDataWithBaseURL(
                    "https://127.0.0.1", webviewData, "text/html", "utf-8", "about:blank");
        }
        updateAppearance();
    }

    private void updateAppearance() {
        if (item == null) {
            Log.d(TAG, "updateAppearance item is null");
            return;
        }
        viewBinding.txtvPodcast.setText(item.getFeed().getTitle());
        viewBinding.txtvTitle.setText(item.getTitle());
        if (item.getPubDate() != null) {
            String pubDateStr = DateFormatter.formatAbbrev(getActivity(), item.getPubDate());
            viewBinding.txtvPublished.setText(pubDateStr);
            viewBinding.txtvPublished.setContentDescription(DateFormatter.formatForAccessibility(item.getPubDate()));
        }
        if (item.getFeed().getState() == Feed.STATE_NOT_SUBSCRIBED) {
            viewBinding.nonSubscribedWarningLabel.setVisibility(View.VISIBLE);
            viewBinding.nonSubscribedWarningLabel.setOnClickListener(v -> openPodcast());
        }
        float radius = 8 * getResources().getDisplayMetrics().density;
        RequestOptions options = new RequestOptions()
                .error(ImagePlaceholder.getDrawable(getContext(), radius))
                .transform(new FitCenter(),
                        new RoundedCorners((int) radius))
                .dontAnimate();
        Glide.with(this)
                .load(item.getImageLocation())
                .error(Glide.with(this)
                        .load(ImageResourceUtils.getFallbackImageLocation(item))
                        .apply(options))
                .apply(options)
                .into(viewBinding.imgvCover);
        updateButtons();
        updateAdSegmentsSummary();
    }

    private void updateButtons() {
        viewBinding.circularProgressBar.setVisibility(View.GONE);
        if (item.hasMedia()) {
            if (DownloadServiceInterface.get().isDownloadingEpisode(item.getMedia().getDownloadUrl())) {
                viewBinding.circularProgressBar.setVisibility(View.VISIBLE);
                viewBinding.circularProgressBar.setPercentage(0.01f * Math.max(1,
                        DownloadServiceInterface.get().getProgress(item.getMedia().getDownloadUrl())), item);
                viewBinding.circularProgressBar.setIndeterminate(
                        DownloadServiceInterface.get().isEpisodeQueued(item.getMedia().getDownloadUrl()));
            }
        }
        FeedMedia media = item.getMedia();
        if (media == null) {
            actionButton1 = new MarkAsPlayedActionButton(item);
            actionButton2 = new VisitWebsiteActionButton(item);
            actionButtonTranscribe = null;
            actionButtonAd = null;
            viewBinding.noMediaLabel.setVisibility(View.VISIBLE);
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            viewBinding.aiButtonsRow.setVisibility(View.GONE);
        } else {
            viewBinding.noMediaLabel.setVisibility(View.GONE);
            if (media.getDuration() > 0) {
                viewBinding.txtvDuration.setText(Converter.getDurationStringLong(media.getDuration()));
                viewBinding.txtvDuration.setContentDescription(
                        Converter.getDurationStringLocalized(getContext(), media.getDuration()));
            }
            if (PlaybackStatus.isCurrentlyPlaying(media)) {
                actionButton1 = new PauseActionButton(item);
            } else if (item.getFeed().isLocalFeed()) {
                actionButton1 = new PlayLocalActionButton(item);
            } else if (media.isDownloaded()) {
                actionButton1 = new PlayActionButton(item);
            } else {
                actionButton1 = new StreamActionButton(item);
            }
            if (DownloadServiceInterface.get().isDownloadingEpisode(media.getDownloadUrl())) {
                actionButton2 = new CancelDownloadActionButton(item);
            } else if (!media.isDownloaded()) {
                actionButton2 = new DownloadActionButton(item);
            } else {
                actionButton2 = new DeleteActionButton(item);
            }
            // Transcribe button: enabled only if episode is downloaded
            if (media.isDownloaded() && isAdAnalysisSupported()) {
                actionButtonTranscribe = new TranscribeActionButton(item);
                // Ad analysis button: enabled if downloaded AND has transcript
                if (hasExistingTranscript(media)) {
                    actionButtonAd = new AnalyzeAdsActionButton(item);
                } else {
                    actionButtonAd = null;
                }
                viewBinding.aiButtonsRow.setVisibility(View.VISIBLE);
                viewBinding.adSegmentsContainer.setVisibility(View.VISIBLE);
            } else {
                actionButtonTranscribe = null;
                actionButtonAd = null;
                viewBinding.adSegmentsContainer.setVisibility(View.GONE);
                viewBinding.aiButtonsRow.setVisibility(View.GONE);
            }
        }

        viewBinding.butAction1Text.setText(actionButton1.getLabel());
        viewBinding.butAction1Text.setTransformationMethod(null);
        viewBinding.butAction1Icon.setImageResource(actionButton1.getDrawable());
        viewBinding.butAction1.setVisibility(actionButton1.getVisibility());

        // Transcribe button
        if (actionButtonTranscribe != null) {
            if (isTranscriptionRunning) {
                viewBinding.butActionTranscribeText.setText(
                        TextUtils.isEmpty(transcriptionStageLabel)
                                ? getString(R.string.ad_analysis_transcribing)
                                : transcriptionStageLabel);
                viewBinding.circularProgressTranscribe.setVisibility(View.VISIBLE);
                viewBinding.circularProgressTranscribe.setIndeterminate(transcriptionPercent < 0);
                if (transcriptionPercent >= 0) {
                    viewBinding.circularProgressTranscribe.setPercentage(
                            Math.max(0.01f, transcriptionPercent / 100f), item);
                }
            } else if (hasExistingTranscript(item.getMedia())) {
                viewBinding.butActionTranscribeText.setText(R.string.transcription_again);
                viewBinding.circularProgressTranscribe.setVisibility(View.GONE);
            } else {
                viewBinding.butActionTranscribeText.setText(actionButtonTranscribe.getLabel());
                viewBinding.circularProgressTranscribe.setVisibility(View.GONE);
            }
            viewBinding.butActionTranscribeText.setTransformationMethod(null);
            viewBinding.butActionTranscribeIcon.setImageResource(actionButtonTranscribe.getDrawable());
            viewBinding.butActionTranscribeIcon.setVisibility(isTranscriptionRunning ? View.INVISIBLE : View.VISIBLE);
            viewBinding.butActionTranscribe.setVisibility(actionButtonTranscribe.getVisibility());
        } else {
            viewBinding.butActionTranscribe.setVisibility(View.GONE);
        }

        // Ad analysis button
        if (actionButtonAd != null) {
            if (isAdAnalysisRunning) {
                viewBinding.butActionAdText.setText(
                        TextUtils.isEmpty(adAnalysisStageLabel)
                                ? getString(R.string.ad_analysis_analyzing)
                                : adAnalysisStageLabel);
                viewBinding.circularProgressAd.setVisibility(View.VISIBLE);
                viewBinding.circularProgressAd.setIndeterminate(adAnalysisPercent < 0);
                if (adAnalysisPercent >= 0) {
                    viewBinding.circularProgressAd.setPercentage(
                            Math.max(0.01f, adAnalysisPercent / 100f), item);
                }
            } else if (AdSegmentStore.hasAnalysis(requireContext(), item.getId())) {
                viewBinding.butActionAdText.setText(R.string.ad_analysis_again);
                viewBinding.circularProgressAd.setVisibility(View.GONE);
            } else {
                viewBinding.butActionAdText.setText(actionButtonAd.getLabel());
                viewBinding.circularProgressAd.setVisibility(View.GONE);
            }
            viewBinding.butActionAdText.setTransformationMethod(null);
            viewBinding.butActionAdIcon.setImageResource(actionButtonAd.getDrawable());
            viewBinding.butActionAdIcon.setVisibility(isAdAnalysisRunning ? View.INVISIBLE : View.VISIBLE);
            viewBinding.butActionAd.setVisibility(actionButtonAd.getVisibility());
        } else {
            viewBinding.butActionAd.setVisibility(View.GONE);
        }

        viewBinding.butAction2Text.setText(actionButton2.getLabel());
        viewBinding.butAction2Text.setTransformationMethod(null);
        viewBinding.butAction2Icon.setImageResource(actionButton2.getDrawable());
        viewBinding.butAction2.setVisibility(actionButton2.getVisibility());
    }

    private boolean hasExistingTranscript(FeedMedia media) {
        if (media == null) {
            return false;
        }
        String transcriptFileUrl = media.getTranscriptFileUrl();
        if (TextUtils.isEmpty(transcriptFileUrl)) {
            return false;
        }
        File transcriptFile = new File(transcriptFileUrl);
        return transcriptFile.exists() && transcriptFile.length() > 0;
    }

    private void updateAdSegmentsSummary() {
        if (!isAdAnalysisSupported()) {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            return;
        }
        if (item == null || item.getMedia() == null || !item.getMedia().isDownloaded()) {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            return;
        }
        if (!AdSegmentStore.hasAnalysis(requireContext(), item.getId())) {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            return;
        }
        FeedMedia media = item.getMedia();
        AdAnalysisResult result = AdSegmentStore.load(requireContext(), item.getId());
        if (result == null) {
            viewBinding.adSegmentsContent.setText(R.string.ad_segments_not_analyzed);
            viewBinding.adTranscriptContent.setText(loadTranscriptText(media, null));
            viewBinding.adSegmentsContainer.setVisibility(View.VISIBLE);
            selectAdTab(0);
            return;
        }
        if (result.getSegments().isEmpty()) {
            viewBinding.adSegmentsContent.setText(R.string.ad_segments_empty);
            viewBinding.adTranscriptContent.setText(loadTranscriptText(media, result));
            viewBinding.adSegmentsContainer.setVisibility(View.VISIBLE);
            selectAdTab(0);
            return;
        }
        long totalAdDurationMs = 0;
        for (AdSegment segment : result.getSegments()) {
            totalAdDurationMs += Math.max(0, (segment.getEndSeconds() - segment.getStartSeconds()) * 1000);
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String totalDurationString = Converter.getDurationStringLong((int) totalAdDurationMs);
        int totalStart = sb.length();
        sb.append(getString(R.string.ad_segments_total_length, totalDurationString));
        sb.setSpan(new StyleSpan(Typeface.BOLD), totalStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n");
        for (int i = 0; i < result.getSegments().size(); i++) {
            AdSegment seg = result.getSegments().get(i);
            if (i > 0) {
                sb.append("\n");
            }

            int startSpan = sb.length();
            sb.append(Converter.getDurationStringLong((int) (seg.getStartSeconds() * 1000)));
            sb.append(" - ");
            sb.append(Converter.getDurationStringLong((int) (seg.getEndSeconds() * 1000)));
            sb.setSpan(new StyleSpan(Typeface.BOLD), startSpan, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.isEmpty(seg.getReason())) {
                sb.append("\n");
                int reasonStart = sb.length();
                sb.append(seg.getReason());
                sb.setSpan(new ForegroundColorSpan(ThemeUtils.getColorFromAttr(requireContext(),
                                android.R.attr.textColorSecondary)), reasonStart, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        viewBinding.adSegmentsContent.setText(sb, TextView.BufferType.SPANNABLE);
        viewBinding.adSegmentsContainer.setVisibility(View.VISIBLE);
        viewBinding.adTranscriptContent.setText(loadTranscriptText(media, result));
        selectAdTab(0);
    }

    private void setupAdTabs() {
        TabLayout tabs = viewBinding.adTabLayout;
        tabs.removeAllTabs();
        tabs.addTab(tabs.newTab().setText(R.string.ad_segments_tab_ads));
        tabs.addTab(tabs.newTab().setText(R.string.ad_segments_tab_transcript));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                viewBinding.adSegmentsContent.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                viewBinding.adTranscriptContent.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        selectAdTab(0);
    }

    private void selectAdTab(int index) {
        TabLayout tabs = viewBinding.adTabLayout;
        if (tabs.getTabCount() > index) {
            TabLayout.Tab tab = tabs.getTabAt(index);
            if (tab != null) {
                tab.select();
            }
        }
        viewBinding.adSegmentsContent.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewBinding.adTranscriptContent.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
    }

    private String loadTranscriptText(@Nullable FeedMedia media, @Nullable AdAnalysisResult result) {
        if (result != null && !TextUtils.isEmpty(result.getTranscript())) {
            return result.getTranscript();
        }
        if (media == null || TextUtils.isEmpty(media.getTranscriptFileUrl())) {
            return getString(R.string.ad_segments_not_analyzed);
        }
        try {
            File transcriptFile = new File(media.getTranscriptFileUrl());
            if (transcriptFile.exists()) {
                return FileUtils.readFileToString(transcriptFile, (String) null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load transcript text", e);
        }
        return getString(R.string.ad_segments_not_analyzed);
    }

    private void observeTranscriptionWork(long feedItemId) {
        if (!isAdAnalysisSupported()) {
            return;
        }
        String tag = "transcription-" + feedItemId;
        if (transcriptionWorkLiveData != null) {
            transcriptionWorkLiveData.removeObservers(getViewLifecycleOwner());
        }
        transcriptionWorkLiveData = WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData(tag);
        transcriptionWorkLiveData.observe(getViewLifecycleOwner(), this::updateTranscriptionProgress);
    }

    private void cancelTranscriptionWork(long feedItemId) {
        String tag = "transcription-" + feedItemId;
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(tag);
    }

    private void updateTranscriptionProgress(List<WorkInfo> workInfos) {
        if (!isAdAnalysisSupported()) {
            isTranscriptionRunning = false;
            transcriptionStageLabel = null;
            transcriptionPercent = -1;
            viewBinding.circularProgressTranscribe.setVisibility(View.GONE);
            viewBinding.butActionTranscribe.setVisibility(View.GONE);
            return;
        }
        if (workInfos == null || workInfos.isEmpty()) {
            isTranscriptionRunning = false;
            transcriptionStageLabel = null;
            transcriptionPercent = -1;
            updateButtons();
            return;
        }
        for (WorkInfo info : workInfos) {
            if (info.getState() == WorkInfo.State.RUNNING) {
                String stage = info.getProgress().getString("transcription_progress_stage");
                int percent = info.getProgress().getInt("transcription_progress_percent", -1);
                showTranscriptionProgress(stage, percent);
                return;
            }
            if (info.getState() == WorkInfo.State.ENQUEUED) {
                showTranscriptionProgress("transcribing", -1);
                return;
            }
            if (info.getState().isFinished()) {
                isTranscriptionRunning = false;
                transcriptionStageLabel = null;
                transcriptionPercent = -1;
                updateButtons();
                updateAdSegmentsSummary();
                return;
            }
        }
        // No running/enqueued work -> reset
        isTranscriptionRunning = false;
        transcriptionStageLabel = null;
        transcriptionPercent = -1;
        updateButtons();
        updateAdSegmentsSummary();
    }

    private void showTranscriptionProgress(String stage, int percent) {
        isTranscriptionRunning = true;
        transcriptionStageLabel = getString(R.string.ad_analysis_transcribing);
        transcriptionPercent = percent;
        viewBinding.circularProgressTranscribe.setVisibility(View.VISIBLE);
        viewBinding.circularProgressTranscribe.setIndeterminate(percent < 0);
        if (percent >= 0) {
            viewBinding.circularProgressTranscribe.setPercentage(Math.max(0.01f, percent / 100f), item);
        }
        viewBinding.butActionTranscribeText.setText(transcriptionStageLabel);
        viewBinding.butActionTranscribeText.setTransformationMethod(null);
        viewBinding.butActionTranscribeIcon.setVisibility(View.INVISIBLE);
    }

    private void observeAdAnalysisWork(long feedItemId) {
        if (!isAdAnalysisSupported()) {
            return;
        }
        String tag = "ad-analysis-" + feedItemId;
        if (adWorkLiveData != null) {
            adWorkLiveData.removeObservers(getViewLifecycleOwner());
        }
        adWorkLiveData = WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData(tag);
        adWorkLiveData.observe(getViewLifecycleOwner(), this::updateAdAnalysisProgress);
    }

    private void cancelAdAnalysisWork(long feedItemId) {
        String tag = "ad-analysis-" + feedItemId;
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(tag);
    }

    private void updateAdAnalysisProgress(List<WorkInfo> workInfos) {
        if (!isAdAnalysisSupported()) {
            isAdAnalysisRunning = false;
            adAnalysisStageLabel = null;
            adAnalysisPercent = -1;
            viewBinding.circularProgressAd.setVisibility(View.GONE);
            viewBinding.butActionAd.setVisibility(View.GONE);
            return;
        }
        if (workInfos == null || workInfos.isEmpty()) {
            isAdAnalysisRunning = false;
            adAnalysisStageLabel = null;
            adAnalysisPercent = -1;
            updateButtons();
            return;
        }
        for (WorkInfo info : workInfos) {
            if (info.getState() == WorkInfo.State.RUNNING) {
                String stage = info.getProgress().getString("analysis_progress_stage");
                int percent = info.getProgress().getInt("analysis_progress_percent", -1);
                showAdAnalysisProgress(stage, percent);
                return;
            }
            if (info.getState() == WorkInfo.State.ENQUEUED) {
                showAdAnalysisProgress("analyzing", -1);
                return;
            }
            if (info.getState().isFinished()) {
                isAdAnalysisRunning = false;
                adAnalysisStageLabel = null;
                adAnalysisPercent = -1;
                updateButtons();
                updateAdSegmentsSummary();
                return;
            }
        }
        // No running/enqueued work -> reset
        isAdAnalysisRunning = false;
        adAnalysisStageLabel = null;
        adAnalysisPercent = -1;
        updateButtons();
        updateAdSegmentsSummary();
    }

    private void showAdAnalysisProgress(String stage, int percent) {
        isAdAnalysisRunning = true;
        adAnalysisStageLabel = mapStageLabel(stage);
        adAnalysisPercent = percent;
        viewBinding.circularProgressAd.setVisibility(View.VISIBLE);
        viewBinding.circularProgressAd.setIndeterminate(percent < 0);
        if (percent >= 0) {
            viewBinding.circularProgressAd.setPercentage(Math.max(0.01f, percent / 100f), item);
        }
        viewBinding.butActionAdText.setText(adAnalysisStageLabel);
        viewBinding.butActionAdText.setTransformationMethod(null);
        viewBinding.butActionAdIcon.setVisibility(View.INVISIBLE);
    }

    private String mapStageLabel(String stage) {
        if ("analyzing".equalsIgnoreCase(stage)) {
            return getString(R.string.ad_analysis_analyzing);
        }
        return getString(R.string.ad_analysis_transcribing);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return viewBinding.webvDescription.onContextItemSelected(item);
    }

    private void openPodcast() {
        if (item == null) {
            return;
        }
        if (item.getFeed().getState() == Feed.STATE_NOT_SUBSCRIBED) {
            startActivity(new OnlineFeedviewActivityStarter(getContext(), item.getFeed().getDownloadUrl())
                    .getIntent());
        } else {
            Fragment fragment = FeedItemlistFragment.newInstance(item.getFeedId());
            ((MainActivity) getActivity()).loadChildFragment(fragment);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedItemEvent event) {
        Log.d(TAG, "onEventMainThread() called with: " + "event = [" + event + "]");
        for (FeedItem item : event.items) {
            if (this.item.getId() == item.getId()) {
                load();
                return;
            }
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(EpisodeDownloadEvent event) {
        if (item == null || item.getMedia() == null) {
            return;
        }
        if (!event.getUrls().contains(item.getMedia().getDownloadUrl())) {
            return;
        }
        if (itemsLoaded && getActivity() != null) {
            updateButtons();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        updateButtons();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUnreadItemsChanged(UnreadItemsUpdateEvent event) {
        load();
    }

    private void load() {
        if (disposable != null) {
            disposable.dispose();
        }
        if (!itemsLoaded) {
            viewBinding.progbarLoading.setVisibility(View.VISIBLE);
        }
        disposable = Observable.fromCallable(this::loadInBackground)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    viewBinding.progbarLoading.setVisibility(View.GONE);
                    viewBinding.header.setVisibility(View.VISIBLE);
                    item = result;
                    onFragmentLoaded();
                    if (isAdAnalysisSupported()) {
                        observeTranscriptionWork(item.getId());
                        observeAdAnalysisWork(item.getId());
                    }
                    itemsLoaded = true;
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    @Nullable
    private FeedItem loadInBackground() {
        FeedItem feedItem = DBReader.getFeedItem(itemId);
        Context context = getContext();
        if (feedItem != null && context != null) {
            int duration = feedItem.getMedia() != null ? feedItem.getMedia().getDuration() : Integer.MAX_VALUE;
            DBReader.loadDescriptionOfFeedItem(feedItem);
            ShownotesCleaner t = new ShownotesCleaner(context, feedItem.getDescription(), duration);
            webviewData = t.processShownotes();
        }
        return feedItem;
    }

}
