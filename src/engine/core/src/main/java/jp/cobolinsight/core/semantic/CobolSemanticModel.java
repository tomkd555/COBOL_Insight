package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.CopyInlineExpansion;

import java.util.List;

/**
 * 正規化意味モデル。COBOLフロントエンドが構築し、dataflow・linker・transpile・rules は
 * このモデルだけを入力とする。1プログラム(PROGRAM-ID)につき1インスタンス。
 */
public record CobolSemanticModel(String programId, String sourceFile, List<DataItem> dataItems,
        List<Procedure> procedures, List<CallRelation> calls, List<PerformRelation> performs,
        List<EmbeddedBlock> embeddedBlocks, List<CopyExpansionEntry> copyExpansions,
        List<CopyInlineExpansion> copyInlineExpansions) {

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
    }
}
