package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.ExpandedCopyLine;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.lsp.cobol.common.mapping.ExtendedDocument;
import org.eclipse.lsp.cobol.core.engine.analysis.AnalysisContext;
import org.eclipse.lsp.cobol.core.semantics.CopybooksRepository;
import org.eclipse.lsp.cobol.dialects.ibm.ParserStageResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Supplementary information from the CST obtained through our own pipeline up to ParserStage.
 * Holds a statement-start-position (original source coordinates) to verb index, and the COPY expansion mapping.
 */
final class CstCapture {

    private final Map<String, String> verbsByPosition;
    private final List<CopyExpansionEntry> copyExpansions;
    private final List<CopyInlineExpansion> copyInlineExpansions;

    private CstCapture(Map<String, String> verbsByPosition,
            List<CopyExpansionEntry> copyExpansions,
            List<CopyInlineExpansion> copyInlineExpansions) {
        this.verbsByPosition = verbsByPosition;
        this.copyExpansions = copyExpansions;
        this.copyInlineExpansions = copyInlineExpansions;
    }

    static CstCapture build(ParserStageResult parserResult, AnalysisContext context,
            String mainUri) {
        Map<String, String> verbs = new HashMap<>();
        collectVerbs(parserResult.getTree(), context, verbs);
        List<CopyExpansionEntry> expansions =
                collectExpansions(parserResult.getTokens().getTokens(), context, mainUri);
        return new CstCapture(verbs, expansions, collectInlineExpansions(context, mainUri));
    }

    /** The verb corresponding to a statement-start position in original-source coordinates (0-based). Null if none. */
    String verbAt(String uri, int line, int character) {
        return verbsByPosition.get(key(uri, line, character));
    }

    List<CopyExpansionEntry> copyExpansions() {
        return copyExpansions;
    }

    List<CopyInlineExpansion> copyInlineExpansions() {
        return copyInlineExpansions;
    }

    private static void collectVerbs(ParseTree tree, AnalysisContext context,
            Map<String, String> verbs) {
        if (tree instanceof ParserRuleContext ruleContext
                && ruleContext.getClass().getSimpleName().endsWith("StatementContext")
                && ruleContext.getStart() != null) {
            Token start = ruleContext.getStart();
            Location mapped = mapToken(start, context);
            if (mapped != null) {
                verbs.putIfAbsent(
                        key(mapped.getUri(), mapped.getRange().getStart().getLine(),
                                mapped.getRange().getStart().getCharacter()),
                        start.getText().toUpperCase(Locale.ROOT));
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectVerbs(tree.getChild(i), context, verbs);
        }
    }

    /**
     * Scans the post-expansion tokens in order and groups a run of consecutive tokens that
     * originate from the same copybook into a single expansion entry. The expanded line range
     * runs from the first to the last line of that run, and the copybook-side start line is the
     * original line of the run's first token. All line numbers are aligned to be 1-based.
     */
    private static List<CopyExpansionEntry> collectExpansions(List<Token> tokens,
            AnalysisContext context, String mainUri) {
        List<CopyExpansionEntry> entries = new ArrayList<>();
        String runUri = null;
        int runExpandedStart = 0;
        int runExpandedEnd = 0;
        int runCopybookStart = 0;
        for (Token token : tokens) {
            if (token.getType() == Token.EOF || token.getText() == null
                    || token.getText().isBlank()) {
                continue;
            }
            Location mapped = mapToken(token, context);
            String uri = mapped == null ? null : mapped.getUri();
            boolean inCopybook = uri != null && !uri.equals(mainUri)
                    && !UriPaths.isImplicit(uri);
            if (inCopybook && uri.equals(runUri)) {
                runExpandedEnd = token.getLine();
                continue;
            }
            if (runUri != null) {
                entries.add(new CopyExpansionEntry(runExpandedStart, runExpandedEnd, runUri,
                        runCopybookStart));
                runUri = null;
            }
            if (inCopybook) {
                runUri = uri;
                runExpandedStart = token.getLine();
                runExpandedEnd = token.getLine();
                runCopybookStart = mapped.getRange().getStart().getLine() + 1;
            }
        }
        if (runUri != null) {
            entries.add(new CopyExpansionEntry(runExpandedStart, runExpandedEnd, runUri,
                    runCopybookStart));
        }
        return entries.stream()
                .map(e -> new CopyExpansionEntry(e.expandedStartLine(), e.expandedEndLine(),
                        UriPaths.toPathString(e.copybookPath()), e.copybookStartLine()))
                .toList();
    }

    /**
     * Builds the inline expansion for each COPY statement. Scans, in order, which copybook and
     * which line in it each line of the post-expansion document originates from, groups a run of
     * consecutive lines from the same copybook into a single expansion, and supplies the original
     * COPY statement's line number from the COPY statement position recorded by Che4z. When the
     * same copybook is pulled in multiple times, the expansions' order of appearance is matched
     * against the COPY statements' line numbers in ascending order.
     *
     * <p>Excludes runs for which no COPY statement is found in the original (nested expansion via
     * a COPY inside a copybook) and code Che4z implicitly inserts (such as SQLCA).
     */
    private static List<CopyInlineExpansion> collectInlineExpansions(AnalysisContext context,
            String mainUri) {
        ExtendedDocument document = context.getExtendedDocument();
        CopybooksRepository copybooks = context.getCopybooksRepository();
        if (document == null || copybooks == null) {
            return List.of();
        }
        Map<String, Deque<Integer>> statementLines = copyStatementLines(copybooks, mainUri);
        List<CopyInlineExpansion> expansions = new ArrayList<>();
        String runUri = null;
        List<ExpandedCopyLine> runLines = new ArrayList<>();
        String[] lines = document.toString().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Location origin = originOf(document, i, lines[i]);
            String uri = origin == null ? null : origin.getUri();
            boolean inCopybook = uri != null && !uri.equals(mainUri) && !UriPaths.isImplicit(uri);
            if (!inCopybook || !uri.equals(runUri)) {
                addInlineExpansion(expansions, runUri, runLines, copybooks, statementLines);
                runUri = inCopybook ? uri : null;
                runLines = new ArrayList<>();
            }
            if (inCopybook) {
                runLines.add(new ExpandedCopyLine(origin.getRange().getStart().getLine() + 1,
                        stripTrailing(lines[i])));
            }
        }
        addInlineExpansion(expansions, runUri, runLines, copybooks, statementLines);
        return List.copyOf(expansions);
    }

