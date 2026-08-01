package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.engineapi.semantic.CallKind;
import jp.cobolinsight.engineapi.semantic.CallRelation;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.ControlKind;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.PerformRelation;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.ProcedureKind;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 段落・文種別・CALL/PERFORM 関係・GO TO の変換の検証。 */
class StatementAndRelationMappingTest {

    private static Procedure procedure(String fileName, String name) {
        CobolSemanticModel model = TestSources.model(fileName);
        return model.procedures().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        fileName + " に段落 " + name + " が無い: "
                                + model.procedures().stream().map(Procedure::name).toList()));
    }

    private static List<String> verbs(Procedure procedure) {
        return procedure.statements().stream()
                .map(s -> s instanceof SimpleStatement simple ? simple.verb()
                        : s.getClass().getSimpleName())
                .toList();
    }

    @Test
    void syk001ParagraphsAreMappedInOrder() {
        CobolSemanticModel model = TestSources.model("SYK001.cbl");
        List<String> names = model.procedures().stream().map(Procedure::name).toList();
        assertEquals(List.of("0000-メイン処理", "1000-初期化処理", "1100-受注データ読込",
                "2000-受注データ処理", "2100-明細検証", "2110-新規登録検証", "2120-訂正検証",
                "2200-結果判定", "8000-終了処理"), names);
        assertTrue(model.procedures().stream().allMatch(p -> p.kind() == ProcedureKind.PARAGRAPH));
        assertEquals(72, procedure("SYK001.cbl", "0000-メイン処理").range().start().line());
    }

    @Test
    void simpleStatementVerbsAreResolved() {
        assertEquals(List.of("PERFORM", "PERFORM", "PERFORM", "STOP"),
                verbs(procedure("SYK001.cbl", "0000-メイン処理")));
        assertEquals(List.of("OPEN", "OPEN", "OPEN", "PERFORM"),
                verbs(procedure("SYK001.cbl", "1000-初期化処理")));
        assertEquals(List.of("READ"), verbs(procedure("SYK001.cbl", "1100-受注データ読込")));
        assertEquals(List.of("CLOSE", "CLOSE", "CLOSE", "DISPLAY", "DISPLAY"),
                verbs(procedure("SYK001.cbl", "8000-終了処理")));
        assertEquals(List.of("COMPUTE"), verbs(procedure("SYK007.cbl", "2100-引当率計算")));
    }

    @Test
    void moveCallAndInlinePerformAreMapped() {
        Procedure proc = procedure("SYK001.cbl", "2110-新規登録検証");
        List<Statement> statements = proc.statements();
        assertEquals(4, statements.size());
        assertEquals("MOVE", ((SimpleStatement) statements.get(0)).verb());
        assertEquals("CALL", ((SimpleStatement) statements.get(1)).verb());
        assertEquals("MOVE", ((SimpleStatement) statements.get(2)).verb());
        CompoundStatement loop = assertInstanceOf(CompoundStatement.class, statements.get(3));
        assertEquals(ControlKind.LOOP, loop.kind());
        assertEquals(1, loop.blocks().size());
        assertEquals("ADD",
                ((SimpleStatement) loop.blocks().get(0).statements().get(0)).verb());
    }

    @Test
    void ifStatementIsMappedToBranchWithThenAndElse() {
        Procedure proc = procedure("SYK001.cbl", "2200-結果判定");
        assertEquals(1, proc.statements().size());
        CompoundStatement branch = assertInstanceOf(CompoundStatement.class, proc.statements().get(0));
        assertEquals(ControlKind.BRANCH, branch.kind());
        assertTrue(branch.conditionText().contains("WS-検証金額"),
                () -> "条件テキスト: " + branch.conditionText());
        assertEquals(2, branch.blocks().size());
        assertEquals("THEN", branch.blocks().get(0).label());
        assertEquals("ELSE", branch.blocks().get(1).label());
        assertEquals(List.of("MOVE", "ADD", "MOVE", "MOVE", "WRITE"),
                branch.blocks().get(0).statements().stream()
                        .map(s -> ((SimpleStatement) s).verb()).toList());
        assertEquals(List.of("MOVE", "MOVE", "WRITE"),
                branch.blocks().get(1).statements().stream()
                        .map(s -> ((SimpleStatement) s).verb()).toList());
    }

    @Test
    void evaluateIsMappedToBranchWithWhenBlocks() {
        Procedure proc = procedure("SYK001.cbl", "2100-明細検証");
        CompoundStatement branch = assertInstanceOf(CompoundStatement.class, proc.statements().get(0));
        assertEquals(ControlKind.BRANCH, branch.kind());
        assertEquals(4, branch.blocks().size());
        assertTrue(branch.blocks().get(0).label().contains("ORD1-新規登録"));
        assertTrue(branch.blocks().get(1).label().contains("ORD1-訂正"));
        assertTrue(branch.blocks().get(2).label().contains("ORD1-取消"));
        assertEquals("OTHER", branch.blocks().get(3).label());
        assertEquals("PERFORM",
                ((SimpleStatement) branch.blocks().get(0).statements().get(0)).verb());
        assertEquals("CONTINUE",
                ((SimpleStatement) branch.blocks().get(2).statements().get(0)).verb());
        assertEquals("MOVE",
                ((SimpleStatement) branch.blocks().get(3).statements().get(0)).verb());
    }

    @Test
    void staticCallIsMapped() {
        CobolSemanticModel model = TestSources.model("SYK001.cbl");
        assertEquals(1, model.calls().size());
        CallRelation call = model.calls().get(0);
        assertEquals("SYK001", call.callerProgramId());
        assertEquals(CallKind.STATIC, call.kind());
        assertEquals("SYK003", call.target());
        assertEquals(110, call.range().start().line());
    }

    @Test
    void dynamicCallIsMapped() {
        CobolSemanticModel model = TestSources.model("SYK002.cbl");
        assertEquals(1, model.calls().size());
        CallRelation call = model.calls().get(0);
        assertEquals(CallKind.DYNAMIC, call.kind());
        assertEquals("WS-PROG-NAME", call.target());
        assertEquals(113, call.range().start().line());
    }

    @Test
    void performRelationsAreMapped() {
        CobolSemanticModel model = TestSources.model("SYK001.cbl");
        assertTrue(model.performs().contains(perform(model,
                "0000-メイン処理", "1000-初期化処理")));
        assertTrue(model.performs().contains(perform(model,
                "2000-受注データ処理", "1100-受注データ読込")));
        assertTrue(model.performs().contains(perform(model,
                "2100-明細検証", "2110-新規登録検証")));
    }

    private static PerformRelation perform(CobolSemanticModel model, String from, String target) {
        return model.performs().stream()
                .filter(p -> p.fromProcedure().equals(from) && p.targetProcedure().equals(target))
                .findFirst().orElseThrow(() -> new AssertionError(
                        from + " -> " + target + " が無い: " + model.performs()));
    }

    @Test
    void performThruIsMapped() {
        CobolSemanticModel model = TestSources.model("SYK002.cbl");
        PerformRelation thru = model.performs().stream()
                .filter(p -> p.thruProcedure().isPresent()).findFirst()
                .orElseThrow(() -> new AssertionError("THRU 付き PERFORM が無い"));
        assertEquals("3030-更新登録処理", thru.fromProcedure());
        assertEquals("4000-マスタ更新処理", thru.targetProcedure());
        assertEquals(Optional.of("4000-マスタ更新処理-EXIT"), thru.thruProcedure());
    }

    @Test
    void goToIsMappedToGoToStatement() {
        Procedure proc = procedure("SYK002.cbl", "9000-緊急再更新処理");
        GoToStatement goTo = proc.statements().stream()
                .filter(GoToStatement.class::isInstance).map(GoToStatement.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("GO TO が無い"));
        assertEquals(List.of("4010-マスタ書込"), goTo.targets());
        assertEquals(Optional.empty(), goTo.dependingOn());
    }
}
