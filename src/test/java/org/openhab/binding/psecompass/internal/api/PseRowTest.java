package org.openhab.binding.psecompass.internal.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

class PseRowTest {
    @Test
    void normalizesUtcTimestampsWithoutUsingTheHostTimezone() {
        PseRow row = new Gson().fromJson("""
                {"dtime_utc":"2026-11-18 23:00","business_date":"2026-11-19",
                 "publication_ts_utc":"2026-11-19 07:26:19.862","usage_fcst":2,"is_active":true}
                """, PseRow.class);
        assertEquals(Instant.parse("2026-11-18T23:00:00Z"), row.start());
        assertEquals(Instant.parse("2026-11-19T07:26:19.862Z"), row.publishedAt());
        assertEquals(2, row.usageForecast());
    }
}
