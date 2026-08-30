package jp.cobolinsight.analysis.linker;

import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.List;
import java.util.Map;

/**
 * The full set of inputs to the linker. sqlStatementsByProgramId holds the embedded SQL
 * analysis results keyed by PROGRAM-ID, and programByTransactionId is the transaction
 * definition table (transaction ID -> program name).
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
