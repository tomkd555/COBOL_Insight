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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link CobolParser} implementation that calls the patched Che4z directly as a library.
 * Builds a normalized semantic model from Che4z's AST/symbol information plus CST capture.
 * A per-file failure (parse exception or error diagnostic) is returned as ParseOutcome.Failure.
 */
public final class Che4zCobolParser implements CobolParser {

    /**
     * What Che4z's Db2 dialect says when it is the dialect, and not the program, that could not
     * go on, as resourceBundles/messages_en.properties spells it: the {@code ErrorStrategy.*}
     * keys Db2ErrorStrategy passes to the message service, the {@code db2Parser.missing*} keys it
     * raises for an unterminated block, and the {@code db2Parser.validation.*} keys the dialect
     * raises about where a statement may stand — a statement its own grammar could not read lands
     * there too, as a UNION cursor declaration in WORKING-STORAGE does.
     *
     * <p>Everything else is a finding about the program: the {@code semantics.*} messages above
     * all, of which "Variable %s is not defined" is the one that reaches into an EXEC SQL block,
     * through a host variable nothing declares.</p>
     */
    private static final List<String> SQL_DIALECT_ERRORS = List.of(
            "No viable alternative at input ",
            "Missing token ",
            "Syntax error on ",
            "Extraneous input ",
            "Unexpected end of line",
            "Unexpected end of file",
            "this DB2 statement is allowed only in ",
            "Db2 variable declaration is only allowed in DATA DIVISION");

    @Override
    public ParseOutcome<CobolSemanticModel> parse(DecodedSource source,
            List<Path> copybookSearchPaths) {
        String uri = Paths.get(source.path()).toUri().toString();
        try {
            Che4zRuntime.Analysis analysis =
                    Che4zRuntime.instance().analyze(uri, source.text(), copybookSearchPaths);
            List<Finding> errors = errors(analysis.result(), source, uri);
            if (!errors.isEmpty()) {
                Che4zRuntime.Analysis retry =
                        retryWithMaskedSql(source, uri, copybookSearchPaths, errors);
                if (retry == null || !errors(retry.result(), source, uri).isEmpty()) {
                    // The masked run's own message would name the placeholder text, which is in
                    // no source the user can open; the first pass said what really broke.
                    return ParseOutcome.failure(errors.get(0));
                }
                analysis = retry;
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
                        "PROGRAM-ID を特定できませんでした: " + source.path()));
            }
            SourceTexts texts = new SourceTexts(uri, source.text());
            CobolSemanticModel model =
                    new SemanticModelMapper(source.path(), texts, analysis.cstCapture())
                            .map(program);
            return ParseOutcome.success(model);
        } catch (RuntimeException e) {
            return ParseOutcome.failure(Finding.parseFailure(
                    SourcePosition.fileStart(source.path()),
                    "COBOL 解析で例外が発生しました: " + e));
        }
    }

    /**
     * A second analysis of the same program with the bodies of its {@code EXEC SQL} blocks
     * replaced by SQL Che4z accepts, or null when the retry does not apply. Che4z parses embedded
     * SQL with its own grammar, which is stricter than Db2 — an ordinary {@code #} in a table name
     * is enough — and one such statement would otherwise cost the whole program.
     *
     * <p>The retry runs only when every error inside a maskable block is one the dialect raised
     * about itself, and there is at least one: masking a block deletes what it holds, so an error
     * the mask would merely hide — an undefined host variable, say — must keep the program
     * failing. A program with no error in a maskable block takes exactly the path it took
     * before.</p>
     *
     * <p>The masked copy keeps the layout of the original, so the ranges Che4z reports still point
     * at the real SQL: the caller reads the block texts out of the original text.</p>
     */
    private static Che4zRuntime.Analysis retryWithMaskedSql(DecodedSource source, String uri,
            List<Path> copybookSearchPaths, List<Finding> errors) {
        List<EmbeddedSqlRanges.Block> blocks = EmbeddedSqlRanges.scan(source.text());
        boolean anyInBlock = false;
        for (Finding error : errors) {
            if (!source.path().equals(error.location().file())
                    || !inMaskableBlock(blocks, error)) {
                continue;
            }
            if (SQL_DIALECT_ERRORS.stream().noneMatch(error.message()::startsWith)) {
                return null;
            }
            anyInBlock = true;
        }
        if (!anyInBlock) {
            return null;
        }
        String masked = EmbeddedSqlRanges.mask(source.text(), blocks);
        return masked == null ? null
                : Che4zRuntime.instance().analyze(uri, masked, copybookSearchPaths);
    }

    /**
     * Whether the error stands in a block {@link EmbeddedSqlRanges#mask} would rewrite. An INCLUDE,
     * a DECLARE SECTION and a WHENEVER are never masked, so an error inside one of them cannot be
     * what the retry would change.
     */
    private static boolean inMaskableBlock(List<EmbeddedSqlRanges.Block> blocks, Finding error) {
        return blocks.stream()
                .filter(block -> block.kind() == EmbeddedSqlRanges.Kind.OTHER)
                .anyMatch(block -> block.contains(error.location().line(),
                        error.location().column()));
    }

    /** Every error diagnostic of the analysis, in the order Che4z reports them. */
    private static List<Finding> errors(AnalysisResult result, DecodedSource source,
            String mainUri) {
        if (result == null || result.getDiagnostics() == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
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
                findings.add(Finding.parseFailure(
                        new SourcePosition(file, line, column, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        diagnostic.getMessage()));
            }
        }
        return findings;
    }
}
