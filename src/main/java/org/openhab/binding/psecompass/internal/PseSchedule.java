/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.openhab.binding.psecompass.internal.api.PseRow;

/** In-memory daily cache. Access is serialized by the handler, never by the network request. */
final class PseSchedule {
    private final Map<LocalDate, List<PseRow>> days = new HashMap<>();
    private PseRow observed;

    record View(Integer current, Integer next, Instant nextChange, String json, String event, Instant boundary) {
    }

    void replace(LocalDate day, List<PseRow> rows) {
        days.put(day, List.copyOf(rows));
    }

    View evaluate(Instant now, boolean boundaryObservation) {
        days.values().removeIf(rows -> rows.stream().noneMatch(row -> now.isBefore(end(row))));
        var rows = days.values().stream().flatMap(List::stream).sorted(Comparator.comparing(PseRow::start)).toList();
        PseRow current = rows.stream().filter(r -> !now.isBefore(r.start()) && now.isBefore(end(r)))
                .findFirst().orElse(null);
        PseRow next = null;
        if (current != null) {
            Instant expected = end(current);
            for (PseRow row : rows) {
                if (row.start().isBefore(expected)) {
                    continue;
                }
                if (!row.start().equals(expected)) {
                    break;
                }
                if (!row.usageForecast().equals(current.usageForecast())) {
                    next = row;
                    break;
                }
                expected = end(row);
            }
        }
        var json = new JsonArray();
        for (PseRow row : rows) {
            if (now.isBefore(end(row))) {
                var interval = new JsonObject();
                interval.addProperty("start", row.start().toString());
                interval.addProperty("end", end(row).toString());
                interval.addProperty("zone", row.usageForecast());
                json.add(interval);
            }
        }
        String event = boundaryObservation && observed != null && current != null
                && end(observed).equals(current.start()) && !observed.usageForecast().equals(current.usageForecast())
                        ? current.usageForecast().toString() : null;
        observed = current;
        Instant boundary = current == null ? rows.stream().map(PseRow::start).filter(now::isBefore)
                .findFirst().orElse(null) : end(current);
        return new View(current == null ? null : current.usageForecast(), next == null ? null : next.usageForecast(),
                next == null ? null : next.start(), json.toString(), event, boundary);
    }

    private static Instant end(PseRow row) {
        return row.start().plusSeconds(3600);
    }
}
