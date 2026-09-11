package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.picture.PictureCategory;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * R034 A MOVE from an elementary alphanumeric or alphabetic item into a numeric item, where the
 * sending item is never tested {@code IS [NOT] NUMERIC} anywhere in the program. Since the sending
 * item's picture carries no digit restriction, a value that fails the numeric class ends up in a
 * numeric item unnoticed. Only the MOVE case (R034); the arithmetic/relation-operand case for a
 * FILE/LINKAGE USAGE DISPLAY numeric item is R049.
 */
public final class NumericClassUncheckedMoveRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R034", "字類検査を経ない数字項目への転記・演算", "データ移動")
            .summary("英字項目・英数字項目から数字項目への MOVE 文のうち、"
                    + "送り出し側項目を NUMERIC で検査していないものを検出します。")
            .rationale("数字として扱えない値が転記され、プログラムが実行時に異常終了するか、"
                    + "以降の演算が誤った値のまま進みます。")
            .detection("MOVE 文の送り出し側項目が英字項目または英数字項目、受け取り側項目が数字項目であり、"
                    + "原始プログラムのどこにも送り出し側項目を NUMERIC で検査する条件がないものを"
                    + "検出します。表意定数・定数・集団項目を送り出し側に持つ MOVE 文、"
                    + "送り出し側が部分参照の MOVE 文、送り出し側項目を検査している MOVE 文は対象外です。")
            .remedy("MOVE の前に送り出し側項目を NUMERIC で検査し、"
                    + "数字でない値を除いてから転記してください。")
            .example("""
                    01  WS-入力金額X  PIC X(09).
                    01  WS-入力金額   PIC 9(09).
                        ACCEPT WS-入力金額X
                        MOVE WS-入力金額X TO WS-入力金額.
                    """, """
                    01  WS-入力金額X  PIC X(09).
                    01  WS-入力金額   PIC 9(09).
                        ACCEPT WS-入力金額X
                        IF WS-入力金額X IS NUMERIC
                            MOVE WS-入力金額X TO WS-入力金額
                        END-IF.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String source = texts.textOf(model.sourceFile()).orElse(null);
            if (source == null) {
                continue;
            }
            Set<String> tested = NumericClassSupport.numericTestedNames(source);
            DataFlowSupport support = DataFlowSupport.of(model, texts);
            NumericClassSupport.walk(flatten(model), statement ->
                    evaluate(model, statement, support, tested, findings));
        }
        return findings;
    }

    private static List<Statement> flatten(CobolSemanticModel model) {
        List<Statement> all = new ArrayList<>();
        for (Procedure procedure : model.procedures()) {
            all.addAll(procedure.statements());
        }
        return all;
    }

    private static void evaluate(CobolSemanticModel model, Statement statement,
            DataFlowSupport support, Set<String> tested, List<Finding> findings) {
        if (!(statement instanceof SimpleStatement simple)
                || !"MOVE".equals(simple.verb().toUpperCase(Locale.ROOT))) {
            return;
        }
        Hit hit = untestedMove(simple.text(), support, tested);
        if (hit == null) {
            return;
        }
        int line = simple.range().start().line();
        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                hit.sender() + " は NUMERIC 検査を経ずに " + hit.receiver() + " へ転記されます。"
                        + "数字として不正な値のまま処理が進み得ます。",
                new SourcePosition(model.sourceFile(), line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET)));
    }

    private record Hit(String sender, String receiver) {
    }

    /** The first receiving numeric item an untested alphanumeric/alphabetic sender reaches. null if none. */
    private static Hit untestedMove(String text, DataFlowSupport support, Set<String> tested) {
        String masked = NumericClassSupport.maskLiterals(text);
        String upper = masked.toUpperCase(Locale.ROOT);
        int to = NumericClassSupport.indexOfWord(upper, "TO");
        if (to < 0) {
            return null;
        }
        String senderRegion = masked.substring(0, to);
        if (senderRegion.indexOf(':') >= 0) {
            return null; // a reference modification is not judged
        }
        Matcher senderToken = NumericClassSupport.NAME_TOKEN.matcher(senderRegion.substring("MOVE".length()));
        if (!senderToken.find()) {
            return null; // a figurative constant or a literal
        }
        String sender = senderToken.group();
        if (tested.contains(NumericClassSupport.norm(sender))) {
            return null;
        }
        PictureType senderPicture = support.pictureType(sender).orElse(null);
        if (senderPicture == null || (senderPicture.category() != PictureCategory.ALPHANUMERIC
                && senderPicture.category() != PictureCategory.ALPHABETIC)) {
            return null;
        }
        Matcher receiverToken = NumericClassSupport.NAME_TOKEN.matcher(
                NumericClassSupport.maskParenthesized(masked.substring(to + "TO".length())));
        while (receiverToken.find()) {
            String receiver = receiverToken.group();
            PictureType receiverPicture = support.pictureType(receiver).orElse(null);
            if (receiverPicture != null && receiverPicture.isNumeric()) {
                return new Hit(sender, receiver);
            }
        }
        return null;
    }
}
