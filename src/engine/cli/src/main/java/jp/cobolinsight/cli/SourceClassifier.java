package jp.cobolinsight.cli;

import jp.cobolinsight.encoding.CodePageDetector;
import jp.cobolinsight.engineapi.source.AssetKind;
import jp.cobolinsight.engineapi.source.FixedFormatColumns;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * ソースの内容から資産の種別を逆算する。ファイル入出力を持たない純関数であり、判定に使うのは
 * 与えられたバイト列だけである。判定表はこの改修で最も回帰を起こしやすい部分なので、
 * バイト列を直接与える単体テストで細かく固められるよう入出力から切り離してある。
 *
 * <p><b>読む量に上限を置かない。</b>標識が現れた行で確定してそこで読むのをやめ、現れなければ
 * 末尾まで読む。「先頭 N 行まで」という制御は、N+1 行目に {@code IDENTIFICATION DIVISION} を
 * 持つソースを黙って落とす。利用者から見て説明のつかない取りこぼしになるため置かない。
 *
 * <p>文字コードの推定は {@link CodePageDetector} へそのまま委ね、ここに独自の判定基準を持たない。
 * EBCDIC と判定されたら単バイトの IBM037 で、それ以外は ISO-8859-1 で写す。いずれも1バイトが
 * 1文字へ対応するので、文字位置がそのままカード桁になる。判定に使うキーワードはすべて ASCII で
 * あり、日本語の注記が化けても種別の判定には影響しない。
 */
final class SourceClassifier {

    /**
     * 内容判定の結末。kind が null のとき、binary が真なら「テキストでないと確定した」、
     * 偽なら「種別を決められなかった」である。両者を分けるのは、前者が取りこぼしではなく
     * 判定であるためで、報告の扱いが変わる。
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
     * JCL の制御文。{@code //} で始まる行は他の種別と衝突し得ないため最優先で判定する。
     * 名前欄はメインフレームのメンバ名の規則(先頭が英字または国別文字、8文字以内)に従う。
     */
    private static final Pattern JCL_STATEMENT = Pattern.compile(
            "^//([A-Z@#$][A-Z0-9@#$]{0,7})?\\s+"
                    + "(JOB|EXEC|DD|PROC|PEND|SET|INCLUDE|IF|ELSE|ENDIF|OUTPUT|JCLLIB|COMMAND)\\b",
            Pattern.CASE_INSENSITIVE);

    /** BMS のマクロ呼出。名前欄は空でもよい。 */
    private static final Pattern BMS_MACRO =
            Pattern.compile("^\\S*\\s+DFH(MSD|MDI|MDF)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * COBOL 本体を名指す語。コピー句との区別はこの語の有無へ一元化する。samples のコピー句は
     * 3ファイルともこの語を持たず、両者を分ける唯一の安定した特徴だからである。
     */
    private static final Pattern COBOL_MARKER = Pattern.compile(
            "\\b(IDENTIFICATION\\s+DIVISION|ID\\s+DIVISION|ENVIRONMENT\\s+DIVISION"
                    + "|DATA\\s+DIVISION|PROCEDURE\\s+DIVISION|PROGRAM-ID)\\b",
            Pattern.CASE_INSENSITIVE);

    /** データ項目のレベル番号。01〜49・66・77・88 を受ける。 */
    private static final Pattern LEVEL_NUMBER =
            Pattern.compile("^(0?[1-9]|[1-4][0-9]|66|77|88)\\s+\\S");

    /** 単バイトの EBCDIC。桁とバイト位置を一致させるため、混在コードページではなくこれを使う。 */
    private static final Charset EBCDIC_SINGLE_BYTE = Charset.forName("IBM037");

    /** EBCDIC の行区切りに使われる NEL。IBM037 で写すと U+0085 になる。 */
    private static final char NEXT_LINE = (char) 0x85;

    private static final CodePageDetector DETECTOR = new CodePageDetector();

    private SourceClassifier() {
    }

    /**
     * バイト列から種別を判定する。
     *
     * <p>仕様書は {@code classify(String fileName, byte[])} の署名を挙げているが、内容判定は
     * ファイル名を一切見ない。受け取ると「名前も判定に効く」と読める死んだ引数になるため落とした。
     * 拡張子との突き合わせは呼び出し側({@link SourceDiscovery})の責務である。
     */
    static Verdict classify(byte[] content) {
        if (containsNul(content)) {
            // COBOL・JCL・BMS のテキストに NUL は現れない。EBCDIC のカード像でも空白は 0x40 で
            // ある。1バイトでも現れた時点でテキストでないと確定するので、件数のしきい値は要らない。
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
        // COBOL 本体を名指す語が末尾まで現れず、最初の有意行がレベル番号で始まるならコピー句。
        return Boolean.TRUE.equals(firstSignificantIsLevelNumber)
                ? Verdict.of(AssetKind.COPYBOOK) : Verdict.UNDECIDED;
    }

    /** 1行から強い根拠を読む。注記行と根拠を持たない行には {@link Verdict#UNDECIDED} を返す。 */
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
     * 判定の根拠に使える行か。種別ごとに注記の桁が違うため、3通りすべてを飛ばす。
     * 7桁目の {@code *} を必ず飛ばすのが要である。{@code samples/cobol/SYK001.cbl} の2行目は
     * 注記行に {@code PROGRAM-ID : SYK001} を含み、これを本文とみなすと注記だけで COBOL 判定が
     * 通ってしまう。
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
     * 判定に使う写し。EBCDIC のソースは行区切りに NEL(0x15)を使うことがあり、IBM037 で写すと
     * U+0085 になるため、EBCDIC のときだけこれを改行へ直す。ISO-8859-1 で写す側で同じことを
     * すると、UTF-8 の日本語が持つ 0x85 バイト(「共」= E5 85 B1 など)を行の切れ目と誤読する。
     */
    private static String decode(byte[] content) {
        boolean ebcdic = DETECTOR.detect(content).codePage().isEbcdic();
        Charset charset = ebcdic ? EBCDIC_SINGLE_BYTE : StandardCharsets.ISO_8859_1;
        String text = new String(content, charset);
        return ebcdic ? text.replace(NEXT_LINE, '\n') : text;
    }

    /** 次の改行の位置。無ければ -1。 */
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
