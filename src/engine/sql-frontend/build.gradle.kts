plugins {
    id("jp.cobolinsight.java-conventions")
    antlr
}

// Embedded SQL statement model, parsed with the MAPA Db2 for z/OS grammar.
dependencies {
    implementation(project(":engine:core"))
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
}

val db2zGrammarDir = rootProject.layout.projectDirectory.dir("src/vendor/mapa/db2z/src")

sourceSets.main {
    // The grammars stay in the MAPA submodule; nothing is copied into this tree.
    antlr.setSrcDirs(listOf(db2zGrammarDir))
    antlr.include("DB2zSQLLexer.g4", "DB2zSQLParser.g4")
}

tasks.generateGrammarSource {
    // The Db2z grammar is an order of magnitude larger than the JCL ones, hence the heap.
    maxHeapSize = "1g"
    // -lib resolves tokenVocab (the lexer .tokens). ANTLR Tool sorts lexer before parser,
    // so pointing at the shared output directory is enough.
    arguments = arguments + listOf(
        "-visitor",
        "-listener",
        "-package", "jp.cobolinsight.frontend.sql.gen",
        "-lib", layout.buildDirectory.dir("generated-src/antlr/main").get().asFile.absolutePath
    )
}

// The guard sits here, not on generateGrammarSource: that task is skipped as NO-SOURCE when the
// submodule is empty, so it would never run its own check.
tasks.compileJava {
    doFirst {
        require(db2zGrammarDir.file("DB2zSQLParser.g4").asFile.isFile) {
            "src/vendor/mapa is empty; run git submodule update --init --recursive"
        }
    }
}
