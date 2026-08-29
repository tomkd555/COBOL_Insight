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
 * ParserStage までの自前パイプラインで得た CST からの補完情報。
 * 文の開始位置(原ソース座標)→動詞の索引と、COPY 展開の対応表を保持する。
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

    /** 原ソース座標(0起点)の文開始位置に対応する動詞。無ければ null。 */
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
     * 展開後のトークンを順に見て、同一のコピー句に由来するトークンが続く区間を1件の展開として
     * まとめる。展開後の行範囲はその区間の先頭行から末尾行まで、コピー句側の開始行は区間の先頭
     * トークンが元にあった行とする。行番号はいずれも1始まりでそろえる。
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
     * COPY 文ごとのインライン展開を組む。展開後ドキュメントの各行がどのコピー句の何行目に由来
     * するかを順に見て、同じコピー句が続く区間を1件の展開へまとめ、原本の COPY 文の行番号を
     * Che4z が記録した COPY 文の位置から与える。同じコピー句を複数回取り込む場合は、展開の
     * 出現順と COPY 文の行番号の昇順を突き合わせる。
     *
     * <p>原本に COPY 文が見つからない区間(コピー句の中の COPY による入れ子展開)と、Che4z が
     * 暗黙に差し込むコード(SQLCA など)は対象外とする。
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

    /** コピー句名→原本に現れる COPY 文の行番号(1始まり・昇順)。 */
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
     * 展開後の1行の由来位置。本文の先頭桁で引く。空白だけの行では行末を越えた桁になるが、
     * 行の最後の文字の対応から外挿されるため、注記行のように空白化された行も由来を引ける。
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