    /** Copybook name to the line numbers of COPY statements appearing in the original (1-based, ascending). */
    private static Map<String, Deque<Integer>> copyStatementLines(CopybooksRepository copybooks,
            String mainUri) {
        Map<String, Deque<Integer>> byName = new HashMap<>();
        copybooks.getDefinitionStatements().asMap().forEach((name, localities) -> {
            List<Integer> lines = localities.stream()
                    .filter(locality -> mainUri.equals(locality.getUri()))
                    .map(locality -> locality.getRange().getStart().getLine() + 1)
                    .sorted()
                    .toList();
            if (!lines.isEmpty()) {
                byName.put(name, new ArrayDeque<>(lines));
            }
        });
        return byName;
    }

    private static void addInlineExpansion(List<CopyInlineExpansion> out, String uri,
            List<ExpandedCopyLine> lines, CopybooksRepository copybooks,
            Map<String, Deque<Integer>> statementLines) {
        if (uri == null || lines.isEmpty()) {
            return;
        }
        String name = copybooks.getCopybookIdByUri(uri);
        Deque<Integer> pending = name == null ? null : statementLines.get(name);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        out.add(new CopyInlineExpansion(pending.removeFirst(), name, UriPaths.toPathString(uri),
                lines));
    }

    /**
     * The origin position of one post-expansion line, looked up at the leading column of its
     * content. For a line consisting only of spaces the column falls past the end of the line,
     * but it is extrapolated from the mapping of the line's last character, so a blanked-out
     * line, such as a note line, can still have its origin resolved.
     */
    private static Location originOf(ExtendedDocument document, int line, String text) {
        int column = 0;
        while (column < text.length() && text.charAt(column) == ' ') {
            column++;
        }
        try {
            return document.mapLocation(new Range(new Position(line, column),
                    new Position(line, column + 1)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    private static Location mapToken(Token token, AnalysisContext context) {
        try {
            int line = token.getLine() - 1;
            int start = token.getCharPositionInLine();
            int length = token.getText() == null ? 0 : token.getText().length();
            return context.getExtendedDocument().mapLocation(
                    new Range(new Position(line, start), new Position(line, start + length)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String key(String uri, int line, int character) {
        return uri + "|" + line + "|" + character;
    }
}
