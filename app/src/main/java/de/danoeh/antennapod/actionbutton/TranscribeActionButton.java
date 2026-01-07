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
import de.danoeh.antennapod.net.download.service.ad.TranscriptionWorkScheduler;
import de.danoeh.antennapod.net.download.service.ad.vosk.VoskTranscriptionManager;
import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;

@RequiresApi(api = Build.VERSION_CODES.O)
public class TranscribeActionButton extends ItemActionButton {

    public TranscribeActionButton(FeedItem item) {
        super(item);
    }

    @Override
    @StringRes
    public int getLabel() {
        return R.string.action_transcribe;
    }

    @Override
    @DrawableRes
    public int getDrawable() {
        return de.danoeh.antennapod.ui.common.R.drawable.transcript;
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
                            runTranscription(context, media);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }

        // Check API key for global cloud usage (when not using local and not feed override)
        if (!feedUsesCloudModel
                && OpenAiPreferences.isApiKeyRequired(context)
                && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))
                && !LocalAiPreferences.isLocalTranscriptionEnabled(context)) {
            showApiKeyMissingDialog(context);
            return;
        }
        // Check if transcript already exists
        if (hasExistingTranscript(media)) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.transcription_overwrite_title)
                    .setMessage(R.string.transcription_overwrite_message)
                    .setPositiveButton(R.string.transcription_overwrite_confirm,
                            (d, w) -> runTranscription(context, media))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        runTranscription(context, media);
    }

    private boolean hasExistingTranscript(FeedMedia media) {
        String transcriptFileUrl = media.getTranscriptFileUrl();
        if (TextUtils.isEmpty(transcriptFileUrl)) {
            return false;
        }
        File transcriptFile = new File(transcriptFileUrl);
        return transcriptFile.exists() && transcriptFile.length() > 0;
    }

    private void runTranscription(Context context, FeedMedia media) {
        TranscriptionWorkScheduler.enqueueManual(context, media);
        Toast.makeText(context, R.string.transcription_requested, Toast.LENGTH_SHORT).show();
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
