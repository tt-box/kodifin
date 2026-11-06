import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Identifiants de signature, hors du dépôt : `keystore.properties` à la racine en local
// (ignoré par git), variables d'environnement ZAPETTE_* en CI. Sans eux, la config release
// est absente et assembleRelease produit un APK non signé (suffisant pour vérifier R8).
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun signingValue(key: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(environmentVariable)

val releaseStoreFile = signingValue("storeFile", "ZAPETTE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ZAPETTE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ZAPETTE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ZAPETTE_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() }

android {
    namespace = "io.github.jmorille.zapette"
    // Compilation contre le SDK 37 (exigé par okhttp-android et mediarouter) ; la cible reste Android 16.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jmorille.zapette"
        minSdk = 26
        targetSdk = 36
        // Le workflow de release surcharge ces valeurs depuis le tag git.
        versionCode = System.getenv("ZAPETTE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ZAPETTE_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Clé ECDSA P-256. minSdk 26 : v1 (JAR) inutile, v2 couvre Android 7+,
                // v3 / v3.1 permettent une future rotation de clé (Android 9+ / 13+).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // Les mises à jour de dépendances sont gérées par Dependabot.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Cible Android 16 (API 36) volontaire.
        disable += "OldTargetApi"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Sans traceur : le SDK Cast embarque le transport de télémétrie Google (datatransport /
// encodeurs Firebase). On l'exclut ; le SDK Cast fonctionne sans (journalisation désactivée).
configurations.configureEach {
    exclude(group = "com.google.android.datatransport")
    exclude(group = "com.google.firebase", module = "firebase-encoders")
    exclude(group = "com.google.firebase", module = "firebase-encoders-json")
    exclude(group = "com.google.firebase", module = "firebase-encoders-proto")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":protocol:kodi"))
    implementation(project(":protocol:mediaserver"))
    implementation(project(":protocol:plex"))
    implementation(project(":protocol:upnp"))
    implementation(project(":protocol:roku"))
    implementation(project(":protocol:airplay"))
    implementation(project(":protocol:firetv"))
    implementation(project(":feature:cast"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // QR code (export/import d'appareils) : ZXing, Apache 2.0, sans télémétrie ni Play Services.
    // zxing-android-embedded tire core 3.4.1 : la déclaration explicite impose la version récente.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
