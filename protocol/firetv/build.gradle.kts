plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.dadb)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
