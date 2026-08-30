package jp.cobolinsight.core.encoding;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Automatic character-code detection. UTF-8/Shift_JIS are determined by ICU4J's content-based
 * detection; EBCDIC (CP930/939) is only estimated from byte distribution and SO/SI detection.
 */
public final class CodePageDetector {

    /** Estimation from byte distribution is weaker evidence than content-based detection, so confidence is fixed low. */
    private static final int EBCDIC_ESTIMATE_CONFIDENCE = 30;
    /** Confidence when no candidate was found and the decision rested solely on whether UTF-8 decoding succeeded. */
    private static final int FALLBACK_CONFIDENCE = 10;

    public DetectionResult detect(byte[] bytes) {
        boolean soSi = containsSoSi(bytes);
        // Automatic EBCDIC detection only ever returns IBM930 as the default candidate. IBM939
        // cannot be distinguished from IBM930 by byte distribution, so it is not auto-detected
        // and is instead left to a manual code-page specification.
        if (looksEbcdic(bytes, soSi)) {
            return new DetectionResult(CodePage.IBM930, EBCDIC_ESTIMATE_CONFIDENCE, soSi, true);
        }
        CharsetDetector icu = new CharsetDetector();
        icu.setText(bytes);
        CharsetMatch[] matches = icu.detectAll();
        if (matches != null) {
            for (CharsetMatch match : matches) {
                if ("UTF-8".equals(match.getName())) {
                    return new DetectionResult(CodePage.UTF_8, match.getConfidence(), soSi, false);
                }
                if ("Shift_JIS".equals(match.getName())) {
                    return new DetectionResult(CodePage.SHIFT_JIS, match.getConfidence(), soSi, false);
                }
            }
        }
        return fallback(bytes, soSi);
    }

    /** Default used when ICU4J returns no candidate. Since the target sources are Japanese, if UTF-8 decoding fails it is treated as Shift_JIS. */
    private static DetectionResult fallback(byte[] bytes, boolean soSi) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return new DetectionResult(CodePage.UTF_8, FALLBACK_CONFIDENCE, soSi, true);
        } catch (CharacterCodingException e) {
            return new DetectionResult(CodePage.SHIFT_JIS, FALLBACK_CONFIDENCE, soSi, true);
        }
    }

    /** Whether the bytes contain SO(0x0E)/SI(0x0F). Mixed EBCDIC code pages bracket full-width character runs with these two bytes. */
    static boolean containsSoSi(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0x0E || b == 0x0F) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines how EBCDIC-like the bytes look. The ASCII-printable ratio has no discriminating
     * power, since DBCS second bytes fall heavily into 0x20-0x7E; the decision instead uses the
     * ratio of 0x40 (EBCDIC space) together with the presence of SO/SI and 0x0A (ASCII LF).
     */
    private static boolean looksEbcdic(byte[] bytes, boolean soSi) {
        if (bytes.length == 0) {
            return false;
        }
        int ebcdicSpace = 0;
        int asciiLf = 0;
        for (byte b : bytes) {
            if (b == 0x40) {
                ebcdicSpace++;
            }
            if (b == 0x0A) {
                asciiLf++;
            }
        }
        double spaceRatio = ebcdicSpace / (double) bytes.length;
        // When SO/SI is present, the evidence for EBCDIC is strong, so the required minimum
        // 0x40 occurrence ratio is relaxed.
        if (soSi && spaceRatio >= 0.05) {
            return true;
        }
        // When SO/SI is absent, additionally require that 0x0A does not appear, since the
        // EBCDIC newline is 0x25.
        return asciiLf == 0 && spaceRatio >= 0.10;
    }
}
