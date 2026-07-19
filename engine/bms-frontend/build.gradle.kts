plugins {
    id("jp.cobolinsight.java-conventions")
    antlr
}

dependencies {
    implementation(project(":engine:engine-api"))
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-package", "jp.cobolinsight.bmsfrontend.grammar", "-no-listener", "-no-visitor")
    outputDirectory = file(layout.buildDirectory.dir("generated-src/antlr/main/jp/cobolinsight/bmsfrontend/grammar").get().asFile)
}
