package jp.cobolinsight.rules.syntax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 構文ルールが共有する字句ヘルパー。COBOL固定形式のコメント判定・リテラル除去・名前抽出を行う。 */
final class CobolTexts {

    /** COBOLのデータ名・段落名(日本語名を含む)。 */
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}-]*");

    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");

    /** 走査対象の1論理行。継続行(7桁目'-')は結合済みで、lineNumberは開始行を指す。 */
    record LogicalLine(int lineNumber, String text) {
    }

    private CobolTexts() {
    }

    /** 固定形式の7桁目が '*' または '/' の行をコメント行とみなす。 */
    static boolean isCommentLine(String line) {
        if (line.length() < 7) {
            return false;
        }
        char indicator = line.charAt(6);
        return indicator == '*' || indicator == '/';
    }

    /**
     * 固定形式ソーステキストから、ルールが原ソース走査に使う本体行を取り出す。コメント行を除き、
     * 1〜7桁目を空白化・73桁目以降(識別領域)を除去し、リテラル外の行内コメント(*>)以降を
     * 空白化する。7桁目が'-'の継続行は直前行の8〜72桁を末尾へ結合する(行番号は開始行を保つ)。
     * 空白化・切り詰めにより、各行の先頭物理行内の桁位置は原ソースの桁と一致する。
     */
    static List<LogicalLine> logicalLines(String text) {
        List<LogicalLine> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].replace("\r", "");
            if (isCommentLine(raw)) {
                continue;
            }
            boolean continuation = raw.length() >= 7 && raw.charAt(6) == '-' && !out.isEmpty();
            String body = blankInlineComment(bodyOf(raw));
            if (continuation) {
                LogicalLine previous = out.remove(out.size() - 1);
                String continued = body.length() > 7 ? body.substring(7) : "";
                out.add(new LogicalLine(previous.lineNumber(), previous.text() + continued));
            } else {
                out.add(new LogicalLine(i + 1, body));
            }
        }
        return out;
    }

    /** 1〜7桁目(一連番号・標識領域)を空白化し、73桁目以降(識別領域)を除去する。 */
    private static String bodyOf(String raw) {
        String line = raw.length() > 72 ? raw.substring(0, 72) : raw;
        if (line.length() <= 7) {
            return line;
        }
        return "       " + line.substring(7);
    }

    /** リテラル外に現れる行内コメント(*>)以降を同じ長さの空白へ置き換える。 */
    private static String blankInlineComment(String line) {
        int index = stripLiterals(line).indexOf("*>");
        if (index < 0) {
            return line;
        }
        return line.substring(0, index) + " ".repeat(line.length() - index);
    }

    /**
     * 文字列リテラルを同じ長さの空白へ置き換える。リテラル内の文字が名前として誤検出されるのを
     * 防ぎつつ、文字位置(桁)を保つ。
     */
    static String stripLiterals(String text) {
        Matcher matcher = STRING_LITERAL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start());
            out.append(" ".repeat(matcher.end() - matcher.start()));
            last = matcher.end();
        } while (matcher.find());
        out.append(text, last, text.length());
        return out.toString();
    }

    /** リテラル除去済みテキストから名前トークン(大文字化)を抽出する。 */
    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = NAME.matcher(stripLiterals(text));
        while (matcher.find()) {
            out.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
