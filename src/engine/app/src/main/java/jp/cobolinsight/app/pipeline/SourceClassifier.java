package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.encoding.CodePageDetector;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.FixedFormatColumns;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Back-calculates the asset kind from the source content. A pure function with no file I/O;
 * classification uses only the given byte array. Because the classification table is the part
 * most prone to regressions in this rework, it is kept separate from I/O so it can be pinned down
 * precisely with unit tests that feed byte arrays directly.
 *
 * <p><b>Do not cap the amount read.</b> Once a marker line appears, stop reading right there;
 * if none appears, read to the end. A control such as "read only the first N lines" would silently
 * drop a source that has {@code IDENTIFICATION DIVISION} on line N+1. That is left out because it
 * would produce a loss the user could not otherwise explain.
 *
 * <p>Character-set detection is delegated entirely to {@link CodePageDetector}; no independent
 * classification criteria live here. Once judged EBCDIC, the content is decoded as single-byte
 * IBM037; otherwise as ISO-8859-1. Either way one byte maps to one character, so the character
 * position is exactly the card column. All keywords used for classification are ASCII, so garbled
 * Japanese comments never affect the kind decision.
 */
final class SourceClassifier {

    /**
     * The outcome of content classification. When kind is null, binary true means "confirmed not
     * to be text"; false means "the kind could not be determined." The distinction matters
     * because the former is a firm judgment rather than a loss, and that changes how it is
     * reported.
     */
    record Verdict(AssetKind kind, boolean binary) {

        static final Verdict UNDECIDED = new Verdict(null, false);
        static final Verdict BINARY = new Verdict(null, true);

        static Verdict of(AssetKind kind) {
            return new Verdict(kind, false);
        }

        boolean decided() {
            return kind != null;
        }
    }

