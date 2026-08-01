plugins {
    id("jp.cobolinsight.java-conventions")
}

dependencies {
    implementation(project(":engine:engine-api"))
    implementation("com.github.jsqlparser:jsqlparser:5.3")
}
