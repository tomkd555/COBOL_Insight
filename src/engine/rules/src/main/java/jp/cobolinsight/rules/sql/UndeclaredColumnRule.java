package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlColumnRef;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * R057 A column the table's declaration does not carry. Every statement of the asset folder that
 * declares columns — {@code DECLARE TABLE}, {@code CREATE TABLE}, the {@code ADD COLUMN} of an
 * {@code ALTER TABLE} — is read as a column list of its table, and a table declared more than once
 * carries the columns of all of them together, because a DDL library keeps the base CREATE and its
 * later ALTERs as separate members. Each column a statement names is looked up in that set. A name
 * that is not there is a typing mistake or a column that was renamed, and the precompiler passes
 * it: the bind, or the first run after it, fails with SQLCODE -206.
 *
 * <p>A table is matched on the name as written first; the unqualified name answers only where no
 * declaration claims that name under a schema, which is the case a DCLGEN member qualifies and the
 * statements around it do not. Matching is case-insensitive. A column reference with no qualifier
 * of its own is attributed to the statement's table only while the statement names one table: with
 * a join in it, which table an unqualified column belongs to is not decidable from the model. One
 * finding per statement, listing what it names that the declaration does not.
 *
 * <p>Three kinds of name are left out, because none of them is a column of a declared table and
 * reporting one promises an SQLCODE -206 that will not happen. A column whose table is one of the
 * statement's {@code cteNames}, because a WITH clause defines a query and a query has no
 * declaration; every unqualified column of a statement that has a WITH clause at all, because a
 * common table expression joined back to its own base table leaves them unattributable; and a name
 * a select-list {@code AS} alias defines, which an ORDER BY then names. A name the frontend put in
 * {@code columnRefs} that is not an SQL identifier is left out as well, so a comment the SQL parse
 * read as part of the statement is not reported as a column. The format keyword of a built-in
 * function ({@code CHAR(CURRENT DATE, ISO)}) never reaches {@code columnRefs}: the frontend drops
 * it as a word of the call, over a wider list of keywords than this rule could keep in step with.
 */
public final class UndeclaredColumnRule implements Rule {

    /** The {@code AS name} an entry of the select list ends with. */
    private static final Pattern SELECT_LIST_ALIAS = Pattern.compile(
            "(?i)\\bAS\\s+([\\p{L}\\p{N}$#@_]+)\\s*$");

