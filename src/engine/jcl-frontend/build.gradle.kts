plugins {
    id("jp.cobolinsight.java-conventions")
    antlr
}

dependencies {
    implementation(project(":engine:core"))
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
}

// The grammars are compiled straight from the MAPA submodule, so no .g4 copy lives in this
// repository. Only the two grammars the JCL listener drives are generated; MAPA ships eight
// more (preprocessor, TSO, DSNTSO, DDAMP) that nothing here walks.
val mapaGrammarDir = rootProject.layout.projectDirectory.dir("src/vendor/mapa/jcl/src")

sourceSets.main {
    antlr.setSrcDirs(listOf(mapaGrammarDir))
}

tasks.generateGrammarSource {
    include("JCLLexer.g4", "JCLParser.g4")
    maxHeapSize = "1g"
    // -lib resolves tokenVocab (the lexer's .tokens file). The ANTLR tool orders lexer before
    // parser, so pointing at the single output directory is enough.
    arguments = arguments + listOf(
        "-visitor",
        "-listener",
        "-package", "jp.cobolinsight.frontend.jcl.gen",
        "-lib", layout.buildDirectory.dir("generated-src/antlr/main").get().asFile.absolutePath
    )
}

// The guard sits on compileJava, not on generateGrammarSource: with the submodule missing the
// grammar task has no source at all, so Gradle skips it and any doFirst of its own with it. What
// the compiler would then report is a missing jp.cobolinsight.frontend.jcl.gen package.
tasks.compileJava {
    doFirst {
        if (!mapaGrammarDir.file("JCLParser.g4").asFile.exists()) {
            throw GradleException(
                "src/vendor/mapa is empty; run git submodule update --init --recursive")
        }
    }
}
