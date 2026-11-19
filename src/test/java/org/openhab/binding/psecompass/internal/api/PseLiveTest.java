package org.openhab.binding.psecompass.internal.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in only: ordinary builds never depend on the availability of PSE. */
@EnabledIfSystemProperty(named = "pse.live", matches = "true")
class PseLiveTest {
    @Test
    void readsTodayAndTomorrowFromThePublicApi() throws Exception {
        HttpClient http = new HttpClient(new SslContextFactory.Client());
        http.start();
        try {
            var client = new PseCompassClient(http);
            LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
            var rows = client.fetch(today);
            assertFalse(rows.isEmpty(), "Today's schedule has not been published");
            System.out.println("PSE live: " + today + " rows=" + rows.size() + ", first=" + rows.getFirst().start());
            var tomorrow = client.fetch(today.plusDays(1));
            System.out.println("PSE live: " + today.plusDays(1) + " rows=" + tomorrow.size());
        } finally {
            http.stop();
        }
    }
}
