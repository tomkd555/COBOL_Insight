package jp.cobolinsight.jclfrontend;

import jp.cobolinsight.engineapi.spi.JclParser;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** JclParser SPI の ServiceLoader 登録の検証。 */
class JclParserServiceLoaderTest {

    @Test
    void serviceLoaderDiscoversMapaJclParser() {
        boolean found = ServiceLoader.load(JclParser.class).stream()
                .anyMatch(p -> p.type() == MapaJclParser.class);
        assertTrue(found, "META-INF/services に MapaJclParser が登録されていること");
    }
}
