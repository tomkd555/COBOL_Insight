package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;

/**
 * Intermediate representation of a procedure statement. Maps one COBOL statement into a
 * language-independent structure. Each statement carries the {@link SourceRange} it was derived from
 * and a note (an explanation of an untranslatable case or simplification; empty means a 1:1 literal
 * translation), which {@link ProcedureRenderer} reflects into the line mapping.
 */
public sealed interface ProcStmt {

    SourceRange range();

    /** Note for this whole statement (empty means no note). */
    String note();

    /** Assignment (MOVE sender to receiver; ADD/COMPUTE receiver = expression). */
    record Assign(PExpr.Ref target, PExpr value, SourceRange range, String note) implements ProcStmt {
    }

    /** Branch (IF -> one arm plus else; EVALUATE -> n arms plus other). Empty elseBody means no else. */
    record Branch(List<Arm> arms, List<ProcStmt> elseBody, SourceRange range, String note)
            implements ProcStmt {
        public Branch {
            arms = List.copyOf(arms);
            elseBody = List.copyOf(elseBody);
        }
    }

    /** One arm of a branch (condition and body). */
    record Arm(PCond cond, List<ProcStmt> body) {
        public Arm {
            body = List.copyOf(body);
        }
    }

    /**
     * Loop (PERFORM UNTIL, inline or out-of-line; PERFORM VARYING). Repeats while until is not satisfied.
     * varyingVar/varyingInit/varyingStep are non-null only when the VARYING clause could be recovered.
     */
    record Loop(PCond until, List<ProcStmt> body, PExpr.Ref varyingVar, PExpr varyingInit,
            PExpr varyingStep, SourceRange range, String note) implements ProcStmt {
        public Loop {
            body = List.copyOf(body);
        }
    }

    /** PERFORM of a paragraph or section (function/method call). */
    record PerformCall(String methodName, SourceRange range, String note) implements ProcStmt {
    }

    /** PERFORM paragraph n TIMES (counted repetition). */
    record PerformTimes(String methodName, PExpr count, SourceRange range, String note)
            implements ProcStmt {
    }

    /** PERFORM paragraph THRU paragraph (sequential call over the range). */
    record PerformThru(List<String> methodNames, SourceRange range, String note) implements ProcStmt {
        public PerformThru {
            methodNames = List.copyOf(methodNames);
        }
    }

    /** DISPLAY (concatenated output to standard output). */
    record Display(List<PExpr> operands, SourceRange range, String note) implements ProcStmt {
        public Display {
            operands = List.copyOf(operands);
        }
    }

    /** CALL (program invocation). argDescriptors describes each argument, including the passing mode. */
    record CallProgram(String target, List<String> argDescriptors, SourceRange range, String note)
            implements ProcStmt {
        public CallProgram {
            argDescriptors = List.copyOf(argDescriptors);
        }
    }

    /** STOP RUN, GOBACK, EXIT PROGRAM (return to caller). verb is the original verb phrase. */
    record Return(String verb, SourceRange range, String note) implements ProcStmt {
    }

    /** CONTINUE, EXIT (no-op). */
    record NoOp(String verb, SourceRange range, String note) implements ProcStmt {
    }

    /** An untranslatable or unsupported statement (file I/O, STRING, etc.). The original lines are turned into comments and made visible via the note. */
    record Untranslated(List<String> cobolTextLines, SourceRange range, String note)
            implements ProcStmt {
        public Untranslated {
            cobolTextLines = List.copyOf(cobolTextLines);
        }
    }

    /**
     * An untranslatable EXEC CICS / EXEC SQL block. The original text is preserved as a comment, and the
     * statement is emitted as a noted stub that throws an exception if reached at runtime
     * (NotImplementedError in Python, UnsupportedOperationException in Java).
     * command is the instruction name used in the exception message (e.g. "EXEC CICS RECEIVE MAP"),
     * and note carries the operands. Multiple COBOL lines collapse into a single stub, so the line
     * mapping is N:1 (MANY_TO_ONE).
     */
    record EmbeddedStub(String command, List<String> cobolTextLines, SourceRange range, String note)
            implements ProcStmt {
        public EmbeddedStub {
            cobolTextLines = List.copyOf(cobolTextLines);
        }
    }
}
