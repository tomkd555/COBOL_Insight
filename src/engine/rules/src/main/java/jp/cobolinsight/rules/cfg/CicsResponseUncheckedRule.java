package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.picture.Usage;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;
import jp.cobolinsight.rules.FixEdits;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R021 Unchecked CICS response code. An EXEC CICS command with neither RESP nor RESP2 cannot have
 * its response checked; one that receives RESP but never tests it before the next CICS command
 * is no better off. Both are reported. A command with NOHANDLE, a command that follows, in source
 * order, a HANDLE CONDITION naming ERROR or a condition the command raises (the program relies on
 * condition handling), and a command that raises no condition worth testing (HANDLE, ASSIGN,
 * RETURN, ...) are not.
 */
public final class CicsResponseUncheckedRule implements Rule {

    /** Commands whose response is not something a program tests. */
    private static final Set<String> EXEMPT = Set.of("HANDLE", "IGNORE", "PUSH", "POP",
            "ADDRESS", "ASSIGN", "ASKTIME", "FORMATTIME", "RETURN", "ABEND", "DUMP", "ENTER",
            "SUSPEND");
    private static final Set<String> QUALIFIERS = Set.of("MAP", "TEXT", "TS", "TD", "PAGE",
            "CONTROL", "CONDITION", "AID");
    private static final Pattern NOHANDLE = Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])NOHANDLE(?![\\p{L}\\p{N}-])");
    /** A condition a HANDLE CONDITION names with a label: {@code MAPFAIL(8100-MAPFAIL)}. */
    private static final Pattern HANDLED_CONDITION = Pattern.compile("([A-Z]+)\\s*\\(");
    /**
     * The conditions a command family raises. A HANDLE CONDITION covers a later command only when
     * it names ERROR or one of these; a HANDLE CONDITION MAPFAIL says nothing about a WRITEQ.
     * A command outside every family is covered by any earlier HANDLE CONDITION.
     */
    private static final Map<String, Set<String>> CONDITIONS_BY_FAMILY = Map.of(
            "MAP", Set.of("MAPFAIL", "INVMPSZ", "RETPAGE", "EOC", "EODS", "OVERFLOW"),
            "FILE", Set.of("NOTFND", "DUPKEY", "DUPREC", "DSIDERR", "FILENOTFOUND", "NOTOPEN",
                    "ILLOGIC", "IOERR", "NOSPACE", "ENDFILE", "RECORDBUSY", "LOCKED", "DISABLED"),
            "QUEUE", Set.of("QIDERR", "ITEMERR", "QZERO", "QBUSY", "NOSPACE", "IOERR", "DISABLED"),
            "PROGRAM", Set.of("PGMIDERR", "NOTAUTH", "ROLLEDBACK", "TERMERR"),
            "INTERVAL", Set.of("TRANSIDERR", "TERMIDERR", "ENDDATA", "EXPIRED", "NOTFND",
                    "USERIDERR"));
    private static final Map<String, String> FAMILY_BY_COMMAND = Map.ofEntries(
            Map.entry("READ", "FILE"), Map.entry("WRITE", "FILE"), Map.entry("REWRITE", "FILE"),
            Map.entry("DELETE", "FILE"), Map.entry("STARTBR", "FILE"),
            Map.entry("READNEXT", "FILE"), Map.entry("READPREV", "FILE"),
            Map.entry("ENDBR", "FILE"), Map.entry("RESETBR", "FILE"), Map.entry("UNLOCK", "FILE"),
            Map.entry("READQ", "QUEUE"), Map.entry("WRITEQ", "QUEUE"),
            Map.entry("DELETEQ", "QUEUE"),
            Map.entry("LINK", "PROGRAM"), Map.entry("XCTL", "PROGRAM"),
            Map.entry("LOAD", "PROGRAM"), Map.entry("RELEASE", "PROGRAM"),
            Map.entry("START", "INTERVAL"), Map.entry("RETRIEVE", "INTERVAL"),
            Map.entry("CANCEL", "INTERVAL"), Map.entry("DELAY", "INTERVAL"));

    /** A HANDLE CONDITION: its line and the conditions it names with a label. */
    private record Handle(int line, Set<String> conditions) {
    }

    private static final RuleMeta META =
            RuleMeta.named("R021", "CICS 応答コード（RESP・RESP2）未検査", "例外処理")
                    .summary("RESP・RESP2 を指定しない EXEC CICS コマンドと、RESP を受け取っても"
                            + "次の CICS コマンドまでに検査しないコマンドを検出します。")
                    .rationale("応答コードを検査しないため、資源の不在や排他の失敗を"
                            + "プログラム側で検知できず、異常時は既定の異常終了になるか、"
                            + "失敗したまま処理が進みます。")
                    .detection("EXEC CICS コマンドのうち、RESP・RESP2 のいずれの作用対象も持たない"
                            + "もの、および RESP を受け取っても次の CICS コマンドまでの前方経路で"
                            + "その項目を条件で参照しないものを検出します。NOHANDLE を持つコマンド、"
                            + "自身より前の行に、自身が起こす条件か ERROR を扱う "
                            + "HANDLE CONDITION があるコマンド、"
                            + "HANDLE・ASSIGN・RETURN のように検査すべき応答のないコマンドは"
                            + "対象外です。")
                    .remedy("RESP を付けて応答コードを受け取り、"
                            + "直後に DFHRESP との比較で分岐してください。")
                    .example("""
                            EXEC CICS READ FILE('CUSTFILE') INTO(WS-REC)
                                 RIDFLD(WS-KEY) END-EXEC.
                            """, """
                            EXEC CICS READ FILE('CUSTFILE') INTO(WS-REC)
                                 RIDFLD(WS-KEY) RESP(WS-RESP) END-EXEC.
                            IF WS-RESP NOT = DFHRESP(NORMAL)
                                PERFORM ERROR-SHORI
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ControlFlowGraph cfg = cfgs == null ? null : cfgs.of(model).orElse(null);
            evaluate(model, cfg, findings);
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, ControlFlowGraph cfg,
            List<Finding> findings) {
        List<Handle> handles = model.embeddedBlocks().stream()
                .filter(block -> block.kind() == EmbeddedBlockKind.CICS_HANDLE_CONDITION)
                .map(block -> new Handle(block.range().start().line(), handledConditions(block)))
                .toList();
        Set<CfgNode> cicsNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<SourceRange, CfgNode> byRange = new HashMap<>();
        if (cfg != null) {
            for (CfgNode node : cfg.nodes()) {
                node.statement().ifPresent(statement -> {
                    if (statement instanceof SimpleStatement simple
                            && "EXEC CICS".equals(simple.verb())) {
                        cicsNodes.add(node);
                        byRange.put(simple.range(), node);
                    }
                });
            }
        }
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (!block.kind().isCics() || EXEMPT.contains(firstWord(block))) {
                continue;
            }
            SourcePosition at = new SourcePosition(model.sourceFile(),
                    block.range().end().line(), 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
            String respItem = block.operands().getOrDefault("RESP", block.operands().get("RESP2"));
            if (respItem == null) {
                if (NOHANDLE.matcher(block.text()).find() || covered(block, handles)) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "EXEC CICS " + commandLabel(block) + " に RESP・RESP2 がありません。"
                                + "応答コードを検査できず、異常時は既定の異常終了になります。", at));
                continue;
            }
            CfgNode start = byRange.get(block.range());
            if (start == null) {
                continue;
            }
            boolean tested = CfgSupport.forwardHasMatch(cfg, start, cicsNodes::contains,
                    node -> referencesItem(node, respItem));
            if (!tested) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "EXEC CICS " + commandLabel(block) + " は RESP(" + respItem
                                + ") を受け取っていますが、次の CICS コマンドまでに検査していません。"
                                + "応答コードを受け取るだけでは失敗が検知されません。", at));
            }
        }
    }

    /** Whether the node's condition names the item as a whole word: WS-RESP2 is not a test of WS-RESP. */
    private static boolean referencesItem(CfgNode node, String item) {
        Pattern word = Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])" + Pattern.quote(item)
                + "(?![\\p{L}\\p{N}-])");
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && word.matcher(compound.conditionText()).find())
                .orElse(false);
    }

    /**
     * Whether an earlier HANDLE CONDITION handles this command's failure: it names ERROR, or a
     * condition of the command's family. A command of no known family is covered by any earlier
     * HANDLE CONDITION.
     */
    private static boolean covered(EmbeddedBlock block, List<Handle> handles) {
        int line = block.range().start().line();
        String name = commandName(block);
        String family = name.endsWith(" MAP") ? "MAP" : FAMILY_BY_COMMAND.get(firstWord(block));
        Set<String> raised = family == null ? null : CONDITIONS_BY_FAMILY.get(family);
        for (Handle handle : handles) {
            if (handle.line() >= line) {
                continue;
            }
            if (raised == null || handle.conditions().contains("ERROR")
                    || handle.conditions().stream().anyMatch(raised::contains)) {
                return true;
            }
        }
        return false;
    }

    /** The conditions a HANDLE CONDITION names with a label, uppercased. */
    private static Set<String> handledConditions(EmbeddedBlock block) {
        Set<String> conditions = new java.util.HashSet<>();
        Matcher matcher = HANDLED_CONDITION.matcher(block.text().toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            conditions.add(matcher.group(1));
        }
        return conditions;
    }

    /** The command's first word after EXEC CICS: READ, WRITEQ, HANDLE, ... */
    private static String firstWord(EmbeddedBlock block) {
        String[] words = commandWords(block);
        return words.length == 0 ? "" : words[0];
    }

    private static String[] commandWords(EmbeddedBlock block) {
        String normalized = block.text().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).trim();
        int index = normalized.indexOf("EXEC CICS");
        String body = index < 0 ? normalized
                : normalized.substring(index + "EXEC CICS".length()).trim();
        return body.isEmpty() ? new String[0] : body.split("[ (]", 3);
    }

    @Override
    public Optional<FixProducer> fix() {
        return Optional.of(new CicsResponseFixProducer());
    }

    /**
     * Adds response-code receipt and checking to an EXEC CICS command that lacks RESP, via two
     * insertions. Places an {@code RESP(<variable>)} operand line right before END-EXEC, and a
     * response-code check statement right after END-EXEC. The check statement is always closed
     * with an explicit END-IF, and a terminating period is added only when END-EXEC itself closes
     * a sentence.
     *
     * <p>The receiving variable is chosen only from elementary items already declared in
     * WORKING-STORAGE whose name contains RESP (excluding RESP2) and whose type is equivalent to
     * PIC S9(08) COMP. If no such variable exists, no fix is produced. No declaration is added to
     * WORKING-STORAGE.
     */
    private static final class CicsResponseFixProducer implements FixProducer {

        private static final Pattern SECTION_BREAK = Pattern.compile(
                "(?i)^\\s*(?:(?:FILE|WORKING-STORAGE|LOCAL-STORAGE|LINKAGE)\\s+SECTION\\s*\\."
                        + "|PROCEDURE\\s+DIVISION\\b)");
        private static final Pattern WORKING_STORAGE = Pattern.compile(
                "(?i)^\\s*WORKING-STORAGE\\s+SECTION\\s*\\.");
        private static final Pattern END_EXEC = Pattern.compile("(?i)^\\s*END-EXEC\\b");

        @Override
        public Optional<FixSuggestion> produce(Finding finding, AnalysisContext context) {
            if (!"R021".equals(finding.ruleId())) {
                return Optional.empty();
            }
            CobolSemanticModel model =
                    FixEdits.modelOf(context, finding.location().file()).orElse(null);
            if (model == null) {
                return Optional.empty();
            }
            String source = context.artifact(SourceTextIndex.class)
                    .flatMap(index -> index.textOf(model.sourceFile())).orElse(null);
            if (source == null) {
                return Optional.empty();
            }
            EmbeddedBlock block = model.embeddedBlocks().stream()
                    .filter(candidate -> candidate.kind().isCics())
                    .filter(candidate -> candidate.range().end().line() == finding.location().line())
                    .filter(candidate -> !candidate.operands().containsKey("RESP")
                            && !candidate.operands().containsKey("RESP2"))
                    .findFirst()
                    .orElse(null);
            if (block == null) {
                return Optional.empty();
            }
            int endExecLine = block.range().end().line();
            // The operand line can be inserted only when END-EXEC is on its own physical line.
            String[] lines = source.split("\n", -1);
            if (endExecLine < 1 || endExecLine > lines.length
                    || !END_EXEC.matcher(lines[endExecLine - 1]).find()) {
                return Optional.empty();
            }
            String var = respVariable(model, source).orElse(null);
            if (var == null) {
                return Optional.empty();
            }

            String file = block.range().end().file();
            TextEdit operand = insertLinesAt(file, endExecLine, "RESP(" + var + ")");
            String terminator = FixEdits.endsSentence(source, endExecLine) ? "." : "";
            String check = "IF " + var + " NOT = 0 DISPLAY '" + model.programId() + " "
                    + verbLabel(block) + "エラー RESP=' " + var + " END-IF" + terminator;
            TextEdit judgement = insertLinesAt(file, endExecLine + 1, check);
            return Optional.of(new FixSuggestion("RESP と応答コードの検査を挿入します",
                    List.of(operand, judgement)));
        }

        /** A zero-width edit that inserts, as a physical line, a statement formatted for fixed format at the start of the given line. */
        private static TextEdit insertLinesAt(String file, int line, String statement) {
            SourcePosition at =
                    new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
            return new TextEdit(new SourceRange(at, at),
                    String.join("\n", FixEdits.layout(statement)) + "\n");
        }

        /** The command name to put in the check statement's DISPLAY. */
        private static String verbLabel(EmbeddedBlock block) {
            return commandName(block);
        }

        /** The first response-code receiving variable declared in the (1-based) line range of the WORKING-STORAGE section. */
        private static Optional<String> respVariable(CobolSemanticModel model, String source) {
            String[] lines = source.split("\n", -1);
            int first = 0;
            int lastLine = lines.length;
            // first is the physical line (1-based) right after the section header; 0 means the
            // header was not found. lastLine is the line (1-based) right before the next section
            // header, and the 0-based index i corresponds to it directly.
            for (int i = 0; i < lines.length; i++) {
                if (first == 0) {
                    if (WORKING_STORAGE.matcher(lines[i]).find()) {
                        first = i + 2;
                    }
                } else if (SECTION_BREAK.matcher(lines[i]).find()) {
                    lastLine = i;
                    break;
                }
            }
            if (first == 0) {
                return Optional.empty();
            }
            return firstRespReceiver(model.dataItems(), first, lastLine);
        }

        private static Optional<String> firstRespReceiver(List<DataItem> items, int from, int to) {
            for (DataItem item : items) {
                if (!item.children().isEmpty()) {
                    Optional<String> nested = firstRespReceiver(item.children(), from, to);
                    if (nested.isPresent()) {
                        return nested;
                    }
                    continue;
                }
                int line = item.position().line();
                if (line >= from && line <= to && isRespReceiver(item)) {
                    return Optional.of(item.name());
                }
            }
            return Optional.empty();
        }

        /**
         * Whether this is an elementary item whose name contains RESP (excluding RESP2) and whose
         * type is equivalent to PIC S9(08) COMP. The RESP option's receiver must be a signed
         * 4-byte binary item, and since CICS specifies this as PIC S9(8) COMP, we filter on this
         * combination of digits, sign, and USAGE.
         */
        private static boolean isRespReceiver(DataItem item) {
            String name = item.name().toUpperCase(Locale.ROOT);
            if (!name.contains("RESP") || name.contains("RESP2")) {
                return false;
            }
            String picture = item.picture().orElse(null);
            if (picture == null) {
                return false;
            }
            PictureType type;
            try {
                type = PictureType.parse(picture, item.usage().orElse(null));
            } catch (RuntimeException e) {
                return false;
            }
            return type.isNumeric() && type.signed() && type.usage() == Usage.BINARY
                    && type.integerDigits() == 8 && type.fractionDigits() == 0;
        }
    }

    /** "SEND MAP", "WRITEQ TS", "READ": the command's own words, as written. */
    private static String commandName(EmbeddedBlock block) {
        String[] words = commandWords(block);
        if (words.length == 0) {
            return "";
        }
        return words.length > 1 && QUALIFIERS.contains(words[1]) ? words[0] + " " + words[1]
                : words[0];
    }

    /** The command name followed by what it acts on: the map, the program, the queue or the file. */
    private static String commandLabel(EmbeddedBlock block) {
        String name = commandName(block);
        for (String operand : List.of("MAP", "PROGRAM", "QUEUE", "DATASET", "FILE")) {
            String target = block.operands().get(operand);
            if (target != null && !target.isBlank()) {
                return name + " " + target;
            }
        }
        return name;
    }
}
