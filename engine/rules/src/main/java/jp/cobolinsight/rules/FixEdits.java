package jp.cobolinsight.rules;

import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.semantic.StatementBlock;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.fix.FixedFormatNormalizer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * FixProducer 実装が共有する編集組み立て補助。Finding.location は列を1固定・byteOffset を
 * 不明に潰すため、意味モデルから対象文の完全な {@link SourceRange} を再解決し、挿入編集を組む。
 *
 * <p>挿入文の桁折り返しは UTF-8 相対のバイト長で行う({@link #LAYOUT_CHARSET})。折り返しは
 * バイト長が広いエンコーディングほど早く発生するため、UTF-8 で決めた物理行は Shift_JIS など
 * より短い符号で書き戻しても72桁を超えない。M7 の対象samplesは全て UTF-8 で、桁は厳密一致する。
 */
public final class FixEdits {

    /** 挿入行の桁折り返し計算に用いる符号。適用時の再符号化は fix モジュールが原本の符号で行う。 */
    public static final Charset LAYOUT_CHARSET = StandardCharsets.UTF_8;

    private static final FixedFormatNormalizer NORMALIZER = new FixedFormatNormalizer();

    private FixEdits() {
    }

    /** sourceFile と一致する意味モデルを引く。 */
    public static Optional<CobolSemanticModel> modelOf(AnalysisContext context, String sourceFile) {
        return context.cobolPrograms().stream()
                .filter(model -> model.sourceFile().equals(sourceFile))
                .findFirst();
    }

    /**
     * 開始行が {@code line} で述語に合致する最初の単文を、手続き部を入れ子まで辿って返す。
     * Finding.location の列が失われるため、開始行と述語で対象文を同定する。
     */
    public static Optional<SimpleStatement> findSimpleStatement(CobolSemanticModel model, int line,
            Predicate<SimpleStatement> predicate) {
        for (Procedure procedure : model.procedures()) {
            Optional<SimpleStatement> found = find(procedure.statements(), line, predicate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<SimpleStatement> find(List<Statement> statements, int line,
            Predicate<SimpleStatement> predicate) {
        for (Statement statement : statements) {
            if (statement instanceof SimpleStatement simple) {
                if (simple.range().start().line() == line && predicate.test(simple)) {
                    return Optional.of(simple);
                }
            } else if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    Optional<SimpleStatement> found = find(block.statements(), line, predicate);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 対象文を固定形式のB領域へ整形した1行以上の物理行。 */
    public static List<String> layout(String statement) {
        return NORMALIZER.layoutStatement(statement, LAYOUT_CHARSET);
    }

    /**
     * 対象文の終端物理行(1始まり)が固定形式の終止ピリオドで文を閉じているかを判定する。閉じて
     * いれば、その直後へピリオド終端の新しい文を挿入しても囲む構造(IF/PERFORM/段落など)を壊さ
     * ない。閉じていない(ブロックの途中にある)文の直後へピリオド終端の文を挿入すると、囲む文を
     * 途中で終止させて構文を壊すため、挿入型の修正案生成はこの判定で安全な場合に限る。
     */
    public static boolean endsSentence(String sourceText, int line) {
        String[] lines = sourceText.split("\n", -1);
        if (line < 1 || line > lines.length) {
            return false;
        }
        return lines[line - 1].stripTrailing().endsWith(".");
    }

    /**
     * 対象範囲の直後(終端行の次行先頭)へ新しい文を挿入する編集。同一物理行の識別欄(73-80桁)を
     * 割らないよう、終端桁ではなく次行先頭の空範囲へ置く。置換は整形済み物理行を改行で連結し、
     * 末尾に改行を付す。
     */
    public static TextEdit insertStatementAfter(SourceRange target, String statement) {
        String file = target.end().file();
        int nextLine = target.end().line() + 1;
        String replacement = String.join("\n", layout(statement)) + "\n";
        SourcePosition at = new SourcePosition(file, nextLine, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new TextEdit(new SourceRange(at, at), replacement);
    }
}
