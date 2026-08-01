plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:engine-api"))
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
}
