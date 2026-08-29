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
    maxHeapSize = "512m"
    // -lib は tokenVocab(レクサの .tokens)の解決先。生成順はレクサ→パーサに
    // ANTLR Tool 側でソートされるため、同一出力ディレクトリを指せばよい。
    arguments = arguments + listOf(
        "-visitor",
        "-listener",
        "-package", "jp.cobolinsight.frontend.jcl.mapa",
        "-lib", layout.buildDirectory.dir("generated-src/antlr/main").get().asFile.absolutePath
    )
}
