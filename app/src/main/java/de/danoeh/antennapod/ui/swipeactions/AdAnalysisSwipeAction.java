package de.danoeh.antennapod.ui.swipeactions;

import android.content.Context;
import android.os.Build;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.common.util.concurrent.ListenableFuture;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.CompleteAnalysisActionButton;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.net.ai.service.ad.AdAnalysisWorkScheduler;
import java.util.List;
import org.greenrobot.eventbus.EventBus;

public class AdAnalysisSwipeAction implements SwipeAction {

    @Override
    public String getId() {
        return AD_ANALYSIS;
    }

    @Override
    public int getActionIcon() {
        return R.drawable.ic_ad_analysis;
    }

    @Override
    public int getActionColor() {
        return R.attr.colorAccent;
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.action_complete_analysis);
    }

    @Override
    public void performAction(FeedItem item, Fragment fragment, FeedItemFilter filter) {
        if (item.getMedia() == null) {
            EventBus.getDefault().post(new MessageEvent(fragment.getString(R.string.no_media_label)));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Context context = fragment.requireContext();
        ListenableFuture<List<WorkInfo>> future = WorkManager.getInstance(context)
                .getWorkInfosByTag(AdAnalysisWorkScheduler.TAG_PREFIX + item.getId());
        future.addListener(() -> {
            if (!fragment.isAdded()) {
                return;
            }
            if (isAnalysisActive(future)) {
                EventBus.getDefault().post(new MessageEvent(context.getString(R.string.ad_analysis_in_queue)));
            } else {
                new CompleteAnalysisActionButton(item).onClick(context);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private static boolean isAnalysisActive(ListenableFuture<List<WorkInfo>> future) {
        try {
            for (WorkInfo info : future.get()) {
                if (!info.getState().isFinished()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @Override
    public boolean willRemove(FeedItemFilter filter, FeedItem item) {
        return false;
    }
}
