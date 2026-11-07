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
                "com.google.guava:guava:33.8.0-jre",
                "com.fasterxml.jackson.core:jackson-core:2.22.4",
                "com.fasterxml.jackson.core:jackson-databind:2.22.4",
                "org.bitbucket.b_c:jose4j:0.9.7",
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
    alias(libs.plugins.kover)
}

// Couverture de code (Kover) : rapport agrégé de tous les modules, calculé localement en CI
// (aucun service externe). `./gradlew koverXmlReport koverHtmlReport`.
subprojects {
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

dependencies {
    listOf(
        ":app", ":core:model", ":feature:cast",
        ":protocol:kodi", ":protocol:mediaserver", ":protocol:plex", ":protocol:upnp",
        ":protocol:roku", ":protocol:airplay", ":protocol:firetv",
    ).forEach { kover(project(it)) }
}

// Vérification (non branchée sur `check`) : `./gradlew koverVerify` échoue si la couverture
// globale en lignes passe sous le seuil (ajusté à la fusion, quelques points sous la valeur réelle).
kover {
    reports {
        filters {
            excludes {
                // Code généré, sans logique écrite à la main.
                classes(
                    "*.BuildConfig", "*.R", "*.R\$*", "*.ComposableSingletons*",
                    "*\$\$serializer", "*.Companion\$serializer",
                    // Routes de navigation @Serializable (constructeur et compagnon générés).
                    "io.github.ttbox.kodifin.ui.*Route", "io.github.ttbox.kodifin.ui.*Route\$Companion",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")

                // UI Compose pure : les fonctions @Composable (et leurs lambdas compilées en classes)
                // sont exclues, mais pas les fichiers *ScreenKt entiers : leur logique non composable
                // (devicesUiState, buildDevice…) reste mesurée. Navigation et thème : sans logique.
                annotatedBy("androidx.compose.runtime.Composable")
                classes("io.github.ttbox.kodifin.ui.NavigationKt*", "io.github.ttbox.kodifin.ui.theme.ThemeKt")

                // Câblage Android / SDK sans logique propre, non testable unitairement.
                classes(
                    "io.github.ttbox.kodifin.MainActivity*",
                    "io.github.ttbox.kodifin.KodifinApp",
                    "io.github.ttbox.kodifin.KodifinAppKt",
                    "io.github.ttbox.kodifin.AppContainer*",
                    // Google Cast : options du SDK, castContext/castDevices, adaptateurs SDK.
                    "io.github.ttbox.kodifin.cast.CastOptionsProvider",
                    "io.github.ttbox.kodifin.cast.CastSdkKt*",
                    "io.github.ttbox.kodifin.cast.SdkCast*",
                    // Découverte : adaptateurs NsdManager / MulticastLock et fabrique des sources réelles.
                    "io.github.ttbox.kodifin.discovery.AndroidNsdBackend*",
                    "io.github.ttbox.kodifin.discovery.AndroidMulticastLock",
                    "io.github.ttbox.kodifin.discovery.AndroidDiscoveryKt",
                    // Contrôleurs exigeant Android (clés ADB dans le stockage privé, SDK Cast).
                    "io.github.ttbox.kodifin.controller.AndroidPlatformControllers",
                    // Lien ADB réel (dadb, connexion TCP) ; DadbShell lui-même reste mesuré.
                    "io.github.ttbox.kodifin.firetv.DadbShell\$Companion\$dadbLink\$1",
                    // Profil Bluetooth HID Device d'Android (pile Bluetooth réelle).
                    "io.github.ttbox.kodifin.bluetooth.AndroidHidKeyboard*",
                    // Résolution des textes via les Resources Android (UiText lui-même reste mesuré).
                    "io.github.ttbox.kodifin.text.AndroidTextResources",
                    "io.github.ttbox.kodifin.text.AndroidTextResourcesKt",
                    "io.github.ttbox.kodifin.ui.text.UiTextAndroidKt",
                )
            }
        }
        verify {
            rule("Couverture globale (lignes)") {
                minBound(80)
            }
        }
        total {
            verify {
                onCheck.set(false)
            }
        }
    }
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
