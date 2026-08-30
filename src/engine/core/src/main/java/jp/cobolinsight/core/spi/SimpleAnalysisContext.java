package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An immutable implementation of {@link AnalysisContext}. Created by {@link AnalysisContext#of}. */
final class SimpleAnalysisContext implements AnalysisContext {

    private final List<CobolSemanticModel> cobolPrograms;
    private final List<JclJobModel> jclJobs;
    private final List<SqlStatementModel> sqlStatements;
    private final List<BmsMapset> bmsMapsets;
    private final Optional<CallGraph> callGraph;
    private final Map<Class<?>, Object> artifacts;

    SimpleAnalysisContext(List<CobolSemanticModel> cobolPrograms, List<JclJobModel> jclJobs,
            List<SqlStatementModel> sqlStatements, List<BmsMapset> bmsMapsets,
            Optional<CallGraph> callGraph, Map<Class<?>, Object> artifacts) {
        this.cobolPrograms = List.copyOf(cobolPrograms);
        this.jclJobs = List.copyOf(jclJobs);
        this.sqlStatements = List.copyOf(sqlStatements);
        this.bmsMapsets = List.copyOf(bmsMapsets);
        this.callGraph = Objects.requireNonNull(callGraph, "callGraph");
        this.artifacts = Map.copyOf(artifacts);
        // Cross-checks each key's type against its value's type so a mismatch is caught at construction time rather than when artifact(Class) is later retrieved.
        for (Map.Entry<Class<?>, Object> entry : this.artifacts.entrySet()) {
            if (!entry.getKey().isInstance(entry.getValue())) {
                throw new IllegalArgumentException("artifact value is not an instance of its key type: "
                        + entry.getKey().getName());
            }
        }
    }

    @Override
    public List<CobolSemanticModel> cobolPrograms() {
        return cobolPrograms;
    }

    @Override
    public List<JclJobModel> jclJobs() {
        return jclJobs;
    }

    @Override
    public List<SqlStatementModel> sqlStatements() {
        return sqlStatements;
    }

    @Override
    public List<BmsMapset> bmsMapsets() {
        return bmsMapsets;
    }

    @Override
    public Optional<CallGraph> callGraph() {
        return callGraph;
    }

    @Override
    public <T> Optional<T> artifact(Class<T> type) {
        return Optional.ofNullable(artifacts.get(type)).map(type::cast);
    }
}
