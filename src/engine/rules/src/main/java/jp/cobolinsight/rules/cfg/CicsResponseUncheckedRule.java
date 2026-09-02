package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.picture.Usage;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * R021 Unchecked CICS response code. When an EXEC CICS command has neither a RESP nor a RESP2
 * operand, the command's response code cannot be checked, so this is flagged. A design that
 * receives RESP/RESP2 can branch on it in a subsequent condition.
 */
public final class CicsResponseUncheckedRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R021", "CICS応答コード(RESP/RESP2)未検査", "例外処理")
                    .summary("RESP・RESP2 のいずれも指定しない EXEC CICS コマンドを検出する。")
                    .rationale("応答コードを受け取れないため、資源の不在や排他の失敗を"
                            + "プログラム側で検査できず、異常時は既定の異常終了になる。")
                    .detection("EXEC CICS コマンドのうち、RESP・RESP2 のいずれの作用対象も"
                            + "持たないものを検出する。")
                    .remedy("RESP を付けて応答コードを受け取り、直後に DFHRESP との比較で分岐する。")
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
                    .commands(Command.LINT, Command.REPORT, Command.FIX)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.SEMANTIC, Needs.SOURCE_TEXT)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (!block.kind().isCics()) {
                    continue;
                }
                if (block.operands().containsKey("RESP") || block.operands().containsKey("RESP2")) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "EXEC CICS コマンド（" + cicsVerb(block) + "）が RESP・RESP2 を持たず、"
                                + "応答コードを検査していない。異常が起きても後続処理が続く。",
                        new SourcePosition(model.sourceFile(), block.range().end().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
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
            return Optional.of(new FixSuggestion("RESP/RESP2 の検査を挿入する",
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
            return block.kind().name().replace("CICS_", "").replace('_', ' ');
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

    private static String cicsVerb(EmbeddedBlock block) {
        String target = block.operands().getOrDefault("MAP",
                block.operands().getOrDefault("PROGRAM", ""));
        String kind = block.kind().name().replace("CICS_", "").replace('_', ' ');
        return target.isBlank() ? kind : kind + " " + target;
    }
}
