plugins {
    id("jp.cobolinsight.java-conventions")
}

// IMS DBDGEN and PSBGEN macro source. The statements are assembler macro calls with keyword
// operands, read by the small statement reader in this module.
dependencies {
    implementation(project(":engine:core"))
}
