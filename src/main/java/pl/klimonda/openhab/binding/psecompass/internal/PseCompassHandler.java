/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package pl.klimonda.openhab.binding.psecompass.internal;

import static pl.klimonda.openhab.binding.psecompass.internal.PseCompassBindingConstants.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import pl.klimonda.openhab.binding.psecompass.internal.api.PseCompassClient;
import pl.klimonda.openhab.binding.psecompass.internal.api.PseRow;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only integration; household control policy belongs to external rules. */
@NonNullByDefault
public class PseCompassHandler extends BaseThingHandler {
    @FunctionalInterface
    interface Fetcher {
        List<PseRow> fetch(LocalDate day) throws IOException, InterruptedException;
    }

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private final Logger logger = LoggerFactory.getLogger(PseCompassHandler.class);
    private final Fetcher fetcher;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private PseSchedule cache = new PseSchedule();
    private boolean active;
    private long generation;
    private @Nullable ScheduledFuture<?> polling;
    private @Nullable ScheduledFuture<?> boundaries;
    private @Nullable Instant nextBoundary;
    private @Nullable Instant evaluatedAt;
    private @Nullable Instant lastSuccess;
    private @Nullable String fetchError;
    private boolean publicationPending;
    private boolean cycleCompleted;

    public PseCompassHandler(Thing thing, PseCompassClient client) {
        this(thing, client::fetch, Clock.systemUTC(), null);
    }

    PseCompassHandler(Thing thing, Fetcher fetcher, Clock clock, @Nullable ScheduledExecutorService executor) {
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
            Object configured = getConfig().get(CONFIG_REFRESH_INTERVAL);
            minutes = configured == null ? 15 : new java.math.BigDecimal(configured.toString()).intValueExact();
            if (minutes < 1 || minutes > 1440) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    CONFIG_REFRESH_INTERVAL + " must be an integer from 1 to 1440 minutes");
            return;
        }
        active = true;
        long run = generation;
        cache = new PseSchedule();
        lastSuccess = null;
        fetchError = null;
        nextBoundary = null;
        evaluatedAt = null;
        publicationPending = false;
        cycleCompleted = false;
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for PSE schedule");
        polling = executor.scheduleWithFixedDelay(() -> runSafely(() -> poll(run)), 0, minutes, TimeUnit.MINUTES);
        boundaries = executor.scheduleWithFixedDelay(() -> runSafely(() -> reevaluate(run)), 1, 1, TimeUnit.SECONDS);
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // An escaping exception would cancel a fixed-delay job permanently.
            logger.debug("PSE scheduled update failed for {}", getThing().getUID(), e);
        }
    }

    private synchronized boolean isActive(long run) {
        return active && generation == run;
    }

    private synchronized void reevaluate(long run) {
        if (!isActive(run)) {
            return;
        }
        Instant now = clock.instant();
        reevaluate(now);
    }

    private void reevaluate(Instant now) {
        if (publicationPending || (nextBoundary != null && !now.isBefore(nextBoundary))
                || (evaluatedAt != null && now.isBefore(evaluatedAt))) {
            publish(now, true);
        }
    }

    private void poll(long run) {
        LocalDate today = clock.instant().atZone(WARSAW).toLocalDate();
        String error = null;
        for (LocalDate day : List.of(today, today.plusDays(1))) {
            if (!isActive(run)) {
                return;
            }
            List<PseRow> rows;
            try {
                rows = fetcher.fetch(day);
            } catch (IOException | RuntimeException e) {
                error = error == null ? "PSE fetch failed: " + day : error + ", " + day;
                logger.debug("PSE fetch failed for {} on {}", getThing().getUID(), day, e);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (this) {
                if (!isActive(run)) {
                    return;
                }
                if (!rows.isEmpty()) {
                    // Observe elapsed cached boundaries before accepting forecast revisions.
                    Instant now = clock.instant();
                    reevaluate(now);
                    cache.replace(day, rows);
                    lastSuccess = now;
                    publish(now, false);
                }
            }
        }
        synchronized (this) {
            if (!isActive(run)) {
                return;
            }
            fetchError = error;
            cycleCompleted = true;
            publish(clock.instant(), true);
        }
    }

    private void publish(Instant now, boolean boundaryObservation) {
        // Retry state/status publication, never replay an event whose delivery is uncertain.
        publicationPending = true;
        var view = cache.evaluate(now, boundaryObservation);
        evaluatedAt = now;
        nextBoundary = view.boundary();
        updateState(CHANNEL_CURRENT_ZONE, view.current() == null ? UnDefType.UNDEF : new DecimalType(view.current()));
        updateState(CHANNEL_NEXT_ZONE, view.next() == null ? UnDefType.UNDEF : new DecimalType(view.next()));
        updateState(CHANNEL_NEXT_CHANGE, view.nextChange() == null ? UnDefType.UNDEF : new DateTimeType(view.nextChange()));
        updateState(CHANNEL_SCHEDULE, new StringType(view.json()));
        updateState(CHANNEL_LAST_SUCCESSFUL_UPDATE, lastSuccess == null ? UnDefType.UNDEF : new DateTimeType(lastSuccess));
        if (view.event() != null) {
            triggerChannel(CHANNEL_ZONE_CHANGED, view.event());
        }
        if (!cycleCompleted) {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for PSE schedule");
        } else if (fetchError != null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    fetchError);
        } else if (view.current() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "No currently valid PSE schedule");
        } else {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        }
        publicationPending = false;
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
            publish(clock.instant(), true);
        }
    }
}
