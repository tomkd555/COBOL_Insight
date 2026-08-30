import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
}

java {
    // Always compile and run with Java 21, independent of the build machine's JAVA_HOME version.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Pin the JUnit version once via the BOM; do not write a version on individual dependencies.
val junitBomVersion = "6.1.2"

dependencies {
    testImplementation(platform("org.junit:junit-bom:$junitBomVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Sources and resources contain Japanese. To avoid being affected by the runtime environment's
// default charset, explicitly set UTF-8 for compilation, Javadoc generation, and resource processing.
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
