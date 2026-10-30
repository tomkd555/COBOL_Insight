package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureNames;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourceRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the procedure division of the semantic model (the tree of paragraphs, sections, and statements)
 * into the language-independent intermediate representation {@link ProcedureIr}/{@link ProcStmt}.
 * SimpleStatement is re-parsed with {@link OperandParser}; CompoundStatement (BRANCH -> if/EVALUATE,
 * LOOP -> while) is mapped by walking its structure. The loop-variable clause of an inline
 * PERFORM VARYING is absent from the semantic model, so it is recovered from the original source
 * ({@link SourceSlicer}). A procedure containing GO TO is reduced to structured control by
 * {@link GotoStructurer}, and only the forms that cannot be reduced fall back to a noted,
 * untranslated form.
 */
public final class ProcedureModelBuilder {

    private final OperandParser parser;
    private final SourceSlicer slicer;
    private final MethodNames methods;

    public ProcedureModelBuilder(ProgramSymbols symbols,
            Map<SourceRange, EmbeddedBlock> embeddedByRange, SourceSlicer slicer,
            MethodNames methods) {
        this.parser = new OperandParser(symbols, embeddedByRange, slicer, methods);
        this.slicer = slicer;
        this.methods = methods;
    }

    /**
     * The procedures {@code methods} was built from, each as the method it names, followed by
     * the method of each THRU range a GO TO inside it keeps from running procedure by procedure
     * (see {@link #rangeCall}).
     */
    public List<ProcedureIr> build(List<Procedure> procedures) {
        GotoStructurer structurer = new GotoStructurer(procedures, parser, this::convert);
        Map<String, OperandParser.RangeCall> rangeCalls = new HashMap<>();
        List<ProcedureIr> rangeMethods = new ArrayList<>();
        parser.rangeCalls(range -> rangeCall(range, procedures, structurer, rangeCalls,
                rangeMethods));
        List<ProcedureIr> result = new ArrayList<>();
        for (int i = 0; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            List<ProcStmt> body = null;
            if (GotoStructurer.containsGoTo(procedure)) {
                body = structurer.structure(procedure);
            }
            if (body == null) {
                body = buildStatements(procedure.statements());
            }
            result.add(new ProcedureIr(procedure.name(), methods.of(i), body, procedure.range()));
        }
        result.addAll(rangeMethods);
        return result;
    }

    /**
     * How a PERFORM runs the THRU range of two procedures or more. Each procedure's method returns
     * at its end, so calling them one by one runs the range as COBOL does while no GO TO in it
     * jumps: then this is null. A GO TO in the range is a jump COBOL takes within it, past the
     * procedures between, and the range becomes one method of its own whose body
     * {@link GotoStructurer#structureRange} builds, shared by every PERFORM of that range. A range
     * that cannot be structured so is called procedure by procedure, with a note.
     */
    private OperandParser.RangeCall rangeCall(ProcedureNames.Range range,
            List<Procedure> procedures, GotoStructurer structurer,
            Map<String, OperandParser.RangeCall> known, List<ProcedureIr> rangeMethods) {
        boolean jumps = false;
        for (int i = range.from(); i <= range.to(); i++) {
            jumps |= GotoStructurer.containsGoTo(procedures.get(i));
        }
        if (!jumps) {
            return null;
        }
        String key = range.from() + ":" + range.to();
        OperandParser.RangeCall call = known.get(key);
        if (call != null) {
            return call;
        }
        String method = methods.ofRange(range.from(), range.to());
        // Taken before the body is built, so a PERFORM of the same range inside it finds it.
        known.put(key, new OperandParser.RangeCall(method, false));
        List<ProcStmt> body = structurer.structureRange(range.from(), range.to());
        if (body == null) {
            call = new OperandParser.RangeCall(null, true);
            known.put(key, call);
            return call;
        }
        Procedure first = procedures.get(range.from());
        rangeMethods.add(new ProcedureIr(first.name() + " THRU "
                + procedures.get(range.to()).name(), method, body, first.range()));
        return known.get(key);
    }

    private List<ProcStmt> buildStatements(List<Statement> statements) {
        List<ProcStmt> result = new ArrayList<>();
        for (Statement statement : statements) {
            result.addAll(convert(statement));
        }
        return result;
    }

    /** Maps one statement to the intermediate representation. GO TO appears only on the fallback path where structuring failed, and is left as a noted, untranslated form. */
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

    /** Joins the untranslatable-condition note and the loop-variable-clause note with "; ", including only the non-empty ones. */
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
