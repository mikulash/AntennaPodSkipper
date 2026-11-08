package de.danoeh.antennapod.playback.service;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class DelayedEpisodeDeletionWorkerTest {
    private SharedPreferences preferences;
    private FeedItem item;
    private Feed feed;
    private FeedPreferences feedPreferences;

    @Before
    public void setUp() {
        UserPreferences.init(ApplicationProvider.getApplicationContext());
        preferences = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext());
        preferences.edit().clear().commit();
        item = mock(FeedItem.class);
        feed = mock(Feed.class);
        feedPreferences = mock(FeedPreferences.class);
        when(item.getFeed()).thenReturn(feed);
        when(feed.getPreferences()).thenReturn(feedPreferences);
        when(feedPreferences.getCurrentAutoDelete()).thenReturn(FeedPreferences.AutoDeleteAction.GLOBAL);
    }

    @Test
    public void delayIsDisabledByDefault() {
        assertEquals(0, UserPreferences.getAutoDeleteDelayMillis());
    }

    @Test
    public void delayChoicesAndDeletionThresholds() {
        preferences.edit().putBoolean(UserPreferences.PREF_AUTO_DELETE_DELAY_ENABLED, true).commit();
        FeedMedia media = mock(FeedMedia.class);
        long completedAt = 1000000;
        for (int days : new int[] {1, 3, 7}) {
            preferences.edit().putString(UserPreferences.PREF_AUTO_DELETE_DELAY, Integer.toString(days)).commit();
            long delay = TimeUnit.DAYS.toMillis(days);
            assertEquals(delay, UserPreferences.getAutoDeleteDelayMillis());
            assertEquals(1, DelayedEpisodeDeletionWorker.getRemainingDelay(
                    media, completedAt, completedAt + delay - 1, delay));
            assertEquals(0, DelayedEpisodeDeletionWorker.getRemainingDelay(
                    media, completedAt, completedAt + delay, delay));
            assertEquals(-1, DelayedEpisodeDeletionWorker.getRemainingDelay(
                    media, completedAt, completedAt + delay + 1, delay));
        }
    }

    @Test
    public void listeningAgainResetsDelay() {
        FeedMedia media = mock(FeedMedia.class);
        long delay = TimeUnit.DAYS.toMillis(1);
        long completedAt = 1000000;
        long replayedAt = completedAt + delay - 1000;
        when(media.getLastPlayedTimeStatistics()).thenReturn(replayedAt);
        assertEquals(delay - 1000, DelayedEpisodeDeletionWorker.getRemainingDelay(
                media, completedAt, completedAt + delay, delay));
        assertEquals(0, DelayedEpisodeDeletionWorker.getRemainingDelay(
                media, completedAt, replayedAt + delay, delay));
    }

    @Test
    public void latestCompletionResetsDelay() {
        FeedMedia media = mock(FeedMedia.class);
        when(media.getLastPlayedTimeStatistics()).thenReturn(1000L);
        long delay = TimeUnit.DAYS.toMillis(3);
        assertEquals(delay, DelayedEpisodeDeletionWorker.getRemainingDelay(media, 2000, 2000, delay));
    }

    @Test
    public void respectsGlobalAndFeedSettings() {
        assertFalse(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
        preferences.edit().putBoolean(UserPreferences.PREF_AUTO_DELETE, true).commit();
        assertTrue(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
        when(feedPreferences.getCurrentAutoDelete()).thenReturn(FeedPreferences.AutoDeleteAction.NEVER);
        assertFalse(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
        preferences.edit().putBoolean(UserPreferences.PREF_AUTO_DELETE, false).commit();
        when(feedPreferences.getCurrentAutoDelete()).thenReturn(FeedPreferences.AutoDeleteAction.ALWAYS);
        assertTrue(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
    }

    @Test
    public void respectsFavoriteAndLocalFolderProtection() {
        preferences.edit().putBoolean(UserPreferences.PREF_AUTO_DELETE, true).commit();
        when(item.isTagged(FeedItem.TAG_FAVORITE)).thenReturn(true);
        assertFalse(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
        preferences.edit().putBoolean(UserPreferences.PREF_FAVORITE_KEEPS_EPISODE, false).commit();
        assertTrue(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
        when(feed.isLocalFeed()).thenReturn(true);
        assertFalse(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
        preferences.edit().putBoolean("prefAutoDeleteLocal", true).commit();
        assertTrue(DelayedEpisodeDeletionWorker.isDeletionAllowed(item));
    }
}
