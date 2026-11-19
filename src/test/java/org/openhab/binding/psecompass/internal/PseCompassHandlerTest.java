package org.openhab.binding.psecompass.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.psecompass.internal.PseScheduleTest.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.State;
import org.openhab.core.library.types.*;

class PseCompassHandlerTest {
    static class MutableClock extends Clock {
        Instant now = START.plusSeconds(1800);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    static class ManualScheduler extends ScheduledThreadPoolExecutor {
        final List<Runnable> jobs = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<ScheduledFuture<?>> futures = new ArrayList<>();
        ManualScheduler() { super(1); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable job, long initial, long delay, TimeUnit unit) {
            jobs.add(job);
            delays.add(unit.toSeconds(delay));
            var future = super.schedule(() -> {}, 365, TimeUnit.DAYS);
            futures.add(future);
            return future;
        }
    }

    static class RecordingHandler extends PseCompassHandler {
        final Map<String, State> states = new HashMap<>();
        final List<String> events = new ArrayList<>();
        ThingStatus status;
        RecordingHandler(Fetcher fetcher, MutableClock clock, ManualScheduler scheduler) {
            super(ThingBuilder.create(PseCompassBindingConstants.THING_TYPE_COMPASS, "test").build(), fetcher,
                    clock, scheduler);
        }
        @Override protected void updateState(String channel, State state) { states.put(channel, state); }
        @Override protected void updateStatus(ThingStatus status, ThingStatusDetail detail, String message) { this.status = status; }
        @Override protected void triggerChannel(String channel, String event) { events.add(channel + ":" + event); }
    }

