package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * samples に対するデータフロー段ルールの受入。混入欠陥オラクル(samples/期待結果.md)のうち、
 * データフロー段が担う R001(No.1/9)・R003(No.2/5)・R005(No.3/13)・R004(No.14)を「該当ファイルの
 * 該当行のみ」で検出し、それ以外の不用意な検出(偽陽性)が samples に出ないことを確認する。
 */
class DataFlowSamplesAcceptanceTest {

    private record Hit(String base, int line) {
    }

    private static List<Hit> hits(Rule rule) {
        AnalysisContext context = DataFlowFixtures.samples();
        return rule.evaluate(context).stream()
                .map(DataFlowSamplesAcceptanceTest::toHit)
                .sorted(java.util.Comparator.comparing(Hit::base).thenComparingInt(Hit::line))
                .collect(Collectors.toList());
    }

    private static Hit toHit(Finding f) {
        String path = f.location().file();
        String base = java.nio.file.Path.of(path).getFileName().toString();
        return new Hit(base, f.location().line());
    }

    @Test
    void r001DetectsExactlyTheTwoUninitializedUses() {
        assertEquals(List.of(new Hit("SYK001.cbl", 121), new Hit("SYK004.cbl", 41)),
                hits(new UninitializedVariableRule()),
                "R001 は SYK001:121 と SYK004:41 のみを検出すること");
    }

    @Test
    void r003DetectsExactlyTheTwoTruncatingMoves() {
        assertEquals(List.of(new Hit("SYK001.cbl", 128), new Hit("SYK002.cbl", 118)),
                hits(new MoveTruncationRule()),
                "R003 は SYK001:128 と SYK002:118 のみを検出すること");
    }

    @Test
    void r004DetectsExactlyTheComputeWithoutOnSizeError() {
        assertEquals(List.of(new Hit("SYK007.cbl", 79)),
                hits(new OnSizeErrorMissingRule()),
                "R004 は SYK007:79 のみを検出すること");
    }

    @Test
    void r005DetectsExactlyTheTwoOutOfRangeSubscripts() {
        assertEquals(List.of(new Hit("SYK001.cbl", 114), new Hit("SYK006.cbl", 139)),
                hits(new OccursSubscriptRangeRule()),
                "R005 は SYK001:114 と SYK006:139 のみを検出すること");
    }

    @Test
    void syntheticRulesAreSilentOnSamples() {
        AnalysisContext context = DataFlowFixtures.samples();
        for (Rule rule : List.of(new PerformUntilNotUpdatedRule(), new DynamicSqlTaintRule(),
                new SensitiveDataOutputRule(), new UnsignedNegativeResultRule(),
                new RedefinesMismatchRule(), new StringOverflowRule(), new IdenticalOperandsRule())) {
            assertEquals(Map.of(), rule.evaluate(context).stream()
                    .collect(Collectors.groupingBy(f -> f.ruleId(), Collectors.counting())),
                    () -> rule.id() + " は samples で検出を出さないこと");
        }
    }
}
