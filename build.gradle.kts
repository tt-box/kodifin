import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.cyclonedx)
}

// Inventaire CycloneDX (scan CVE) : uniquement les bibliothèques réellement embarquées
// dans l'application, pas l'outillage de build (lint, compilateur, plugins).
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath", "releaseRuntimeClasspath"))
        includeBuildEnvironment.set(false)
    }
}

// Modules protocole : bibliothèques Kotlin/JVM pures, testables sans Android.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(false)
            }
        }
        tasks.withType<Test>().configureEach {
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
