package jp.cobolinsight.engineapi.sql;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.List;
import java.util.Objects;

/**
 * 埋め込みSQL文の解析結果。原文(originalText)とマングリング後テキスト(mangledText)の両方を
 * 保持し、ルールは原データ名で報告できる。range は元のCOBOLソース上の位置。
 */
public record SqlStatementModel(SqlStatementKind kind, String originalText, String mangledText,
        List<HostVariableBinding> hostVariables, List<String> referencedTables, SourceRange range) {

    public SqlStatementModel {
        Objects.requireNonNull(kind, "kind");
        if (originalText == null || originalText.isBlank()) {
            throw new IllegalArgumentException("originalText must not be blank");
        }
        if (mangledText == null || mangledText.isBlank()) {
            throw new IllegalArgumentException("mangledText must not be blank");
        }
        hostVariables = List.copyOf(hostVariables);
        referencedTables = List.copyOf(referencedTables);
        Objects.requireNonNull(range, "range");
    }
}
