plugins {
    id("jp.cobolinsight.java-conventions")
}

// Embedded SQL statement model. JSQLParser is the V1 parser; MAPA db2z replaces it in E2.
dependencies {
    implementation(project(":engine:core"))
    implementation("com.github.jsqlparser:jsqlparser:5.3")
}
