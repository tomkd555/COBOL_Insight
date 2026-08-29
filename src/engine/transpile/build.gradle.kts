plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:core"))
    // GotoNormalizer rewrites GO TO before translation.
    implementation(project(":engine:analysis"))
    testImplementation(project(":engine:cobol-frontend"))
}

tasks.test {
    // -Dgolden.regenerate=true rewrites the golden translations under src/test/resources/golden.
    System.getProperty("golden.regenerate")?.let { systemProperty("golden.regenerate", it) }
}
