package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;
import jp.cobolinsight.rules.FixEdits;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R017 Unchecked file status. After a record-access I/O (READ/WRITE/REWRITE/DELETE) executes,
 * finds points on the forward path leading up to the next I/O on the same file where that FD's
 * FILE STATUS variable is never referenced in a condition. Without a FILE STATUS check,
 * subsequent processing never detects an I/O error. AT END and INVALID KEY clauses catch only
 * specific events, so their presence does not count as a check. Because the FILE STATUS
 * variable name is not in the semantic model, it is resolved from the SELECT/FD text in the raw
 * source (and any COPY'd copybook) via SourceTextIndex.
 */
public final class FileStatusUncheckedRule implements Rule {

    private static final String NAME = "[\\p{L}\\p{N}$#_-]+";
    private static final Pattern SELECT_STATUS = Pattern.compile(
            "(?is)\\bSELECT\\s+(" + NAME + ")[^.]*?FILE\\s+STATUS\\s+(?:IS\\s+)?(" + NAME + ")");
    private static final Pattern FD_HEADER = Pattern.compile("(?is)\\bFD\\s+(" + NAME + ")");
    private static final Pattern SECTION_BREAK = Pattern.compile(
            "(?is)\\bFD\\s+" + NAME + "|\\bWORKING-STORAGE\\b|\\bLOCAL-STORAGE\\b|\\bLINKAGE\\b"
                    + "|\\bPROCEDURE\\s+DIVISION\\b");
    private static final Pattern LEVEL_01 = Pattern.compile("(?im)^\\s*01\\s+(" + NAME + ")");
    private static final Pattern COPY_CLAUSE = Pattern.compile(
            "(?is)\\bCOPY\\s+(" + NAME + ")(?:\\s+REPLACING\\s+LEADING\\s+==\\s*(.+?)\\s*=="
                    + "\\s+BY\\s+==\\s*(.+?)\\s*==)?");

    private static final Set<String> IO_VERBS = Set.of("READ", "WRITE", "REWRITE", "DELETE");

    private static final RuleMeta META = RuleMeta.named("R017", "ファイル状態(FILE STATUS)未検査", "例外処理")
            .summary("入出力文の後、次の同一ファイルの入出力に達するまでに"
                    + "FILE STATUS を検査しない箇所を検出します。")
            .rationale("入出力の失敗を検知しないまま後続が進み、"
                    + "読めなかったレコードの内容を使うなど、誤った結果をそのまま出します。")
            .detection("READ・WRITE・REWRITE・DELETE の実行後、前方経路で当該 FD の"
                    + "FILE STATUS 変数を条件参照しないものを検出します。AT END・INVALID KEY 句は"
                    + "特定の事象しか捉えないため、検査とみなしません。")
            .remedy("入出力の直後に FILE STATUS を判定し、正常値以外を異常として処理します。")
            .example("""
                    READ CUST-FILE INTO WS-REC.
                    MOVE WS-REC TO WS-OUT.
                    """, """
                    READ CUST-FILE INTO WS-REC.
                    IF CUST-STATUS NOT = "00"
                        PERFORM FILE-ERROR
                    END-IF.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT, Command.FIX)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        SourceTextIndex index = context.artifact(SourceTextIndex.class).orElse(null);
        if (cfgs == null || index == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, index, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, SourceTextIndex index,
            List<Finding> findings) {
        String source = index.textOf(model.sourceFile()).orElse(null);
        if (source == null) {
            return;
        }
        Map<String, String> fdToVar = fdToVar(source);
        Map<String, String> recordToFd = recordToFd(source, index);

        // The FD of each I/O node (only those that could be resolved). Used for boundary checks.
        Map<CfgNode, String> ioFd = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            SimpleStatement io = ioStatement(node);
            if (io == null) {
                continue;
            }
            String fd = fdOf(io, recordToFd);
            if (fd != null) {
                ioFd.put(node, fd);
            }
        }

        for (Map.Entry<CfgNode, String> entry : ioFd.entrySet()) {
            CfgNode node = entry.getKey();
            String fd = entry.getValue();
            String var = fdToVar.get(fd);
            if (var == null) {
                continue;
            }
            boolean checked = CfgSupport.forwardHasMatch(cfg, node,
                    other -> other != node && fd.equals(ioFd.get(other)),
                    other -> referencesStatusVar(other, var));
            if (!checked) {
                SimpleStatement io = (SimpleStatement) node.statement().orElseThrow();
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        CfgSupport.upper(io.verb()) + " " + fd
                                + " の実行後、FILE STATUS 変数 " + var + " を検査していない。"
                                + "入出力異常が後続処理で検知されない。",
                        new SourcePosition(model.sourceFile(), io.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    @Override
    public Optional<FixProducer> fix() {
        return Optional.of(new FileStatusFixProducer());
    }

    /**
     * Inserts, right after an unchecked record-access I/O statement, an IF statement that checks
     * that FD's FILE STATUS variable. Re-identifies the target statement using Finding.location's
     * line (= the I/O statement's start line) as the anchor, and re-resolves the STATUS variable
     * and FD name using the same SELECT/FD resolution as evaluate. The inserted IF is always
     * closed with an explicit END-IF, and a terminating period is added only when the I/O
     * statement itself closes a sentence.
     */
    private static final class FileStatusFixProducer implements FixProducer {

        @Override
        public Optional<FixSuggestion> produce(Finding finding, AnalysisContext context) {
            if (!"R017".equals(finding.ruleId())) {
                return Optional.empty();
            }
            SourceTextIndex index = context.artifact(SourceTextIndex.class).orElse(null);
            CobolSemanticModel model =
                    FixEdits.modelOf(context, finding.location().file()).orElse(null);
            if (index == null || model == null) {
                return Optional.empty();
            }
            String source = index.textOf(model.sourceFile()).orElse(null);
            if (source == null) {
                return Optional.empty();
            }
            SimpleStatement io = FixEdits.findSimpleStatement(model, finding.location().line(),
                    candidate -> IO_VERBS.contains(CfgSupport.upper(candidate.verb()))).orElse(null);
            if (io == null) {
                return Optional.empty();
            }
            String fd = fdOf(io, recordToFd(source, index));
            String var = fd == null ? null : fdToVar(source).get(fd);
            if (var == null) {
                return Optional.empty();
            }
            // Only close the inserted IF with a terminating period when the I/O statement itself
            // closes a sentence with one. Placing a period right after an I/O statement that sits
            // in the middle of an enclosing statement (IF/ELSE, PERFORM, etc.) would prematurely
            // terminate the outer statement, so in that case close with only an explicit END-IF.
            // As long as it closes with END-IF, the binding to an outer ELSE/END-IF is unchanged.
            String terminator =
                    FixEdits.endsSentence(source, io.range().end().line()) ? "." : "";
            // FILE STATUS of '00' means the I/O completed normally. Any other value is treated as an error.
            String statement = "IF " + var + " NOT = '00' DISPLAY 'FILE ERROR: " + fd + " ' "
                    + var + " END-IF" + terminator;
            TextEdit edit = FixEdits.insertStatementAfter(io.range(), statement);
            return Optional.of(new FixSuggestion("FILE STATUS 検査を挿入する", List.of(edit)));
        }
    }

    private static SimpleStatement ioStatement(CfgNode node) {
        return node.statement()
                .filter(SimpleStatement.class::isInstance)
                .map(SimpleStatement.class::cast)
                .filter(simple -> IO_VERBS.contains(CfgSupport.upper(simple.verb())))
                .orElse(null);
    }

    /** The target FD of an I/O statement. For READ/DELETE the operand is the FD name; for WRITE/REWRITE the operand is the record name. */
    private static String fdOf(SimpleStatement io, Map<String, String> recordToFd) {
        String verb = CfgSupport.upper(io.verb());
        String operand = firstOperand(io.text(), verb);
        if (operand == null) {
            return null;
        }
        String key = operand.toUpperCase(Locale.ROOT);
        if (verb.equals("WRITE") || verb.equals("REWRITE")) {
            return recordToFd.get(key);
        }
        return key;
    }

    private static String firstOperand(String text, String verb) {
        String trimmed = text.trim();
        String rest = trimmed.length() >= verb.length()
                ? trimmed.substring(verb.length()) : "";
        Matcher matcher = Pattern.compile(NAME).matcher(rest);
        return matcher.find() ? matcher.group() : null;
    }

    private static boolean referencesStatusVar(CfgNode node, String var) {
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && mentionsWord(compound.conditionText(), var))
                .orElse(false);
    }

    private static boolean mentionsWord(String text, String word) {
        Matcher matcher = Pattern.compile(
                "(?i)(?<![\\p{L}\\p{N}$#_-])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}$#_-])")
                .matcher(text);
        return matcher.find();
    }

    private static Map<String, String> fdToVar(String source) {
        Map<String, String> map = new java.util.HashMap<>();
        Matcher matcher = SELECT_STATUS.matcher(source);
        while (matcher.find()) {
            map.put(matcher.group(1).toUpperCase(Locale.ROOT),
                    matcher.group(2).toUpperCase(Locale.ROOT));
        }
        return map;
    }

    private static Map<String, String> recordToFd(String source, SourceTextIndex index) {
        Map<String, String> map = new java.util.HashMap<>();
        Matcher header = FD_HEADER.matcher(source);
        while (header.find()) {
            String fd = header.group(1).toUpperCase(Locale.ROOT);
            int bodyStart = header.end();
            int bodyEnd = nextBreak(source, bodyStart);
            String body = source.substring(bodyStart, bodyEnd);
            String record = recordOf(body, index);
            if (record != null) {
                map.put(record.toUpperCase(Locale.ROOT), fd);
            }
        }
        return map;
    }

    private static int nextBreak(String source, int from) {
        Matcher matcher = SECTION_BREAK.matcher(source);
        if (matcher.find(from)) {
            return matcher.start();
        }
        return source.length();
    }

    /** The record name in the FD body. Resolves a literal 01 first, and if absent, the 01 in a COPY'd copybook. */
    private static String recordOf(String fdBody, SourceTextIndex index) {
        Matcher literal = LEVEL_01.matcher(fdBody);
        if (literal.find()) {
            return literal.group(1);
        }
        Matcher copy = COPY_CLAUSE.matcher(fdBody);
        if (!copy.find()) {
            return null;
        }
        String copybookText = index.textOfBaseName(copy.group(1)).orElse(null);
        if (copybookText == null) {
            return null;
        }
        Matcher record = LEVEL_01.matcher(copybookText);
        if (!record.find()) {
            return null;
        }
        String name = record.group(1);
        String from = copy.group(2);
        String to = copy.group(3);
        if (from != null && to != null
                && name.toUpperCase(Locale.ROOT).startsWith(from.toUpperCase(Locale.ROOT))) {
            return to + name.substring(from.length());
        }
        return name;
    }
}
