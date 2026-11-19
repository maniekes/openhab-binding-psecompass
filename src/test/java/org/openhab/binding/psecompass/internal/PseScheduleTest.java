package org.openhab.binding.psecompass.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openhab.binding.psecompass.internal.api.PseRow;

class PseScheduleTest {
    static final Instant START = Instant.parse("2026-11-18T23:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 11, 19);

    static List<PseRow> rows(int... zones) {
        var rows = new ArrayList<PseRow>();
        for (int i = 0; i < zones.length; i++) {
            rows.add(new PseRow(START.plusSeconds(i * 3600L), DAY.toString(), START, zones[i], true));
        }
        return rows;
    }

    @Test
    void emitsOnlyObservedAdjacentBoundaryChanges() {
        var schedule = new PseSchedule();
        schedule.replace(DAY, rows(0, 0, 2, 3));
        assertNull(schedule.evaluate(START, true).event());
        assertNull(schedule.evaluate(START.plusSeconds(3600), true).event());
        assertEquals("2", schedule.evaluate(START.plusSeconds(7200), true).event());
        assertNull(schedule.evaluate(START.plusSeconds(7201), true).event());
        assertNull(schedule.evaluate(START.plusSeconds(10800), false).event());
        assertNull(schedule.evaluate(START.plusSeconds(14400), true).current());
        assertNull(schedule.evaluate(START.plusSeconds(14400), true).next());
        assertEquals("[]", schedule.evaluate(START.plusSeconds(14400), true).json());
        schedule.replace(DAY, rows(0, 1, 2, 3, 1));
        assertNull(schedule.evaluate(START.plusSeconds(14401), true).event());
    }

    @Test
    void futureOnlyCacheStillHasAnActivationBoundary() {
        var schedule = new PseSchedule();
        schedule.replace(DAY, rows(2, 3));
        var before = schedule.evaluate(START.minusSeconds(1), true);
        assertNull(before.current());
        assertNull(before.next());
        assertEquals(START, before.boundary());
        var started = schedule.evaluate(START, true);
        assertEquals(2, started.current());
        assertNull(started.event());
    }

    @Test
    void discardsExpiredDaysRatherThanGrowingOrResurrectingThem() {
        var schedule = new PseSchedule();
        schedule.replace(DAY, rows(0));
        schedule.evaluate(START.plusSeconds(3600), true);
        assertNull(schedule.evaluate(START, false).current());
    }

    @Test
    void nextDistinctCrossesMidnightButNotGaps() {
        var schedule = new PseSchedule();
        int[] zones = new int[24];
        schedule.replace(DAY, rows(zones));
        schedule.replace(DAY.plusDays(1), List.of(new PseRow(START.plusSeconds(86400), DAY.plusDays(1).toString(), START, 3, true)));
        var view = schedule.evaluate(START.plusSeconds(23 * 3600), false);
        assertEquals(3, view.next());
        assertEquals(START.plusSeconds(86400), view.nextChange());
        assertEquals("3", schedule.evaluate(START.plusSeconds(86400), true).event());
        var gap = new PseSchedule();
        gap.replace(DAY, rows(0));
        gap.replace(DAY.plusDays(1), List.of(new PseRow(START.plusSeconds(86400), DAY.plusDays(1).toString(), START, 3, true)));
        assertNull(gap.evaluate(START, false).next());
        assertNull(gap.evaluate(START.plusSeconds(86400), true).event());
    }

    @Test
    void skippedBoundariesAreNotReplayed() {
        var schedule = new PseSchedule();
        schedule.replace(DAY, rows(0, 1, 2, 3));
        schedule.evaluate(START, false);
        assertNull(schedule.evaluate(START.plusSeconds(7200), true).event());
    }

    @Test
    void projectsCurrentAndNextDistinctWithUtcIntervals() {
        var schedule = new PseSchedule();
        schedule.replace(DAY, rows(0, 0, 2, 3));
        var view = schedule.evaluate(START.plusSeconds(1800), false);
        assertEquals(0, view.current());
        assertEquals(2, view.next());
        assertEquals(START.plusSeconds(7200), view.nextChange());
        var json = com.google.gson.JsonParser.parseString(view.json()).getAsJsonArray();
        assertEquals(4, json.size());
        assertEquals("2026-11-18T23:00:00Z", json.get(0).getAsJsonObject().get("start").getAsString());
        assertEquals("2026-11-19T00:00:00Z", json.get(0).getAsJsonObject().get("end").getAsString());
        assertEquals(0, json.get(0).getAsJsonObject().get("zone").getAsInt());
        assertNull(view.event());
    }
}
