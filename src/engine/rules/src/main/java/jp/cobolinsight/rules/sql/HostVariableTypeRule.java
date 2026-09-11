package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.picture.PictureCategory;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.picture.Usage;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.sql.Db2Schema.Column;
import jp.cobolinsight.rules.sql.Db2Schema.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R038 A host variable whose PICTURE does not match the DCLGEN column it carries. Compares each
 * paired column and host variable against the mapping DCLGEN itself generates — CHAR(n) to X(n),
 * DECIMAL(p,s) to S9(p-s)V9(s) COMP-3, SMALLINT/INTEGER/BIGINT to S9(4)/S9(9)/S9(18) COMP,
 * DATE/TIME/TIMESTAMP to X(10)/X(8)/X(26) — and checks that an indicator is S9(4) COMP. A
 * mismatch truncates the value, or fails the statement with SQLCODE -301.
 */
public final class HostVariableTypeRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R038", "DCLGEN の列と型の合わないホスト変数", "SQL")
            .summary("DCLGEN が生成する対応と合わない PICTURE のホスト変数を"
                    + "埋込みSQL文から検出します。")
            .rationale("受け取り側項目が短ければ値が切り捨てられ、"
                    + "型が違えば SQLCODE -301 で文が失敗します。")
            .detection("SELECT INTO・FETCH INTO の INTO の並び、UPDATE の SET、"
                    + "INSERT の VALUES を DECLARE TABLE の列と突き合わせます。"
                    + "対応は CHAR(n) と X(n)、DECIMAL(p,s) と S9(p-s)V9(s) COMP-3、"
                    + "SMALLINT・INTEGER・BIGINT と S9(4)・S9(9)・S9(18) COMP、"
                    + "DATE・TIME・TIMESTAMP と X(10)・X(8)・X(26) です。"
                    + "対応から外れるものと、S9(4) COMP でない標識を検出します。"
                    + "DCLGEN が生成した集団項目と、DECLARE TABLE がフォルダにない表は"
                    + "対象外です。")
            .remedy("DCLGEN が生成した宣言をそのまま使うか、"
                    + "列の型に合わせて PICTURE を書き直してください。")
            .example("""
                    01  WS-KEIYAKU-NO PIC X(08).
                    EXEC SQL SELECT KEIYAKU_NO INTO :WS-KEIYAKU-NO
                             FROM FLDB.KEIYAKU END-EXEC.
                    """, """
                    01  WS-KEIYAKU-NO PIC X(10).
                    EXEC SQL SELECT KEIYAKU_NO INTO :WS-KEIYAKU-NO
                             FROM FLDB.KEIYAKU END-EXEC.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SQL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            List<EmbeddedBlock> blocks = Db2Schema.sqlBlocks(model);
            Map<String, List<Column>> tables = Db2Schema.tables(blocks);
            if (tables.isEmpty()) {
                continue;
            }
            Map<String, String> cursors = Db2Schema.cursorQueries(blocks);
            Map<String, DataItem> items = itemsByName(model);
            for (EmbeddedBlock block : blocks) {
                String body = Db2Schema.body(block.text());
                List<Pair> pairs = new ArrayList<>(Db2Schema.pairs(body, tables, cursors));
                pairs.addAll(Db2Schema.writePairs(body, tables));
                evaluate(model, block, pairs, items, findings);
            }
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, EmbeddedBlock block, List<Pair> pairs,
            Map<String, DataItem> items, List<Finding> findings) {
        Set<Integer> reported = new LinkedHashSet<>();
        for (Pair pair : pairs) {
            String message = mismatch(pair.column(), items.get(key(pair.target().host())));
            String host = pair.target().host();
            if (message == null && pair.target().indicator() != null) {
                host = pair.target().indicator();
                message = indicatorMismatch(items.get(key(host)));
            }
            int line = message == null ? 0 : Db2Schema.lineOf(block, host);
            if (message == null || !reported.add(line)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(), message,
                    new SourcePosition(model.sourceFile(), line, 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    /** The message for a host variable that does not carry the column's type; null when it does, or when nothing can be judged. */
    private static String mismatch(Column column, DataItem item) {
        // A DCLGEN host structure and a VARCHAR pair are group items; they match by construction.
        if (item == null || !item.children().isEmpty() || item.picture().isEmpty()) {
            return null;
        }
        String wanted = expectedPicture(column);
        if (wanted == null) {
            return null;
        }
        PictureType picture = pictureOf(item);
        if (picture == null || wanted.equals(canonical(picture))) {
            return null;
        }
        return item.name() + " は " + column.name() + " と型が合いません。"
                + columnText(column) + " の列には " + wanted + " が必要です。";
    }

    /** The message for an indicator that is not S9(4) COMP; null when it is, or when nothing can be judged. */
    private static String indicatorMismatch(DataItem item) {
        if (item == null || !item.children().isEmpty() || item.picture().isEmpty()) {
            return null;
        }
        PictureType picture = pictureOf(item);
        if (picture == null || "S9(4) COMP".equals(canonical(picture))) {
            return null;
        }
        return item.name() + " は標識に使える型ではありません。"
                + "Db2 が NULL を示す -1 を受け取れません。";
    }

    private static PictureType pictureOf(DataItem item) {
        try {
            return PictureType.parse(item.picture().orElseThrow(), item.usage().orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The COBOL declaration DCLGEN generates for the column; null for a type this rule does not map. */
    private static String expectedPicture(Column column) {
        int length = column.length();
        return switch (column.type()) {
            case "CHAR", "CHARACTER" -> "X(" + Math.max(1, length) + ")";
            case "DATE" -> "X(10)";
            case "TIME" -> "X(8)";
            case "TIMESTAMP" -> "X(26)";
            case "SMALLINT" -> "S9(4) COMP";
            case "INT", "INTEGER" -> "S9(9) COMP";
            case "BIGINT" -> "S9(18) COMP";
            case "DECIMAL", "DEC", "NUMERIC" -> length == 0 ? null
                    : column.scale() == 0 ? "S9(" + length + ") COMP-3"
                    : "S9(" + (length - column.scale()) + ")V9(" + column.scale() + ") COMP-3";
            default -> null;
        };
    }

    /** The same canonical form for the item's own PICTURE and USAGE, so the two can be compared as text. */
    private static String canonical(PictureType picture) {
        if (picture.category() == PictureCategory.ALPHANUMERIC
                || picture.category() == PictureCategory.ALPHABETIC) {
            return "X(" + picture.totalDigits() + ")";
        }
        if (!picture.isNumeric()) {
            return "?";
        }
        String digits = picture.fractionDigits() == 0
                ? "9(" + picture.integerDigits() + ")"
                : "9(" + picture.integerDigits() + ")V9(" + picture.fractionDigits() + ")";
        String usage = picture.usage() == Usage.PACKED_DECIMAL ? " COMP-3"
                : picture.usage() == Usage.BINARY ? " COMP" : "";
        return (picture.signed() ? "S" : "") + digits + usage;
    }

    private static String columnText(Column column) {
        if (column.length() == 0) {
            return column.type();
        }
        return column.type() + "(" + column.length()
                + (column.scale() == 0 ? "" : "," + column.scale()) + ")";
    }

    private static Map<String, DataItem> itemsByName(CobolSemanticModel model) {
        Map<String, DataItem> items = new LinkedHashMap<>();
        for (DataItem item : model.dataItems()) {
            index(item, items);
        }
        return items;
    }

    private static void index(DataItem item, Map<String, DataItem> items) {
        items.putIfAbsent(key(item.name()), item);
        for (DataItem child : item.children()) {
            index(child, items);
        }
    }

    private static String key(String name) {
        return name.trim().toUpperCase(Locale.ROOT);
    }
}
