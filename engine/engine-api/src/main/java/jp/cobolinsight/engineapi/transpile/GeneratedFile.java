package jp.cobolinsight.engineapi.transpile;

import java.util.Objects;

/** 逐語対訳で生成した1ファイル。content は改行LF・BOMなしUTF-8を前提とする逐語のテキスト。 */
public record GeneratedFile(String fileName, String content) {

    public GeneratedFile {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        Objects.requireNonNull(content, "content");
    }
}