    @Test
    void cachedBoundariesAdvanceDuringOutageAndExpireWithoutRecoveryEvents() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var outage = new java.util.concurrent.atomic.AtomicBoolean();
        var handler = new RecordingHandler(day -> {
            if (outage.get()) { throw new java.io.IOException("offline"); }
            return day.equals(DAY) ? rows(0, 2) : List.of();
        }, clock, executor);
        try {
            handler.initialize();
            executor.jobs.get(0).run();
            var success = handler.states.get("last-successful-update");
            outage.set(true);
            executor.jobs.get(0).run();
            assertEquals(ThingStatus.OFFLINE, handler.status);
            assertEquals(new DecimalType(0), handler.states.get("current-zone"));
            assertEquals(2, executor.jobs.size(), "Network and boundary jobs must be independent");
            clock.now = START.plusSeconds(3600);
            executor.jobs.get(1).run();
            assertEquals(new DecimalType(2), handler.states.get("current-zone"));
            assertEquals(List.of("zone-changed:2"), handler.events);
            assertEquals(success, handler.states.get("last-successful-update"));
            clock.now = START.plusSeconds(7200);
            executor.jobs.get(1).run();
            assertEquals(org.openhab.core.types.UnDefType.UNDEF, handler.states.get("current-zone"));
            assertEquals(org.openhab.core.types.UnDefType.UNDEF, handler.states.get("next-zone"));
            assertEquals(org.openhab.core.types.UnDefType.UNDEF, handler.states.get("next-change"));
            outage.set(false);
            clock.now = START.plusSeconds(3601);
            executor.jobs.get(0).run();
            assertEquals(ThingStatus.ONLINE, handler.status);
            assertEquals(List.of("zone-changed:2"), handler.events);
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void emptyAndFailedDaysDoNotDiscardUsableCache() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new RecordingHandler(day -> {
            if (attempt.get() == 0) { return day.equals(DAY) ? rows(0, 2) : List.of(); }
            if (!day.equals(DAY)) { throw new java.io.IOException("tomorrow unavailable"); }
            return List.of();
        }, clock, executor);
        try {
            handler.initialize();
            executor.jobs.get(0).run();
            var success = handler.states.get("last-successful-update");
            attempt.incrementAndGet();
            executor.jobs.get(0).run();
            assertEquals(new DecimalType(0), handler.states.get("current-zone"));
            assertEquals(new DecimalType(2), handler.states.get("next-zone"));
            assertEquals(success, handler.states.get("last-successful-update"));
            assertEquals(ThingStatus.OFFLINE, handler.status);
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void disposalCancelsJobsAndDiscardsInFlightResponse() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var ref = new java.util.concurrent.atomic.AtomicReference<RecordingHandler>();
        var calls = new ArrayList<LocalDate>();
        var handler = new RecordingHandler(day -> {
            calls.add(day);
            ref.get().dispose();
            return rows(0, 2);
        }, clock, executor);
        ref.set(handler);
        try {
            handler.initialize();
            executor.jobs.get(0).run();
            assertTrue(handler.states.isEmpty(), "Do not publish an in-flight response after disposal");
            assertTrue(executor.futures.stream().allMatch(java.util.concurrent.Future::isCancelled));
            assertFalse(executor.isShutdown(), "Shared executor is not owned by this handler");
            executor.jobs.forEach(Runnable::run);
            assertEquals(List.of(DAY), calls);
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void refreshRepublishesAndReevaluatesCacheWithoutNetwork() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var calls = new ArrayList<LocalDate>();
        var handler = new RecordingHandler(day -> {
            calls.add(day);
            return day.equals(DAY) ? rows(0, 2) : List.of();
        }, clock, executor);
        try {
            handler.initialize();
            executor.jobs.get(0).run();
            handler.states.clear();
            clock.now = START.plusSeconds(3600);
            handler.handleCommand(new ChannelUID(handler.getThing().getUID(), "current-zone"),
                    org.openhab.core.types.RefreshType.REFRESH);
            assertEquals(new DecimalType(2), handler.states.get("current-zone"));
            assertEquals(List.of(DAY, DAY.plusDays(1)), calls);
            assertEquals(List.of("zone-changed:2"), handler.events);
            handler.states.clear();
            handler.handleCommand(new ChannelUID(handler.getThing().getUID(), "current-zone"), new DecimalType(3));
            assertTrue(handler.states.isEmpty());
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"5,300", "60,3600", "0,-1", "-1,-1", "1.5,-1", "abc,-1"})
    void validatesConfigurablePollingMinutes(String minutes, long expectedSeconds) {
        var executor = new ManualScheduler();
        var handler = new RecordingHandler(day -> List.of(), new MutableClock(), executor);
        handler.getThing().getConfiguration().put("refreshInterval", minutes);
        try {
            handler.initialize();
            if (expectedSeconds < 0) {
                assertEquals(ThingStatus.OFFLINE, handler.status);
                assertTrue(executor.jobs.isEmpty());
            } else {
                assertEquals(expectedSeconds, executor.delays.get(0));
            }
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void pollAtBoundaryDoesNotLoseEventButForecastRevisionIsNotAnEvent() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var revision = new java.util.concurrent.atomic.AtomicBoolean();
        var handler = new RecordingHandler(day -> day.equals(DAY) ? (revision.get() ? rows(0, 3) : rows(0, 2))
                : List.of(), clock, executor);
        try {
            handler.initialize();
            executor.jobs.get(0).run();
            clock.now = START.plusSeconds(3600);
            executor.jobs.get(0).run();
            assertEquals(List.of("zone-changed:2"), handler.events);
            revision.set(true);
            executor.jobs.get(0).run();
            assertEquals(new DecimalType(3), handler.states.get("current-zone"));
            assertEquals(List.of("zone-changed:2"), handler.events);
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void unexpectedTransportFailureDoesNotKillRecurringPoll() {
        var executor = new ManualScheduler();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var handler = new RecordingHandler(day -> {
            if (fail.get()) { throw new IllegalStateException("HTTP client unavailable"); }
            return day.equals(DAY) ? rows(1) : List.of();
        }, new MutableClock(), executor);
        try {
            handler.initialize();
            assertDoesNotThrow(() -> executor.jobs.get(0).run());
            assertEquals(ThingStatus.OFFLINE, handler.status);
            fail.set(false);
            executor.jobs.get(0).run();
            assertEquals(ThingStatus.ONLINE, handler.status);
            assertTrue(handler.events.isEmpty());
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void blockedFetchDoesNotBlockCachedBoundaryEvaluation() throws Exception {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var shouldBlock = new java.util.concurrent.atomic.AtomicBoolean();
        var handler = new RecordingHandler(day -> {
            if (shouldBlock.get()) {
                blocked.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) { throw new java.io.IOException("test timeout"); }
            }
            return day.equals(DAY) ? rows(0, 2) : List.of();
        }, clock, executor);
        try (var workers = Executors.newSingleThreadExecutor()) {
            handler.initialize();
            executor.jobs.get(0).run();
            shouldBlock.set(true);
            var request = workers.submit(executor.jobs.get(0));
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            clock.now = START.plusSeconds(3600);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> executor.jobs.get(1).run());
            assertEquals(new DecimalType(2), handler.states.get("current-zone"));
            assertEquals(List.of("zone-changed:2"), handler.events);
            release.countDown();
            request.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void reinitializeInvalidatesOldCallbacksAndUsesWarsawBusinessDate() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var calls = new ArrayList<LocalDate>();
        var handler = new RecordingHandler(day -> { calls.add(day); return List.of(); }, clock, executor);
        try {
            handler.initialize();
            var stale = List.copyOf(executor.jobs);
            handler.dispose();
            handler.initialize();
            stale.forEach(Runnable::run);
            assertTrue(calls.isEmpty());
            executor.jobs.get(2).run();
            // UTC is still November 18; Warsaw is already November 19.
            assertEquals(List.of(DAY, DAY.plusDays(1)), calls);
            assertEquals(org.openhab.core.types.UnDefType.UNDEF, handler.states.get("current-zone"));
            assertTrue(handler.events.isEmpty());
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptionIsPreservedAndDoesNotFetchAnotherDay() {
        var executor = new ManualScheduler();
        var calls = new ArrayList<LocalDate>();
        var handler = new RecordingHandler(day -> {
            calls.add(day);
            throw new InterruptedException();
        }, new MutableClock(), executor);
        try {
            handler.initialize();
            executor.jobs.get(0).run();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of(DAY), calls);
        } finally {
            Thread.interrupted();
            handler.dispose();
            executor.shutdownNow();
        }
    }

    @Test
    void initializesWithoutNetworkThenPublishesCurrentAndFuture() {
        var clock = new MutableClock();
        var executor = new ManualScheduler();
        var calls = new ArrayList<LocalDate>();
        var handler = new RecordingHandler(day -> {
            calls.add(day);
            return day.equals(DAY) ? rows(0, 0, 2, 3) : List.of();
        }, clock, executor);
        try {
            handler.initialize();
            assertTrue(calls.isEmpty());
            assertEquals(ThingStatus.UNKNOWN, handler.status);
            executor.jobs.get(0).run();
            assertEquals(List.of(DAY, DAY.plusDays(1)), calls);
            assertEquals(new DecimalType(0), handler.states.get("current-zone"));
            assertEquals(new DecimalType(2), handler.states.get("next-zone"));
            assertEquals(new DateTimeType(START.plusSeconds(7200)), handler.states.get("next-change"));
            assertEquals(new DateTimeType(clock.now), handler.states.get("last-successful-update"));
            assertEquals(ThingStatus.ONLINE, handler.status);
            assertTrue(handler.events.isEmpty());
            assertEquals(900, executor.delays.get(0));
        } finally {
            handler.dispose();
            executor.shutdownNow();
        }
    }
}
