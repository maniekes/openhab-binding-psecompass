package org.openhab.binding.psecompass.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class PseMetadataTest {
    @Test
    void channelsAndLocalizedRawCodeOptionsMatchHandlerContract() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var stream = getClass().getResourceAsStream("/OH-INF/thing/thing-types.xml")) {
            var document = factory.newDocumentBuilder().parse(stream);
            var xpath = XPathFactory.newInstance().newXPath();
            assertEquals("6", xpath.evaluate("count(//thing-type/channels/channel)", document));
            for (String id : new String[] { "current-zone", "next-zone", "next-change", "schedule",
                    "last-successful-update", "zone-changed" }) {
                assertEquals("1", xpath.evaluate("count(//thing-type/channels/channel[@id='" + id + "'])", document));
            }
            assertEquals("Number", xpath.evaluate("//channel-type[@id='zone']/item-type", document));
            assertEquals("trigger", xpath.evaluate("//channel-type[@id='zone-changed']/kind", document));
            assertEquals("true", xpath.evaluate("//channel-type[@id='zone']/state/@readOnly", document));
            String[] english = { "Recommended Usage", "Normal Usage", "Recommended Saving", "Required Reduction" };
            String[] polish = { "Zalecane użytkowanie", "Normalne użytkowanie", "Zalecane oszczędzanie", "Wymagane ograniczanie" };
            Properties pl = new Properties();
            try (var translations = new InputStreamReader(getClass().getResourceAsStream(
                    "/OH-INF/i18n/psecompass_pl.properties"), StandardCharsets.UTF_8)) {
                pl.load(translations);
            }
            for (int code = 0; code <= 3; code++) {
                assertEquals(english[code], xpath.evaluate("//channel-type[@id='zone']/state/options/option[@value='"
                        + code + "']", document));
                assertEquals(polish[code], pl.getProperty("channel-type.psecompass.zone.state.option." + code));
            }
        }
    }
}
