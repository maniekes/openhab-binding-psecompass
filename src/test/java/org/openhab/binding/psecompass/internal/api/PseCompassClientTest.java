package org.openhab.binding.psecompass.internal.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class PseCompassClientTest {
    static final LocalDate DAY = LocalDate.of(2026, 11, 19);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 4, 99})
    void rejectsUnknownRecommendationCodes(int code) {
        String body = schedule(DAY).replace("\"usage_fcst\":0", "\"usage_fcst\":" + code);
        var error = assertThrows(java.io.IOException.class, () -> fetchResponse(200, body, DAY));
        assertTrue(error.getMessage().contains("Unknown PSE usage_fcst"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"business_date,wrong_day", "usage_fcst,missing_code",
            "publication_ts_utc,missing_publication", "dtime_utc,missing_timestamp"})
    void requiredFieldsCannotBeSilentlyDropped(String field, String replacement) {
        String body = schedule(DAY).replace('"' + field + '"', '"' + replacement + '"');
        assertThrows(java.io.IOException.class, () -> fetchResponse(200, body, DAY));
    }

    @Test
    void rejectsInactiveRowsAndReturnsImmutableResults() throws Exception {
        assertThrows(java.io.IOException.class,
                () -> fetchResponse(200, schedule(DAY).replace("true", "false"), DAY));
        var rows = fetchResponse(200, schedule(DAY), DAY);
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
    }

    @Test
    void rejectsObjectInsteadOfTimestamp() {
        String body = schedule(DAY).replaceFirst("\"2026-11-18 23:00\"", "{}");
        assertThrows(java.io.IOException.class, () -> fetchResponse(200, body, DAY));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2026-02-30 12:00", "bad timestamp"})
    void rejectsInvalidTimestamps(String timestamp) {
        String body = schedule(DAY).replaceFirst("2026-11-18 23:00", timestamp);
        assertThrows(java.io.IOException.class, () -> fetchResponse(200, body, DAY));
    }

    @Test
    void requestHasAFiniteTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/pdgsz", exchange -> {
            try {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        HttpClient http = new HttpClient();
        http.start();
        try {
            var client = new PseCompassClient(http,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/pdgsz"));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(15),
                    () -> assertThrows(java.io.IOException.class, () -> client.fetch(DAY)));
        } finally {
            release.countDown();
            http.stop();
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {301, 429, 500})
    void rejectsHttpErrorsEvenWithPlausibleJson(int status) {
        assertThrows(java.io.IOException.class, () -> fetchResponse(status, schedule(DAY), DAY));
    }

    @Test
    void missingTomorrowIsAnEmptySchedule() throws Exception {
        assertTrue(fetchResponse(200, "{\"value\":[]}", DAY.plusDays(1)).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2026-03-29,23", "2026-10-25,25"})
    void acceptsDstDays(String date, int expectedHours) throws Exception {
        LocalDate day = LocalDate.parse(date);
        assertEquals(expectedHours, fetchResponse(200, schedule(day), day).size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "null", "{}", "{\"value\":null}", "not json",
            "{\"value\":[],\"nextLink\":\"https://other.invalid/page\"}",
            "{\"value\":[{\"dtime_utc\":\"2026-11-18 23:00\"}]}"
    })
    void rejectsInvalidOrIncompleteEnvelopes(String body) {
        assertThrows(java.io.IOException.class, () -> fetchResponse(200, body, DAY));
    }

    @Test
    void refusesPartialDays() {
        String body = schedule(DAY).replaceFirst("2026-11-19 22:00", "2026-11-19 21:00");
        assertThrows(java.io.IOException.class, () -> fetchResponse(200, body, DAY));
    }

    static java.util.List<PseRow> fetchResponse(int status, String body, LocalDate day) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/pdgsz", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        HttpClient http = new HttpClient();
        http.start();
        try {
            return new PseCompassClient(http,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/pdgsz")).fetch(day);
        } finally {
            http.stop();
            server.stop(0);
        }
    }

    static String schedule(LocalDate day) {
        var start = day.atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant();
        var end = day.plusDays(1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant();
        int hours = (int) java.time.Duration.between(start, end).toHours();
        var format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.of("UTC"));
        return "{\"value\":[" + IntStream.range(0, hours).mapToObj(i -> """
                {"dtime_utc":"%s","business_date":"%s","publication_ts_utc":"2026-11-18 10:00:00",
                "usage_fcst":%d,"is_active":true}
                """.formatted(format.format(start.plusSeconds(i * 3600L)), day, i % 4))
                .collect(Collectors.joining(",")) + "]}";
    }

    @Test
    void fetchesActiveScheduleForRequestedDay() throws Exception {
        var request = new AtomicReference<URI>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/pdgsz", exchange -> {
            request.set(exchange.getRequestURI());
            byte[] body = schedule(DAY).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        HttpClient http = new HttpClient();
        http.start();
        try {
            var client = new PseCompassClient(http, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/pdgsz"));
            var rows = client.fetch(DAY);
            assertEquals(24, rows.size());
            assertEquals(0, rows.getFirst().usageForecast());
            assertTrue(java.net.URLDecoder.decode(request.get().getRawQuery(), StandardCharsets.UTF_8)
                    .contains("business_date eq '2026-11-19' and is_active eq true"));
            assertTrue(request.get().getQuery().contains("$orderby=dtime_utc"));
            assertTrue(request.get().getQuery().contains("$first=100"));
        } finally {
            http.stop();
            server.stop(0);
        }
    }
}
