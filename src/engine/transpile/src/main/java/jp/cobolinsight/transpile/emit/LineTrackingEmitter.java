package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.linemap.MappingKind;
import jp.cobolinsight.core.source.LineRange;

import java.util.ArrayList;
import java.util.List;

/**
 * The line-tracking emitter foundation: appends output lines while retaining the COBOL line range
 * each line originates from, and assembles {@link PendingMapping} entries. Independent of the target
 * language. Line breaks are hard-coded as LF at render time, with one trailing LF as well
 * (deterministic, assumes no BOM). Indentation is one level per {@code indentUnit}; blank lines get
 * no indentation. Generators read {@link #nextLine()}/{@link #lastLine()} before and after
 * rendering, and record the emitted line range into the correspondence table.
 */
public final class LineTrackingEmitter {

    private final String indentUnit;
    private final List<String> lines = new ArrayList<>();
    private final List<PendingMapping> mappings = new ArrayList<>();
    private int indent;

    public LineTrackingEmitter(String indentUnit) {
        this.indentUnit = indentUnit;
    }

    /** Appends one line at the current indentation. An empty string becomes a blank line with no indentation. */
    public void emit(String text) {
        if (text.isEmpty()) {
            lines.add("");
        } else {
            lines.add(indentUnit.repeat(indent) + text);
        }
    }

    /** Appends one blank line. */
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

    /** The 1-based line number that the next {@link #emit} call will occupy. */
    public int nextLine() {
        return lines.size() + 1;
    }

    /** The 1-based line number of the most recently appended line. */
    public int lastLine() {
        return lines.size();
    }

    /**
     * Records a correspondence from a COBOL line range to the generated line range [genStart, genEnd].
     * The kind is determined from the line counts of both ranges.
     */
    public void addMapping(String cobolSourceId, LineRange cobolLines, String generatedFile,
            int genStart, int genEnd, String note) {
        LineRange generatedLines = new LineRange(genStart, genEnd);
        MappingKind kind = kindOf(cobolLines, generatedLines);
        mappings.add(new PendingMapping(cobolSourceId, cobolLines, generatedFile, generatedLines,
                kind, note));
    }

    /**
     * Records a correspondence with an explicit kind. Used to specify a semantic kind that cannot be
     * derived from line counts, such as N:1 folding of an untranslatable block (EXEC CICS/SQL) into
     * a single annotation stub.
     */
    public void addMapping(String cobolSourceId, LineRange cobolLines, String generatedFile,
            int genStart, int genEnd, MappingKind kind, String note) {
        mappings.add(new PendingMapping(cobolSourceId, cobolLines, generatedFile,
                new LineRange(genStart, genEnd), kind, note));
    }

    public List<PendingMapping> mappings() {
        return List.copyOf(mappings);
    }

    /** Returns the generated text with all lines joined by LF, plus one trailing LF. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Determines the correspondence kind from the line counts of both ranges. When both are multi-line, the side with more lines decides between 1:N and N:1. */
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
