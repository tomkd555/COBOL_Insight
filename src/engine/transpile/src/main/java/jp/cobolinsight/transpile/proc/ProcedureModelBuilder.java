package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.transpile.emit.Identifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 意味モデルの手続き部(段落・節と文の木)を、言語非依存の中間表現 {@link ProcedureIr}/{@link ProcStmt} へ写す。
 * SimpleStatement は {@link OperandParser} で再パースし、CompoundStatement(BRANCH→if/EVALUATE、LOOP→while)は
 * 構造を辿って写す。inline PERFORM VARYING の反復変数句は意味モデルに無いため、原ソース({@link SourceSlicer})から復元する。
 * GO TO を含む手続きは {@link GotoStructurer} で構造化制御へ還元し、還元できない形だけを注記付きの
 * 非対訳として落とす。
 */
public final class ProcedureModelBuilder {

    private final OperandParser parser;
    private final SourceSlicer slicer;

    public ProcedureModelBuilder(ProgramSymbols symbols,
            Map<SourceRange, EmbeddedBlock> embeddedByRange, SourceSlicer slicer) {
        this.parser = new OperandParser(symbols, embeddedByRange, slicer);
        this.slicer = slicer;
    }

    public List<ProcedureIr> build(List<Procedure> procedures) {
        GotoStructurer structurer = new GotoStructurer(procedures, parser, this::convert);
        List<ProcedureIr> result = new ArrayList<>();
        for (Procedure procedure : procedures) {
            List<ProcStmt> body = null;
            if (GotoStructurer.containsGoTo(procedure)) {
                body = structurer.structure(procedure);
            }
            if (body == null) {
                body = buildStatements(procedure.statements());
            }
            result.add(new ProcedureIr(procedure.name(), Identifiers.sanitize(procedure.name()),
                    body, procedure.range()));
        }
        return result;
    }

    private List<ProcStmt> buildStatements(List<Statement> statements) {
        List<ProcStmt> result = new ArrayList<>();
        for (Statement statement : statements) {
            result.addAll(convert(statement));
        }
        return result;
    }

    /** 1文を中間表現へ写す。GO TO は構造化に失敗した退避経路でのみ現れ、注記付き非対訳とする。 */
    List<ProcStmt> convert(Statement statement) {
        return switch (statement) {
            case SimpleStatement s -> parser.parseSimple(s);
            case CompoundStatement c -> List.of(buildCompound(c));
            case GoToStatement g -> List.of(new ProcStmt.Untranslated(
                    List.of("GO TO " + String.join(" ", g.targets())), g.range(),
                    "GO TO を構造化制御へ還元できず注記付き非対訳とした"));
        };
    }

    private ProcStmt buildCompound(CompoundStatement cs) {
        if (cs.kind() == ControlKind.BRANCH) {
            return isIf(cs) ? buildIf(cs) : buildEvaluate(cs);
        }
        return buildLoop(cs);
    }

    private static boolean isIf(CompoundStatement cs) {
        for (StatementBlock block : cs.blocks()) {
            if (block.label().equalsIgnoreCase("THEN")) {
                return true;
            }
        }
        return false;
    }

    private ProcStmt buildIf(CompoundStatement cs) {
        List<ProcStmt> thenBody = List.of();
        List<ProcStmt> elseBody = List.of();
        for (StatementBlock block : cs.blocks()) {
            if (block.label().equalsIgnoreCase("THEN")) {
                thenBody = buildStatements(block.statements());
            } else if (block.label().equalsIgnoreCase("ELSE")) {
                elseBody = buildStatements(block.statements());
            }
        }
        PCond cond = parser.parseBranchCondition(cs);
        ProcStmt.Arm arm = new ProcStmt.Arm(cond, thenBody);
        return new ProcStmt.Branch(List.of(arm), elseBody, cs.range(),
                PCond.untranslatableNote(cond));
    }

    private ProcStmt buildEvaluate(CompoundStatement cs) {
        String subject = cs.conditionText();
        List<ProcStmt.Arm> arms = new ArrayList<>();
        List<ProcStmt> elseBody = List.of();
        boolean rawSeen = false;
        for (StatementBlock block : cs.blocks()) {
            if (block.label().equalsIgnoreCase("OTHER")) {
                elseBody = buildStatements(block.statements());
                continue;
            }
            PCond cond = parser.evaluateArm(subject, block.label());
            rawSeen |= cond instanceof PCond.Raw;
            arms.add(new ProcStmt.Arm(cond, buildStatements(block.statements())));
        }
        String note = rawSeen ? "一部の WHEN 条件を直訳できない" : "";
        return new ProcStmt.Branch(arms, elseBody, cs.range(), note);
    }

    private ProcStmt buildLoop(CompoundStatement cs) {
        PCond until = parser.parseCondition(stripUntil(cs.conditionText()));
        String condNote = PCond.untranslatableNote(until);
        List<ProcStmt> body = new ArrayList<>();
        for (StatementBlock block : cs.blocks()) {
            body.addAll(buildStatements(block.statements()));
        }
        if (slicer == null) {
            return new ProcStmt.Loop(until, body, null, null, null, cs.range(),
                    join(condNote, "反復変数句(VARYING)は原ソース未供給のため未復元(該当時)"));
        }
        List<String> toks = OperandParser.splitTokens(slicer.linesOf(cs.range()));
        int varyingIdx = indexOf(toks, "VARYING");
        if (varyingIdx < 0) {
            return new ProcStmt.Loop(until, body, null, null, null, cs.range(), condNote);
        }
        int fromIdx = indexOf(toks, "FROM");
        int byIdx = indexOf(toks, "BY");
        if (fromIdx > varyingIdx && byIdx > fromIdx && varyingIdx + 1 < toks.size()) {
            Optional<PExpr> var = parser.parseOperand(toks.get(varyingIdx + 1));
            Optional<PExpr> from = parser.parseOperand(toks.get(fromIdx + 1));
            Optional<PExpr> by = parser.parseOperand(toks.get(byIdx + 1));
            if (var.isPresent() && var.get() instanceof PExpr.Ref varRef && from.isPresent()
                    && by.isPresent()) {
                return new ProcStmt.Loop(until, body, varRef, from.get(), by.get(), cs.range(),
                        condNote);
            }
        }
        return new ProcStmt.Loop(until, body, null, null, null, cs.range(),
                join(condNote, "VARYING 句を解釈できず反復変数の初期化・増分を省略"));
    }

    /** 直訳不能条件の注記と反復変数句の注記を、非空のものだけ「; 」で連結する。 */
    private static String join(String condNote, String varyingNote) {
        if (condNote.isEmpty()) {
            return varyingNote;
        }
        return condNote + "; " + varyingNote;
    }

    private static String stripUntil(String conditionText) {
        List<String> toks = OperandParser.splitTokens(conditionText);
        int untilIdx = indexOf(toks, "UNTIL");
        if (untilIdx < 0) {
            return conditionText;
        }
        return String.join(" ", toks.subList(untilIdx + 1, toks.size()));
    }

    private static int indexOf(List<String> toks, String keyword) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).equalsIgnoreCase(keyword)) {
                return i;
            }
        }
        return -1;
    }
}
