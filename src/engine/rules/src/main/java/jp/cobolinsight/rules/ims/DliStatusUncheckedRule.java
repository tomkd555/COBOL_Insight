package jp.cobolinsight.rules.ims;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgSupport;
import jp.cobolinsight.rules.cobol.ServiceCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * R089 A DL/I call whose status code nobody looks at. DL/I reports every outcome — segment not
 * found, end of database, a duplicate key, a call the PCB does not allow — in the two-byte status
 * code of the PCB and returns normally. A program that does not test the code reads an I/O area
 * the call never filled, or carries on after an update that was not made.
 *
 * <p>The status code is found by position, not by name: it is the two bytes at offset 10 of the
 * PCB mask the call passes, whatever the program calls them. A call whose PCB operand is not a
 * group with such a field — one reached through an address, or an AIB — is left alone rather than
 * guessed at.
 */
public final class DliStatusUncheckedRule implements Rule {

    private static final Set<String> DLI = Set.of("CBLTDLI", "CEETDLI");

    /** Where the status code stands in every PCB mask, and how long it is. */
    private static final int STATUS_OFFSET = 10;
    private static final int STATUS_LENGTH = 2;

    private static final RuleMeta META = RuleMeta
            .named("R089", "DL/I 呼び出しの状態コード未検査", RuleCategory.INTEGRITY)
            .technology(Technology.IMS)
            .symptom("セグメント未検出・更新失敗の見逃し")
            .summary("CBLTDLI の呼び出しの後、次の DL/I 呼び出しまでに PCB の状態コードを"
                    + "検査しない箇所を検出します。")
            .rationale("DL/I はセグメントなし（GE）・データベースの終わり（GB）・重複キー（II）"
                    + "などの結果をすべて PCB の状態コードで返し、呼び出し自体は正常に戻ります。"
                    + "検査しないと、設定されなかった入出力域を読むか、失敗した更新の後も"
                    + "処理が進みます。")
            .detection("CBLTDLI・CEETDLI を CALL する文について、USING に渡した PCB マスクの"
                    + "先頭から 11 バイト目にある 2 バイトの項目を状態コードとします。"
                    + "次の DL/I 呼び出しに達するまでの前方経路で、その項目か項目の条件名を"
                    + "条件で参照せず、他の項目へ転記もしないものを検出します。"
                    + "PCB マスクから状態コードの項目を求められない呼び出しと、"
                    + "AIB を使う AIBTDLI は対象外です。")
            .remedy("呼び出しの直後に状態コードを検査し、空白と想定した値（GE・GB など）以外を"
                    + "異常として処理してください。")
            .example("""
                    CALL 'CBLTDLI' USING DLI-GU CUST-PCB CUST-IOAREA CUST-SSA.
                    MOVE IO-NAME TO WS-NAME.
                    """, """
                    CALL 'CBLTDLI' USING DLI-GU CUST-PCB CUST-IOAREA CUST-SSA.
                    IF CP-STATUS NOT = SPACES
                        PERFORM DLI-ERROR
                    END-IF.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            ServiceCalls.Call call = dliCall(model, node).orElse(null);
            if (call == null) {
                continue;
            }
            Set<String> status = statusNames(model, call);
            if (status.isEmpty() || ServiceCalls.readBeforeNext(cfg, node,
                    other -> dliCall(model, other).isPresent(), status)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    "DL/I 呼び出しの後で PCB の状態コード " + status.iterator().next()
                            + " を検査していません。セグメントなしや更新の失敗に"
                            + "気づかないまま後続が進みます。",
                    CfgSupport.at(call.statement().range())));
        }
    }

    private static Optional<ServiceCalls.Call> dliCall(CobolSemanticModel model, CfgNode node) {
        return node.statement().flatMap(statement -> ServiceCalls.of(model, statement))
                .filter(call -> DLI.contains(call.target()));
    }

    /**
     * The status code item of the PCB a call passes, with its condition names. The PCB is the
     * second operand, or the third when a parameter count comes first, so each is tried in turn.
     */
    private static Set<String> statusNames(CobolSemanticModel model, ServiceCalls.Call call) {
        for (int index = 1; index <= 2 && index < call.operands().size(); index++) {
            Optional<DataItem> status = CfgSupport.itemNamed(model, call.operands().get(index))
                    .flatMap(DliStatusUncheckedRule::statusItemOf);
            if (status.isPresent()) {
                return ServiceCalls.namesOf(status.get());
            }
        }
        return Set.of();
    }

    /** The elementary item of two bytes at offset 10 of a group, when the group has one. */
    static Optional<DataItem> statusItemOf(DataItem pcb) {
        int offset = 0;
        for (DataItem child : pcb.children()) {
            if (child.redefines().isPresent()) {
                continue;
            }
            Optional<Integer> length = CfgSupport.byteLength(child);
            if (length.isEmpty()) {
                return Optional.empty();
            }
            if (offset == STATUS_OFFSET && length.get() == STATUS_LENGTH
                    && child.children().isEmpty()) {
                return Optional.of(child);
            }
            if (offset <= STATUS_OFFSET && STATUS_OFFSET < offset + length.get()
                    && !child.children().isEmpty()) {
                // The status code lies inside a nested group: look for it there, at the offset
                // the nesting leaves.
                return nested(child, STATUS_OFFSET - offset);
            }
            offset += length.get();
            if (offset > STATUS_OFFSET) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<DataItem> nested(DataItem group, int wanted) {
        int offset = 0;
        for (DataItem child : group.children()) {
            if (child.redefines().isPresent()) {
                continue;
            }
            Optional<Integer> length = CfgSupport.byteLength(child);
            if (length.isEmpty()) {
                return Optional.empty();
            }
            if (offset == wanted && length.get() == STATUS_LENGTH
                    && child.children().isEmpty()) {
                return Optional.of(child);
            }
            offset += length.get();
        }
        return Optional.empty();
    }
}
