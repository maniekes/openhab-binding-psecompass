# Changelog

## 0.1.0-rc.1

First release candidate for openHAB 5.2.4 and Java 21. This remains a prerelease.

- PSE's current recommendation, next distinct zone/change, remaining UTC schedule and last successful update, with numeric 0–3 states and English/Polish labels.
- Independent API polling and cached boundary evaluation; no device-control policy or tariff pricing.
- Use a single captured time when observing and replacing a schedule, avoiding a lost boundary trigger during a polling race.
- Contain unexpected publication exceptions so scheduled polling and boundary checks continue; retry state/status publication without replaying uncertain trigger events.
- Keep UNKNOWN during initial loading; derive communication status from completed polling cycles and distinguish missing current coverage from communication failure.
- Preserve both failed dates and log root causes at debug level.
- Reject malformed JSON and number/boolean string coercion; use exact integral recommendation codes.
- Preserve existing Thing UID, channel IDs, Item types and trigger payloads.
- Add CI, bundle packaging regression checks, commit-identifiable artifacts and draft-only tagged releases with SHA-256 checksums. No automatic deployment.

### Known boundaries

- The schedule cache is in memory and is rebuilt after a bundle reload.
- The time-transition trigger is not a forecast-revision notification or an exactly-once stream. See README for boundary/revision and clock-rollback behavior.
- Framework-callback tests are not a substitute for observing a real boundary event in a running OSGi installation.
- Full upstream SAT/Spotless checks are not integrated; compatibility beyond openHAB 5.2.4 has not been established.
