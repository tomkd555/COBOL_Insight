package jp.cobolinsight.analysis.linker;

import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.MiddlewareAssets;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.List;
import java.util.Map;

/**
 * The full set of inputs to the linker. sqlStatementsByProgramId holds the embedded SQL
 * analysis results keyed by PROGRAM-ID, and programByTransactionId is the transaction
 * definition table (transaction ID -> program name).
 *
 * <p>The two SQL script components hold what no PROGRAM-ID owns: sqlStatementsByScript is the
 * statements of each script keyed by its relative path, and sqlRoutines the procedures, functions
 * and triggers those scripts define.
 *
 * <p>middleware holds the Easytrieve programs and the IMS DBD and PSB generations. The SQL of an
 * Easytrieve member stands in sqlStatementsByProgramId under the member name, beside the COBOL
 * programs: a member is executed under that name the way a COBOL program is under its PROGRAM-ID.
 *
 * <p>scoped says this run was narrowed by {@code lint --scope}, which leaves the COBOL outside the
 * scope unread. A callee such a run cannot resolve may well be in the estate — nothing here has
 * read the sources that would say so — so the linker marks every unresolved callee as outside the
 * scope, and a rule about unanalysed callees stays silent rather than claim a source is missing.
 */
public record LinkerInput(List<CobolSemanticModel> cobolModels, List<JclJobModel> jobs,
        List<BmsMapset> mapsets, Map<String, List<SqlStatementModel>> sqlStatementsByProgramId,
        Map<String, String> programByTransactionId,
        Map<String, List<SqlStatementModel>> sqlStatementsByScript,
        List<SqlRoutineDefinition> sqlRoutines, boolean scoped, MiddlewareAssets middleware) {

    public LinkerInput {
        java.util.Objects.requireNonNull(middleware, "middleware");
        cobolModels = List.copyOf(cobolModels);
        jobs = List.copyOf(jobs);
        mapsets = List.copyOf(mapsets);
        sqlStatementsByProgramId = Map.copyOf(sqlStatementsByProgramId);
        programByTransactionId = Map.copyOf(programByTransactionId);
        sqlStatementsByScript = Map.copyOf(sqlStatementsByScript);
        sqlRoutines = List.copyOf(sqlRoutines);
    }

    /** An input with no Easytrieve program and no IMS generation among the assets. */
    public LinkerInput(List<CobolSemanticModel> cobolModels, List<JclJobModel> jobs,
            List<BmsMapset> mapsets, Map<String, List<SqlStatementModel>> sqlStatementsByProgramId,
            Map<String, String> programByTransactionId,
            Map<String, List<SqlStatementModel>> sqlStatementsByScript,
            List<SqlRoutineDefinition> sqlRoutines, boolean scoped) {
        this(cobolModels, jobs, mapsets, sqlStatementsByProgramId, programByTransactionId,
                sqlStatementsByScript, sqlRoutines, scoped, MiddlewareAssets.NONE);
    }

    /** An input of a whole-folder run, where nothing the walk found is out of scope. */
    public LinkerInput(List<CobolSemanticModel> cobolModels, List<JclJobModel> jobs,
            List<BmsMapset> mapsets, Map<String, List<SqlStatementModel>> sqlStatementsByProgramId,
            Map<String, String> programByTransactionId,
            Map<String, List<SqlStatementModel>> sqlStatementsByScript,
            List<SqlRoutineDefinition> sqlRoutines) {
        this(cobolModels, jobs, mapsets, sqlStatementsByProgramId, programByTransactionId,
                sqlStatementsByScript, sqlRoutines, false);
    }

    /** An input with no SQL script among the assets, the shape every caller built before scripts. */
    public LinkerInput(List<CobolSemanticModel> cobolModels, List<JclJobModel> jobs,
            List<BmsMapset> mapsets, Map<String, List<SqlStatementModel>> sqlStatementsByProgramId,
            Map<String, String> programByTransactionId) {
        this(cobolModels, jobs, mapsets, sqlStatementsByProgramId, programByTransactionId,
                Map.of(), List.of());
    }
}
