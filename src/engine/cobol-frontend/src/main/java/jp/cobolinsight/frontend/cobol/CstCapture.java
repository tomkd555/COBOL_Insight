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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
     * Builds the inline expansion for each COPY statement the program file writes. Che4z splices
     * a copybook in between lines of the program file, so a run of consecutive post-expansion
     * lines from copybooks, ended by the next line of the program file, is one COPY statement's
     * expansion. It holds the lines of the copybook the statement names and, where a COPY inside
     * that copybook brought another one in, the nested copybook's lines at their place, each
     * marked with its copybook. The expansion stands on the last COPY statement of that copybook
     * Che4z recorded in the program file at or above the run, which is the statement itself.
     *
     * <p>A nested COPY statement's own line appears, blanked, before and after the lines it
     * brought in; the expansion keeps its first appearance. Code Che4z implicitly inserts (such as
     * SQLCA) ends a run and belongs to none.
     *
     * <p>A COPY written after other code on its line is spliced into that line: the copybook's
     * first line joins the text around the statement, and its further lines follow as lines of
     * their own. Each post-expansion line is therefore read as pieces, one for each stretch of
     * text from one file, and the pieces take the place of lines in the runs above. Text of the
     * program file after such a COPY can end the run after the copybook's first line; the lines
     * that follow it continue the same expansion.
     */
    private static List<CopyInlineExpansion> collectInlineExpansions(AnalysisContext context,
            String mainUri) {
        ExtendedDocument document = context.getExtendedDocument();
        CopybooksRepository copybooks = context.getCopybooksRepository();
        if (document == null || copybooks == null) {
            return List.of();
        }
        Map<String, List<Integer>> statementLines = copyStatementLines(copybooks, mainUri);
        List<CopyInlineExpansion> expansions = new ArrayList<>();
        List<Location> run = new ArrayList<>();
        List<String> runTexts = new ArrayList<>();
        int lastProgramLine = 0;
        String[] lines = document.toString().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Piece piece : piecesOf(document, i, lines[i])) {
                Location origin = piece.origin();
                String uri = origin == null ? null : origin.getUri();
                if (uri != null && !uri.equals(mainUri) && !UriPaths.isImplicit(uri)) {
                    run.add(origin);
                    runTexts.add(piece.text());
                    continue;
                }
                addInlineExpansion(expansions, run, runTexts, lastProgramLine, copybooks,
                        statementLines);
                run = new ArrayList<>();
                runTexts = new ArrayList<>();
                if (uri != null && uri.equals(mainUri)) {
                    lastProgramLine = origin.getRange().getStart().getLine() + 1;
                }
            }
        }
        addInlineExpansion(expansions, run, runTexts, lastProgramLine, copybooks, statementLines);
        return List.copyOf(expansions);
    }

    /** A stretch of one post-expansion line that comes from one file, and its text. */
    private record Piece(Location origin, String text) {
    }

    /**
     * The pieces of one post-expansion line. A line whose text comes from one file is one piece
     * holding the whole line, as Che4z wrote it. A line that joins text of several files splits
     * where the file changes, each piece indented to the column its first character has in its
     * own file, so a copybook line reads as the copybook writes it.
     */
    private static List<Piece> piecesOf(ExtendedDocument document, int line, String text) {
        Location first = originOf(document, line, text);
        int start = 0;
        while (start < text.length() && text.charAt(start) == ' ') {
            start++;
        }
        List<Piece> pieces = new ArrayList<>();
        Location pieceOrigin = first;
        int pieceStart = start;
        for (int column = start + 1; column < text.length(); column++) {
            if (text.charAt(column) == ' ') {
                continue;
            }
            Location origin = locationAt(document, line, column);
            if (origin == null || pieceOrigin == null
                    || origin.getUri().equals(pieceOrigin.getUri())) {
                continue;
            }
            pieces.add(piece(pieceOrigin, text, pieceStart, column));
            pieceOrigin = origin;
            pieceStart = column;
        }
        if (pieces.isEmpty()) {
            return List.of(new Piece(first, stripTrailing(text)));
        }
        pieces.add(piece(pieceOrigin, text, pieceStart, text.length()));
        return pieces;
    }

    private static Piece piece(Location origin, String text, int start, int end) {
        int column = origin == null ? 0 : origin.getRange().getStart().getCharacter();
        return new Piece(origin, " ".repeat(column) + stripTrailing(text.substring(start, end)));
    }

    /** Copybook name to the line numbers of COPY statements appearing in the original (1-based, ascending). */
    private static Map<String, List<Integer>> copyStatementLines(CopybooksRepository copybooks,
            String mainUri) {
        Map<String, List<Integer>> byName = new HashMap<>();
        copybooks.getDefinitionStatements().asMap().forEach((name, localities) -> {
            List<Integer> lines = localities.stream()
                    .filter(locality -> mainUri.equals(locality.getUri()))
                    .map(locality -> locality.getRange().getStart().getLine() + 1)
                    .sorted()
                    .toList();
            if (!lines.isEmpty()) {
                byName.put(name, lines);
            }
        });
        return byName;
    }

    /**
     * Adds the expansion one run of copybook lines makes. Its copybook is the first one in the
     * run the program file writes a COPY statement for at or above the run; a run with none is
     * left out.
     */
    private static void addInlineExpansion(List<CopyInlineExpansion> out, List<Location> run,
            List<String> texts, int lastProgramLine, CopybooksRepository copybooks,
            Map<String, List<Integer>> statementLines) {
        String uri = null;
        String name = null;
        int statementLine = 0;
        for (Location origin : run) {
            String candidate = copybooks.getCopybookIdByUri(origin.getUri());
            int line = candidate == null ? 0
                    : statementLines.getOrDefault(candidate, List.of()).stream()
                            .filter(l -> l <= lastProgramLine)
                            .reduce(0, Math::max);
            if (line > 0) {
                uri = origin.getUri();
                name = candidate;
                statementLine = line;
                break;
            }
        }
        if (uri == null) {
            return;
        }
        List<ExpandedCopyLine> lines = new ArrayList<>();
        Map<String, Integer> lastLineOf = new HashMap<>();
        for (int i = 0; i < run.size(); i++) {
            String lineUri = run.get(i).getUri();
            int copybookLine = run.get(i).getRange().getStart().getLine() + 1;
            Integer previous = lastLineOf.put(lineUri, copybookLine);
            if (texts.get(i).isBlank() && previous != null && previous == copybookLine) {
                continue;
            }
            lines.add(new ExpandedCopyLine(copybookLine, texts.get(i), lineUri.equals(uri)
                    ? Optional.empty() : Optional.of(UriPaths.toPathString(lineUri))));
        }
        String path = UriPaths.toPathString(uri);
        CopyInlineExpansion previous = out.isEmpty() ? null : out.get(out.size() - 1);
        if (previous != null && continues(previous, statementLine, path, lines.get(0))) {
            List<ExpandedCopyLine> joined = new ArrayList<>(previous.lines());
            joined.addAll(lines);
            out.set(out.size() - 1, new CopyInlineExpansion(statementLine, name, path, joined));
            return;
        }
        out.add(new CopyInlineExpansion(statementLine, name, path, lines));
    }

    /**
     * Whether a run goes on with the expansion before it: the same COPY statement, and a line of
     * its copybook below the last one that expansion holds. That is the rest of a copybook
     * spliced in after other code, once the program text after the COPY statement has ended the
     * run at the copybook's first line.
     */
    private static boolean continues(CopyInlineExpansion previous, int statementLine, String path,
            ExpandedCopyLine first) {
        if (previous.copyStatementLine() != statementLine
                || !previous.copybookPath().equals(path) || first.copybookPath().isPresent()) {
            return false;
        }
        int last = 0;
        for (ExpandedCopyLine line : previous.lines()) {
            if (line.copybookPath().isEmpty()) {
                last = line.copybookLine();
            }
        }
        return first.copybookLine() > last;
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
        return locationAt(document, line, column);
    }

    /** The origin position of the character at one column of a post-expansion line. */
    private static Location locationAt(ExtendedDocument document, int line, int column) {
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
