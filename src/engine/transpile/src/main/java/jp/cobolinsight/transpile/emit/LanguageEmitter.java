package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;

import java.util.Optional;

/**
 * The rendering responsibility for each target language. Traversal (which items to emit in what
 * order) is handled by {@link RecordClassGenerator}; this interface writes each element's
 * language-specific surface form (class scaffolding, accessors, predicates, runtime helper) to the
 * output. Each emit method only appends lines to the {@link LineTrackingEmitter}; recording the
 * line correspondence is the caller generator's responsibility.
 */
public interface LanguageEmitter {

    static LanguageEmitter of(TargetLanguage language) {
        return switch (language) {
            case PYTHON -> new PythonEmitter();
            case JAVA -> new JavaEmitter();
        };
    }

    TargetLanguage language();

    /** The string for one level of indentation. */
    String indentUnit();

    /** The generated file name holding the record class (including extension). */
    String recordFileName(String recordCobolName);

    /** The generated file name of the runtime helper. */
    String runtimeFileName();

    /** The runtime helper (COMP-3/zoned decimal/BINARY/alphanumeric encode/decode) that is always the same regardless of input. */
    GeneratedFile runtimeLibrary();

    /** Emits the file header (module description, runtime import). No line correspondence is recorded. */
    void emitFileHeader(LineTrackingEmitter out, String programId, String recordCobolName);

    /** Emits the head of the class definition (declaration, total byte length constant, constructors, buffer reference). */
    void emitClassHeader(LineTrackingEmitter out, String className, String cobolName, int totalBytes);

    /** Emits the closing of the class definition (only for languages that need one). */
    void emitClassFooter(LineTrackingEmitter out);

    /** Emits a comment line describing the structure of a group item (including REDEFINES/OCCURS). */
    void emitGroupComment(LineTrackingEmitter out, String cobolName, int offset, int byteLength,
            Optional<Integer> occursCount, Optional<String> redefinesTarget);

    /** Emits the get/set accessor for an elementary item. */
    void emitAccessor(LineTrackingEmitter out, AccessorSpec spec);

    /** Emits the predicate method for an 88-level condition name. */
    void emitConditionPredicate(LineTrackingEmitter out, ConditionSpec spec);
}
