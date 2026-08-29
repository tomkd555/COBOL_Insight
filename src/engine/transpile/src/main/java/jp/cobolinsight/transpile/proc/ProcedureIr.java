package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;

/** Intermediate representation of one translated paragraph/section. methodName is the method name on the generated side, headerRange is the position of the paragraph header. */
public record ProcedureIr(String cobolName, String methodName, List<ProcStmt> body,
        SourceRange headerRange) {

    public ProcedureIr {
        body = List.copyOf(body);
    }
}
