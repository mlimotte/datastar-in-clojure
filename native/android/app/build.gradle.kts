// Minimal app module. Needs a root settings.gradle.kts / build.gradle.kts from a fresh
// Android Studio "Empty Activity" project; drop this module and the sources in.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.brewboard"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.brewboard.shell"
        minSdk = 26          // NotificationChannel
        targetSdk = 35
    }
}
