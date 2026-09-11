package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R040 A repeated update that never commits. A batch program that runs UPDATE, INSERT or DELETE
 * inside a loop and issues no COMMIT holds every row lock until the step ends, and an abend at the
 * last row throws away the whole run. CICS programs are out of scope: there the unit of work ends
 * with SYNCPOINT or with the transaction itself.
 */
public final class UncommittedUpdateLoopRule implements Rule {

    private static final Pattern LOOP_PERFORM = Pattern.compile("(?i)\\b(UNTIL|VARYING)\\b");
    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?i)^(?:UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\s+([\\p{L}\\p{N}$#@_.-]+)");
    private static final Set<String> DML = Set.of("UPDATE", "INSERT", "DELETE");

    private static final RuleMeta META = RuleMeta
            .named("R040", "COMMIT のない更新の繰り返し", "SQL")
            .summary("繰り返しの中で更新しながら COMMIT を一度も実行しない"
                    + "バッチプログラムを検出します。")
            .rationale("行ロックがステップの終わりまで解放されず、"
                    + "終盤で異常終了するとそれまでの更新がすべて取り消されます。")
            .detection("PERFORM UNTIL・PERFORM VARYING の本体、"
                    + "またはそこから PERFORM される段落に UPDATE・INSERT・DELETE があり、"
                    + "プログラムのどこにも EXEC SQL COMMIT がないものを検出します。"
                    + "報告は最初の更新の位置に 1 件だけです。"
                    + "COMMIT のあるプログラムと、EXEC CICS を含むプログラムは対象外です。")
            .remedy("一定件数ごとに COMMIT を実行し、"
                    + "再実行できるように再開位置を記録してください。")
            .example("""
                    PERFORM 2000-UPDATE-LOOP UNTIL WS-EOF.
                    """, """
                    PERFORM 2000-UPDATE-LOOP UNTIL WS-EOF.
                    ...
                    2900-COMMIT-CHECK.
                        ADD 1 TO WS-COMMIT-CNT
                        IF WS-COMMIT-CNT >= WS-COMMIT-MAX
                            EXEC SQL COMMIT END-EXEC
                            MOVE ZERO TO WS-COMMIT-CNT
                        END-IF.
                    """)
            .severity(Severity.ADVISORY)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            evaluate(model, findings);
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, List<Finding> findings) {
        if (Db2Schema.hasCics(model)) {
            return;
        }
        List<EmbeddedBlock> blocks = Db2Schema.sqlBlocks(model);
        List<EmbeddedBlock> updates = new ArrayList<>();
        for (EmbeddedBlock block : blocks) {
            String keyword = Db2Schema.keyword(Db2Schema.body(block.text()));
            if (keyword.equals("COMMIT")) {
                return;
            }
            if (DML.contains(keyword)) {
                updates.add(block);
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        List<SourceRange> loops = loopBodies(model);
        EmbeddedBlock first = null;
        for (EmbeddedBlock block : updates) {
            if (loops.stream().anyMatch(loop -> covers(loop, block.range().start()))
                    && (first == null
                            || block.range().start().line() < first.range().start().line())) {
                first = block;
            }
        }
        if (first == null) {
            return;
        }
        String body = Db2Schema.body(first.text());
        Matcher table = TARGET_TABLE.matcher(body);
        String name = table.find() ? table.group(1) : Db2Schema.keyword(body);
        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                name + " の更新に COMMIT がありません。"
                        + "繰り返しの途中で異常終了すると、それまでの更新が取り消されます。",
                new SourcePosition(model.sourceFile(), first.range().start().line(), 1,
                        SourcePosition.UNKNOWN_BYTE_OFFSET)));
    }

    /**
     * The source ranges a loop runs over: the body of an inline PERFORM UNTIL/VARYING, and every
     * procedure a paragraph PERFORM UNTIL/VARYING reaches, directly or through a further PERFORM.
     */
    private static List<SourceRange> loopBodies(CobolSemanticModel model) {
        List<SourceRange> loops = new ArrayList<>();
        List<Statement> statements = new ArrayList<>();
        for (Procedure procedure : model.procedures()) {
            collect(procedure.statements(), statements);
        }
        for (Statement statement : statements) {
            if (statement instanceof CompoundStatement compound
                    && compound.kind() == ControlKind.LOOP) {
                loops.add(compound.range());
            } else if (statement instanceof SimpleStatement simple
                    && "PERFORM".equalsIgnoreCase(simple.verb())
                    && LOOP_PERFORM.matcher(simple.text()).find()) {
                addPerformed(model, simple.range(), loops);
            }
        }
        return loops;
    }

    /** The ranges of the procedures the PERFORM at {@code header} reaches, transitively. */
    private static void addPerformed(CobolSemanticModel model, SourceRange header,
            List<SourceRange> loops) {
        List<Procedure> procedures = model.procedures();
        Set<String> seen = new LinkedHashSet<>();
        Deque<PerformRelation> pending = new ArrayDeque<>();
        for (PerformRelation perform : model.performs()) {
            if (perform.range().equals(header)) {
                pending.add(perform);
            }
        }
        while (!pending.isEmpty()) {
            PerformRelation perform = pending.removeFirst();
            int from = indexOf(procedures, perform.targetProcedure());
            if (from < 0) {
                continue;
            }
            int to = perform.thruProcedure().map(name -> indexOf(procedures, name)).orElse(from);
            for (int i = from; i <= Math.max(from, to) && i < procedures.size(); i++) {
                Procedure procedure = procedures.get(i);
                if (!seen.add(procedure.name().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                loops.add(procedure.range());
                for (PerformRelation nested : model.performs()) {
                    if (covers(procedure.range(), nested.range().start())
                            && !nested.range().equals(header)) {
                        pending.add(nested);
                    }
                }
            }
        }
    }

    private static int indexOf(List<Procedure> procedures, String name) {
        for (int i = 0; i < procedures.size(); i++) {
            if (procedures.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean covers(SourceRange range, SourcePosition position) {
        return range.start().file().equals(position.file())
                && range.start().line() <= position.line()
                && position.line() <= range.end().line();
    }

    private static void collect(List<Statement> statements, List<Statement> out) {
        for (Statement statement : statements) {
            out.add(statement);
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    collect(block.statements(), out);
                }
            }
        }
    }
}
