package de.danoeh.antennapod.actionbutton;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.ai.service.ad.AdAnalysisWorkScheduler;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;

/**
 * Action button to run ad analysis on an existing transcript.
 * Requires that the episode is downloaded and has been transcribed.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class AnalyzeAdsActionButton extends ItemActionButton {

    public AnalyzeAdsActionButton(FeedItem item) {
        super(item);
    }

    @Override
    @StringRes
    public int getLabel() {
        return R.string.action_analyze_ads;
    }

    @Override
    @DrawableRes
    public int getDrawable() {
        return R.drawable.ic_ad_analysis;
    }

    @Override
    public int getVisibility() {
        return item.getMedia() == null ? View.GONE : View.VISIBLE;
    }

    @Override
    public void onClick(Context context) {
        FeedMedia media = item.getMedia();
        if (media == null) {
            return;
        }
        if (TextUtils.isEmpty(media.getLocalFileUrl()) || !new File(media.getLocalFileUrl()).exists()) {
            Toast.makeText(context, R.string.ad_analysis_requires_download, Toast.LENGTH_LONG).show();
            return;
        }
        // Check for existing transcript - required for analysis
        if (!hasExistingTranscript(media)) {
            Toast.makeText(context, R.string.ad_analysis_requires_transcript, Toast.LENGTH_LONG).show();
            return;
        }
        // Check API key - required for cloud-based analysis
        if (TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            Toast.makeText(context, R.string.ad_analysis_missing_key, Toast.LENGTH_LONG).show();
            return;
        }
        if (AdSegmentStore.hasAnalysis(context, item.getId())) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.ad_analysis_overwrite_title)
                    .setMessage(R.string.ad_analysis_overwrite_message)
                    .setPositiveButton(R.string.ad_analysis_overwrite_confirm,
                            (d, w) -> runAnalysis(context, media))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        runAnalysis(context, media);
    }

    private boolean hasExistingTranscript(FeedMedia media) {
        String transcriptFileUrl = media.getTranscriptFileUrl();
        if (TextUtils.isEmpty(transcriptFileUrl)) {
            return false;
        }
        File transcriptFile = new File(transcriptFileUrl);
        return transcriptFile.exists() && transcriptFile.length() > 0;
    }

    private void runAnalysis(Context context, FeedMedia media) {
        AdAnalysisWorkScheduler.enqueueManual(context, media);
        Toast.makeText(context, R.string.ad_analysis_requested, Toast.LENGTH_SHORT).show();
    }
}
