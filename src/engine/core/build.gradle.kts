plugins {
    id("jp.cobolinsight.java-conventions")
}

// Value objects, frontend ports, the Rule contract, decoding and byte-preserving edits.
// Depends on nothing in the tree; ICU4J is for codepage detection (CharsetDetector).
dependencies {
    implementation("com.ibm.icu:icu4j:78.3")
}
