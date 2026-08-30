plugins {
    id("jp.cobolinsight.java-conventions")
    antlr
}

dependencies {
    implementation(project(":engine:core"))
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
}

tasks.generateGrammarSource {
    // The parse tree is walked directly by BmsModelBuilder, so no listener or visitor is generated.
    arguments = arguments + listOf("-package", "jp.cobolinsight.frontend.bms.grammar", "-no-listener", "-no-visitor")
    outputDirectory = file(layout.buildDirectory.dir("generated-src/antlr/main/jp/cobolinsight/frontend/bms/grammar").get().asFile)
}
