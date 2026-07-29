plugins {
    id("jp.cobolinsight.java-conventions")
}

// テストは samples を実パーサー(cobol-frontend)で解析してレコードレイアウトを検証する。cobol-frontend
// が依存する org.eclipse.lsp.cobol 系は mavenLocal にのみ存在するため、テスト実行時解決に同じ宣言が要る。
repositories {
    mavenLocal {
        content {
            includeGroup("org.eclipse.lsp.cobol")
        }
    }
}

dependencies {
    implementation(project(":engine:engine-api"))
    // GO TO 構造化で GotoNormalizer を呼び、段落単位の部分 CFG を可約化する。
    implementation(project(":engine:dataflow"))
    testImplementation(project(":engine:cobol-frontend"))
}

// GoldenTranspileTest の再生成モードは fork したテスト JVM 内で判定するため、指定時のみ転送する。
tasks.test {
    System.getProperty("golden.regenerate")?.let { systemProperty("golden.regenerate", it) }
}
