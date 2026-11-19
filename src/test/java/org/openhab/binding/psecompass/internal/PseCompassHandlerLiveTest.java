package org.openhab.binding.psecompass.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openhab.binding.psecompass.internal.api.PseCompassClient;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.State;

/** Real scheduler + real transport, with recorded channel callbacks instead of an OSGi runtime. */
@EnabledIfSystemProperty(named = "pse.live", matches = "true")
class PseCompassHandlerLiveTest {
    @Test
    void publishesLiveDataThroughProductionConstructorAndSharedScheduler() throws Exception {
        var http = new HttpClient(new SslContextFactory.Client());
        http.start();
        var states = new ConcurrentHashMap<String, State>();
        var online = new CountDownLatch(1);
        var status = new java.util.concurrent.atomic.AtomicReference<String>();
        var handler = new PseCompassHandler(
                ThingBuilder.create(PseCompassBindingConstants.THING_TYPE_COMPASS, "live-test").build(),
                new PseCompassClient(http)) {
            @Override
            protected void updateState(String channel, State state) {
                states.put(channel, state);
            }

            @Override
            protected void updateStatus(ThingStatus value, ThingStatusDetail detail, String description) {
                status.set(value + ": " + description);
                if (value == ThingStatus.ONLINE) {
                    online.countDown();
                }
            }
        };
        try {
            handler.initialize();
            assertTrue(online.await(25, TimeUnit.SECONDS), status::get);
            Map<String, State> snapshot = Map.copyOf(states);
            var current = assertInstanceOf(DecimalType.class, snapshot.get("current-zone"));
            assertTrue(current.intValue() >= 0 && current.intValue() <= 3);
            assertInstanceOf(DateTimeType.class, snapshot.get("last-successful-update"));
            assertFalse(JsonParser.parseString(snapshot.get("schedule").toString()).getAsJsonArray().isEmpty());
            System.out.println("PSE handler live: current=" + current + ", next=" + snapshot.get("next-zone")
                    + ", change=" + snapshot.get("next-change") + ", status=" + status.get());
        } finally {
            handler.dispose();
            http.stop();
        }
    }
}
