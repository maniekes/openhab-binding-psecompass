#!/usr/bin/env python3
"""Check the built bundle, not source intent. Python standard library only."""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

SYMBOL = "pl.klimonda.openhab.binding.psecompass"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(jar, pom, tag):
    version = ET.parse(pom).findtext("{http://maven.apache.org/POM/4.0.0}version", "")
    require(re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", version), "pom version format")
    require(not tag or "SNAPSHOT" not in version, "cannot release SNAPSHOT")
    require(not tag or tag == "v" + version, "tag must match pom version")
    base, _, qualifier = version.partition("-")
    expected = re.escape(base)
    if qualifier == "SNAPSHOT":
        expected += r"\.[0-9]{12}"
    elif qualifier:
        expected += r"\." + re.escape(qualifier.replace(".", ""))
    with zipfile.ZipFile(jar) as archive:
        raw = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n", "\n")
        manifest = dict(line.split(": ", 1) for line in raw.replace("\n ", "").split("\n\n")[0].splitlines())
        require(manifest.get("Bundle-SymbolicName", "").split(";")[0] == SYMBOL, "bundle symbolic name")
        require(re.fullmatch(expected, manifest.get("Bundle-Version", "")), "bundle version")
        require('osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=21))"' in manifest.get("Require-Capability", ""), "Java 21 requirement")
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate ZIP entry")
        for name in names:
            content = archive.read(name)
            legacy = b"org.openhab.binding.psecompass"
            require(all(token not in name.encode() and token not in content
                        for token in (legacy, legacy.replace(b".", b"/"))), "legacy namespace: " + name)
            require(not name.lower().endswith(".jar") and
                    (not name.endswith(".class") or name.startswith(SYMBOL.replace(".", "/") + "/")), "embedded dependency: " + name)
            if name.endswith(".class"):
                require(archive.read(name)[:8] == bytes.fromhex("cafebabe00000041"), "Java 21 bytecode: " + name)
        factory = SYMBOL + ".internal.PseCompassHandlerFactory"
        descriptor = "OSGI-INF/" + factory + ".xml"
        require(descriptor in manifest.get("Service-Component", "").split(","), "DS wiring: descriptor not registered")
        ds = ET.fromstring(archive.read(descriptor))
        require(ds.find(f"implementation[@class='{factory}']") is not None and
                ds.find("service/provide[@interface='org.openhab.core.thing.binding.ThingHandlerFactory']") is not None and
                ds.find("reference[@interface='org.openhab.core.io.net.http.HttpClientFactory'][@parameter='0']") is not None and
                ds.get("init") == "1", "DS wiring")
        require(factory.replace(".", "/") + ".class" in names, "missing implementation class")
        for name in ("LICENSE", "NOTICE", "OH-INF/i18n/psecompass.properties", "OH-INF/i18n/psecompass_pl.properties"):
            require(bool(archive.read(name)), "empty resource: " + name)
        addon = ET.fromstring(archive.read("OH-INF/addon/addon.xml"))
        require(addon.get("id") == "psecompass" and addon.findtext("type") == "binding", "addon metadata")
        things = ET.fromstring(archive.read("OH-INF/thing/thing-types.xml"))
        require(things.get("bindingId") == "psecompass", "channel contract: binding ID")
        channels = things.findall("thing-type[@id='compass']/channels/channel")
        expected_channels = {"current-zone": "zone", "next-zone": "zone", "next-change": "next-change",
                             "schedule": "schedule", "last-successful-update": "last-successful-update", "zone-changed": "zone-changed"}
        require(len(channels) == 6 and {c.get("id"): c.get("typeId") for c in channels} == expected_channels, "channel contract: IDs")
        for type_id, item in {"zone": "Number", "next-change": "DateTime", "schedule": "String", "last-successful-update": "DateTime", "zone-changed": None}.items():
            channel = things.find(f"channel-type[@id='{type_id}']")
            if channel is None:
                raise ValueError("channel contract: missing type")
            require(channel.findtext("item-type") == item and
                    channel.findtext("kind", "state") == ("state" if item else "trigger"), "channel contract: Item/kind")
            if item:
                require(channel.find("state[@readOnly='true']") is not None, "channel contract: read-only")
        for path in ("channel-type[@id='zone']/state/options/option", "channel-type[@id='zone-changed']/event/options/option"):
            require([o.get("value") for o in things.findall(path)] == ["0", "1", "2", "3"], "channel contract: raw codes")
    return version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("--pom", default="pom.xml")
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    try:
        print("Verified " + args.jar + " (" + verify(args.jar, args.pom, args.tag) + ")")
    except (ValueError, KeyError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        sys.exit("Bundle verification failed: " + str(error))
