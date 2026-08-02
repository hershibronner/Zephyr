import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.zephyr"
version = "1.0"

// Targets Java 17 bytecode using whatever JDK runs the build, rather than demanding a specific
// toolchain be installed. 17 is what the Android module consumes, and a `jvmToolchain(17)` call
// would fail on any machine that only has 21 — including this one and some CI images.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
