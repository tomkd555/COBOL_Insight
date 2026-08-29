package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.linemap.MappingKind;
import jp.cobolinsight.core.source.LineRange;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ProcedureIr}/{@link ProcStmt} の中間表現を {@link ProcedureDialect} 経由で対象言語へ描画し、
 * 各生成行に由来 COBOL 行の対応({@link LineTrackingEmitter#addMapping})を付す言語非依存の走査。
 * 段落見出しと複合文の見出しは COBOL 見出し行へ、単文は原文の行範囲へ対応づける。
 */
public final class ProcedureRenderer {

    private final LineTrackingEmitter out;
    private final String generatedFile;
    private final ProcedureDialect dialect;

    private ProcedureRenderer(LineTrackingEmitter out, String generatedFile,
            ProcedureDialect dialect) {
        this.out = out;
        this.generatedFile = generatedFile;
        this.dialect = dialect;
    }

    private static final String WS_SQL_NOTE =
            "作業部の埋め込み SQL 指令(DB2 プリプロセッサ)は直訳不能・注記のみ";

    public static void render(LineTrackingEmitter out, String generatedFile, String programId,
            String programSourceId, ProgramSymbols symbols, List<ProcedureIr> procedures,
            List<DataDivisionSql.Directive> dataSql, ProcedureDialect dialect) {
        ProcedureRenderer renderer = new ProcedureRenderer(out, generatedFile, dialect);
        dialect.emitProgramPrologue(out, programId, symbols);
        renderer.renderDataDivisionSql(programSourceId, dataSql);
        for (ProcedureIr procedure : procedures) {
            renderer.renderProcedure(procedure);
        }
        dialect.emitProgramEpilogue(out);
    }

    /** 作業部の埋め込み SQL 指令を原文コメントとして出力し、原ソース行への対応を付す。 */
    private void renderDataDivisionSql(String sourceId, List<DataDivisionSql.Directive> directives) {
        for (DataDivisionSql.Directive directive : directives) {
            int start = out.nextLine();
            dialect.emitComment(out,
                    "作業部 SQL 指令(直訳不能・注記のみ): " + String.join(" ", directive.textLines()));
            out.addMapping(sourceId, new LineRange(directive.startLine(), directive.endLine()),
                    generatedFile, start, out.lastLine(), WS_SQL_NOTE);
        }
    }

    private void renderProcedure(ProcedureIr procedure) {
        dialect.openMethod(out, procedure.methodName());
        int headerLine = out.lastLine();
        addMapping(headerLine, headerLine, procedure.headerRange(), headerStartLine(procedure), "");
        renderBlock(procedure.body());
        dialect.closeMethod(out);
    }

    private void renderBlock(List<ProcStmt> statements) {
        boolean anyExecutable = false;
        for (ProcStmt statement : statements) {
            renderOne(statement);
            // Untranslated は原文コメントだけを出力するため、実行文として数えない。
            anyExecutable |= !(statement instanceof ProcStmt.Untranslated);
        }
        if (!anyExecutable) {
            dialect.emitBlockFiller(out);
        }
    }

    private void renderOne(ProcStmt statement) {
        switch (statement) {
            case ProcStmt.Assign s -> leaf(s.range(), s.note(),
                    () -> dialect.emitAssign(out, ExprWriter.expr(s.target(), dialect),
                            ExprWriter.expr(s.value(), dialect)));
            case ProcStmt.PerformCall s -> leaf(s.range(), s.note(),
                    () -> dialect.emitInvoke(out, s.methodName()));
            case ProcStmt.PerformTimes s -> leaf(s.range(), s.note(),
                    () -> dialect.emitTimesLoop(out, s.methodName(),
                            ExprWriter.expr(s.count(), dialect)));
            case ProcStmt.PerformThru s -> leaf(s.range(), s.note(), () -> {
                dialect.emitComment(out, "PERFORM THRU: " + String.join(" .. ", s.methodNames()));
                for (String method : s.methodNames()) {
                    dialect.emitInvoke(out, method);
                }
            });
            case ProcStmt.Display s -> leaf(s.range(), s.note(),
                    () -> dialect.emitDisplay(out, displayParts(s)));
            case ProcStmt.CallProgram s -> leaf(s.range(), s.note(),
                    () -> dialect.emitCallProgram(out, s.target(), s.argDescriptors(), s.note()));
            case ProcStmt.Return s -> leaf(s.range(), s.note(),
                    () -> dialect.emitReturn(out, s.verb()));
            case ProcStmt.NoOp s -> leaf(s.range(), s.note(), () -> dialect.emitNoOp(out, s.verb()));
            case ProcStmt.Untranslated s -> leaf(s.range(), s.note(),
                    () -> dialect.emitUntranslated(out, s.cobolTextLines(), s.note()));
            case ProcStmt.EmbeddedStub s -> renderEmbeddedStub(s);
            case ProcStmt.Branch s -> renderBranch(s);
            case ProcStmt.Loop s -> renderLoop(s);
        }
    }

    /** EXEC CICS/SQL の注記スタブ。複数の COBOL 行を1つのスタブへ畳むため N:1(MANY_TO_ONE)で対応づける。 */
    private void renderEmbeddedStub(ProcStmt.EmbeddedStub stub) {
        SourceRange range = stub.range();
        int start = out.nextLine();
        dialect.emitEmbeddedStub(out, stub.command(), stub.cobolTextLines(), stub.note());
        out.addMapping(sourceId(range.start().file()),
                new LineRange(range.start().line(), range.end().line()), generatedFile, start,
                out.lastLine(), MappingKind.MANY_TO_ONE, stub.note());
    }

    private void renderBranch(ProcStmt.Branch branch) {
        List<ProcStmt.Arm> arms = branch.arms();
        for (int i = 0; i < arms.size(); i++) {
            ProcStmt.Arm arm = arms.get(i);
            String cond = ExprWriter.cond(arm.cond(), dialect);
            int start = out.nextLine();
            if (i == 0) {
                dialect.openIf(out, cond);
                addMapping(start, out.lastLine(), branch.range(), branch.range().start().line(),
                        branch.note());
            } else {
                dialect.openElseIf(out, cond);
            }
            renderBlock(arm.body());
        }
        if (!branch.elseBody().isEmpty()) {
            dialect.openElse(out);
            renderBlock(branch.elseBody());
        }
        dialect.closeBranch(out);
    }

    private void renderLoop(ProcStmt.Loop loop) {
        int start = out.nextLine();
        if (loop.varyingVar() != null) {
            dialect.emitAssign(out, ExprWriter.expr(loop.varyingVar(), dialect),
                    ExprWriter.expr(loop.varyingInit(), dialect));
        }
        // COBOL の UNTIL は条件が成立した時点で反復を終える。while の継続条件はその否定になる。
        dialect.openWhile(out, ExprWriter.cond(new PCond.Negate(loop.until()), dialect));
        addMapping(start, out.lastLine(), loop.range(), loop.range().start().line(), loop.note());
        renderBlock(loop.body());
        if (loop.varyingVar() != null) {
            int stepStart = out.nextLine();
            List<PExpr> step = new ArrayList<>();
            step.add(loop.varyingVar());
            step.add(new PExpr.Op("+"));
            step.add(loop.varyingStep());
            dialect.emitAssign(out, ExprWriter.expr(loop.varyingVar(), dialect),
                    ExprWriter.expr(new PExpr.Arith(step), dialect));
            addMapping(stepStart, out.lastLine(), loop.range(), loop.range().start().line(),
                    loop.note());
        }
        dialect.closeWhile(out);
    }

    private List<ProcedureDialect.DisplayPart> displayParts(ProcStmt.Display display) {
        List<ProcedureDialect.DisplayPart> parts = new ArrayList<>();
        for (PExpr operand : display.operands()) {
            parts.add(new ProcedureDialect.DisplayPart(ExprWriter.expr(operand, dialect),
                    ExprWriter.isString(operand)));
        }
        return parts;
    }

    private void leaf(SourceRange range, String note, Runnable emit) {
        int start = out.nextLine();
        emit.run();
        addMapping(start, out.lastLine(), range, range.start().line(), range.end().line(), note);
    }

    private void addMapping(int genStart, int genEnd, SourceRange range, int cobolStart, String note) {
        addMapping(genStart, genEnd, range, cobolStart, cobolStart, note);
    }

    private void addMapping(int genStart, int genEnd, SourceRange range, int cobolStart,
            int cobolEnd, String note) {
        out.addMapping(sourceId(range.start().file()), new LineRange(cobolStart, cobolEnd),
                generatedFile, genStart, genEnd, note);
    }

    private static String sourceId(String file) {
        int separator = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return separator >= 0 ? file.substring(separator + 1) : file;
    }

    private static int headerStartLine(ProcedureIr procedure) {
        return procedure.headerRange().start().line();
    }
}
