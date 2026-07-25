plugins {
    id("jp.cobolinsight.java-conventions")
}

// 再パース検証ゲートのテストは cobol-frontend(Che4z)を実行時に要する。cobol-frontend が依存する
// org.eclipse.lsp.cobol 系は mavenLocal にのみ存在し、プロジェクト依存の実行時解決は fix 自身が
// 宣言したリポジトリで行われるため、fix 側にも同じ宣言が要る。
repositories {
    mavenLocal {
        content {
            includeGroup("org.eclipse.lsp.cobol")
        }
    }
}

dependencies {
    implementation(project(":engine:engine-api"))
    // 原バイト再取得・ByteOffsetTable によるバイト単位桁計算のため、コンパイル依存とする。
    implementation(project(":engine:encoding"))
    // unified diff 算出の単一正本(fix preview/apply が使う)。
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")

    // ReparseVerifier は AnalysisServices.load() 経由で CobolParser を ServiceLoader 解決する。
    // 本体は engine-api だけへ依存し、実装(Che4zCobolParser)はテスト実行時クラスパスへ同梱する。
    testRuntimeOnly(project(":engine:cobol-frontend"))
}
