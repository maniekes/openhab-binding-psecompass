"""Packaging regression tests; ZIP fixtures are not executable bundles."""
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).with_name("verify_bundle.py")
SYMBOL = "pl.klimonda.openhab.binding.psecompass"
FACTORY = SYMBOL + ".internal.PseCompassHandlerFactory"
DS = "OSGI-INF/" + FACTORY + ".xml"


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.entries = {"META-INF/MANIFEST.MF": (
            "Manifest-Version: 1.0\r\n"
            f"Bundle-SymbolicName: {SYMBOL}\r\n"
            "Bundle-Version: 0.1.0\r\n"
            "Require-Capability: osgi.ee;filter:=\"(&(osgi.ee=JavaSE)\r\n"
            " (version=21))\"\r\n"
            f"Service-Component: {DS}\r\n\r\n"
        ).encode()}
        self.entries[DS] = (
            '<scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.4.0" init="1">'
            f'<implementation class="{FACTORY}"/>'
            '<service><provide interface="org.openhab.core.thing.binding.ThingHandlerFactory"/></service>'
            '<reference interface="org.openhab.core.io.net.http.HttpClientFactory" parameter="0"/>'
            '</scr:component>').encode()
        # The class header represents Java 21 bytecode, not an executable mock.
        self.entries[FACTORY.replace(".", "/") + ".class"] = bytes.fromhex("cafebabe00000041")
        for relative in ("addon/addon.xml", "thing/thing-types.xml", "i18n/psecompass.properties",
                         "i18n/psecompass_pl.properties"):
            self.entries["OH-INF/" + relative] = (ROOT / "src/main/resources/OH-INF" / relative).read_bytes()
        self.entries["LICENSE"] = (ROOT / "LICENSE").read_bytes()
        self.entries["NOTICE"] = (ROOT / "NOTICE").read_bytes()

    def run_verifier(self, version="0.1.0", tag="", error=None):
        pom = self.directory / "pom.xml"
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                       f'<version>{version}</version></project>')
        jar = self.directory / "binding.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for name, value in self.entries.items():
                archive.writestr(name, value)
        result = subprocess.run([sys.executable, str(SCRIPT), str(jar),
                                 "--pom", str(pom), "--tag", tag],
                                text=True, capture_output=True)
        if error is None:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(error, result.stderr)

    def test_bad_ds_wiring(self):
        for old, new in ((b'HttpClientFactory', b'MissingFactory'),
                         (b'parameter="0"', b'parameter="1"'),
                         (b'ThingHandlerFactory', b'OtherFactory')):
            with self.subTest(old=old):
                original = self.entries[DS]
                self.entries[DS] = original.replace(old, new)
                self.run_verifier(error="DS wiring")
                self.entries[DS] = original

    def test_missing_registered_descriptor(self):
        del self.entries[DS]
        self.run_verifier(error="OSGI-INF/")

    def test_missing_implementation_class(self):
        del self.entries[FACTORY.replace(".", "/") + ".class"]
        self.run_verifier(error="implementation class")

    def test_missing_metadata(self):
        del self.entries["OH-INF/i18n/psecompass_pl.properties"]
        self.run_verifier(error="OH-INF/i18n/")

    def test_channel_contract(self):
        name = "OH-INF/thing/thing-types.xml"
        original = self.entries[name]
        for old, new in ((b'id="current-zone"', b'id="renamed-zone"'),
                         (b'<item-type>Number', b'<item-type>String'),
                         (b'<kind>trigger', b'<kind>state'),
                         (b'readOnly="true"', b'readOnly="false"')):
            with self.subTest(old=old):
                self.entries[name] = original.replace(old, new)
                self.run_verifier(error="channel contract")
        self.entries[name] = original

    def test_embedded_dependencies(self):
        for name in ("com/google/gson/Gson.class", "lib/jetty.jar"):
            with self.subTest(name=name):
                self.entries[name] = b"unexpected dependency"
                self.run_verifier(error="embedded dependency")
                del self.entries[name]

    def test_java_requirement(self):
        self.entries["META-INF/MANIFEST.MF"] = self.entries["META-INF/MANIFEST.MF"].replace(b'version=21', b'version=17')
        self.run_verifier(error="Java 21 requirement")

    def test_wrong_bytecode_level(self):
        self.entries[FACTORY.replace(".", "/") + ".class"] = bytes.fromhex("cafebabe00000042")
        self.run_verifier(error="Java 21 bytecode")

    def test_stable_version_and_matching_tag(self):
        self.run_verifier(tag="v0.1.0")

    def test_timestamped_snapshot(self):
        self.entries["META-INF/MANIFEST.MF"] = self.entries["META-INF/MANIFEST.MF"].replace(
            b"Bundle-Version: 0.1.0", b"Bundle-Version: 0.1.0.202611190911")
        self.run_verifier(version="0.1.0-SNAPSHOT")

    def test_snapshot_cannot_be_released(self):
        self.run_verifier(version="0.1.0-SNAPSHOT", tag="v0.1.0-SNAPSHOT", error="SNAPSHOT")

    def test_prerelease_uses_bnd_qualifier(self):
        self.entries["META-INF/MANIFEST.MF"] = self.entries["META-INF/MANIFEST.MF"].replace(
            b"Bundle-Version: 0.1.0", b"Bundle-Version: 0.1.0.rc1")
        self.run_verifier(version="0.1.0-rc.1", tag="v0.1.0-rc.1")

    def test_tag_must_match_pom(self):
        self.run_verifier(tag="v0.2.0", error="tag must match")

    def test_legacy_namespace_rejected(self):
        legacy = b"org.openhab.binding.psecompass"
        for name, content in (
                ("legacy.txt", legacy),
                ("legacy.txt", legacy.replace(b".", b"/")),
                (legacy.decode().replace(".", "/") + "/Legacy.class", bytes.fromhex("cafebabe00000041"))):
            with self.subTest(name=name, content=content):
                self.entries[name] = content
                self.run_verifier(error="legacy namespace")
                del self.entries[name]

    def test_legacy_bundle_identity_rejected(self):
        self.entries["META-INF/MANIFEST.MF"] = self.entries["META-INF/MANIFEST.MF"].replace(
            SYMBOL.encode(), b"org.openhab.binding.psecompass")
        self.run_verifier(error="bundle symbolic name")


if __name__ == "__main__":
    unittest.main()
