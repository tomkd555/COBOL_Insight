package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import org.eclipse.lsp.cobol.common.AnalysisResult;
import org.eclipse.lsp.cobol.common.model.tree.ProgramNode;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * {@link CobolParser} implementation that calls the patched Che4z directly as a library.
 * Builds a normalized semantic model from Che4z's AST/symbol information plus CST capture.
 * A per-file failure (parse exception or error diagnostic) is returned as ParseOutcome.Failure.
 */
public final class Che4zCobolParser implements CobolParser {

    @Override
    public ParseOutcome<CobolSemanticModel> parse(DecodedSource source,
            List<Path> copybookSearchPaths) {
        String uri = Paths.get(source.path()).toUri().toString();
        try {
            Che4zRuntime.Analysis analysis =
                    Che4zRuntime.instance().analyze(uri, source.text(), copybookSearchPaths);
            Finding errorFinding = firstError(analysis.result(), source, uri);
            if (errorFinding != null) {
                return ParseOutcome.failure(errorFinding);
            }
            ProgramNode program = analysis.result().getRootNode() == null ? null
                    : analysis.result().getRootNode().getDepthFirstStream()
                            .filter(ProgramNode.class::isInstance)
                            .map(ProgramNode.class::cast)
                            .findFirst().orElse(null);
            if (program == null || program.getProgramName() == null
                    || program.getProgramName().isBlank()) {
                return ParseOutcome.failure(Finding.parseFailure(
                        SourcePosition.fileStart(source.path()),
                        "PROGRAM-ID を特定できない: " + source.path()));
            }
            SourceTexts texts = new SourceTexts(uri, source.text());
            CobolSemanticModel model =
                    new SemanticModelMapper(source.path(), texts, analysis.cstCapture())
                            .map(program);
            return ParseOutcome.success(model);
        } catch (RuntimeException e) {
            return ParseOutcome.failure(Finding.parseFailure(
                    SourcePosition.fileStart(source.path()),
                    "COBOL 解析で例外が発生した: " + e));
        }
    }

    private static Finding firstError(AnalysisResult result, DecodedSource source, String mainUri) {
        if (result == null || result.getDiagnostics() == null) {
            return null;
        }
        for (Map.Entry<String, List<Diagnostic>> entry : result.getDiagnostics().entrySet()) {
            for (Diagnostic diagnostic : entry.getValue()) {
                if (diagnostic.getSeverity() != DiagnosticSeverity.Error) {
                    continue;
                }
                String file = entry.getKey().equals(mainUri) ? source.path()
                        : UriPaths.toPathString(entry.getKey());
                int line = 1;
                int column = 1;
                if (diagnostic.getRange() != null && diagnostic.getRange().getStart() != null) {
                    line = diagnostic.getRange().getStart().getLine() + 1;
                    column = diagnostic.getRange().getStart().getCharacter() + 1;
                }
                return Finding.parseFailure(
                        new SourcePosition(file, line, column, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        diagnostic.getMessage());
            }
        }
        return null;
    }
}
