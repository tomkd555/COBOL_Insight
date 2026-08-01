package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.FixProducer;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
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
 * R017 ファイルステータス未検査。record-access I/O(READ/WRITE/REWRITE/DELETE)の実行後、次の
 * 同一ファイル I/O に達するまでの前方経路で、その FD の FILE STATUS 変数を条件参照しない箇所を
 * 検出する。FILE STATUS を検査しないと、入出力の異常が後続処理で検知されない。AT END・
 * INVALID KEY 句は特定の事象だけを捉えるため、その存在は検査とみなさない。FILE STATUS 変数名は
 * 意味モデルに無いため、SourceTextIndex の原ソース(および COPY 先コピー句)から SELECT・FD の
 * 記述で解決する。
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

    @Override
    public String id() {
        return "R017";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("ファイル状態(FILE STATUS)未検査", "例外処理")
                .summary("入出力文の後、次の同一ファイルの入出力に達するまでに"
                        + "FILE STATUS を検査しない箇所を検出する。")
                .rationale("入出力の失敗を検知しないまま後続が進み、"
                        + "読めなかったレコードの内容を使うなど、誤った結果をそのまま出す。")
                .detection("READ・WRITE・REWRITE・DELETE の実行後、前方経路で当該 FD の"
                        + "FILE STATUS 変数を条件参照しないものを検出する。AT END・INVALID KEY 句は"
                        + "特定の事象しか捉えないため、検査とみなさない。")
                .remedy("入出力の直後に FILE STATUS を判定し、正常値以外を異常として処理する。")
                .example("""
                        READ CUST-FILE INTO WS-REC.
                        MOVE WS-REC TO WS-OUT.
                        """, """
                        READ CUST-FILE INTO WS-REC.
                        IF CUST-STATUS NOT = "00"
                            PERFORM FILE-ERROR
                        END-IF.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
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

        // 各 I/O ノードの FD(解決できたもののみ)。境界判定に使う。
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        CfgSupport.upper(io.verb()) + " " + fd
                                + " の実行後、FILE STATUS 変数 " + var + " を検査していない。"
                                + "入出力異常が後続処理で検知されない。",
                        new SourcePosition(model.sourceFile(), io.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    @Override
    public Optional<FixProducer> fixProducer() {
        return Optional.of(new FileStatusFixProducer());
    }

    /**
     * 未検査の record-access I/O 文の直後へ、その FD の FILE STATUS 変数を判定する IF 文を挿入する。
     * Finding.location の行(=I/O 文の開始行)を anchor に対象文を再同定し、STATUS 変数と FD 名は
     * evaluate と同じ SELECT/FD 解決で再取得する。挿入する IF は常に明示的な END-IF で閉じ、
     * 終止ピリオドは I/O 文が文を閉じている場合にのみ付ける。
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
            // I/O 文が終止ピリオドで文を閉じているときだけ、挿入する IF も終止ピリオドで閉じる。
            // 囲む文(IF/ELSE・PERFORM など)の途中にある I/O の直後へピリオドを置くと外側の文を
            // 途中で終止させるため、その場合は明示的な END-IF だけで閉じる。END-IF で閉じる限り、
            // 外側の ELSE・END-IF との結合は変わらない。
            String terminator =
                    FixEdits.endsSentence(source, io.range().end().line()) ? "." : "";
            // FILE STATUS の '00' は入出力の正常完了を表す。それ以外の値はすべて異常として扱う。
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

    /** I/O 文の対象 FD。READ/DELETE は operand が FD 名、WRITE/REWRITE は operand がレコード名。 */
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

    /** FD 本体のレコード名。まず literal 01、無ければ COPY 先コピー句の 01 を解決する。 */
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
