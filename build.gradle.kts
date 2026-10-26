plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere (AGP 9 has built-in Kotlin); declared to pin the Kotlin Gradle plugin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
