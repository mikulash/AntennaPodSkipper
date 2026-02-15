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
import de.danoeh.antennapod.net.ai.service.ad.vosk.VoskTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;

/**
 * Action button that performs complete ad analysis: transcription followed by
 * ad detection.
 * Uses a queue so that only one episode is processed at a time.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class CompleteAnalysisActionButton extends ItemActionButton {

    public CompleteAnalysisActionButton(FeedItem item) {
        super(item);
    }

    @Override
    @StringRes
    public int getLabel() {
        return R.string.action_complete_analysis;
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
            Toast.makeText(context, R.string.transcription_requires_download, Toast.LENGTH_LONG).show();
            return;
        }

        // Check if feed has a cloud model override
        String feedModelOverride = null;
        if (item.getFeed() != null && item.getFeed().getPreferences() != null) {
            feedModelOverride = item.getFeed().getPreferences().getTranscriptionModel();
        }
        boolean feedUsesCloudModel = feedModelOverride != null && feedModelOverride.startsWith("cloud:");

        // If feed uses cloud model, check for API key first
        if (feedUsesCloudModel && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            showApiKeyMissingDialog(context);
            return;
        }

        // Check local model availability (only if not using feed cloud override)
        if (!feedUsesCloudModel && LocalAiPreferences.isLocalTranscriptionEnabled(context)) {
            String model = LocalAiPreferences.getLocalTranscriptionModel(context);
            if (!new VoskTranscriptionManager(context).isModelDownloaded(model)) {
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.ad_analysis_model_missing_title)
                        .setMessage(R.string.ad_analysis_model_missing_message)
                        .setPositiveButton(R.string.action_download_model, (d, w) -> {
                            Intent intent = new Intent(context, PreferenceActivity.class);
                            intent.putExtra(PreferenceActivity.OPEN_AI_SETTINGS, true);
                            context.startActivity(intent);
                        })
                        .setNeutralButton(R.string.action_use_cloud, (d, w) -> {
                            LocalAiPreferences.setLocalTranscriptionEnabled(context, false);
                            enqueueAnalysis(context, media);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }

        // Check API key for analysis (always required for ad analysis)
        if (TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
            showApiKeyMissingDialog(context);
            return;
        }

        enqueueAnalysis(context, media);
    }

    private void enqueueAnalysis(Context context, FeedMedia media) {
        AdAnalysisWorkScheduler.enqueueManual(context, media);
        Toast.makeText(context, R.string.ad_analysis_queued, Toast.LENGTH_SHORT).show();
    }

    private void showApiKeyMissingDialog(Context context) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.transcription_api_key_missing_title)
                .setMessage(R.string.transcription_api_key_missing_message)
                .setPositiveButton(R.string.open_settings, (d, w) -> {
                    Intent intent = new Intent(context, PreferenceActivity.class);
                    intent.putExtra(PreferenceActivity.OPEN_AI_SETTINGS, true);
                    context.startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
