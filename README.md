# openhab-binding-psecompass

Community openHAB binding for PSE Energetyczny Kompas (Energy Compass).

## Status

Minimal **read-only polling binding** with a manually configured `psecompass:compass` Thing.
It exposes consumption guidance and a UTC schedule; it does not control devices, implement tariff
policy, create Items or discover Things. Unit tests and a public-API smoke test are provided.
Compiling the bundle is not proof of OSGi resolution or acceptance on a running openHAB server;
installation and runtime verification are separate steps.

The current release candidate is **0.1.0-rc.2**, targeting openHAB **5.2.4 / Java 21**.
It is a prerelease, not a claim of compatibility with every openHAB 5.x version.

## Thing and channels

```java
Thing psecompass:compass:poland "PSE Energy Compass" [ refreshInterval=15 ]
```

`refreshInterval` is an integer number of minutes, 1–1440, default 15. Polling begins on the
shared openHAB scheduler, not on the initialization thread, and uses a fixed delay after each poll.
Each poll requests **today and tomorrow in Europe/Warsaw**, regardless of the host timezone.

| Channel | Item type | Meaning |
| --- | --- | --- |
| `current-zone` | Number | Raw PSE code for the current interval, or `UNDEF` |
| `next-zone` | Number | Next **different** code in the contiguous known schedule, or `UNDEF` |
| `next-change` | DateTime | Start of that next distinct zone, or `UNDEF` |
| `schedule` | String | JSON array of unexpired hourly intervals with `start`, exclusive `end`, `zone` |
| `last-successful-update` | DateTime | Local receipt time of the most recent non-empty, validated daily report |
| `zone-changed` | Trigger, no Item | New raw code as event payload (`0`, `1`, `2`, `3`) |

Number states are never translated into strings. English and Polish channel state-option labels are
included (display depends on the UI using the state description):

| Code | English | Polish |
| --- | --- | --- |
| 0 | Recommended Usage | Zalecane użytkowanie |
| 1 | Normal Usage | Normalne użytkowanie |
| 2 | Recommended Saving | Zalecane oszczędzanie |
| 3 | Required Reduction | Wymagane ograniczanie |

These meanings were checked against PSE's official Energetyczny Kompas frontend, whose status
mapping is `0 → recommended`, `1 → normal`, `2 → warning`, `3 → danger`. The API OpenAPI schema
only specifies an integer, not the enum meanings. Unknown codes are rejected explicitly as an
invalid daily report, never converted into one of the known recommendations.

Example Items (create manually; no device-control rule is supplied):

```java
Number PSE_CurrentZone "PSE current zone" { channel="psecompass:compass:poland:current-zone" }
Number PSE_NextZone "PSE next distinct zone" { channel="psecompass:compass:poland:next-zone" }
DateTime PSE_NextChange "PSE next change [%1$tF %1$tT]" { channel="psecompass:compass:poland:next-change" }
String PSE_Schedule "PSE schedule" { channel="psecompass:compass:poland:schedule" }
DateTime PSE_LastSuccess "PSE last successful update [%1$tF %1$tT]" { channel="psecompass:compass:poland:last-successful-update" }
```

Example schedule shape (illustrative, not live data):

```json
[{"start":"2026-11-18T23:00:00Z","end":"2026-11-19T00:00:00Z","zone":1}]
```

## Cache, failures and event semantics

- The cache is in memory, not persisted across restart. An empty tomorrow is normal before
  publication. A successful non-empty day replaces only that day; empty responses and failed
  requests do not discard still-valid cached data. Expired days are removed.
- A separate lightweight scheduler task checks time every second and republishes only when a
  cached interval boundary is due (or the clock moves backwards). Network I/O does not hold the
  cache lock. Boundaries therefore continue to update during an API outage; scheduling is not
  hard real-time and can be delayed by executor load.
- Any failed daily request sets the Thing `OFFLINE / COMMUNICATION_ERROR`, even when current
  cached values remain valid. A later successful polling cycle with valid current coverage returns
  it `ONLINE`. No valid current interval means `current-zone`, `next-zone` and `next-change` are
  `UNDEF`; future-only intervals can remain in `schedule` and activate at their start.
- Until the first polling cycle finishes, the Thing stays `UNKNOWN`, including on `REFRESH`.
  Communication status is based on the last completed cycle, not an intermediate daily response.
  A successful fetch without current coverage uses `OFFLINE / NONE`, not a communication error.
  Debug logging includes the causes of fetch/publication failures. Unexpected publication
  exceptions do not terminate repeating jobs: state/status publication is retried on a later
  evaluation, but a trigger with uncertain delivery is not replayed automatically.
