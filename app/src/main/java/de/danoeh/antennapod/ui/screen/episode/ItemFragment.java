package de.danoeh.antennapod.ui.screen.episode;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.tabs.TabLayout;
import com.skydoves.balloon.ArrowOrientation;
import com.skydoves.balloon.ArrowOrientationRules;
import com.skydoves.balloon.Balloon;
import com.skydoves.balloon.BalloonAnimation;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.CancelDownloadActionButton;
import de.danoeh.antennapod.actionbutton.CompleteAnalysisActionButton;
import de.danoeh.antennapod.actionbutton.DeleteActionButton;
import de.danoeh.antennapod.actionbutton.DownloadActionButton;
import de.danoeh.antennapod.actionbutton.ItemActionButton;
import de.danoeh.antennapod.actionbutton.MarkAsPlayedActionButton;
import de.danoeh.antennapod.actionbutton.PauseActionButton;
import de.danoeh.antennapod.actionbutton.PlayActionButton;
import de.danoeh.antennapod.actionbutton.PlayLocalActionButton;
import de.danoeh.antennapod.actionbutton.StreamActionButton;
import de.danoeh.antennapod.actionbutton.VisitWebsiteActionButton;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.databinding.FeeditemFragmentBinding;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.UnreadItemsUpdateEvent;
import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackStatus;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UsageStatistics;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
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
    private ItemActionButton actionButtonAnalysis;
    private Disposable disposable;
    private PlaybackController controller;
    private FeeditemFragmentBinding viewBinding;
    private LiveData<List<WorkInfo>> analysisWorkLiveData;
    private boolean isAnalysisRunning = false;
    private boolean pendingEnqueue = false;
    private String analysisStageLabel = null;
    private int analysisPercent = -1;

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
        viewBinding.butActionComplete.setOnClickListener(v -> {
            if (actionButtonAnalysis == null || item == null) {
                return;
            }
            if (isAnalysisRunning) {
                cancelAnalysisWork(item.getId());
                isAnalysisRunning = false;
                analysisStageLabel = null;
                analysisPercent = -1;
                viewBinding.circularProgressComplete.setVisibility(View.GONE);
                updateButtons();
                return;
            }
            actionButtonAnalysis.onClick(getContext());
            // Immediately reflect "In queue" state without waiting for LiveData
            pendingEnqueue = true;
            isAnalysisRunning = true;
            analysisStageLabel = getString(R.string.ad_analysis_in_queue);
            analysisPercent = -1;
            updateButtons();
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
        if (isAdAnalysisSupported() && UserPreferences.isAdSkipEnabled()) {
            setupAdTabs();
            if (item != null) {
                observeAnalysisWork(item.getId());
            }
            // Development: Long-press transcript to share
            viewBinding.adTranscriptContent.setOnLongClickListener(v -> {
                CharSequence transcriptText = viewBinding.adTranscriptContent.getText();
                if (transcriptText != null && transcriptText.length() > 0) {
                    shareTranscript(transcriptText.toString());
                    return true;
                }
                return false;
            });
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

    private void shareTranscript(String transcript) {
        try {
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, transcript);
            if (item != null) {
                shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                        "Transcript: " + item.getTitle());
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "Share Transcript"));
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Not enough memory to share transcript", e);
            EventBus.getDefault().post(new MessageEvent("Transcript too large to share"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to share transcript", e);
            EventBus.getDefault().post(new MessageEvent("Failed to share transcript"));
        }
    }

    private void showOnDemandConfigBalloon(boolean offerStreaming) {
        final boolean isLocaleRtl = TextUtils
                .getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL;
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
                ? R.string.on_demand_config_stream_text
                : R.string.on_demand_config_download_text);
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
            actionButtonAnalysis = null;
            viewBinding.noMediaLabel.setVisibility(View.VISIBLE);
            viewBinding.txtvDuration.setVisibility(View.GONE);
            viewBinding.separatorIcons.setVisibility(View.GONE);
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            viewBinding.aiButtonsRow.setVisibility(View.GONE);
        } else {
            viewBinding.noMediaLabel.setVisibility(View.GONE);
            boolean hasDuration = media.getDuration() > 0;
            viewBinding.txtvDuration.setVisibility(hasDuration ? View.VISIBLE : View.GONE);
            viewBinding.separatorIcons.setVisibility(hasDuration ? View.VISIBLE : View.GONE);
            if (hasDuration) {
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
            // Ad analysis button: enabled only if episode is downloaded and AI analysis is
            // enabled
            if (media.isDownloaded() && isAdAnalysisSupported() && UserPreferences.isAdSkipEnabled()) {
                actionButtonAnalysis = new CompleteAnalysisActionButton(item);
                viewBinding.aiButtonsRow.setVisibility(View.VISIBLE);
                viewBinding.adSegmentsContainer.setVisibility(View.VISIBLE);
            } else {
                actionButtonAnalysis = null;
                viewBinding.adSegmentsContainer.setVisibility(View.GONE);
                viewBinding.aiButtonsRow.setVisibility(View.GONE);
            }
        }

        viewBinding.butAction1Text.setText(actionButton1.getLabel());
        viewBinding.butAction1Text.setTransformationMethod(null);
        viewBinding.butAction1Icon.setImageResource(actionButton1.getDrawable());
        viewBinding.butAction1.setVisibility(actionButton1.getVisibility());

        // Ad analysis button
        if (actionButtonAnalysis != null) {
            if (isAnalysisRunning) {
                viewBinding.butActionCompleteText.setText(
                        TextUtils.isEmpty(analysisStageLabel)
                                ? getString(R.string.ad_analysis_transcribing)
                                : analysisStageLabel);
                viewBinding.circularProgressComplete.setVisibility(View.VISIBLE);
                viewBinding.circularProgressComplete.setIndeterminate(analysisPercent < 0);
                if (analysisPercent >= 0) {
                    viewBinding.circularProgressComplete.setPercentage(
                            Math.max(0.01f, analysisPercent / 100f), item);
                }
            } else if (AdSegmentStore.hasAnalysis(requireContext(), item.getId())) {
                viewBinding.butActionCompleteText.setText(R.string.ad_analysis_again);
                viewBinding.circularProgressComplete.setVisibility(View.GONE);
            } else {
                viewBinding.butActionCompleteText.setText(actionButtonAnalysis.getLabel());
                viewBinding.circularProgressComplete.setVisibility(View.GONE);
            }
            viewBinding.butActionCompleteText.setTransformationMethod(null);
            viewBinding.butActionCompleteIcon.setImageResource(actionButtonAnalysis.getDrawable());
            viewBinding.butActionCompleteIcon.setVisibility(isAnalysisRunning ? View.INVISIBLE : View.VISIBLE);
            viewBinding.aiButtonsRow.setVisibility(View.VISIBLE);
        } else {
            viewBinding.aiButtonsRow.setVisibility(View.GONE);
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
        if (!isAdAnalysisSupported() || !UserPreferences.isAdSkipEnabled()) {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            return;
        }
        if (item == null || item.getMedia() == null || !item.getMedia().isDownloaded()) {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            return;
        }
        boolean hasAnalysis = AdSegmentStore.hasAnalysis(requireContext(), item.getId());
        FeedMedia media = item.getMedia();
        boolean hasTranscript = hasExistingTranscript(media);

        if (!hasAnalysis && !hasTranscript) {
            viewBinding.adSegmentsContainer.setVisibility(View.GONE);
            return;
        }

        // Show container
        viewBinding.adSegmentsContainer.setVisibility(View.VISIBLE);

        AdAnalysisResult result = hasAnalysis ? AdSegmentStore.load(requireContext(), item.getId()) : null;

        if (result == null) {
            // No analysis, but we have transcript (checked above)
            viewBinding.adSegmentsContent.setText(R.string.ad_segments_not_analyzed);
            viewBinding.adTranscriptContent.setText(loadTranscriptText(media, null));
            // Default to transcript tab if no ad analysis
            selectAdTab(1);
            return;
        }

        if (result.getSegments().isEmpty()) {
            viewBinding.adSegmentsContent.setText(R.string.ad_segments_empty);
            viewBinding.adTranscriptContent.setText(loadTranscriptText(media, result));
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
            Log.d(TAG, "Loading transcript from " + transcriptFile.getAbsolutePath() + ", exists="
                    + transcriptFile.exists() + ", length=" + transcriptFile.length());
            if (transcriptFile.exists()) {
                String content;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    content = new String(Files.readAllBytes(transcriptFile.toPath()), StandardCharsets.UTF_8);
                } else {
                    content = org.apache.commons.io.FileUtils.readFileToString(transcriptFile, StandardCharsets.UTF_8);
                }
                Log.d(TAG, "Read transcript content length=" + content.length());
                if (content.length() > 0) {
                    Log.d(TAG, "First 100 chars: " + content.substring(0, Math.min(content.length(), 100)));
                    return content;
                } else {
                    return "Transcript file exists but is empty.";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load transcript text", e);
            return "Failed to load transcript: " + e.getMessage();
        }
        return getString(R.string.ad_segments_not_analyzed);
    }

    private void observeAnalysisWork(long feedItemId) {
        if (!isAdAnalysisSupported()) {
            return;
        }
        String tag = "ad-analysis-" + feedItemId;
        if (analysisWorkLiveData != null) {
            analysisWorkLiveData.removeObservers(getViewLifecycleOwner());
        }
        analysisWorkLiveData = WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData(tag);
        analysisWorkLiveData.observe(getViewLifecycleOwner(), this::updateAnalysisProgress);
    }

    private void cancelAnalysisWork(long feedItemId) {
        String tag = "ad-analysis-" + feedItemId;
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(tag);
    }

    private void updateAnalysisProgress(List<WorkInfo> workInfos) {
        if (!isAdAnalysisSupported() || !UserPreferences.isAdSkipEnabled()) {
            isAnalysisRunning = false;
            analysisStageLabel = null;
            analysisPercent = -1;
            viewBinding.circularProgressComplete.setVisibility(View.GONE);
            viewBinding.aiButtonsRow.setVisibility(View.GONE);
            return;
        }
        if (workInfos == null || workInfos.isEmpty()) {
            isAnalysisRunning = false;
            analysisStageLabel = null;
            analysisPercent = -1;
            updateButtons();
            return;
        }
        // Two-pass scan: prioritize active work over old finished work.
        // When re-running analysis, old finished WorkInfos with the same tag
        // remain in the list alongside the new ENQUEUED/RUNNING entry.
        WorkInfo runningInfo = null;
        WorkInfo enqueuedInfo = null;
        boolean hasFinished = false;
        for (WorkInfo info : workInfos) {
            if (info.getState() == WorkInfo.State.RUNNING) {
                runningInfo = info;
                break; // RUNNING is highest priority
            }
            if (info.getState() == WorkInfo.State.ENQUEUED) {
                enqueuedInfo = info;
            }
            if (info.getState().isFinished()) {
                hasFinished = true;
            }
        }
        if (runningInfo != null) {
            pendingEnqueue = false;
            String stage = runningInfo.getProgress().getString("ad_analysis_progress_stage");
            int percent = runningInfo.getProgress().getInt("ad_analysis_progress_percent", -1);
            int chunksDone = runningInfo.getProgress().getInt("ad_analysis_chunks_done", 0);
            int chunksTotal = runningInfo.getProgress().getInt("ad_analysis_chunks_total", 0);
            showAnalysisProgress(stage, percent, chunksDone, chunksTotal);
            return;
        }
        if (enqueuedInfo != null) {
            pendingEnqueue = false;
            showAnalysisProgress("queued", -1, 0, 0);
            return;
        }
        // If we just enqueued but LiveData hasn't caught up yet, keep the optimistic
        // state
        if (pendingEnqueue) {
            return;
        }
        // All work is finished (or cancelled) — reset
        isAnalysisRunning = false;
        analysisStageLabel = null;
        analysisPercent = -1;
        updateButtons();
        if (hasFinished) {
            updateAdSegmentsSummary();
        }
    }

    private void showAnalysisProgress(String stage, int percent, int chunksDone, int chunksTotal) {
        isAnalysisRunning = true;
        String baseLabel = mapStageLabel(stage);
        // Add chunk information if chunks are available
        if (chunksTotal > 0 && ("transcribing".equalsIgnoreCase(stage) || "analyzing".equalsIgnoreCase(stage))) {
            analysisStageLabel = baseLabel + " (" + chunksDone + "/" + chunksTotal + ")";
        } else {
            analysisStageLabel = baseLabel;
        }
        analysisPercent = percent;
        viewBinding.circularProgressComplete.setVisibility(View.VISIBLE);
        viewBinding.circularProgressComplete.setIndeterminate(percent < 0);
        if (percent >= 0) {
            viewBinding.circularProgressComplete.setPercentage(Math.max(0.01f, percent / 100f), item);
        }
        viewBinding.butActionCompleteText.setText(analysisStageLabel);
        viewBinding.butActionCompleteText.setTransformationMethod(null);
        viewBinding.butActionCompleteIcon.setVisibility(View.INVISIBLE);
    }

    private String mapStageLabel(String stage) {
        if ("queued".equalsIgnoreCase(stage)) {
            return getString(R.string.ad_analysis_in_queue);
        }
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
                    if (isAdAnalysisSupported() && UserPreferences.isAdSkipEnabled()) {
                        observeAnalysisWork(item.getId());
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
