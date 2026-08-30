package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.source.DecodedSource;

/**
 * The contract for character encoding detection and decoding. Implemented by the encoding
 * module and assembled by app's EngineWiring.
 */
public interface CharsetProvider {

    /** Auto-detects the character encoding and decodes. The detection result and confidence are held in the returned EncodingInfo. */
    DecodedSource decode(String path, byte[] bytes);

    /** Decodes using a character encoding the user manually specified. The returned EncodingInfo has manualOverride=true. */
    DecodedSource decode(String path, byte[] bytes, String charsetName);
}
