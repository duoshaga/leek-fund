import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "cn.leekbox.rider"
version = "1.0.7"

val riderHome = providers.gradleProperty("riderHome")
    .orElse(providers.environmentVariable("RIDER_HOME"))
    .orElse("C:/Program Files/JetBrains/JetBrains Rider 2026.3.0.2")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local(riderHome)
        pluginVerifier("1.411")
    }
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    buildSearchableOptions = false
    // All UI is defined in Kotlin. No GUI Designer forms or Java nullability instrumentation.
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263.7307.355"
        }
    }
    pluginVerification {
        ides { local(riderHome) }
    }
}

tasks.test {
    useJUnitPlatform()
}
