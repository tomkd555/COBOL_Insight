package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.CallEdgeRecord;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ソースの内容ハッシュ比較により、再解析が必要なソースID集合を決定する。
 * 依存範囲は「当該コピー句を取り込むプログラム」と
 * 「当該プログラムを呼ぶJCL」の2種に限る。対象ソースのノードは NODE.id = SOURCE.id の規約で
 * 1件登録されている前提とし、2種の依存は CALL_EDGE.kind の値で区別する。
 */
public final class IncrementalAnalysisPlanner {

    /** コピー句 → 取込プログラムの依存を表すCALL_EDGE.kindの値。 */
    public static final String COPY_EDGE_KIND = "COPY";
    /** 呼出JCL → プログラムの実行依存を表すCALL_EDGE.kindの値。 */
    public static final String EXECUTION_EDGE_KIND = "EXECUTION";

    private final PersistenceDao dao;

    public IncrementalAnalysisPlanner(PersistenceDao dao) {
        this.dao = dao;
    }

    /** 空集合は再解析不要を表す。非空の場合、変更ソース自身と直接の依存元・依存先を含む。 */
    public Set<Long> determineReanalysisTargets(long sourceId, String newContentHash) {
        var existing = dao.findSource(sourceId);
        if (existing.isPresent() && existing.get().contentHash().equals(newContentHash)) {
            return Set.of();
        }

        Set<Long> targets = new LinkedHashSet<>();
        targets.add(sourceId);
        targets.addAll(dependentsOf(sourceId));
        return targets;
    }

    /**
     * 当該ソースの変更・削除にともなって解析をやり直す必要がある他のソース。当該ソース自身は含まない。
     * 資産フォルダから消えたソースは内容ハッシュを比較できないため、削除の伝播はこの問い合わせで行う。
     */
    public Set<Long> dependentsOf(long sourceId) {
        Set<Long> dependents = new LinkedHashSet<>();
        for (CallEdgeRecord edge : dao.findEdgesFrom(sourceId)) {
            if (COPY_EDGE_KIND.equals(edge.kind())) {
                dependents.add(edge.toNode());
            }
        }
        for (CallEdgeRecord edge : dao.findEdgesTo(sourceId)) {
            if (EXECUTION_EDGE_KIND.equals(edge.kind())) {
                dependents.add(edge.fromNode());
            }
        }
        return dependents;
    }
}
