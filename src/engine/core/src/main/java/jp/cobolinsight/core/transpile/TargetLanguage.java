package jp.cobolinsight.core.transpile;

/** 逐語対訳の対象言語。fileExtension は生成ファイルの拡張子(ドットなし)。 */
public enum TargetLanguage {
    PYTHON("py"),
    JAVA("java");

    private final String fileExtension;

    TargetLanguage(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public String fileExtension() {
        return fileExtension;
    }
}
