plugins {
    id("jp.cobolinsight.java-conventions")
}

// Easytrieve Plus programs and macro members, read by a statement reader written here: no grammar
// of the vendored parsers covers the language, and what the analysis needs of it — files, fields,
// activities, procedures, calls and SQL — is decided statement by statement.
dependencies {
    implementation(project(":engine:core"))
}
