package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.ByteOffsetTable;
import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.DecodedSource;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * 編集後の全文と原本の復号済みテキストを突き合わせ、食い違う中央部だけを1つの {@link TextEdit} へ
 * まとめて {@link ByteSpliceApplier} でバイトスプライスする。
 *
 * <p>行単位で先頭と末尾の一致部分を削り取り、残った中央の行範囲をまるごと置き換える。差分アルゴリズム
 * (LCS)は用いない。編集の外側は原バイト列をそのまま持ち越すため、触れていない行のバイト列・コード
 * ページ・改行様式・バイト順マークは原本と一致する。
 *
 * <p>突き合わせの前に CRLF を LF へそろえる。編集後の全文は画面から UTF-8 テキストとして渡され、
 * 改行様式が原本と異なりうるためである。置換テキストの改行を原本の様式へ戻すのは
 * {@link ByteSpliceApplier} が担う。
 */
public final class MinimalLineEdit {

    private MinimalLineEdit() {
    }

    /**
     * スプライス結果。
     *
     * @param bytes            修正後のバイト列(原本と同一のコードページ)
     * @param changed          食い違いがあったか。無ければ {@code bytes} は原バイト列そのもの
     * @param changedLineFrom  食い違いの先頭行(1始まり)。{@code changed} が偽なら0
     * @param changedLineTo    食い違いの末尾行(1始まり・その行を含む)。行の挿入だけの場合は
     *                         {@code changedLineFrom - 1} となり、挿入点の直前の行を指す
     */
    public record Result(byte[] bytes, boolean changed, int changedLineFrom, int changedLineTo) {
    }

    /**
     * 原本の復号済みソースへ編集後の全文を反映したバイト列を返す。原本のコードページで符号化できない
     * 文字が編集後の本文にある場合は {@link IllegalArgumentException} を投げ、バイト列を返さない。
     *
     * @param path        編集位置に添えるソースのパス(内容には影響しない)
     * @param original    原本の復号済みソース
     * @param editedText  編集後の全文
     */
    public static Result apply(String path, DecodedSource original, String editedText) {
        List<String> originalLines = lines(original.text());
        List<String> editedLines = lines(editedText);
        int prefix = commonPrefix(originalLines, editedLines);
        int suffix = commonSuffix(originalLines, editedLines, prefix);
        int originalEnd = originalLines.size() - suffix;
        int editedEnd = editedLines.size() - suffix;
        if (prefix == originalEnd && prefix == editedEnd) {
            return new Result(original.originalBytes(), false, 0, 0);
        }

        String replacement = String.join("", editedLines.subList(prefix, editedEnd));
        SourcePosition start;
        if (prefix == 0) {
            requireNoLeadingShiftCode(original);
            start = lineStartPosition(path, 1);
        } else {
            // 直前の行の改行文字から置き換える。EBCDIC では行頭の DBCS を開くシフトコード(SO)が
            // 行頭文字のバイト位置より前に置かれ、行頭を起点にすると原本の SO が残ったまま置換
            // テキストの SO が加わって二重になる。改行文字を含めれば置換範囲がシフトコードを覆う。
            start = lineBreakPosition(path, original, prefix);
            replacement = "\n" + replacement;
        }
        TextEdit edit = new TextEdit(
                new SourceRange(start, lineStartPosition(path, originalEnd + 1)), replacement);
        requireEncodable(replacement, original.encodingInfo().codePage());
        byte[] bytes = new ByteSpliceApplier().apply(original, List.of(edit));
        return new Result(bytes, true, prefix + 1, originalEnd);
    }

    /**
     * 行末の改行を各行へ含めたまま分割する。改行を行の一部として扱うため、末尾改行の有無の違いも
     * 最終行の食い違いとして現れ、行番号と要素位置が一対一で対応する。
     */
    private static List<String> lines(String text) {
        String normalized = text.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < normalized.length()) {
            lines.add(normalized.substring(start));
        }
        return lines;
    }

    private static int commonPrefix(List<String> a, List<String> b) {
        int max = Math.min(a.size(), b.size());
        int prefix = 0;
        while (prefix < max && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        return prefix;
    }

    /** 末尾からの一致行数。先頭で削り取った行を二重に数えないよう、残りの行数を上限とする。 */
    private static int commonSuffix(List<String> a, List<String> b, int prefix) {
        int max = Math.min(a.size(), b.size()) - prefix;
        int suffix = 0;
        while (suffix < max
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        return suffix;
    }

    /** 指定行(1始まり)の先頭を指す位置。{@code lineCount+1} は本文の末尾を指す。 */
    private static SourcePosition lineStartPosition(String path, int line) {
        return new SourcePosition(path, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    /**
     * 指定行(1始まり)の行末の改行を指す位置。CRLF の行では CR を指す。置換テキストの先頭の改行が
     * 原本の改行様式へ戻るため、CR ごと置き換えても改行様式は保たれる。
     */
    private static SourcePosition lineBreakPosition(String path, DecodedSource original, int line) {
        ByteOffsetTable table = original.offsetTable();
        int lineStart = table.lineStartCharIndex(line);
        int breakAt = table.lineStartCharIndex(line + 1) - 1;
        if (breakAt > lineStart && original.text().charAt(breakAt - 1) == '\r') {
            breakAt--;
        }
        return new SourcePosition(path, line, breakAt - lineStart + 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    /**
     * 1行目から置き換える場合に、本文の先頭に文字を産まないバイトが無いことを確かめる。EBCDIC で
     * 本文がシフトコードから始まると、そのバイトは置換範囲へ入れられず原本に残る一方で置換テキスト
     * も自前のシフトコードを持つため、書き戻しが壊れる。固定形式では1行目が一連番号領域から始まる
     * ため実在しないが、壊れた結果を書き出すよりは断る。
     *
     * <p>UTF-8 のバイト順マークも本文の先頭に置かれるが、置換テキストはこれを産まないため、原本の
     * ものがそのまま残るのが正しい。したがって EBCDIC だけを見る。
     */
    private static void requireNoLeadingShiftCode(DecodedSource original) {
        if (original.encodingInfo().codePage().isEbcdic()
                && original.offsetTable().byteOffsetOfChar(0) > 0) {
            throw new IllegalArgumentException(
                    "本文がシフトコードから始まる EBCDIC ソースは、1行目を含む編集を書き戻せない");
        }
    }

    /**
     * 置換テキストが原本のコードページで符号化できることを確かめる。
     *
     * <p>{@code String.getBytes(Charset)} は符号化できない文字を黙って '?' などへ置き換えるため、
     * 全角文字を持たない EBCDIC の SBCS 面などでは原本を書き換えたうえで内容が壊れる。ここで
     * {@link CodingErrorAction#REPORT} の符号化器に通し、置き換えが起きる編集は例外で拒む。
     */
    private static void requireEncodable(String replacement, CodePage codePage) {
        CharsetEncoder encoder = codePage.charset().newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            encoder.encode(CharBuffer.wrap(replacement));
            return;
        } catch (CharacterCodingException e) {
            // 例外は符号化できない文字の位置を持たないため、下で1文字ずつ当たって特定する。
        }
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (!encoder.canEncode(c)) {
                throw new IllegalArgumentException("コードページ " + codePage.charsetName()
                        + " で符号化できない文字が編集後の本文にある: '" + c
                        + "' (U+" + String.format("%04X", (int) c) + ")");
            }
        }
        throw new IllegalArgumentException(
                "コードページ " + codePage.charsetName() + " で符号化できない文字が編集後の本文にある");
    }
}
