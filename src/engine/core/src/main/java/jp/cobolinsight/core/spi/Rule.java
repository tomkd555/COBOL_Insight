package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;

import java.util.List;
import java.util.Optional;

/**
 * ルールプラグインの契約。実装は META-INF/services で登録し、ServiceLoader が発見する。
 * 全ルールは既定で有効とし、有効・無効の切替は呼出側が id 単位で行う。
 */
public interface Rule {

    /** ルールID(R001〜R031・S001〜S006 など)。全ルールで一意。 */
    String id();

    /**
     * ルールの説明。名称・カテゴリを含み、CLI の rules サブコマンド・SARIF の rules・
     * GUI の設定画面がいずれもここを引く。必須メソッドとするのは、説明の無いルールを
     * 追加できないようにするためである。
     */
    RuleDoc doc();

    Severity defaultSeverity();

    /** このルールが依存する解析段階。 */
    AnalysisPhase phase();

    List<Finding> evaluate(AnalysisContext context);

    /** 修正案生成器。定型的な修正を持つルール(R004・R017・R018・R021)のみが提供する。 */
    default Optional<FixProducer> fixProducer() {
        return Optional.empty();
    }
}
