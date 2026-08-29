plugins {
    id("jp.cobolinsight.java-conventions")
}

// The only module that sees the Che4z jar. Its transitive dependencies are declared here, not in the
// vendored POM, so the supply chain is readable in one place (versions from che4z server/engine/pom.xml).
val lsp4j = "0.14.0"
val guice = "4.2.3"

dependencies {
    implementation(project(":engine:engine-api"))
    implementation(project(":engine:encoding"))
    implementation("jp.cobolinsight.vendor:che4z-cobol-engine:2.5.1-ja1")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4j")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4j")
    // Stock Guice 4.2.3 fails class generation on JDK 21; the no_aop classifier works.
    implementation("com.google.inject:guice:$guice:no_aop")
    implementation("com.google.inject.extensions:guice-assistedinject:$guice") {
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation("commons-io:commons-io:2.14.0")
    implementation("org.apache.commons:commons-text:1.14.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.3.16")
    implementation("info.picocli:picocli:4.7.5")
}
