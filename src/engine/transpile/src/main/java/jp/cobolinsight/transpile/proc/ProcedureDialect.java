package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;

import java.util.List;

/**
 * Per-language rendering responsibility for the procedure translation. Traversal and line mapping
 * are handled by {@link ProcedureRenderer}; this interface writes the literal text of each element
 * (class skeleton, field declarations, opening/closing control structures, assignment, invocation,
 * output, notes) to the output. The control-structure open/close methods adjust the indentation of
 * {@link LineTrackingEmitter} themselves.
 */
public interface ProcedureDialect {

    static ProcedureDialect of(TargetLanguage language) {
        return switch (language) {
            case PYTHON -> new PythonProcedureDialect();
            case JAVA -> new JavaProcedureDialect();
        };
    }

    /** String representing one indentation level. */
    default String indentUnit() {
        return "    ";
    }

    /** Rendering result for one DISPLAY operand (including whether it is a string type). */
    record DisplayPart(String text, boolean isString) {
    }

    // ---- Literal text for expressions and conditions ----

    /** Literal text for a data reference (e.g. self.NAME in Python, NAME in Java). */
    String ref(String fieldName);

    /** Literal text for one subscript dimension. Adjusts COBOL's 1-based subscript to 0-based (narrows to int in Java). */
    String subscript(String indexExpr);

    /** Literal text for a string literal (including quoting and escaping). */
    String stringLiteral(String content);

    /** Literal text for a relational comparison. When stringCompare is true, follows string comparison conventions. */
    String comparison(String left, RelOp op, String right, boolean stringCompare);

    String logicalAnd(String left, String right);

    String logicalOr(String left, String right);

    String negate(String cond);

    String trueLiteral();

    /**
     * Renders an untranslatable condition ({@link PCond.Raw}) from its original text. The literal
     * form differs by language, but each preserves COBOL's raw operand names (undeclared items,
     * special registers) rather than silently collapsing to a tautology.
     */
    String rawCondition(String cobolConditionText);

    // ---- File skeleton and declarations ----

    /** Generated file name (including extension) that holds the procedure translation. */
    String programFileName(String programId);

    /** Emits the file header and class head, field declarations, and CALL stubs (no line mapping is attached). */
    void emitProgramPrologue(LineTrackingEmitter out, String programId, ProgramSymbols symbols);

    /** Emits the class closing (only for languages that need one). */
    void emitProgramEpilogue(LineTrackingEmitter out);

    // ---- Paragraph/section (method) ----

    void openMethod(LineTrackingEmitter out, String methodName);

    void closeMethod(LineTrackingEmitter out);

    // ---- Branch (if/elif/else chain for IF/EVALUATE) ----

    void openIf(LineTrackingEmitter out, String cond);

    void openElseIf(LineTrackingEmitter out, String cond);

    void openElse(LineTrackingEmitter out);

    void closeBranch(LineTrackingEmitter out);

    // ---- Loop ----

    void openWhile(LineTrackingEmitter out, String cond);

    void closeWhile(LineTrackingEmitter out);

    void emitTimesLoop(LineTrackingEmitter out, String methodName, String count);

    // ---- Simple statements ----

    void emitAssign(LineTrackingEmitter out, String target, String value);

    /** PERFORM of a paragraph (method call). */
    void emitInvoke(LineTrackingEmitter out, String methodName);

    void emitDisplay(LineTrackingEmitter out, List<DisplayPart> parts);

    void emitCallProgram(LineTrackingEmitter out, String target, List<String> argNames, String note);

    void emitReturn(LineTrackingEmitter out, String verb);

    void emitNoOp(LineTrackingEmitter out, String verb);

    /** Filler that keeps a block with no executable statements syntactically valid (pass in Python, nothing in Java). */
    void emitBlockFiller(LineTrackingEmitter out);

    void emitComment(LineTrackingEmitter out, String text);

    /** Emits untranslatable original text as a group of noted comments. */
    void emitUntranslated(LineTrackingEmitter out, List<String> cobolLines, String note);

    /**
     * Emits an untranslatable EXEC CICS / EXEC SQL block as a noted stub. Follows the noted header
     * comment and the group of original-text comments with a single line that throws an exception if
     * reached at runtime (raise in Python, throw in Java). command is the exception message.
     */
    void emitEmbeddedStub(LineTrackingEmitter out, String command, List<String> cobolLines,
            String note);
}
