package jp.cobolinsight.core.encoding;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文字コードの自動判別。UTF-8/Shift_JISはICU4Jの内容判別で確定し、
 * EBCDIC(CP930/939)はバイト分布とSO/SI検出による推定に留める。
 */
public final class CodePageDetector {

    /** バイト分布による推定は内容判別より根拠が弱いため、確信度を低く固定する。 */
    private static final int EBCDIC_ESTIMATE_CONFIDENCE = 30;
    /** 候補が得られずUTF-8として復号できるかだけで決めた場合の確信度。 */
    private static final int FALLBACK_CONFIDENCE = 10;

    public DetectionResult detect(byte[] bytes) {
        boolean soSi = containsSoSi(bytes);
        // EBCDIC自動判別はIBM930を既定候補として返すに留める。IBM939はバイト分布から
        // IBM930と区別できないため自動判別せず、コードページの手動指定に委ねる。
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

    /** ICU4Jが候補を返さないときの既定。対象は日本語のソースであるため、UTF-8で復号できなければShift_JISとみなす。 */
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

    /** SO(0x0E)/SI(0x0F)を含むか。EBCDICの混在コードページは全角文字の区間をこの2バイトで挟む。 */
    static boolean containsSoSi(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0x0E || b == 0x0F) {
                return true;
            }
        }
        return false;
    }

    /**
     * EBCDICらしさの判定。ASCII可読率はDBCS第2バイトが0x20〜0x7Eへ大量に落ちるため
     * 識別力がなく、0x40(EBCDIC空白)率とSO/SI・0x0A(ASCII LF)の有無で判定する。
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
        // SO/SIを含む場合はEBCDICの根拠が強いため、0x40の出現率に求める下限を緩める。
        if (soSi && spaceRatio >= 0.05) {
            return true;
        }
        // SO/SIが無い場合は、EBCDICの改行が0x25であり0x0Aが現れないことを条件に加える。
        return asciiLf == 0 && spaceRatio >= 0.10;
    }
}
