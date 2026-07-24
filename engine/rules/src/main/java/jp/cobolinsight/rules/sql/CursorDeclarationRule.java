package jp.cobolinsight.rules.sql;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.sql.CursorSignals;
import jp.cobolinsight.engineapi.sql.SqlStatementKind;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * S004 カーソルの宣言・後始末。DECLARE CURSOR で FOR UPDATE を伴わない(更新を伴わない)のに
 * FOR READ ONLY・FOR FETCH ONLY のいずれも指定していないカーソルを指摘する。更新可能カーソルと
 * して扱われ、ロック競合の原因になる。OPEN/CLOSE の突合は R019 の責務のため本ルールでは扱わない。
 */
public final class CursorDeclarationRule implements Rule {

    @Override
    public String id() {
        return "S004";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (statement.kind() != SqlStatementKind.DECLARE_CURSOR) {
                continue;
            }
            CursorSignals cursor = statement.structureSignals().cursor().orElse(null);
            if (cursor == null) {
                continue;
            }
            if (!cursor.forUpdate() && !cursor.forReadOnly() && !cursor.forFetchOnly()) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "カーソル " + cursor.cursorName()
                                + " に FOR READ ONLY(または FOR FETCH ONLY)の指定が無い。"
                                + "更新可能カーソルとして扱われ、ロック競合の原因になる。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
