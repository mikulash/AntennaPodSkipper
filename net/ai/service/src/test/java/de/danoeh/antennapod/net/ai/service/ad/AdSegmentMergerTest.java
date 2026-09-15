package de.danoeh.antennapod.net.ai.service.ad;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.model.ad.AdSegment;

public class AdSegmentMergerTest {

    @Test
    public void expandAndMerge_addsOneSecondOnBothSides() {
        List<AdSegment> result = AdSegmentMerger.expandAndMerge(
                Collections.singletonList(new AdSegment(10, 20, "sponsor", 0.9)),
                1.0, 100);

        assertEquals(1, result.size());
        assertEquals(9.0, result.get(0).getStartSeconds(), 0.001);
        assertEquals(21.0, result.get(0).getEndSeconds(), 0.001);
    }

    @Test
    public void expandAndMerge_clampsToEpisodeBounds() {
        List<AdSegment> result = AdSegmentMerger.expandAndMerge(Arrays.asList(
                new AdSegment(0.25, 5, "pre-roll", 0.9),
                new AdSegment(95, 99.75, "post-roll", 0.8)
        ), 1.0, 100);

        assertEquals(2, result.size());
        assertEquals(0.0, result.get(0).getStartSeconds(), 0.001);
        assertEquals(6.0, result.get(0).getEndSeconds(), 0.001);
        assertEquals(94.0, result.get(1).getStartSeconds(), 0.001);
        assertEquals(100.0, result.get(1).getEndSeconds(), 0.001);
    }

    @Test
    public void expandAndMerge_combinesSegmentsThatOverlapAfterPadding() {
        List<AdSegment> result = AdSegmentMerger.expandAndMerge(Arrays.asList(
                new AdSegment(10, 20, "first", 0.8),
                new AdSegment(21, 30, "second", 0.9)
        ), 1.0, 100);

        assertEquals(1, result.size());
        assertEquals(9.0, result.get(0).getStartSeconds(), 0.001);
        assertEquals(31.0, result.get(0).getEndSeconds(), 0.001);
        assertEquals(0.9, result.get(0).getConfidence(), 0.001);
    }

    @Test
    public void expandAndMerge_doesNotClampWhenDurationIsUnknown() {
        List<AdSegment> result = AdSegmentMerger.expandAndMerge(
                Collections.singletonList(new AdSegment(10, 20, "sponsor", 0.9)),
                1.0, Double.POSITIVE_INFINITY);

        assertEquals(21.0, result.get(0).getEndSeconds(), 0.001);
    }
}
