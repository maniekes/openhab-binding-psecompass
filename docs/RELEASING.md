# Releases and manual updates

## Compatibility and identity

The tested runtime baseline is **openHAB 5.2.4 with Java 21**. The POM targets
5.2.4; import ranges are not a claim that every openHAB 5.x release works.
Each candidate still needs runtime acceptance. CI checks packaging, not an OSGi
container, the live provider's future availability, or household rules.

For updates within the new bundle identity, preserve the existing
`psecompass:compass:<id>` Thing UID, configuration, channel
IDs and Item links during updates. The contract is five read-only state channels
(`current-zone`/`next-zone`: Number, `next-change`/`last-successful-update`:
DateTime, `schedule`: String), plus the `zone-changed` trigger with no Item.
Do not delete/recreate Things or rename Items just to replace the JAR.

## Namespace migration from 0.1.x

Version **0.2.0-rc.1** deliberately changes Maven coordinates to
`pl.klimonda.openhab.binding:psecompass:0.2.0-rc.1`, Java packages and the actual
OSGi Bundle-SymbolicName to `pl.klimonda.openhab.binding.psecompass`.
The binding ID `psecompass`, Thing type and channel contract do not change.
No legacy bundle/package alias is supplied. Published old tags remain immutable.
This section is a migration procedure, not evidence of completed runtime acceptance.

1. In an authorized maintenance window, back up the installed old JAR/checksum,
   bundle identity/status and the relevant Thing, Items, links and page definitions.
2. Remove the old JAR from the watched `addons` directory and uninstall the old
   bundle. Verify that its old bundle identity and handler service are absent
   before installing the new JAR. An atomic overwrite alone is not sufficient
   proof of migration across different bundle identities.
3. Install only `psecompass-0.2.0-rc.1.jar` (or a fixed `psecompass.jar` filename),
   using the checksum/staging precautions below. Verify the new symbolic name,
   OSGi version `0.2.0.rc1`, activation and DS service wiring.
4. If intentionally replacing a test setup, recreate the scoped Thing as
   `psecompass:compass:poland` and use `PSECompass_CurrentZone`,
   `PSECompass_NextZone`, `PSECompass_NextChange`, `PSECompass_Schedule` and
   `PSECompass_LastUpdate`, with the links in README. Replace references to old
   test Items in the page and any affected rules; remove obsolete test entities
   only within the approved scope. This cleanup is optional for installations
   already using their intended Thing/Items, not a change to the binding contract.
5. Verify ONLINE, a successful fetch after activation, each channel/link, Polish
   labels and rendered page references. Record the exact artifact hash and
   observed results; do not infer runtime success from the build or old states.
6. To roll back across this identity change, uninstall/remove the new bundle
   first, restore the saved old JAR and any intentionally changed configuration,
   then verify old bundle/Thing/link health. Never install both identities together.

## Build and draft a release

1. Select a version deliberately; no stable release version is implied by this
   document. Set the POM to `X.Y.Z` or a prerelease such as `X.Y.Z-rc.1`, with no
   `SNAPSHOT`. Commit the reviewed changes, then create the matching `vX.Y.Z`
   (or `vX.Y.Z-rc.1`) tag only when release creation is authorized.
2. CI runs JDK 21 / Maven `clean verify`, Python verifier regressions and checks
   the **produced JAR**: bundle identity/version, Java requirement and bytecode,
   DS factory/HTTP-service injection, metadata/channel contract and absence of
   embedded dependencies. A tag must exactly match the non-SNAPSHOT POM version.
   Bnd maps prerelease qualifiers to OSGi versions (for example `rc.1` → `rc1`);
   development SNAPSHOT builds already receive a timestamp qualifier.
3. Master pushes, pull requests and manual runs archive the tested JAR and
   `SHA256SUMS`. Artifact names identify `dev` or the tag and the full commit;
   JAR filenames also carry the commit. CI artifacts expire after 30 days.
   Manual dispatch can optionally run the public PSE API tests; these are not
   required for ordinary offline unit tests and are not OSGi runtime tests.
4. A tag push (or manual workflow dispatch targeting that tag) creates a **draft** GitHub Release from that same build artifact,
   without rebuilding. Hyphenated versions are marked prerelease. Only this job
   has `contents: write`; nothing deploys automatically. If a draft already
   exists, a rerun fails rather than silently replacing assets. Inspect the
   existing draft and resolve it manually; do not move a released tag.
5. Before publication, download the draft assets, verify `sha256sum -c
   SHA256SUMS`, and perform the separately authorized runtime acceptance below.
   Record the exact commit, checksum, bundle version, runtime version and result
   in the release notes. Publish the draft only after acceptance; a green build
   alone is not acceptance. Keep checksums/assets for rollback.

Local packaging checks (Python standard library only):

```sh
mvn -B -ntp clean verify
python3 -m unittest discover -s scripts/verification -v
python3 scripts/verification/verify_bundle.py target/psecompass-*.jar
# For a release candidate, append --tag vX.Y.Z (matching the actual POM).
```

## Install, update and rollback

The following replacement procedure applies within the new bundle identity;
for 0.1.x, complete the explicit uninstall/install migration above instead.

Use the installation's actual `addons` directory and its existing ownership;
no production paths or credentials belong in this repository.

1. Download the chosen JAR and its `SHA256SUMS` outside `addons`; verify the
   checksum. Record current bundle version/status, Thing UID/status, linked
   Items and relevant logs. Retain a verified copy of the previous JAR **outside
   `addons`**, together with its checksum. Back up configuration as usual.
2. Keep **one** installed PSE Compass JAR. Prefer a fixed installed filename
   such as `psecompass.jar`, independent of asset version.
   Stage the verified new bytes on the same filesystem as `addons`, with a
   non-`.jar` suffix (for example `.jar.pending`), and recheck the checksum and
   readability by openHAB before an atomic rename over the installed filename.
   Do not stream a download directly into a watched `.jar` file.
3. When migrating from a versioned installed filename, use a maintenance window:
   stop openHAB, move the old JAR outside `addons`, rename the staged file to the
   fixed filename, then start openHAB. Never leave old and new `.jar` files
   together. Do not alter directory permissions to make an update work.
4. Confirm bundle resolution/activation and `HttpClientFactory` wiring, no
   linkage/DS errors in logs, the same Thing UID and channel/Item links, and
   ONLINE with plausible live values. Check Polish labels, schedule freshness,
   and refresh behavior. Startup is not expected to emit `zone-changed`; a real
   boundary event needs separate observation. Do not add device-control rules
   as an installation test.
5. On failure, stage the backed-up JAR with a non-`.jar` suffix, verify its saved
   checksum, then atomically replace the same installed filename (or use the
   stopped-runtime procedure). Verify bundle/Thing/channel health again. Retain
   the failed artifact and logs outside `addons` for diagnosis. Do not delete
   Things, Item links or configuration as a rollback shortcut.
