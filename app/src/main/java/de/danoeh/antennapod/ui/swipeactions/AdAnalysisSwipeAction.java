package de.danoeh.antennapod.ui.swipeactions;

import android.content.Context;
import android.os.Build;
import androidx.fragment.app.Fragment;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.CompleteAnalysisActionButton;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            new CompleteAnalysisActionButton(item).onClick(fragment.requireContext());
        }
    }

    @Override
    public boolean willRemove(FeedItemFilter filter, FeedItem item) {
        return false;
    }
}