    /**
     * A JCL control statement. A line starting with {@code //} cannot collide with any other
     * kind, so it is judged first. The name field follows the mainframe member-name rule (starts
     * with a letter or national character, at most 8 characters).
     */
    private static final Pattern JCL_STATEMENT = Pattern.compile(
            "^//([A-Z@#$][A-Z0-9@#$]{0,7})?\\s+"
                    + "(JOB|EXEC|DD|PROC|PEND|SET|INCLUDE|IF|ELSE|ENDIF|OUTPUT|JCLLIB|COMMAND)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A BMS macro call. The name field may be empty. */
    private static final Pattern BMS_MACRO =
            Pattern.compile("^\\S*\\s+DFH(MSD|MDI|MDF)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The keyword that names a COBOL main body. Distinguishing it from a copybook is unified
     * around whether this keyword is present: a copybook is a fragment inserted into the main
     * body and writes neither DIVISION nor PROGRAM-ID, which is the one stable feature that
     * separates the two.
     */
    private static final Pattern COBOL_MARKER = Pattern.compile(
            "\\b(IDENTIFICATION\\s+DIVISION|ID\\s+DIVISION|ENVIRONMENT\\s+DIVISION"
                    + "|DATA\\s+DIVISION|PROCEDURE\\s+DIVISION|PROGRAM-ID)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A data item level number. Accepts 01-49, 66, 77, 88. */
    private static final Pattern LEVEL_NUMBER =
            Pattern.compile("^(0?[1-9]|[1-4][0-9]|66|77|88)\\s+\\S");

    /** Single-byte EBCDIC. Used instead of a mixed-byte code page so columns and byte positions line up. */
    private static final Charset EBCDIC_SINGLE_BYTE = Charset.forName("IBM037");

    /** NEL, used as the line separator in EBCDIC. Decodes to U+0085 under IBM037. */
    private static final char NEXT_LINE = (char) 0x85;

    private static final CodePageDetector DETECTOR = new CodePageDetector();

    private SourceClassifier() {
    }

    /**
     * Classifies the kind from a byte array.
     *
     * <p>The spec lists the signature {@code classify(String fileName, byte[])}, but content
     * classification never looks at the file name at all. Accepting it would read as "the name
     * also affects the decision," which would be a dead parameter, so it was dropped.
     * Matching against the extension is the responsibility of the caller ({@link SourceDiscovery}).
     */
    static Verdict classify(byte[] content) {
        if (containsNul(content)) {
            // NUL never appears in COBOL/JCL/BMS text. Even in an EBCDIC card image, blank is
            // 0x40. The moment even a single byte appears, the content is confirmed to not be text, so no count threshold is needed.
            return Verdict.BINARY;
        }
        String text = decode(content);
        Boolean firstSignificantIsLevelNumber = null;
        int from = 0;
        while (from <= text.length()) {
            int breakAt = indexOfLineBreak(text, from);
            String line = text.substring(from, breakAt < 0 ? text.length() : breakAt);
            Verdict verdict = classifyLine(line);
            if (verdict.decided()) {
                return verdict;
            }
            if (firstSignificantIsLevelNumber == null && isSignificant(line)) {
                firstSignificantIsLevelNumber =
                        LEVEL_NUMBER.matcher(FixedFormatColumns.body(line)).find();
            }
            if (breakAt < 0) {
                break;
            }
            from = breakAt + lineBreakLength(text, breakAt);
        }
        // If the keyword naming a COBOL main body never appears through to the end, and the first significant line starts with a level number, it is a copybook.
        return Boolean.TRUE.equals(firstSignificantIsLevelNumber)
                ? Verdict.of(AssetKind.COPYBOOK) : Verdict.UNDECIDED;
    }

    /** Reads strong evidence from a single line. Returns {@link Verdict#UNDECIDED} for comment lines and lines with no evidence. */
    private static Verdict classifyLine(String line) {
        if (!isSignificant(line)) {
            return Verdict.UNDECIDED;
        }
        if (JCL_STATEMENT.matcher(line).find()) {
            return Verdict.of(AssetKind.JCL);
        }
        if (BMS_MACRO.matcher(line).find()) {
            return Verdict.of(AssetKind.BMS);
        }
        if (COBOL_MARKER.matcher(FixedFormatColumns.body(line)).find()) {
            return Verdict.of(AssetKind.COBOL);
        }
        return Verdict.UNDECIDED;
    }

    /**
     * Whether the line can be used as evidence for classification. Skips all three comment forms,
     * since the comment column differs by kind. Skipping the {@code *} in column 7 of fixed
     * format is essential. A COBOL heading comment commonly writes body keywords verbatim, as in
     * {@code PROGRAM-ID : ~}; treating a comment as body text would let a copybook or JCL file
     * pass as COBOL.
     */
    private static boolean isSignificant(String line) {
        if (line.isBlank()) {
            return false;
        }
        if (line.startsWith("//*") || line.startsWith("/*")) {
            return false;
        }
        if (line.charAt(0) == '*') {
            return false;
        }
        char indicator = FixedFormatColumns.indicator(line);
        return indicator != '*' && indicator != '/';
    }

    private static boolean containsNul(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The copy used for classification. An EBCDIC source may use NEL (0x15) as its line
     * separator, which decodes to U+0085 under IBM037, so only for EBCDIC is this converted to a
     * newline. Doing the same on the side that decodes as ISO-8859-1 would misread the 0x85 byte
     * that appears inside a UTF-8 Japanese character (e.g. "共" = E5 85 B1) as a line break.
     */
    private static String decode(byte[] content) {
        boolean ebcdic = DETECTOR.detect(content).codePage().isEbcdic();
        Charset charset = ebcdic ? EBCDIC_SINGLE_BYTE : StandardCharsets.ISO_8859_1;
        String text = new String(content, charset);
        return ebcdic ? text.replace(NEXT_LINE, '\n') : text;
    }

    /** The position of the next line break. -1 if none. */
    private static int indexOfLineBreak(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    private static int lineBreakLength(String text, int at) {
        return text.charAt(at) == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n'
                ? 2 : 1;
    }
}
