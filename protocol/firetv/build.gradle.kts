plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.dadb)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Contrat des capacités (assertHonorsCapabilities) partagé par les modules.
    testImplementation(testFixtures(project(":core:model")))
}
