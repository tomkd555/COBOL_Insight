package jp.cobolinsight.sqlfrontend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPTIMIZE FOR n ROWS・WITH UR の2句を、抽出SQLテキストへの正規表現検査で検出する。
 * 文字列リテラル内は対象外とする。SQL助言 S005・S006 の判定に用いる。
 */
public final class Db2ClauseDetector {

    private static final Pattern OPTIMIZE_FOR = Pattern.compile(
            "\\bOPTIMIZE\\s+FOR\\s+(?:\\d+|:[A-Za-z0-9_]+)\\s+ROWS?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH_UR = Pattern.compile(
            "\\bWITH\\s+UR\\b", Pattern.CASE_INSENSITIVE);

    public List<Db2ClauseFinding> detect(String sqlText) {
        String masked = SqlTextScanner.maskStringLiterals(sqlText);
        List<Db2ClauseFinding> findings = new ArrayList<>();
        collect(findings, Db2Clause.OPTIMIZE_FOR, OPTIMIZE_FOR, masked, sqlText);
        collect(findings, Db2Clause.WITH_UR, WITH_UR, masked, sqlText);
        findings.sort(Comparator.comparingInt(Db2ClauseFinding::offset));
        return findings;
    }

    private static void collect(List<Db2ClauseFinding> findings, Db2Clause clause,
            Pattern pattern, String masked, String original) {
        Matcher m = pattern.matcher(masked);
        while (m.find()) {
            findings.add(new Db2ClauseFinding(clause, m.start(), original.substring(m.start(), m.end())));
        }
    }
}
