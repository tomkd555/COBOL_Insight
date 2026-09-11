package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.CopyInlineExpansion;

import java.util.List;

/**
 * The normalized semantic model. Built by the COBOL frontend; dataflow, linker, transpile, and
 * rules take only this model as input. One instance per program (PROGRAM-ID).
 *
 * <p>{@code files} are the FILE-CONTROL entries, which is how a DD name of the JCL reaches the
 * statements that read and write through it.
 */
public record CobolSemanticModel(String programId, String sourceFile, List<DataItem> dataItems,
        List<Procedure> procedures, List<CallRelation> calls, List<PerformRelation> performs,
        List<EmbeddedBlock> embeddedBlocks, List<CopyExpansionEntry> copyExpansions,
        List<CopyInlineExpansion> copyInlineExpansions, List<FileDefinition> files) {

    public CobolSemanticModel {
        if (programId == null || programId.isBlank()) {
            throw new IllegalArgumentException("programId must not be blank");
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        dataItems = List.copyOf(dataItems);
        procedures = List.copyOf(procedures);
        calls = List.copyOf(calls);
        performs = List.copyOf(performs);
        embeddedBlocks = List.copyOf(embeddedBlocks);
        copyExpansions = List.copyOf(copyExpansions);
        copyInlineExpansions = List.copyOf(copyInlineExpansions);
        files = List.copyOf(files);
    }

    /** A model of a program whose FILE-CONTROL entries were not read: what a caller builds by hand. */
    public CobolSemanticModel(String programId, String sourceFile, List<DataItem> dataItems,
            List<Procedure> procedures, List<CallRelation> calls, List<PerformRelation> performs,
            List<EmbeddedBlock> embeddedBlocks, List<CopyExpansionEntry> copyExpansions,
            List<CopyInlineExpansion> copyInlineExpansions) {
        this(programId, sourceFile, dataItems, procedures, calls, performs, embeddedBlocks,
                copyExpansions, copyInlineExpansions, List.of());
    }
}
