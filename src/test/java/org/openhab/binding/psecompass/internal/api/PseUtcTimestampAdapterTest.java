package org.openhab.binding.psecompass.internal.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;

class PseUtcTimestampAdapterTest {
    @Test
    void parsesMinutesSecondsAndNanosecondsAsUtcEvenOnAnotherHostZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var adapter = new PseUtcTimestampAdapter();
            assertEquals(Instant.parse("2026-11-18T23:00:00Z"),
                    adapter.deserialize(new JsonPrimitive("2026-11-18 23:00"), Instant.class, null));
            assertEquals(Instant.parse("2026-11-19T07:26:19Z"),
                    adapter.deserialize(new JsonPrimitive("2026-11-19 07:26:19"), Instant.class, null));
            assertEquals(Instant.parse("2026-11-19T07:26:19.123456789Z"),
                    adapter.deserialize(new JsonPrimitive("2026-11-19 07:26:19.123456789"), Instant.class, null));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
