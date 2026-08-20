package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.picture.PictureType;
import jp.cobolinsight.engineapi.picture.Usage;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.FixProducer;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.rules.FixEdits;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * R021 CICS応答コード未検査。EXEC CICS コマンドが RESP・RESP2 いずれのオペランドも持たない場合、
 * コマンドの応答コードを検査できないため検出する。RESP/RESP2 を受ける設計であれば、後続の条件で
 * 判定できる。
 */
public final class CicsResponseUncheckedRule implements Rule {

    @Override
    public String id() {
        return "R021";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("CICS応答コード(RESP/RESP2)未検査", "例外処理")
                .summary("RESP・RESP2 のいずれも指定していない EXEC CICS コマンドを検出します。")
                .rationale("応答コードを受け取れないため、資源の不在や排他の失敗を"
                        + "プログラム側で判定できず、異常時は既定の異常終了へ落ちます。")
                .detection("EXEC CICS コマンドのうち、RESP・RESP2 のいずれのオペランドも"
                        + "持たないものを検出します。")
                .remedy("RESP を付けて応答コードを受け、直後に DFHRESP との比較で分岐します。")
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (!block.kind().isCics()) {
                    continue;
                }
                if (block.operands().containsKey("RESP") || block.operands().containsKey("RESP2")) {
                    continue;
                }
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "EXEC CICS コマンド(" + cicsVerb(block) + ")が RESP・RESP2 を持たず、"
                                + "応答コードを検査していない。異常終了しても後続処理が継続する。",
                        new SourcePosition(model.sourceFile(), block.range().end().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    @Override
    public Optional<FixProducer> fixProducer() {
        return Optional.of(new CicsResponseFixProducer());
    }

    /**
     * RESP を持たない EXEC CICS コマンドへ、応答コードの受け取りと判定を2か所の挿入で足す。
     * END-EXEC の直前へ {@code RESP(<変数>)} のオペランド行を、END-EXEC の直後へ応答コードの
     * 判定文を置く。判定文は常に明示的な END-IF で閉じ、終止ピリオドは END-EXEC が文を閉じて
     * いる場合にのみ付ける。
     *
     * <p>受け変数は WORKING-STORAGE に宣言済みで、名前に RESP を含み(RESP2 を除く)、
     * PIC S9(08) COMP 相当の基本項目に限って選ぶ。該当する変数が無い場合は修正案を出さない。
     * WORKING-STORAGE への宣言追加は行わない。
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
            // オペランド行を差し込めるのは END-EXEC が独立した物理行にある場合に限る。
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
            return Optional.of(new FixSuggestion("RESP/RESP2 検査を挿入する",
                    List.of(operand, judgement)));
        }

        /** 指定行の先頭へ、固定形式へ整形した文を物理行として差し込む空範囲の編集。 */
        private static TextEdit insertLinesAt(String file, int line, String statement) {
            SourcePosition at =
                    new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
            return new TextEdit(new SourceRange(at, at),
                    String.join("\n", FixEdits.layout(statement)) + "\n");
        }

        /** 判定文の DISPLAY へ載せるコマンド名。 */
        private static String verbLabel(EmbeddedBlock block) {
            return block.kind().name().replace("CICS_", "").replace('_', ' ');
        }

        /** WORKING-STORAGE 節の行範囲(1始まり)に宣言された、最初の応答コード受け変数。 */
        private static Optional<String> respVariable(CobolSemanticModel model, String source) {
            String[] lines = source.split("\n", -1);
            int first = 0;
            int lastLine = lines.length;
            // first は節見出しの次の物理行(1始まり)。0 は見出し未検出を表す。lastLine は次の節
            // 見出しの直前行(1始まり)であり、0始まりの添字 i がそのまま該当する。
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
         * 名前に RESP を含み(RESP2 を除く)、PIC S9(08) COMP 相当の基本項目か。RESP オプションの
         * 受け取り先は符号付き4バイト二進項目でなければならず、CICS が定める記述が
         * PIC S9(8) COMP であるため、この桁・符号・USAGE の組で絞る。
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
