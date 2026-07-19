plugins {
    id("jp.cobolinsight.java-conventions")
}

// テストは samples を実パーサー(cobol-frontend)で解析してCFGを構築する。cobol-frontendが依存する
// org.eclipse.lsp.cobol系はmavenLocalにのみ存在するため、テスト実行時解決に同じ宣言が要る。
repositories {
    mavenLocal {
        content {
            includeGroup("org.eclipse.lsp.cobol")
        }
    }
}

dependencies {
    implementation(project(":engine:engine-api"))
    testImplementation(project(":engine:cobol-frontend"))
}
