package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.engineapi.linemap.MappingKind;
import jp.cobolinsight.engineapi.source.LineRange;

import java.util.ArrayList;
import java.util.List;

/**
 * 出力行を追記しつつ、各行が由来する COBOL 行範囲を保持して {@link PendingMapping} を組む行追跡エミッタ基盤。
 * 対象言語に依存しない。改行は render 時に LF をハードコードし、末尾にも1つ付す(決定論・BOMなし前提)。
 * インデントは 1 レベル {@code indentUnit} 分で、空行にはインデントを付けない。
 * 生成器はレンダリング前後で {@link #nextLine()}/{@link #lastLine()} を読み、出力した行範囲を対応表へ記録する。
 */
public final class LineTrackingEmitter {

    private final String indentUnit;
    private final List<String> lines = new ArrayList<>();
    private final List<PendingMapping> mappings = new ArrayList<>();
    private int indent;

    public LineTrackingEmitter(String indentUnit) {
        this.indentUnit = indentUnit;
    }

    /** 現在のインデントで1行を追記する。空文字列はインデントなしの空行になる。 */
    public void emit(String text) {
        if (text.isEmpty()) {
            lines.add("");
        } else {
            lines.add(indentUnit.repeat(indent) + text);
        }
    }

    /** 空行を1行追記する。 */
    public void blank() {
        lines.add("");
    }

    public void indent() {
        indent++;
    }

    public void dedent() {
        if (indent > 0) {
            indent--;
        }
    }

    /** 次の {@link #emit} が占める1始まりの行番号。 */
    public int nextLine() {
        return lines.size() + 1;
    }

    /** 直近に追記した行の1始まりの行番号。 */
    public int lastLine() {
        return lines.size();
    }

    /**
     * COBOL 行範囲から生成行範囲 [genStart, genEnd] への対応を記録する。種別は両範囲の行数から決める。
     */
    public void addMapping(String cobolSourceId, LineRange cobolLines, String generatedFile,
            int genStart, int genEnd, String note) {
        LineRange generatedLines = new LineRange(genStart, genEnd);
        MappingKind kind = kindOf(cobolLines, generatedLines);
        mappings.add(new PendingMapping(cobolSourceId, cobolLines, generatedFile, generatedLines,
                kind, note));
    }

    /**
     * 種別を明示して対応を記録する。直訳不能ブロック(EXEC CICS/SQL)を1つの注記スタブへ畳む N:1 のように、
     * 行数からは導けない意味上の種別を指定するために使う。
     */
    public void addMapping(String cobolSourceId, LineRange cobolLines, String generatedFile,
            int genStart, int genEnd, MappingKind kind, String note) {
        mappings.add(new PendingMapping(cobolSourceId, cobolLines, generatedFile,
                new LineRange(genStart, genEnd), kind, note));
    }

    public List<PendingMapping> mappings() {
        return List.copyOf(mappings);
    }

    /** 全行を LF 連結し、末尾にも LF を付した生成テキストを返す。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 両範囲の行数から対応種別を決める。双方が複数行のときは、行数の多い側で 1:N・N:1 を決める。 */
    static MappingKind kindOf(LineRange cobolLines, LineRange generatedLines) {
        int cobolCount = cobolLines.endLine() - cobolLines.startLine() + 1;
        int genCount = generatedLines.endLine() - generatedLines.startLine() + 1;
        if (cobolCount == 1 && genCount == 1) {
            return MappingKind.ONE_TO_ONE;
        }
        if (cobolCount == 1) {
            return MappingKind.ONE_TO_MANY;
        }
        if (genCount == 1) {
            return MappingKind.MANY_TO_ONE;
        }
        return genCount >= cobolCount ? MappingKind.ONE_TO_MANY : MappingKind.MANY_TO_ONE;
    }
}
