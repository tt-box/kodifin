plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
