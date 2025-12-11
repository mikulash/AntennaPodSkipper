package de.danoeh.antennapod.actionbutton;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;

import android.content.Intent;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;

import java.io.File;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.service.ad.AdAnalysisWorkScheduler;
import de.danoeh.antennapod.net.download.service.ad.whisper.LocalTranscriptionManager;
import de.danoeh.antennapod.storage.database.AdSegmentStore;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

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
        if (OpenAiPreferences.isLocalTranscriptionEnabled(context)) {
            String model = OpenAiPreferences.getLocalTranscriptionModel(context);
            if (!new LocalTranscriptionManager(context).isModelDownloaded(model)) {
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.ad_analysis_model_missing_title)
                        .setMessage(R.string.ad_analysis_model_missing_message)
                        .setPositiveButton(R.string.action_download_model, (d, w) -> {
                            Intent intent = new Intent(context, PreferenceActivity.class);
                            intent.putExtra(PreferenceActivity.OPEN_AI_SETTINGS, true);
                            context.startActivity(intent);
                        })
                        .setNeutralButton(R.string.action_use_cloud, (d, w) -> {
                            OpenAiPreferences.setLocalTranscriptionEnabled(context, false);
                            runAnalysis(context, media);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }
        if (OpenAiPreferences.isLocalAdAnalysisEnabled(context)) {
            String model = OpenAiPreferences.getLocalAdAnalysisModel(context);
            if (!new de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager(context).isModelDownloaded(model)) {
                 new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.ad_analysis_model_missing_title)
                        .setMessage(R.string.ad_analysis_llm_missing_message)
                        .setPositiveButton(R.string.action_download_model, (d, w) -> {
                            Intent intent = new Intent(context, PreferenceActivity.class);
                            intent.putExtra(PreferenceActivity.OPEN_AI_SETTINGS, true);
                            context.startActivity(intent);
                        })
                        .setNeutralButton(R.string.action_use_cloud, (d, w) -> {
                            // Disable local analysis so we fall back to cloud (or local transcription+cloud)
                            // We need a setter for this preference in OpenAiPreferences, 
                            // but currently I only added the key constant. 
                            // I will use shared preferences directly or skip the neutral button logic complexity for now 
                            // and just let them go to settings.
                            // Actually, I can just use PreferenceManager to disable it.
                            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                                .edit().putBoolean("prefLocalAdAnalysisEnabled", false).apply();
                            runAnalysis(context, media);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }
        if (OpenAiPreferences.isApiKeyRequired(context)
                && TextUtils.isEmpty(OpenAiPreferences.getApiKey(context))) {
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

    private void runAnalysis(Context context, FeedMedia media) {
        AdAnalysisWorkScheduler.enqueueManual(context, media);
        Toast.makeText(context, R.string.ad_analysis_requested, Toast.LENGTH_SHORT).show();
    }
}
