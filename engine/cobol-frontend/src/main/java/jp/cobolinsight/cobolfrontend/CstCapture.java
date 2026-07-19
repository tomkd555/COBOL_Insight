package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.engineapi.source.CopyExpansionEntry;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.lsp.cobol.core.engine.analysis.AnalysisContext;
import org.eclipse.lsp.cobol.dialects.ibm.ParserStageResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ParserStage までの自前パイプラインで得た CST からの補完情報。
 * 文の開始位置(原ソース座標)→動詞の索引と、COPY 展開の対応表を保持する。
 */
final class CstCapture {

    private final Map<String, String> verbsByPosition;
    private final List<CopyExpansionEntry> copyExpansions;

    private CstCapture(Map<String, String> verbsByPosition,
            List<CopyExpansionEntry> copyExpansions) {
        this.verbsByPosition = verbsByPosition;
        this.copyExpansions = copyExpansions;
    }

    static CstCapture build(ParserStageResult parserResult, AnalysisContext context,
            String mainUri) {
        Map<String, String> verbs = new HashMap<>();
        collectVerbs(parserResult.getTree(), context, verbs);
        List<CopyExpansionEntry> expansions =
                collectExpansions(parserResult.getTokens().getTokens(), context, mainUri);
        return new CstCapture(verbs, expansions);
    }

    /** 原ソース座標(0起点)の文開始位置に対応する動詞。無ければ null。 */
    String verbAt(String uri, int line, int character) {
        return verbsByPosition.get(key(uri, line, character));
    }

    List<CopyExpansionEntry> copyExpansions() {
        return copyExpansions;
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