- No next **distinct** known zone means `next-zone` and `next-change` are `UNDEF`, not a fabricated
  midnight change. Look-ahead does not cross gaps. JSON timestamps are UTC; DateTime Items may
  be displayed in the openHAB/UI timezone. DST days contain 23 or 25 real hourly intervals.
- `last-successful-update` is **not** the PSE publication timestamp and does not prove that both
  days succeeded. It advances on any non-empty validated daily response, even if the other day
  fails. It remains unchanged on empty responses, failures and local boundary updates.
- `zone-changed` fires only when an already observed cached interval is followed immediately
  by an observed interval with a different code. No event is emitted on startup, cache recovery,
  a same-zone hourly boundary, a forecast revision, or skipped/gapped intervals. Missed transitions
  are not replayed. A polling result cannot hide a boundary already present in the old cache.
- If a revised forecast arrives at a boundary, the elapsed boundary is observed against the
  previous cache before the revision is applied. The trigger can therefore carry the old plan's
  code while `current-zone` subsequently reflects the revision. Use Item state-change rules when
  every change, including forecast corrections, matters; the trigger specifically reports time
  transitions. A system-clock rollback followed by crossing the same UTC boundary again can
  produce another observed transition; this is not an exactly-once event stream.
- `REFRESH` republishes the cache evaluated at the current time; it does **not** perform HTTP I/O
  or force a remote poll. Other commands are ignored. All owned tasks are cancelled on disposal;
  late responses and callbacks from a previous initialization are discarded. The shared HTTP
  client and scheduler are never stopped by the binding.

## Build

See [release, installation and rollback instructions](docs/RELEASING.md).

Use **JDK 21** and **Maven 3.9.6 or newer**:

```sh
mvn -B -ntp clean verify
```

Output: `target/org.openhab.binding.psecompass-0.1.0-rc.2.jar`.
The default tests use loopback HTTP fixtures and deterministic clocks/schedulers. Opt-in tests
exercise the real public API and the production handler constructor/shared scheduler with recorded
channel callbacks (not an OSGi container):

```sh
mvn -B -ntp -Dpse.live=true -Dtest=PseLiveTest,PseCompassHandlerLiveTest test
```

This standalone POM uses Maven and Bnd, matching openHAB's bundle layout and declarative-service
generation without requiring a checkout of the full add-ons reactor. It is not the full upstream
SAT/Spotless quality pipeline. The official generator creates a module inheriting from that reactor:
https://github.com/openhab/openhab-addons/blob/5.2.4/bundles/create_openhab_binding_skeleton.sh

The build pins openHAB **5.2.4** artifacts, which were verified as downloadable from the official
openHAB Maven release repository. JDK 21, Gson 2.13.2 and Jetty 9.4.58.v20250814 match the
5.2.4 core/add-ons build metadata. All production dependencies have `provided` scope;
the bundle embeds no third-party libraries. JUnit is test-only. There is no Lombok.
The generated imports were checked against the resolved dependency JAR exports: openHAB packages
`[5.2,6)` are supplied by 5.2.4, Gson `[2.13,3)` by 2.13.2, and Jetty `[9.4,10)` by 9.4.58.
The bundle also requires Java 21, Declarative Services 1.5 and the `HttpClientFactory` service.
These artifact checks do not substitute for checking the actual installed runtime's resolver and
service wiring. The Thing XML was validated against the official openHAB XSD.

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
JSON syntax and the code/active field types are checked strictly: string-coerced numbers or
booleans and non-integral codes are rejected rather than silently converted.

`usage_fcst` remains the raw PSE code (0–3). This is consumption guidance, not a price or tariff.

## References

- Binding lifecycle: https://www.openhab.org/docs/developer/bindings/
- Core 5.2.4 dependency versions: https://github.com/openhab/openhab-core/blob/5.2.4/bom/compile/pom.xml
- Add-ons 5.2.4 build/JDK: https://github.com/openhab/openhab-addons/blob/5.2.4/pom.xml
- Public reports API: https://api.raporty.pse.pl/
- Official Compass frontend: https://www.energetycznykompas.pl/
- Verified frontend mapping asset: https://www.energetycznykompas.pl/_nuxt/C6cib_-z.js
- API schema (integer, no semantic enum): https://api.raporty.pse.pl/api/openapi

Licensed under EPL-2.0.
