plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:engine-api"))
    implementation("com.ibm.icu:icu4j:78.3")
}
