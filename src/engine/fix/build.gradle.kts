plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:engine-api"))
    // 原バイト再取得・ByteOffsetTable によるバイト単位桁計算のため、コンパイル依存とする。
    implementation(project(":engine:encoding"))
    // unified diff の算出はこのライブラリへ一元化する(修正案のプレビューと適用が使う)。
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")

    // ReparseVerifier は AnalysisServices.load() 経由で CobolParser を ServiceLoader 解決する。
    // 本体は engine-api だけへ依存し、実装(Che4zCobolParser)はテスト実行時クラスパスへ同梱する。
    testRuntimeOnly(project(":engine:cobol-frontend"))
}
