plugins {
    id("jp.cobolinsight.java-conventions")
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
