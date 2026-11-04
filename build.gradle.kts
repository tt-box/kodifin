import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Outillage de build (AGP, lint, CycloneDX) : ces bibliothèques transitives ne sont pas
// livrées dans l'APK, mais leurs CVE remontent dans les alertes Dependabot. Dependabot ne
// sait pas mettre à jour une dépendance transitive de plugin : on force ici les versions
// corrigées (à retirer quand les plugins les embarqueront d'eux-mêmes).
buildscript {
    dependencies {
        constraints {
            listOf(
                "org.bouncycastle:bcprov-jdk18on:1.86",
                "org.bouncycastle:bcpkix-jdk18on:1.86",
                "org.bouncycastle:bcutil-jdk18on:1.86",
                "com.google.guava:guava:33.7.2-jre",
                "com.fasterxml.jackson.core:jackson-core:2.22.4",
                "com.fasterxml.jackson.core:jackson-databind:2.22.4",
                "org.bitbucket.b_c:jose4j:0.9.6",
                "org.jdom:jdom2:2.0.6.1",
                "org.apache.commons:commons-lang3:3.21.0",
            ).forEach { coordinates ->
                classpath(coordinates) { because("correctif de sécurité (outillage de build)") }
            }
        }
    }
}

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
