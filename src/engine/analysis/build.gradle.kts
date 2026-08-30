plugins {
    id("jp.cobolinsight.java-conventions")
}

// CFG construction, def-use / interval / taint analysis and call-graph linking.
dependencies {
    implementation(project(":engine:core"))
    testImplementation(project(":engine:cobol-frontend"))
}
