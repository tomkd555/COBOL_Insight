package jp.cobolinsight.engineapi.sql;

import java.util.Objects;
import java.util.Optional;

/**
 * ホスト変数の可逆マングリング対応。原データ名(ハイフン付き)とマングリング後の名前を
 * 双方向に対応づける。指示変数(:host:ind の ind)を任意で持つ。
 */
public record HostVariableBinding(String originalName, String mangledName,
        Optional<String> indicatorName) {

    public HostVariableBinding {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("originalName must not be blank");
        }
        if (mangledName == null || mangledName.isBlank()) {
            throw new IllegalArgumentException("mangledName must not be blank");
        }
        Objects.requireNonNull(indicatorName, "indicatorName");
    }
}
