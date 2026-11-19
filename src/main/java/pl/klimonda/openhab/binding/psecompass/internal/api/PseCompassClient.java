/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package pl.klimonda.openhab.binding.psecompass.internal.api;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

/** Read-only client; the caller owns the shared HTTP client lifecycle. */
@NonNullByDefault
public final class PseCompassClient {
    public static final URI ENDPOINT = URI.create("https://api.raporty.pse.pl/api/pdgsz");
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT)
            .registerTypeAdapter(Integer.class, (JsonDeserializer<Integer>) (json, type, context) -> {
                if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
                    throw new JsonParseException("Expected an integer PSE usage_fcst");
                }
                try {
                    return json.getAsBigDecimal().intValueExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    throw new JsonParseException("Expected an exact integer PSE usage_fcst", e);
                }
            }).registerTypeAdapter(Boolean.class, (JsonDeserializer<Boolean>) (json, type, context) -> {
                if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean()) {
                    throw new JsonParseException("Expected a boolean PSE is_active");
                }
                return json.getAsBoolean();
            }).create();

    public PseCompassClient(HttpClient httpClient) {
        this(httpClient, ENDPOINT);
    }

    PseCompassClient(HttpClient httpClient, URI endpoint) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
    }

    public List<PseRow> fetch(LocalDate day) throws IOException, InterruptedException {
        try {
            var response = httpClient.newRequest(endpoint)
                    .param("$filter", "business_date eq '" + day + "' and is_active eq true")
                    .param("$orderby", "dtime_utc").param("$first", "100")
                    .timeout(10, TimeUnit.SECONDS).followRedirects(false).send();
            if (response.getStatus() != 200) {
                throw new IOException("PSE HTTP status " + response.getStatus());
            }
            return validate(gson.fromJson(response.getContentAsString(), PseResponse.class), day);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("PSE request failed", e);
        } catch (com.google.gson.JsonParseException | java.time.DateTimeException | NullPointerException e) {
            throw new IOException("Invalid PSE report", e);
        }
    }

    private List<PseRow> validate(@Nullable PseResponse response, LocalDate day) throws IOException {
        if (response == null || response.value() == null) {
            throw new IOException("Missing PSE report value");
        }
        if (response.nextLink() != null && !response.nextLink().isBlank()) {
            throw new IOException("Incomplete PSE report: pagination is not supported");
        }
        var rows = response.value();
        if (rows.isEmpty()) {
            return List.of();
        }
        var zone = java.time.ZoneId.of("Europe/Warsaw");
        var start = day.atStartOfDay(zone).toInstant();
        var end = day.plusDays(1).atStartOfDay(zone).toInstant();
        long hours = java.time.Duration.between(start, end).toHours();
        if (rows.size() != hours) {
            throw new IOException("Incomplete PSE daily schedule");
        }
        for (int i = 0; i < rows.size(); i++) {
            PseRow row = rows.get(i);
            if (row == null || !day.toString().equals(row.businessDate()) || !Boolean.TRUE.equals(row.active())
                    || row.usageForecast() == null || row.publishedAt() == null
                    || !row.start().equals(start.plusSeconds(i * 3600L))) {
                throw new IOException("Invalid PSE schedule row");
            }
            if (row.usageForecast() < 0 || row.usageForecast() > 3) {
                throw new IOException("Unknown PSE usage_fcst: " + row.usageForecast());
            }
        }
        return List.copyOf(rows);
    }
}
