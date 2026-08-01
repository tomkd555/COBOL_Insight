package jp.cobolinsight.linker;

import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

import java.util.List;
import java.util.Map;

/**
 * linker への入力一式。sqlStatementsByProgramId は PROGRAM-ID をキーとする埋め込みSQL解析結果、
 * programByTransactionId はトランザクション定義表(トランザクションID→プログラム名)である。
 */
public record LinkerInput(List<CobolSemanticModel> cobolModels, List<JclJobModel> jobs,
        List<BmsMapset> mapsets, Map<String, List<SqlStatementModel>> sqlStatementsByProgramId,
        Map<String, String> programByTransactionId) {

    public LinkerInput {
        cobolModels = List.copyOf(cobolModels);
        jobs = List.copyOf(jobs);
        mapsets = List.copyOf(mapsets);
        sqlStatementsByProgramId = Map.copyOf(sqlStatementsByProgramId);
        programByTransactionId = Map.copyOf(programByTransactionId);
    }
}
