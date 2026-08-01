package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.List;

/** 1つの段落・節を対訳した中間表現。methodName は生成側メソッド名、headerRange は段落見出しの位置。 */
public record ProcedureIr(String cobolName, String methodName, List<ProcStmt> body,
        SourceRange headerRange) {

    public ProcedureIr {
        body = List.copyOf(body);
    }
}
