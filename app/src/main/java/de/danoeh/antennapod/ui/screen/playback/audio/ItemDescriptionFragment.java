package de.danoeh.antennapod.ui.screen.playback.audio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.model.ad.AdAnalysisResult;
import de.danoeh.antennapod.model.ad.AdSegment;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.cleaner.ShownotesCleaner;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.ui.view.ShownotesWebView;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Displays the description of a Playable object in a Webview.
 */
public class ItemDescriptionFragment extends Fragment {
    private static final String TAG = "ItemDescriptionFragment";

    private static final String PREF = "ItemDescriptionFragmentPrefs";
    private static final String PREF_SCROLL_Y = "prefScrollY";
    private static final String PREF_PLAYABLE_ID = "prefPlayableId";

    private ShownotesWebView webvDescription;
    private NestedScrollView scrollView;
    private View adSegmentsContainer;
    private TextView adSegmentsContent;
    private boolean showingAdSegments;
    private Disposable webViewLoader;
    private String loadedData = "";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "Creating view");
        View root = inflater.inflate(R.layout.item_description_fragment, container, false);
        webvDescription = root.findViewById(R.id.webview);
        scrollView = root.findViewById(R.id.scrollview);
        adSegmentsContainer = root.findViewById(R.id.adSegmentsContainer);
        adSegmentsContent = root.findViewById(R.id.adSegmentsContent);
        webvDescription.setTimecodeSelectedListener(time -> {
            if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                PlaybackController.bindToMedia3Service(getActivity(), controller ->
                        controller.seekTo(time));
            } else {
                PlaybackController.bindToService(getActivity(), playbackService ->
                        playbackService.seekTo(time));
            }
        });
        webvDescription.setPageFinishedListener(() -> {
            // Restoring the scroll position might not always work
            scrollView.postDelayed(() -> {
                if (showingAdSegments) {
                    scrollToAdSegments();
                } else {
                    restoreFromPreference();
                }
            }, 50);
        });

        registerForContextMenu(webvDescription);
        return root;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Fragment destroyed");
        if (webvDescription != null) {
            webvDescription.removeAllViews();
            webvDescription.destroy();
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return webvDescription.onContextItemSelected(item);
    }

    private void load() {
        Log.d(TAG, "load()");
        if (webViewLoader != null) {
            webViewLoader.dispose();
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        webViewLoader = Maybe.<Pair<String, AdAnalysisResult>>create(emitter -> {
            Playable media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
            if (media == null) {
                emitter.onComplete();
                return;
            }
            if (media instanceof FeedMedia) {
                FeedMedia feedMedia = ((FeedMedia) media);
                if (feedMedia.getItem() == null) {
                    feedMedia.setItem(DBReader.getFeedItem(feedMedia.getItemId()));
                }
                DBReader.loadDescriptionOfFeedItem(feedMedia.getItem());
            }
            ShownotesCleaner shownotesCleaner = new ShownotesCleaner(
                    context, media.getDescription(), media.getDuration());
            AdAnalysisResult result = UserPreferences.isAdSkipEnabled() && media instanceof FeedMedia
                    ? AdSegmentStore.load(context, ((FeedMedia) media).getItemId()) : null;
            emitter.onSuccess(new Pair<>(shownotesCleaner.processShownotes(), result));
        })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(data -> {
                    updateAdSegments(data.second);
                    if (TextUtils.equals(loadedData, data.first)) {
                        return;
                    }
                    loadedData = data.first;
                    webvDescription.loadDataWithBaseURL("https://127.0.0.1", data.first, "text/html",
                            "utf-8", "about:blank");
                    Log.d(TAG, "Webview loaded");
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void updateAdSegments(AdAnalysisResult result) {
        adSegmentsContainer.setVisibility(UserPreferences.isAdSkipEnabled() ? View.VISIBLE : View.GONE);
        if (result == null) {
            adSegmentsContent.setText(R.string.ad_segments_not_analyzed);
        } else if (!TextUtils.isEmpty(result.getError())) {
            adSegmentsContent.setText(result.getError());
        } else if (result.getSegments().isEmpty()) {
            adSegmentsContent.setText(R.string.ad_segments_empty);
        } else {
            long totalDuration = 0;
            for (AdSegment segment : result.getSegments()) {
                totalDuration += (long) Math.max(0,
                        (segment.getEndSeconds() - segment.getStartSeconds()) * 1000);
            }
            SpannableStringBuilder summary = new SpannableStringBuilder();
            summary.append(getString(R.string.ad_segments_total_length,
                    Converter.getDurationStringLong((int) totalDuration)));
            summary.setSpan(new StyleSpan(Typeface.BOLD), 0, summary.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (AdSegment segment : result.getSegments()) {
                summary.append("\n\n");
                int start = summary.length();
                summary.append(Converter.getDurationStringLong((int) (segment.getStartSeconds() * 1000)));
                summary.append(" - ");
                summary.append(Converter.getDurationStringLong((int) (segment.getEndSeconds() * 1000)));
                summary.setSpan(new StyleSpan(Typeface.BOLD), start, summary.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!TextUtils.isEmpty(segment.getReason())) {
                    summary.append("\n").append(segment.getReason());
                }
            }
            adSegmentsContent.setText(summary);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        savePreference();
    }

    private void savePreference() {
        Log.d(TAG, "Saving preferences");
        SharedPreferences prefs = getActivity().getSharedPreferences(PREF, Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (scrollView != null) {
            Log.d(TAG, "Saving scroll position: " + scrollView.getScrollY());
            editor.putInt(PREF_SCROLL_Y, scrollView.getScrollY());
            editor.putString(PREF_PLAYABLE_ID, "" + PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
        } else {
            Log.d(TAG, "savePreferences was called while webview was null");
            editor.putInt(PREF_SCROLL_Y, -1);
            editor.putString(PREF_PLAYABLE_ID, "");
        }
        editor.apply();
    }

    private void restoreFromPreference() {
        Log.d(TAG, "Restoring from preferences");
        Activity activity = getActivity();
        if (activity != null) {
            SharedPreferences prefs = activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE);
            String id = prefs.getString(PREF_PLAYABLE_ID, "");
            int scrollY = prefs.getInt(PREF_SCROLL_Y, -1);
            if (scrollY != -1
                    && id.equals("" + PlaybackPreferences.getCurrentlyPlayingFeedMediaId())
                    && scrollView != null) {
                Log.d(TAG, "Restored scroll Position: " + scrollY);
                scrollView.scrollTo(scrollView.getScrollX(), scrollY);
            }
        }
    }

    public void scrollToTop() {
        showingAdSegments = false;
        scrollView.scrollTo(0, 0);
        savePreference();
    }

    public void scrollToAdSegments() {
        showingAdSegments = true;
        scrollView.post(() -> {
            if (showingAdSegments) {
                scrollView.scrollTo(0, adSegmentsContainer.getTop());
                savePreference();
            }
        });
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

        if (webViewLoader != null) {
            webViewLoader.dispose();
        }
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onItemsUpdate(FeedItemEvent event) {
        load();
    }
}
