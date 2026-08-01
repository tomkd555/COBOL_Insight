package jp.cobolinsight.engineapi.dataflow;

import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * プログラム横断の不動点解析結果コンテナ。programId で索引化し、rules・cli が
 * {@code AnalysisContext.artifact()} 経由で受け取るキー型として使う。
 * {@link jp.cobolinsight.engineapi.cfg.ControlFlowGraphs} と同じ配置・利用形態を採る。
 */
public final class DataFlowFacts {

    private final Map<String, ProgramDataFlow> byProgramId;

    /** 同じ programId の解析結果が複数含まれる場合は、投入順で最初のものだけを採る。 */
    public DataFlowFacts(Collection<ProgramDataFlow> flows) {
        Map<String, ProgramDataFlow> map = new LinkedHashMap<>();
        for (ProgramDataFlow flow : flows) {
            map.putIfAbsent(flow.programId(), flow);
        }
        this.byProgramId = Collections.unmodifiableMap(map);
    }

    public Optional<ProgramDataFlow> of(String programId) {
        return Optional.ofNullable(byProgramId.get(programId));
    }

    public Optional<ProgramDataFlow> of(CobolSemanticModel model) {
        return of(model.programId());
    }

    /** 全プログラムの解析結果(投入順)。 */
    public Collection<ProgramDataFlow> all() {
        return byProgramId.values();
    }
}
