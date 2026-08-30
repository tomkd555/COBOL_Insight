package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The analysis results available when evaluating rules. Artifacts engine-api has no type for
 * (CFG, dataflow facts, etc.) are passed through {@link #artifact(Class)}, keyed by the
 * producing module's type.
 */
public interface AnalysisContext {

    List<CobolSemanticModel> cobolPrograms();

    List<JclJobModel> jclJobs();

    List<SqlStatementModel> sqlStatements();

    List<BmsMapset> bmsMapsets();

    /** The call relationship graph. Empty before the linker has run. */
    Optional<CallGraph> callGraph();

    /** Retrieves an ancillary artifact keyed by type. Returns empty for an unregistered type. */
    <T> Optional<T> artifact(Class<T> type);

    /** Each key in artifacts must match the type of its value. */
    static AnalysisContext of(List<CobolSemanticModel> cobolPrograms, List<JclJobModel> jclJobs,
            List<SqlStatementModel> sqlStatements, List<BmsMapset> bmsMapsets,
            Optional<CallGraph> callGraph, Map<Class<?>, Object> artifacts) {
        return new SimpleAnalysisContext(cobolPrograms, jclJobs, sqlStatements, bmsMapsets,
                callGraph, artifacts);
    }
}
