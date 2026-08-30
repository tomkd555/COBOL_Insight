package jp.cobolinsight.core.transpile;

/** The target language for line-by-line translation. fileExtension is the generated file's extension (no dot). */
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
