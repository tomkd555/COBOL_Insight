package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;
import jp.cobolinsight.core.sql.WheneverClause;
import jp.cobolinsight.rules.FixEdits;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R018 Unchecked SQLCODE. After an EXEC SQL that changes data (INSERT/UPDATE/DELETE) or reads a
 * row (SELECT INTO, FETCH) executes, detects the absence, on every forward path up to the next
 * EXEC SQL, of a condition referencing SQLCODE/SQLSTATE. Because SQLCODE gets overwritten by the
 * next SQL statement, the boundary is the next EXEC SQL statement. A {@code WHENEVER SQLERROR
 * GO TO/PERFORM} in effect (the last WHENEVER SQLERROR before the statement in source order, a
 * later CONTINUE cancelling it; one a COPY brought in stands where the COPY statement does, which
 * is where the precompiler reads it) branches on every negative SQLCODE; the statement is then
 * reported at WARNING level for the +100 it still leaves unhandled, and an INSERT, which has no
 * +100 worth testing, is not reported at all. With a {@code WHENEVER NOT FOUND} branch in effect
 * as well, nothing is left unhandled and the statement is not reported.
 *
 * <p>A {@code GET DIAGNOSTICS} reached on the way counts as the check rather than as the boundary:
 * it is the statement that reads the condition the statement before it raised, and a program that
 * runs one has not left the outcome unread.
 */
