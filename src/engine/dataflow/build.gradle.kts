plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:engine-api"))
    testImplementation(project(":engine:cobol-frontend"))
}
