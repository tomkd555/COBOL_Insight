plugins {
    id("jp.cobolinsight.java-conventions")
}

// テストは合成fixtureを実パーサー(cobol-frontend)で解析してルールへ通す。cobol-frontendが依存する
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
    // CFGルールのテストで CfgBuilder.build を呼び ControlFlowGraphs を組む(本体はengine-apiのみ依存)。
    testImplementation(project(":engine:dataflow"))
    // R031のテストでBMSソースをengine-apiのBmsMapsetへ写像する。
    testImplementation(project(":engine:bms-frontend"))
    // SARIF出力のスキーマ検証(src/test/resources/sarif/sarif-schema-2.1.0.json)に使う。
    testImplementation("com.networknt:json-schema-validator:3.0.6")
}