    private static final RuleMeta META =
            RuleMeta.named("R057", "宣言にない列の参照", "SQL")
            .summary("DECLARE TABLE・CREATE TABLE の列にない列名を参照する"
                    + "埋込みSQL文を検出します。")
            .rationale("プリコンパイルは通り、BIND か実行時に SQLCODE -206 で失敗します。")
            .detection("解析対象のフォルダーの DECLARE TABLE・CREATE TABLE・"
                    + "ALTER TABLE の列の追加を表の列の定義として読み、"
                    + "同じ表の宣言が複数あればすべての列を合わせて、"
                    + "埋込みSQL文が参照する列名のうち定義にないものを検出します。"
                    + "表は書かれたままの名前で先に対応づけ、"
                    + "同じ名前をスキーマ名付きで宣言した表がないときだけ、"
                    + "スキーマ名を除いた名前で対応づけます。大文字と小文字は区別しません。"
                    + "修飾のない列名は、文が 1 つの表だけを参照するときに限り対応づけます。"
                    + "共通表式（WITH 句）が定義する名前を表とする列は、"
                    + "宣言を持たないため対象外です。"
                    + "WITH 句を持つ文の修飾のない列名も、どの表の列か決まらないため対象外です。"
                    + "選択列の AS で付けた別名も対象外です。"
                    + "CHAR(日付, ISO) の ISO のような組み込み関数の書式語は、"
                    + "列として読み取りません。"
                    + "完全に解析できなかった文は対象外です。")
            .remedy("列名の誤りを直すか、"
                    + "表の定義を現在の列に合わせて更新してください。")
            .example("""
                    EXEC SQL SELECT ZAIKO_SUU INTO :HOST-在庫数量
                             FROM SYKDB.ZAIKOM END-EXEC.
                    """, """
                    EXEC SQL SELECT ZAIKO_SU INTO :HOST-在庫数量
                             FROM SYKDB.ZAIKOM END-EXEC.
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
        Declarations declared = declarations(context.sqlStatements());
        if (declared.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (!statement.isFullyAnalysed() || isDeclaration(statement.kind())) {
                continue;
            }
            String only = statement.referencedTables().size() == 1
                    ? statement.referencedTables().get(0) : null;
            Set<String> cteNames = upperCased(statement.cteNames());
            Set<String> aliases = selectListAliases(statement);
            List<String> undeclared = new ArrayList<>();
            for (SqlColumnRef reference : statement.columnRefs()) {
                // A column of a WITH clause belongs to the query, and a query has no declaration;
                // with a WITH clause anywhere in the statement an unqualified column cannot be
                // attributed either, because the query and its base table are both in scope.
                String table = reference.table()
                        .orElse(cteNames.isEmpty() ? only : null);
                if (table == null || cteNames.contains(table.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                String column = reference.column().toUpperCase(Locale.ROOT);
                if (aliases.contains(column)) {
                    continue;
                }
                Set<String> columns = declared.columnsOf(table);
                String place = Db2Schema.unqualified(table) + "." + column;
                if (columns != null && Db2Schema.isPlainColumn(column)
                        && !columns.contains(column) && !undeclared.contains(place)) {
                    undeclared.add(place);
                }
            }
            if (!undeclared.isEmpty()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        String.join("、", undeclared) + " は表の定義にない列です。"
                                + "BIND か実行時に SQLCODE -206 で失敗します。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }

    /**
     * The declared column lists of the folder, held under the table name as written and under the
     * unqualified name, each the union over every statement that declares columns of that table. A
     * statement's table is looked up under its full name first, so two tables that share a name in
     * different schemas are told apart; the unqualified name answers only where no declaration
     * claims that name under a schema of its own, which is the case a DCLGEN member qualifies and
     * the statements around it do not.
     */
    private record Declarations(Map<String, Set<String>> byFullName,
            Map<String, Set<String>> byShortName, Set<String> qualifiedNames) {

        boolean isEmpty() {
            return byShortName.isEmpty();
        }

        Set<String> columnsOf(String table) {
            String full = table.toUpperCase(Locale.ROOT);
            Set<String> exact = byFullName.get(full);
            if (exact != null) {
                return exact;
            }
            String shortName = Db2Schema.unqualified(table);
            boolean claimedByASchema = qualifiedNames.contains(shortName);
            return claimedByASchema && !full.equals(shortName) ? null : byShortName.get(shortName);
        }
    }

    private static Declarations declarations(List<SqlStatementModel> statements) {
        Map<String, Set<String>> byFullName = new LinkedHashMap<>();
        Map<String, Set<String>> byShortName = new LinkedHashMap<>();
        Set<String> qualifiedNames = new LinkedHashSet<>();
        for (SqlStatementModel statement : statements) {
            if (statement.declaredColumns().isEmpty()) {
                continue;
            }
            String table = statement.declaredTable()
                    .or(() -> statement.referencedTables().stream().findFirst()).orElse(null);
            if (table == null) {
                continue;
            }
            String full = table.toUpperCase(Locale.ROOT);
            String shortName = Db2Schema.unqualified(table);
            if (!full.equals(shortName)) {
                qualifiedNames.add(shortName);
            }
            Set<String> columns = new LinkedHashSet<>();
            for (SqlDeclaredColumn column : statement.declaredColumns()) {
                columns.add(column.name().toUpperCase(Locale.ROOT));
            }
            byFullName.computeIfAbsent(full, key -> new LinkedHashSet<>()).addAll(columns);
            byShortName.computeIfAbsent(shortName, key -> new LinkedHashSet<>()).addAll(columns);
        }
        return new Declarations(byFullName, byShortName, qualifiedNames);
    }

    /**
     * The names the select list defines with {@code AS}. An ORDER BY or a HAVING naming one of them
     * names the result of the query, not a column of a table.
     */
    private static Set<String> selectListAliases(SqlStatementModel statement) {
        Set<String> aliases = new LinkedHashSet<>();
        for (String entry : statement.selectList()) {
            Matcher alias = SELECT_LIST_ALIAS.matcher(entry.strip());
            if (alias.find()) {
                aliases.add(alias.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return aliases;
    }

    private static Set<String> upperCased(List<String> names) {
        return names.stream().map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isDeclaration(SqlStatementKind kind) {
        return kind == SqlStatementKind.DECLARE_TABLE || kind == SqlStatementKind.DDL;
    }
}
