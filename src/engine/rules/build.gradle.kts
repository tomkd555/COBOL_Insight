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
    // FixProducer 実装が挿入文を固定形式へ整形するため FixedFormatNormalizer を用いる。
    implementation(project(":engine:fix"))
    testImplementation(project(":engine:cobol-frontend"))
    // FixProducer の修正受入テストが ByteSpliceApplier で編集を適用する(DecodedSource を参照する)。
    testImplementation(project(":engine:encoding"))
    // CFGルールのテストで CfgBuilder.build を呼び ControlFlowGraphs を組む(本体はengine-apiのみ依存)。
    testImplementation(project(":engine:dataflow"))
    // R031のテストでBMSソースをengine-apiのBmsMapsetへ変換する。
    testImplementation(project(":engine:bms-frontend"))
    // SQL指摘ルール(S001〜)のテストでEmbeddedBlockのSQLを
    // SqlParser SPI(JsqlSqlParser)でSqlStatementModel化する。
    testImplementation(project(":engine:sql-frontend"))
    // SARIF出力のスキーマ検証(src/test/resources/sarif/sarif-schema-2.1.0.json)に使う。
    testImplementation("com.networknt:json-schema-validator:3.0.6")
}
