/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.binding.psecompass.internal.api.PseCompassClient;
import org.openhab.binding.psecompass.internal.api.PseRow;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;

/** Read-only integration; household control policy belongs to external rules. */
public class PseCompassHandler extends BaseThingHandler {
    @FunctionalInterface
    interface Fetcher {
        List<PseRow> fetch(LocalDate day) throws IOException, InterruptedException;
    }

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private final Fetcher fetcher;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private PseSchedule cache = new PseSchedule();
    private boolean active;
    private long generation;
    private ScheduledFuture<?> polling;
    private ScheduledFuture<?> boundaries;
    private Instant nextBoundary;
    private Instant evaluatedAt;
    private Instant lastSuccess;
    private String fetchError;

    public PseCompassHandler(Thing thing, PseCompassClient client) {
        this(thing, client::fetch, Clock.systemUTC(), null);
    }

    PseCompassHandler(Thing thing, Fetcher fetcher, Clock clock, ScheduledExecutorService executor) {
        super(thing);
        this.fetcher = fetcher;
        this.clock = clock;
        this.executor = executor == null ? scheduler : executor;
    }

    @Override
    public synchronized void initialize() {
        dispose();
        int minutes;
        try {
            Object configured = getConfig().get("refreshInterval");
            minutes = configured == null ? 15 : new java.math.BigDecimal(configured.toString()).intValueExact();
            if (minutes < 1 || minutes > 1440) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "refreshInterval must be an integer from 1 to 1440 minutes");
            return;
        }
        active = true;
        long run = generation;
        cache = new PseSchedule();
        lastSuccess = null;
        fetchError = null;
        nextBoundary = null;
        evaluatedAt = null;
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for PSE schedule");
        polling = executor.scheduleWithFixedDelay(() -> poll(run), 0, minutes, TimeUnit.MINUTES);
        boundaries = executor.scheduleWithFixedDelay(() -> reevaluate(run), 1, 1, TimeUnit.SECONDS);
    }

    private synchronized boolean isActive(long run) {
        return active && generation == run;
    }

    private synchronized void reevaluate(long run) {
        if (!isActive(run)) {
            return;
        }
        Instant now = clock.instant();
        if ((nextBoundary != null && !now.isBefore(nextBoundary))
                || (evaluatedAt != null && now.isBefore(evaluatedAt))) {
            publish(true);
        }
    }

    private void poll(long run) {
        LocalDate today = clock.instant().atZone(WARSAW).toLocalDate();
        String error = null;
        for (LocalDate day : List.of(today, today.plusDays(1))) {
            if (!isActive(run)) {
                return;
            }
            try {
                var rows = fetcher.fetch(day);
                synchronized (this) {
                    if (!isActive(run)) {
                        return;
                    }
                    if (!rows.isEmpty()) {
                        // Observe elapsed cached boundaries before accepting forecast revisions.
                        reevaluate(run);
                        cache.replace(day, rows);
                        lastSuccess = clock.instant();
                        publish(false);
                    }
                }
            } catch (IOException | RuntimeException e) {
                error = "PSE " + day + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        synchronized (this) {
            if (!isActive(run)) {
                return;
            }
            fetchError = error;
            publish(true);
        }
    }

    private void publish(boolean boundaryObservation) {
        var view = cache.evaluate(clock.instant(), boundaryObservation);
        evaluatedAt = clock.instant();
        nextBoundary = view.boundary();
        updateState("current-zone", view.current() == null ? UnDefType.UNDEF : new DecimalType(view.current()));
        updateState("next-zone", view.next() == null ? UnDefType.UNDEF : new DecimalType(view.next()));
        updateState("next-change", view.nextChange() == null ? UnDefType.UNDEF : new DateTimeType(view.nextChange()));
        updateState("schedule", new StringType(view.json()));
        updateState("last-successful-update", lastSuccess == null ? UnDefType.UNDEF : new DateTimeType(lastSuccess));
        if (view.event() != null) {
            triggerChannel("zone-changed", view.event());
        }
        if (fetchError != null || view.current() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    fetchError != null ? fetchError : "No currently valid PSE schedule");
        } else {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        }
    }

    @Override
    public synchronized void dispose() {
        active = false;
        generation++;
        if (boundaries != null) {
            boundaries.cancel(true);
            boundaries = null;
        }
        if (polling != null) {
            polling.cancel(true);
            polling = null;
        }
        super.dispose();
    }

    @Override
    public synchronized void handleCommand(ChannelUID channelUID, Command command) {
        if (active && command == RefreshType.REFRESH) {
            publish(true);
        }
    }
}
