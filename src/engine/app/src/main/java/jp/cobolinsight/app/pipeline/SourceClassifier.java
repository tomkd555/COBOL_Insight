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
 * <p><b>Which kind wins.</b> A marker that cannot belong to another kind decides on the spot: JCL's
 * {@code //} statement, BMS's DFHMSD macro, the DBD and PCB macros of an IMS generation, COBOL's
 * DIVISION and PROGRAM-ID, and the JOB INPUT, SORT … TO … USING and FILE … FB(…) statements of
 * Easytrieve. An Easytrieve source that writes none of those three is decided at the end from a
 * FILE and a JOB statement between them, the way a copybook is decided from its level number. SQL is decided last and
 * only from the first significant line, so a {@code .sql} file holding COBOL or JCL stays COBOL or
 * JCL — the marker of either appears somewhere in it and takes the decision before the end is
 * reached. A copybook is decided the same way, from the level number on its first significant line.
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

    /** The DBD statement that opens an IMS DBDGEN source. The label field may be empty. */
    private static final Pattern DBD_MACRO =
            Pattern.compile("^\\S*\\s+DBD\\s+NAME=", Pattern.CASE_INSENSITIVE);

    /** A PCB statement, or the PSBGEN that closes the source, of an IMS PSBGEN source. */
    private static final Pattern PSB_MACRO =
            Pattern.compile("^\\S*\\s+(PCB\\s+TYPE=|PSBGEN(\\s|$))", Pattern.CASE_INSENSITIVE);

    /**
     * A statement only Easytrieve writes: a JOB activity with its INPUT, a SORT activity, or a FILE
     * statement carrying a record format. Any one of them decides, because none of the three is a
     * line another kind could begin with: COBOL and JCL have declared themselves by the time such
     * a line is reached, and a copybook or an SQL script has no such line at all.
     */
    private static final Pattern EASYTRIEVE_STATEMENT = Pattern.compile(
            "^\\s*(JOB\\s+INPUT(\\s|$)|SORT\\s+\\S+\\s+TO\\s+\\S+\\s+USING(\\s|$)"
                    + "|FILE\\s+[A-Z@#$][A-Z0-9@#$_-]*\\s+(?:FB|VB|VBS|F|V|U)\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    /**
     * Weaker Easytrieve evidence, which takes two lines to decide: a FILE statement and a JOB
     * activity. Each has to be a whole statement of the language — FILE with a name and nothing
     * after it but the attributes a FILE statement takes, JOB alone or with one of its own
     * keywords — because a line of prose can begin with either word, and so can the FILE SECTION,
     * FILE STATUS and FILE-CONTROL of COBOL.
     */
    private static final Pattern EASYTRIEVE_FILE = Pattern.compile(
            "^\\s*FILE\\s+[A-Z@#$][A-Z0-9@#$_-]{0,39}(\\s+(VS|IS|SQL|DLI|IDMS|VIRTUAL|PRINTER|PUNCH"
                    + "|CARD|TABLE|EXIT|SEQUENTIAL|RETAIN|DEFER|ASA|BUFNO|WORKAREA|EXTENDED"
                    + "|SYS[0-9]+|F|V|U|FB|VB|VBS)\\b.*)?\\s*$");
    private static final Pattern EASYTRIEVE_JOB = Pattern.compile(
            "^\\s*JOB(\\s+(INPUT|NAME|START|FINISH|COMMIT|ENVIRONMENT|ACTIVITY|TERMINAL)\\b.*)?"
                    + "\\s*\\.?\\s*$");

    /** The COBOL phrases that begin with FILE and are no Easytrieve FILE statement. */
    private static final Pattern COBOL_FILE_PHRASE =
            Pattern.compile("^\\s*FILE\\s+(SECTION|STATUS|CONTROL)\\b");

    /**
     * What an Easytrieve source opens with when it is not a FILE statement: the PARM statement of a
     * program, or the prototype of a macro member, which starts in column 1 (an assembler macro
     * definition writes the word in the operation field, never there).
     */
    private static final Pattern EASYTRIEVE_PARM =
            Pattern.compile("^\\s*PARM\\s+\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern EASYTRIEVE_MACRO =
            Pattern.compile("^MACRO(\\s|$)", Pattern.CASE_INSENSITIVE);

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

    /**
     * The word an SQL script's first statement starts with. Three things narrow it, because SET,
     * CALL, DELETE and MERGE are verbs of both COBOL and SQL and the first two are IDCAMS control
     * cards as well: the keyword has to be the whole first token, so a {@code SET-FLAG} data name
     * does not match; it has to begin within the first seven columns, which is where a script writes
     * a statement and where COBOL writes nothing but its sequence number; and only the first line of
     * the file that is not a comment is offered here. A script that indents its statements into
     * Area B is left to its extension.
     */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "^ {0,6}(CREATE|ALTER|DROP|GRANT|REVOKE|SELECT|INSERT|UPDATE|DELETE|MERGE|COMMENT"
                    + "|LABEL|SET|DECLARE|CALL|WITH|VALUES|CONNECT|EXPLAIN|LOCK|RENAME|TRUNCATE"
                    + "|REFRESH)(?=\\s|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * The line that names the terminator a script ends its statements with. DSNTEP4 writes it as a
     * comment; SPUFI's own control line writes the word on its own.
     */
    private static final Pattern TERMINATOR_DIRECTIVE = Pattern.compile(
            "(?im)^\\s*(?:--\\s*#?\\s*SET\\s+)?TERMINATOR\\s+\\S\\s*$");

    /**
     * The byte order mark, in the two spellings this class can meet. The bytes are decoded as
     * ISO-8859-1, so a UTF-8 mark arrives as three characters rather than as U+FEFF; either way it
     * stands in front of the first keyword and would hide it.
     */
    private static final String UTF8_BOM_AS_LATIN1 = "ï»¿";

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
        boolean firstSignificantIsSqlStatement = false;
        boolean opensLikeEasytrieve = false;
        boolean opensWithMacroPrototype = false;
        boolean easytrieveFile = false;
        boolean easytrieveJob = false;
        int from = 0;
        int blockCommentEnd = 0;
        while (from <= text.length()) {
            int breakAt = indexOfLineBreak(text, from);
            String line = text.substring(from, breakAt < 0 ? text.length() : breakAt);
            if (from >= blockCommentEnd) {
                if (line.startsWith("/*")) {
                    // A banner spanning several lines hides its continuations too. An unclosed
                    // /* is JCL's delimiter statement, not a banner, so it opens nothing.
                    int close = text.indexOf("*/", from + 2);
                    if (close >= 0) {
                        blockCommentEnd = close + 2;
                    }
                }
                Verdict verdict = classifyLine(line);
                if (verdict.decided()) {
                    return verdict;
                }
                if (firstSignificantIsLevelNumber == null && isSignificant(line)) {
                    firstSignificantIsLevelNumber =
                            LEVEL_NUMBER.matcher(FixedFormatColumns.body(line)).find();
                    firstSignificantIsSqlStatement = SQL_STATEMENT.matcher(line).find();
                    opensLikeEasytrieve = EASYTRIEVE_PARM.matcher(line).find();
                    opensWithMacroPrototype = EASYTRIEVE_MACRO.matcher(line).find();
                }
                if (isSignificant(line)) {
                    // Columns 1 to 72, as the language reads a line: 73 onwards is the sequence
                    // number, and the two statements are written in upper case.
                    String statement = line.length() > 72 ? line.substring(0, 72) : line;
                    easytrieveFile |= EASYTRIEVE_FILE.matcher(statement).find()
                            && !COBOL_FILE_PHRASE.matcher(statement).find();
                    easytrieveJob |= EASYTRIEVE_JOB.matcher(statement).find();
                }
            }
            if (breakAt < 0) {
                break;
            }
            from = breakAt + lineBreakLength(text, breakAt);
        }
        // If the keyword naming a COBOL main body never appears through to the end, and the first significant line starts with a level number, it is a copybook.
        if (Boolean.TRUE.equals(firstSignificantIsLevelNumber)) {
            return Verdict.of(AssetKind.COPYBOOK);
        }
        // An Easytrieve source that wrote none of its unmistakable statements: a FILE and a JOB
        // between them, a PARM in front of either, or the prototype of a macro member.
        if ((easytrieveFile && easytrieveJob)
                || (opensLikeEasytrieve && (easytrieveFile || easytrieveJob))
                || opensWithMacroPrototype) {
            return Verdict.of(AssetKind.EASYTRIEVE);
        }
        // The same reading for an SQL script, and one signal more: SET and CALL open an IDCAMS
        // control card and a batch script as readily as a statement, so the file also has to end a
        // statement somewhere. A file whose extension says SQL and that ends none is still taken in
        // by its extension; what the second signal buys is the decision made on content alone.
        return firstSignificantIsSqlStatement && terminatesAStatement(text)
                ? Verdict.of(AssetKind.SQL) : Verdict.UNDECIDED;
    }

    /**
     * Whether the text ends a statement anywhere: a {@code ;} that stands outside a string literal,
     * outside a {@code --} line comment and outside a {@code /* *}{@code /} block comment, or a line
     * naming another terminator. An unclosed literal swallows the rest of the file, which costs the
     * signal rather than inventing it.
     */
    private static boolean terminatesAStatement(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ';') {
                return true;
            }
            if (c == '\'') {
                int close = text.indexOf('\'', i + 1);
                i = close < 0 ? text.length() : close;
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                int newline = text.indexOf('\n', i);
                i = newline < 0 ? text.length() : newline;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                i = close < 0 ? text.length() : close + 1;
            }
        }
        return TERMINATOR_DIRECTIVE.matcher(text).find();
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
        if (DBD_MACRO.matcher(line).find()) {
            return Verdict.of(AssetKind.IMS_DBD);
        }
        if (PSB_MACRO.matcher(line).find()) {
            return Verdict.of(AssetKind.IMS_PSB);
        }
        if (COBOL_MARKER.matcher(FixedFormatColumns.body(line)).find()) {
            return Verdict.of(AssetKind.COBOL);
        }
        if (EASYTRIEVE_STATEMENT.matcher(line).find()) {
            return Verdict.of(AssetKind.EASYTRIEVE);
        }
        return Verdict.UNDECIDED;
    }

    /**
     * Whether the line can be used as evidence for classification. Skips every comment form, since
     * the comment column differs by kind. Skipping the {@code *} in column 7 of fixed format is
     * essential. A COBOL heading comment commonly writes body keywords verbatim, as in
     * {@code PROGRAM-ID : ~}; treating a comment as body text would let a copybook or JCL file
     * pass as COBOL. An SQL script's banner is written with {@code --}, and the statement it
     * describes stands under it. This looks at one line: the continuation lines of a
     * {@code /* *}{@code /} banner are skipped by {@link #classify(byte[])}, which alone knows
     * where the banner closes.
     */
    private static boolean isSignificant(String line) {
        if (line.isBlank()) {
            return false;
        }
        if (line.startsWith("//*") || line.startsWith("/*") || line.startsWith("--")) {
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
        return withoutByteOrderMark(ebcdic ? text.replace(NEXT_LINE, '\n') : text);
    }

    /** The text with a leading byte order mark taken off, so the first keyword stands first. */
    private static String withoutByteOrderMark(String text) {
        if (text.startsWith(UTF8_BOM_AS_LATIN1)) {
            return text.substring(UTF8_BOM_AS_LATIN1.length());
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
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
