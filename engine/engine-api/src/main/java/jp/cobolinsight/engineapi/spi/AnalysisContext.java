package jp.cobolinsight.engineapi.spi;

import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.callgraph.CallGraph;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ルール評価時に参照できる解析成果。engine-api が型を持たない成果物(CFG・データフロー事実
 * など)は、生成側モジュールの型をキーとして {@link #artifact(Class)} で受け渡す。
 */
public interface AnalysisContext {

    List<CobolSemanticModel> cobolPrograms();

    List<JclJobModel> jclJobs();

    List<SqlStatementModel> sqlStatements();

    List<BmsMapset> bmsMapsets();

    /** 呼出関係グラフ。linker 実行前の段階では empty。 */
    Optional<CallGraph> callGraph();

    /** 型をキーとする付帯成果物の取得。未登録の型に対しては empty を返す。 */
    <T> Optional<T> artifact(Class<T> type);

    static AnalysisContext of(List<CobolSemanticModel> cobolPrograms, List<JclJobModel> jclJobs,
            List<SqlStatementModel> sqlStatements, List<BmsMapset> bmsMapsets,
            Optional<CallGraph> callGraph, Map<Class<?>, Object> artifacts) {
        return new SimpleAnalysisContext(cobolPrograms, jclJobs, sqlStatements, bmsMapsets,
                callGraph, artifacts);
    }
}
