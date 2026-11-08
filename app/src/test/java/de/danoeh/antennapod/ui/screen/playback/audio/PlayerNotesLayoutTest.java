package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PlayerNotesLayoutTest {
    @Test
    public void chapterControlsFitNarrowPortrait() {
        checkChapterControls(320);
    }

    @Test
    public void chapterControlsFitLandscapeHalfWidth() {
        checkChapterControls(240);
    }

    @Test
    public void shownotesKeepViewportMinimumHeight() {
        Context context = getContext();
        ItemDescriptionFragment fragment = new ItemDescriptionFragment();
        View root = fragment.onCreateView(LayoutInflater.from(context), null, null);
        int width = dp(context, 320);
        int height = dp(context, 480);
        measureAndLayout(root, width, height);
        measureAndLayout(root, width, height);

        View shownotes = root.findViewById(R.id.webview);
        assertEquals(height, shownotes.getMinimumHeight());
        assertTrue(shownotes.getMeasuredHeight() >= height);
        height = dp(context, 240);
        measureAndLayout(root, width, height);
        measureAndLayout(root, width, height);
        assertEquals(height, shownotes.getMinimumHeight());
        assertEquals(height, shownotes.getMeasuredHeight());
        fragment.onDestroy();
    }

    private void checkChapterControls(int widthDp) {
        Context context = getContext();
        View root = LayoutInflater.from(context).inflate(R.layout.cover_fragment, null);
        View shownotes = root.findViewById(R.id.openDescription);
        View adSegments = root.findViewById(R.id.openAdSegments);
        View chapters = root.findViewById(R.id.chapterButton);
        TextView label = root.findViewById(R.id.chapters_label);
        adSegments.setVisibility(View.VISIBLE);
        chapters.setVisibility(View.VISIBLE);
        measureAndLayout(root, dp(context, widthDp), dp(context, 600));

        assertEquals(shownotes.getParent(), adSegments.getParent());
        assertTrue(chapters.getTop() >= ((View) shownotes.getParent()).getBottom());
        assertTrue(label.getWidth() >= label.getPaint().measureText(label.getText().toString()));
        assertEquals(dp(context, 36), root.findViewById(R.id.butPrevChapter).getWidth());
        assertEquals(dp(context, 36), root.findViewById(R.id.butNextChapter).getWidth());
    }

    private Context getContext() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_AntennaPod_Light);
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void measureAndLayout(View view, int width, int height) {
        forceLayout(view);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private void forceLayout(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                forceLayout(group.getChildAt(i));
            }
        }
    }
}
