import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
}

java {
    // ビルド機の JAVA_HOME の版に依存させず、常に Java 21 でコンパイル・実行する。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// JUnit の版はBOMで一括して固定し、個別の依存にはバージョンを書かない。
val junitBomVersion = "6.1.2"

dependencies {
    testImplementation(platform("org.junit:junit-bom:$junitBomVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ソースとリソースは日本語を含む。実行環境の既定文字コードに左右されないよう、
// コンパイル・Javadoc生成・リソース処理のすべてでUTF-8を明示する。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events = setOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