public final class SqlCodeUncheckedRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R018", "SQLCODE・SQLSTATE 未検査", "例外処理")
            .summary("INSERT・UPDATE・DELETE・SELECT INTO・FETCH の後、次の埋込みSQL文までに"
                    + "SQLCODE・SQLSTATE を検査しない箇所を検出します。")
            .rationale("更新の失敗や該当行なしを検知せずに後続が進み、"
                    + "更新されたつもりのデータや古いホスト変数で処理を続けます。")
            .detection("データを変更する DML と行を読む SELECT INTO・FETCH の実行後、"
                    + "次の埋込みSQL文に達するまでの前方経路で SQLCODE・SQLSTATE を条件で参照しない"
                    + "ものを検出します。直後の GET DIAGNOSTICS は、"
                    + "前の文の状態を読むため検査として扱います。"
                    + "WHENEVER SQLERROR GO TO・PERFORM が有効な文"
                    + "（それより前の最後の WHENEVER SQLERROR が分岐するもの。"
                    + "COPY で取り込んだ WHENEVER は COPY 文の位置にあるものとして並べます）では、"
                    + "負の SQLCODE は捕捉済みとみなします。これらの文については、該当行なし（+100）が"
                    + "残る場合だけを WARNING で報告します。INSERT はその場合報告せず、WHENEVER NOT FOUND の"
                    + "分岐も有効な文についても報告しません。")
            .remedy("SQL 文の直後に SQLCODE を検査し、0 と +100 以外を異常として、+100 を"
                    + "該当行なしとして処理してください。")
            .example("""
                    EXEC SQL UPDATE CUSTOMER SET NAME = :WS-NAME
                             WHERE ID = :WS-ID END-EXEC.
                    PERFORM NEXT-SHORI.
                    """, """
                    EXEC SQL UPDATE CUSTOMER SET NAME = :WS-NAME
                             WHERE ID = :WS-ID END-EXEC.
                    IF SQLCODE NOT = ZERO
                        PERFORM SQL-ERROR
                    END-IF.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.FIX)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        // Build the set of EXEC SQL statement nodes (all SQL; used as boundaries) and a range->node index.
        Set<CfgNode> execSqlNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<SourceRange, CfgNode> byRange = new HashMap<>();
        for (CfgNode node : cfg.nodes()) {
            node.statement().ifPresent(statement -> {
                if (statement instanceof SimpleStatement simple
                        && "EXEC SQL".equals(simple.verb())) {
                    execSqlNodes.add(node);
                    byRange.put(simple.range(), node);
                }
            });
        }
        List<Whenever> whenevers = whenevers(model);
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() != EmbeddedBlockKind.SQL) {
                continue;
            }
            String keyword = leadingSqlKeyword(block.text());
            boolean reads = isSingleRowRead(keyword, block.text());
            if (!isDataChangeDml(keyword) && !reads) {
                continue;
            }
            // A WHENEVER SQLERROR GO TO/PERFORM in effect branches on every negative SQLCODE of
            // this statement. What it leaves is +100: no row for a SELECT INTO, a FETCH, an UPDATE
            // or a DELETE, which a WHENEVER NOT FOUND branch covers. An INSERT has no +100 worth
            // testing.
            int blockLine = block.range().start().line();
            boolean wheneverInEffect = inEffect(whenevers, "SQLERROR", blockLine);
            boolean notFoundHandled = inEffect(whenevers, "NOT FOUND", blockLine);
            if (wheneverInEffect && (keyword.equals("INSERT") || notFoundHandled)) {
                continue;
            }
            CfgNode start = byRange.get(block.range());
            if (start == null) {
                continue;
            }
            // GET DIAGNOSTICS reads the condition of the statement before it, so reaching one
            // counts as the check; it is left out of the boundary set to be checked at all.
            boolean checked = CfgSupport.forwardHasMatch(cfg, start,
                    node -> execSqlNodes.contains(node) && !CfgSupport.isGetDiagnostics(node),
                    node -> CfgSupport.isGetDiagnostics(node) || referencesSqlCode(node));
            if (checked) {
                continue;
            }
            String file = model.sourceFile();
            int last = block.range().end().line();
            String dml = dmlLabel(keyword, block.text());
            CfgNode next = CfgSupport.firstBoundary(cfg, start, execSqlNodes::contains)
                    .orElse(null);
            Integer nextLine = next == null ? null
                    : next.statement().orElseThrow().range().start().line();
            String until = nextLine == null ? "プログラムの終端まで進む"
                    : nextLine <= last ? "ループで " + nextLine + "行の SQL へ戻る"
                    : "次の SQL（" + nextLine + "行）へ進む";
            List<CodeFlowStep> steps = new ArrayList<>();
            steps.add(CfgSupport.step(file, blockLine, dml + " の実行（SQLCODE が設定される）"));
            if (nextLine != null) {
                steps.add(CfgSupport.step(file, nextLine, (nextLine <= last
                        ? "ループで戻る SQL（" : "次の SQL（") + "SQLCODE が上書きされる）"));
            }
            String consequence = wheneverInEffect
                    ? "WHENEVER SQLERROR が負の SQLCODE を捕捉しますが、該当行なし（+100）は"
                            + "捕捉しません。" + (reads ? "ホスト変数が更新されないまま" : "更新対象がなくても")
                            + "処理が進みます。"
                    : notFoundHandled
                    ? "WHENEVER NOT FOUND が該当行なし（+100）を捕捉しますが、負の SQLCODE は"
                            + "検査されず、" + (reads ? "読込" : "更新") + "の失敗が検知されません。"
                    : reads ? "該当行なし（+100）でもホスト変数が更新されないまま処理が進みます。"
                    : "更新の失敗が検知されません。";
            findings.add(new Finding(META.id(),
                    wheneverInEffect ? FindingLevel.WARNING : META.defaultSeverity().toLevel(),
                    "SQLCODE を EXEC SQL " + dml + " の後で検査していません。" + until + "ため、"
                            + consequence,
                    new SourcePosition(file, last, 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                    List.of(new CodeFlow(steps)), List.of()));
        }
    }

    /** One WHENEVER statement: its line, its condition and whether it branches (GO TO or PERFORM) rather than CONTINUE. */
    private record Whenever(int line, String condition, boolean branches) {
    }

    /** Every WHENEVER of the program in source order. */
    private static List<Whenever> whenevers(CobolSemanticModel model) {
        List<Whenever> whenevers = new ArrayList<>();
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() == EmbeddedBlockKind.SQL) {
                WheneverClause.clauseOf(block.text()).ifPresent(clause -> whenevers.add(
                        new Whenever(lineInTheProgram(model, block), clause.condition(),
                                clause.branches())));
            }
        }
        whenevers.sort(Comparator.comparingInt(Whenever::line));
        return whenevers;
    }

    /**
     * Where the block stands in the program's own numbering, which is what the ordering compares.
     * A block a COPY brought in carries a line of the copybook, measured in another file; the
     * precompiler applies it where the COPY statement stands, so a copybook holding the shop's
     * error handling at its top does not take effect over the statements written above the COPY.
     * A copybook the expansions do not name — one a nested COPY brought in — keeps its own line.
     */
    private static int lineInTheProgram(CobolSemanticModel model, EmbeddedBlock block) {
        String file = block.range().start().file();
        if (file.equals(model.sourceFile())) {
            return block.range().start().line();
        }
        return model.copyInlineExpansions().stream()
                .filter(expansion -> expansion.copybookPath().equals(file))
                .mapToInt(CopyInlineExpansion::copyStatementLine).min()
                .orElse(block.range().start().line());
    }

    /**
     * Whether a branching WHENEVER for the condition is in effect at the line. The precompiler
     * applies WHENEVER in source order, so the last one before the line decides, and a
     * CONTINUE there cancels an earlier GO TO or PERFORM.
     */
    private static boolean inEffect(List<Whenever> whenevers, String condition, int line) {
        boolean branches = false;
        for (Whenever whenever : whenevers) {
            if (whenever.line() < line && whenever.condition().equals(condition)) {
                branches = whenever.branches();
            }
        }
        return branches;
    }

    /** SELECT ... INTO and FETCH: the statements whose +100 leaves the host variables stale. */
    private static boolean isSingleRowRead(String keyword, String blockText) {
        if (keyword.equals("FETCH")) {
            return true;
        }
        return keyword.equals("SELECT")
                && blockText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).contains(" INTO ");
    }

    private static String dmlLabel(String keyword, String blockText) {
        return switch (keyword) {
            case "SELECT" -> "SELECT INTO";
            case "FETCH" -> "FETCH " + wordAfter(blockText, "FETCH ");
            default -> keyword + " " + targetTable(blockText, keyword);
        };
    }

    private static String wordAfter(String blockText, String marker) {
        String normalized = blockText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        int at = normalized.indexOf(marker);
        if (at < 0) {
            return "";
        }
        String[] words = normalized.substring(at + marker.length()).trim().split("[ (]", 2);
        return words.length == 0 ? "" : words[0];
    }

    @Override
    public Optional<FixProducer> fix() {
        return Optional.of(new SqlCodeFixProducer());
    }

    private static boolean isDataChangeDml(String keyword) {
        return keyword.equals("INSERT") || keyword.equals("UPDATE") || keyword.equals("DELETE");
    }

    /**
     * Inserts an SQLCODE check statement right after an unchecked DML or single-row read's EXEC
     * SQL (on the line after the END-EXEC line). Re-identifies the block with the same terminal
     * line using Finding.location's line (= the END-EXEC line) as the anchor. The inserted IF
     * tests for any non-zero code, so it catches +100 as well as the negative codes; it is always
     * closed with an explicit END-IF, and a terminating period is added only when END-EXEC itself
     * closes a sentence.
     */
    private static final class SqlCodeFixProducer implements FixProducer {

        @Override
        public Optional<FixSuggestion> produce(Finding finding, AnalysisContext context) {
            if (!"R018".equals(finding.ruleId())) {
                return Optional.empty();
            }
            String file = finding.location().file();
            int line = finding.location().line();
            CobolSemanticModel model = FixEdits.modelOf(context, file).orElse(null);
            if (model == null) {
                return Optional.empty();
            }
            EmbeddedBlock block = model.embeddedBlocks().stream()
                    .filter(candidate -> candidate.kind() == EmbeddedBlockKind.SQL)
                    .filter(candidate -> candidate.range().end().line() == line)
                    .filter(candidate -> {
                        String keyword = leadingSqlKeyword(candidate.text());
                        return isDataChangeDml(keyword) || isSingleRowRead(keyword, candidate.text());
                    })
                    .findFirst()
                    .orElse(null);
            if (block == null) {
                return Optional.empty();
            }
            // Only close the inserted IF with a terminating period when END-EXEC itself closes a
            // sentence with one. Placing a period right after an EXEC SQL that sits in the middle
            // of an enclosing statement (IF/PERFORM, etc.) would prematurely terminate the outer
            // statement, so in that case close with only an explicit END-IF.
            String source = context.artifact(SourceTextIndex.class)
                    .flatMap(index -> index.textOf(model.sourceFile())).orElse(null);
            String terminator = source == null
                    || FixEdits.endsSentence(source, block.range().end().line()) ? "." : "";
            TextEdit edit = FixEdits.insertStatementAfter(block.range(),
                    "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF" + terminator);
            return Optional.of(new FixSuggestion("SQLCODE の検査を挿入します", List.of(edit)));
        }
    }

    private static boolean referencesSqlCode(CfgNode node) {
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && containsSqlCode(compound.conditionText()))
                .orElse(false);
    }

    private static boolean containsSqlCode(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("SQLCODE") || upper.contains("SQLSTATE");
    }

    /** The leading SQL keyword (uppercase), with EXEC SQL stripped off. */
    private static String leadingSqlKeyword(String blockText) {
        String normalized = blockText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).trim();
        int index = normalized.indexOf("EXEC SQL");
        String body = index < 0 ? normalized
                : normalized.substring(index + "EXEC SQL".length()).trim();
        String[] words = body.split(" ", 2);
        return words.length == 0 ? "" : words[0];
    }

    private static String targetTable(String blockText, String keyword) {
        String normalized = blockText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String marker = switch (keyword) {
            case "INSERT" -> "INTO ";
            case "DELETE" -> "FROM ";
            default -> "UPDATE ";
        };
        int at = normalized.indexOf(marker);
        if (at < 0) {
            return "";
        }
        String rest = normalized.substring(at + marker.length()).trim();
        String[] words = rest.split("[ (]", 2);
        return words.length == 0 ? "" : words[0];
    }
}
