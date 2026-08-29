plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:core"))
    testImplementation(project(":engine:cobol-frontend"))
    testImplementation(project(":engine:analysis"))
    testImplementation(project(":engine:bms-frontend"))
    testImplementation(project(":engine:sql-frontend"))
    testImplementation("com.networknt:json-schema-validator:3.0.6")
}
