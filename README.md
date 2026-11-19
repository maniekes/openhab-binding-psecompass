# openhab-binding-psecompass

Community openHAB binding for PSE Energetyczny Kompas (Energy Compass).

## Status

Buildable **development skeleton plus a tested read-only API client**, not a finished binding.
The `psecompass:compass` Thing and its OSGi handler factory are registered, but the handler deliberately
stays `UNKNOWN`: polling, channels, discovery and automation are not implemented. It does not
claim to be online without obtaining data. No installation or deployment has been performed.

## Build

Use **JDK 21** and **Maven 3.9.6 or newer**:

```sh
mvn -B -ntp clean verify
```

Output: `target/org.openhab.binding.psecompass-0.1.0-SNAPSHOT.jar`.
The default tests use only loopback HTTP fixtures. An opt-in test exercises the real public API:

```sh
mvn -B -ntp -Dpse.live=true -Dtest=PseLiveTest test
```

This standalone POM uses Maven and Bnd, matching openHAB's bundle layout and declarative-service
generation without requiring a checkout of the full add-ons reactor. It is not the full upstream
SAT/Spotless quality pipeline. The official generator creates a module inheriting from that reactor:
https://github.com/openhab/openhab-addons/blob/5.2.4/bundles/create_openhab_binding_skeleton.sh

The build pins openHAB **5.2.4** artifacts, which were verified as downloadable from the official
openHAB Maven release repository. JDK 21, Gson 2.13.2 and Jetty 9.4.58.v20250814 match the
5.2.4 core/add-ons build metadata. All production dependencies have `provided` scope;
the bundle embeds no third-party libraries. JUnit is test-only. There is no Lombok.

## API contract

`org.openhab.binding.psecompass.internal.api.PseCompassClient`:

```java
PseCompassClient(HttpClient sharedHttpClient)
List<PseRow> fetch(LocalDate businessDay) throws IOException, InterruptedException
```

The factory supplies `HttpClientFactory.getCommonHttpClient()`. The binding never starts, stops
or reconfigures that shared client. Call `fetch` on a scheduler/background thread, not in
`initialize()` or on the openHAB event thread. Preserve interruption when integrating polling.

The client queries `https://api.raporty.pse.pl/api/pdgsz` with the requested `business_date`,
`is_active eq true`, chronological ordering, and a page size of 100. Requests have a 10-second
total timeout and do not follow redirects. Only HTTP 200 is accepted. Jetty's synchronous
response listener bounds buffered response bodies; there is no unbounded accumulation.

There are exactly **two wire DTO records**: `PseResponse` (envelope) and `PseRow` (consumed fields).
`PseRow` exposes `start()` and `publishedAt()` as `Instant`, `businessDate()` as `String`,
`usageForecast()` as `Integer`, and `active()` as `Boolean`. Extra server fields are ignored.
A small Gson deserializer delegates timestamp parsing to `java.time.DateTimeFormatter` and
explicitly assigns UTC to `*_utc` fields, which have no offset in the wire format. Minutes,
seconds and fractional seconds are supported. There is no handwritten date parser. openHAB
5.2.4 `DateTimeType(String)` can parse a space separator, but its fallback timezone is the host
timezone; it is therefore not used to interpret these unzoned UTC fields.

An empty list means no published data (including tomorrow before publication), **not** a
recommendation. Non-empty schedules must cover the complete Polish business day, with ordered,
consecutive hourly records: 23, 24 or 25 hours according to `Europe/Warsaw` DST rules. Missing,
duplicate, inactive or wrong-day rows fail explicitly. Any nonblank `nextLink` fails explicitly
rather than silently returning a truncated schedule or following an arbitrary URL.
The result is immutable. HTTP/protocol failures are `IOException`; interruption propagates.

`usage_fcst` remains the raw PSE code. No price, tariff, colour or translated recommendation
mapping is inferred from that number. Semantic labels must be checked against primary PSE
documentation before channels are added.

## References

- Binding lifecycle: https://www.openhab.org/docs/developer/bindings/
- Core 5.2.4 dependency versions: https://github.com/openhab/openhab-core/blob/5.2.4/bom/compile/pom.xml
- Add-ons 5.2.4 build/JDK: https://github.com/openhab/openhab-addons/blob/5.2.4/pom.xml
- Public reports API: https://api.raporty.pse.pl/

Licensed under EPL-2.0.
