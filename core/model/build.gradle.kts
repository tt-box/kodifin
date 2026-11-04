plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Utilitaires de test partagés (MockWebServer, assertions) : testFixtures(project(":core:model")).
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesApi(libs.junit)
    testFixturesApi(kotlin("test-junit"))
    testFixturesApi(libs.okhttp.mockwebserver)
}
